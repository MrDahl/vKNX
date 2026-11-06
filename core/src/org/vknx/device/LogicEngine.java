package org.vknx.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * The 1 bit logic of the logic module: gates, delay, stairway timer, impulse relay and a clock, wired between the bus
 * inputs and outputs of the device. It follows the logic of Marcs GateCraft (levelled evaluation, edge triggered
 * stairway and impulse relay, delays that follow their input after a set time) so a circuit behaves the same in both.
 *
 * <p>A circuit is plain text, one element per line, so it can be stored in the device memory and sent over HTTP without
 * a JSON library:
 * <pre>
 * n id KIND x y param      (KIND: IN OUT BUFFER NOT AND NAND OR NOR XOR XNOR DELAY STAIRWAY TOGGLE CLOCK)
 * w sourceId targetId port
 * </pre>
 * {@code param} is the input/output number (IN, OUT), the number of inputs (gates), milliseconds (DELAY, STAIRWAY) or
 * tenths of a hertz (CLOCK). The engine does not know the bus: the owner sets the inputs and is told about changed outputs.
 */
public final class LogicEngine {
	public static final int MAX_NODES = 200, MAX_INPUTS_PER_GATE = 16, MAX_MS = 3599_000, MAX_IO = 16;

	enum Kind {
		IN, OUT, BUFFER, NOT, AND, NAND, OR, NOR, XOR, XNOR, DELAY, STAIRWAY, TOGGLE, CLOCK;

		boolean gate() { return ordinal() >= BUFFER.ordinal() && ordinal() <= XNOR.ordinal(); }

		boolean source() { return this == IN || this == CLOCK; }
	}

	static final class Node {
		final String id;
		final Kind kind;
		final int x, y, param;
		boolean value, target;
		boolean[] in;
		int gen;
		long endTime;
		ScheduledFuture<?> task;

		Node(final String id, final Kind kind, final int x, final int y, final int param) {
			this.id = id;
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.param = param;
			this.in = new boolean[inputCount()];
		}

		int inputCount() {
			return switch (kind) {
				case IN, CLOCK -> 0;
				case OUT, DELAY, STAIRWAY, TOGGLE -> 1;
				default -> kind.gate() ? param : 1;
			};
		}
	}

	record Wire(String src, String dst, int port) {}

	/** What the engine needs from its owner. */
	public interface Host {
		/** Runs {@code r} after {@code ms} milliseconds. */
		ScheduledFuture<?> later(Runnable r, long ms);

		/** An output node changed its value (called without the engine lock held). */
		void output(int index, boolean value);
	}

	private final Host host;
	private final Map<String, Node> nodes = new LinkedHashMap<>();
	private final List<Wire> wires = new ArrayList<>();
	private final boolean[] busIn = new boolean[MAX_IO + 1];
	private final List<Node> order = new ArrayList<>();
	private final List<int[]> pending = new ArrayList<>();
	private boolean init;
	private volatile boolean oscillating;
	private String text = "";

	public LogicEngine(final Host host) { this.host = host; }

	// ------------------------------------------------------------------ circuit text

	/** The circuit text exactly as it was loaded (empty when there is none). */
	public synchronized String text() { return text; }

	public synchronized int nodeCount() { return nodes.size(); }

	public boolean oscillating() { return oscillating; }

	/**
	 * Replaces the circuit. Returns null when it was accepted, otherwise the reason; a rejected circuit leaves the
	 * running one untouched.
	 */
	public String load(final String circuit) {
		final var newNodes = new LinkedHashMap<String, Node>();
		final var newWires = new ArrayList<Wire>();
		final String err = parse(circuit, newNodes, newWires);
		if (err != null)
			return err;
		final List<int[]> out;
		synchronized (this) {
			stopTimers();
			nodes.clear();
			nodes.putAll(newNodes);
			wires.clear();
			wires.addAll(newWires);
			text = circuit.strip();
			order();
			start();
			out = drain();
		}
		send(out);
		return null;
	}

	private static String parse(final String circuit, final Map<String, Node> nodes, final List<Wire> wires) {
		final var usedIn = new boolean[MAX_IO + 1];
		final var usedOut = new boolean[MAX_IO + 1];
		int lineNo = 0;
		for (final String raw : circuit.split("\\R")) {
			lineNo++;
			final String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#") || line.equals("v1"))
				continue;
			final String[] f = line.split("\\s+");
			try {
				if (f[0].equals("n") && f.length == 6) {
					if (!f[1].matches("[A-Za-z0-9_]{1,20}"))
						return "line " + lineNo + ": bad element id";
					if (nodes.containsKey(f[1]))
						return "line " + lineNo + ": element id " + f[1] + " is used twice";
					final Kind k = Kind.valueOf(f[2]);
					final int x = Integer.parseInt(f[3]), y = Integer.parseInt(f[4]), p = Integer.parseInt(f[5]);
					final String perr = checkParam(k, p);
					if (perr != null)
						return "line " + lineNo + ": " + perr;
					if (k == Kind.IN) {
						if (usedIn[p])
							return "Input " + p + " is used twice";
						usedIn[p] = true;
					}
					if (k == Kind.OUT) {
						if (usedOut[p])
							return "Output " + p + " is used twice";
						usedOut[p] = true;
					}
					nodes.put(f[1], new Node(f[1], k, x, y, p));
					if (nodes.size() > MAX_NODES)
						return "too many elements (at most " + MAX_NODES + ")";
				}
				else if (f[0].equals("w") && f.length == 4)
					wires.add(new Wire(f[1], f[2], Integer.parseInt(f[3])));
				else
					return "line " + lineNo + ": not understood";
			}
			catch (final IllegalArgumentException e) {
				return "line " + lineNo + ": not understood";
			}
		}
		final var seen = new java.util.HashSet<String>();
		for (final Wire w : wires) {
			final Node s = nodes.get(w.src()), d = nodes.get(w.dst());
			if (s == null || d == null)
				return "a wire leads to an element that does not exist";
			if (s.kind == Kind.OUT)
				return "an output cannot be the start of a wire";
			if (w.port() < 0 || w.port() >= d.inputCount())
				return "a wire ends at an input that " + d.id + " does not have";
			if (!seen.add(w.dst() + ":" + w.port()))
				return "two wires end at the same input of " + d.id;
		}
		return null;
	}

	private static String checkParam(final Kind k, final int p) {
		return switch (k) {
			case IN, OUT -> p >= 1 && p <= MAX_IO ? null : "input/output number must be 1-" + MAX_IO;
			case DELAY, STAIRWAY -> p >= 0 && p <= MAX_MS ? null : "time out of range";
			case CLOCK -> p >= 1 && p <= 200 ? null : "clock frequency out of range";
			case BUFFER, NOT -> p == 1 ? null : "this gate has one input";
			case TOGGLE -> null;
			default -> p >= 2 && p <= MAX_INPUTS_PER_GATE ? null : "a gate has 2-" + MAX_INPUTS_PER_GATE + " inputs";
		};
	}

	// ------------------------------------------------------------------ running

	/** Evaluation order: sources first; loops are broken at the node with the fewest open inputs (as in GateCraft). */
	private void order() {
		order.clear();
		final var preds = new HashMap<String, java.util.Set<String>>();
		for (final Node n : nodes.values())
			preds.put(n.id, new java.util.HashSet<>());
		for (final Wire w : wires)
			preds.get(w.dst()).add(w.src());
		final var remaining = new java.util.LinkedHashSet<>(nodes.keySet());
		while (!remaining.isEmpty()) {
			String pick = null;
			int best = Integer.MAX_VALUE;
			for (final String id : remaining) {
				int open = 0;
				for (final String p : preds.get(id))
					if (remaining.contains(p) && !p.equals(id))
						open++;
				if (open < best) {
					best = open;
					pick = id;
					if (open == 0)
						break;
				}
			}
			remaining.remove(pick);
			order.add(nodes.get(pick));
		}
	}

	/** Initial evaluation after a load: timers restart, no edges are triggered. */
	private void start() {
		oscillating = false;
		for (final Node n : nodes.values()) {
			n.gen++;
			n.target = false;
			n.value = n.kind == Kind.IN && busIn[n.param];
			java.util.Arrays.fill(n.in, false);
			if (n.kind == Kind.CLOCK)
				scheduleClock(n);
		}
		init = true;
		settle();
		init = false;
	}

	private void stopTimers() {
		for (final Node n : nodes.values()) {
			n.gen++;
			if (n.task != null)
				n.task.cancel(false);
		}
	}

	public synchronized void stop() { stopTimers(); }

	/** The value of bus input {@code index} (1-16) changed. */
	public void setInput(final int index, final boolean value) {
		final List<int[]> out;
		synchronized (this) {
			if (index < 1 || index > MAX_IO)
				return;
			busIn[index] = value;
			boolean any = false;
			for (final Node n : nodes.values())
				if (n.kind == Kind.IN && n.param == index && n.value != value) {
					n.value = value;
					any = true;
				}
			if (any)
				settle();
			out = drain();
		}
		send(out);
	}

	public synchronized boolean input(final int index) { return index >= 1 && index <= MAX_IO && busIn[index]; }

	/** Current value of output {@code index}, or false when the circuit has no such output. */
	public synchronized boolean output(final int index) {
		for (final Node n : nodes.values())
			if (n.kind == Kind.OUT && n.param == index)
				return n.value;
		return false;
	}

	public synchronized boolean hasOutput(final int index) {
		for (final Node n : nodes.values())
			if (n.kind == Kind.OUT && n.param == index)
				return true;
		return false;
	}

	/** Values of all elements by id, for the live display in the editor. */
	public synchronized Map<String, Boolean> live() {
		final var m = new LinkedHashMap<String, Boolean>();
		for (final Node n : nodes.values())
			m.put(n.id, n.value);
		return m;
	}

	private boolean[] inputsOf(final Node n) {
		final boolean[] iv = new boolean[n.in.length];
		for (final Wire w : wires)
			if (w.dst().equals(n.id))
				iv[w.port()] = nodes.get(w.src()).value;
		return iv;
	}

	/** Evaluates every element in order until nothing changes any more (or gives up: a circuit that never settles). */
	private void settle() {
		int passes = 0;
		boolean changed;
		do {
			changed = false;
			for (final Node n : order)
				changed |= eval(n);
		}
		while (changed && ++passes < 100);
		oscillating = changed;
	}

	private static boolean gateValue(final Kind k, final boolean[] iv) {
		boolean all = true, any = false, parity = false;
		for (final boolean b : iv) {
			all &= b;
			any |= b;
			parity ^= b;
		}
		return switch (k) {
			case BUFFER -> iv[0];
			case NOT -> !iv[0];
			case AND -> all;
			case NAND -> !all;
			case OR -> any;
			case NOR -> !any;
			case XOR -> parity;
			case XNOR -> !parity;
			default -> false;
		};
	}

	/** Evaluates one element; true if its value changed. */
	private boolean eval(final Node n) {
		if (n.kind.source())
			return false;
		final boolean[] iv = inputsOf(n);
		final boolean rising = iv.length > 0 && iv[0] && !n.in[0] && !init;
		n.in = iv;
		boolean v = n.value;
		switch (n.kind) {
			case OUT -> v = iv[0];
			case DELAY -> {
				if (iv[0] != n.target) {
					n.target = iv[0];
					if (n.param <= 0) {
						v = iv[0];
						cancel(n);
					}
					else if (iv[0] == n.value)
						cancel(n);
					else
						schedule(n, n.param, iv[0] ? 1 : 0);
				}
			}
			case STAIRWAY -> {
				if (rising && n.param > 0) {
					v = true;
					schedule(n, n.param, 0);
				}
			}
			case TOGGLE -> {
				if (rising)
					v = !v;
			}
			default -> v = gateValue(n.kind, iv);
		}
		if (v == n.value)
			return false;
		n.value = v;
		if (n.kind == Kind.OUT)
			pending.add(new int[] { n.param, v ? 1 : 0 });
		return true;
	}

	private void cancel(final Node n) {
		n.gen++;
		if (n.task != null)
			n.task.cancel(false);
	}

	/** Timed event: type 0 = "off" / "set false", 1 = "set true", 2 = clock toggle. */
	private void schedule(final Node n, final long ms, final int type) {
		cancel(n);
		final int gen = n.gen;
		n.endTime = System.currentTimeMillis() + ms;
		n.task = host.later(() -> fire(n, gen, type), ms);
	}

	private void scheduleClock(final Node n) {
		final long half = Math.max(1, Math.round(500.0 / (n.param / 10.0)));
		final int gen = n.gen;
		n.task = host.later(() -> fire(n, gen, 2), half);
	}

	private void fire(final Node n, final int gen, final int type) {
		final List<int[]> out;
		synchronized (this) {
			if (n.gen != gen || nodes.get(n.id) != n)
				return;
			switch (type) {
				case 0 -> n.value = false;
				case 1 -> n.value = true;
				default -> {
					n.value = !n.value;
					scheduleClock(n);
				}
			}
			settle();
			out = drain();
		}
		send(out);
	}

	private List<int[]> drain() {
		final var out = new ArrayList<>(pending);
		pending.clear();
		return out;
	}

	private void send(final List<int[]> changes) {
		for (final int[] c : changes)
			host.output(c[0], c[1] != 0);
	}
}
