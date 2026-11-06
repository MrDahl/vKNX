package org.vknx;

import org.vknx.device.LogicEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The logic engine on its own (no bus, no ETS): every gate, delay, stairway timer, impulse relay, clock, loops (SR latch,
 * oscillation) and the checks on a circuit that is rejected. Own test only; says nothing about ETS.
 */
public final class LogicEngineTest {
	private static int failed;
	private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(2);
	private static final List<String> changes = new ArrayList<>();

	private static void check(final String what, final boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok)
			failed++;
	}

	private static LogicEngine engine() {
		synchronized (changes) { changes.clear(); }
		return new LogicEngine(new LogicEngine.Host() {
			@Override public ScheduledFuture<?> later(final Runnable r, final long ms) { return POOL.schedule(r, ms, TimeUnit.MILLISECONDS); }
			@Override public void output(final int index, final boolean value) { synchronized (changes) { changes.add("O" + index + "=" + (value ? 1 : 0)); } }
		});
	}

	/** Circuit text for one gate with {@code n} inputs 1..n and output 1. */
	private static String gate(final String kind, final int n) {
		final var sb = new StringBuilder();
		for (int i = 1; i <= n; i++)
			sb.append("n i").append(i).append(" IN 0 ").append(i * 40).append(' ').append(i).append('\n');
		sb.append("n g ").append(kind).append(" 100 0 ").append(n).append("\nn o OUT 200 0 1\n");
		for (int i = 1; i <= n; i++)
			sb.append("w i").append(i).append(" g ").append(i - 1).append('\n');
		sb.append("w g o 0\n");
		return sb.toString();
	}

	private static boolean sleepUntil(final java.util.function.BooleanSupplier c, final long ms) throws InterruptedException {
		final long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (c.getAsBoolean())
				return true;
			Thread.sleep(10);
		}
		return c.getAsBoolean();
	}

	public static void main(final String[] args) throws Exception {
		// ---- every gate, all input combinations (2 inputs; BUFFER and NOT with 1; AND/OR/XOR also with 3)
		for (final String k : List.of("AND", "NAND", "OR", "NOR", "XOR", "XNOR")) {
			for (final int n : new int[] { 2, 3 }) {
				final var e = engine();
				check("accepts " + k + n, e.load(gate(k, n)) == null);
				boolean ok = true;
				for (int bits = 0; bits < (1 << n); bits++) {
					boolean all = true, any = false, par = false;
					for (int i = 0; i < n; i++) {
						final boolean b = (bits >> i & 1) != 0;
						e.setInput(i + 1, b);
						all &= b;
						any |= b;
						par ^= b;
					}
					final boolean want = switch (k) {
						case "AND" -> all; case "NAND" -> !all; case "OR" -> any; case "NOR" -> !any; case "XOR" -> par; default -> !par;
					};
					ok &= e.output(1) == want;
				}
				check(k + " with " + n + " inputs: whole truth table", ok);
			}
		}
		final var e0 = engine();
		e0.load(gate("NOT", 1).replace("NOT 100 0 1", "NOT 100 0 1"));
		check("NOT: output is 1 while the input is 0 (settles at load)", e0.output(1));
		e0.setInput(1, true);
		check("NOT: input 1 gives output 0", !e0.output(1));
		final var e1 = engine();
		e1.load(gate("BUFFER", 1));
		e1.setInput(1, true);
		check("BUFFER follows its input", e1.output(1));

		// ---- output changes are reported once per change
		final var e2 = engine();
		e2.load(gate("AND", 2));
		e2.setInput(1, true);
		e2.setInput(2, true);
		e2.setInput(2, true);
		e2.setInput(1, false);
		synchronized (changes) { check("AND: output changes reported as O1=1 then O1=0 (not repeated): " + changes, changes.equals(List.of("O1=1", "O1=0"))); }

		// ---- delay: follows the input after 300 ms in both directions
		final var e3 = engine();
		e3.load("n i IN 0 0 1\nn d DELAY 100 0 300\nn o OUT 200 0 1\nw i d 0\nw d o 0\n");
		e3.setInput(1, true);
		check("delay: nothing at once", !e3.output(1));
		check("delay: on after the delay", sleepUntil(() -> e3.output(1), 1000));
		e3.setInput(1, false);
		check("delay: still on right after the input went off", e3.output(1));
		check("delay: off after the delay", sleepUntil(() -> !e3.output(1), 1000));
		e3.setInput(1, true);
		Thread.sleep(100);
		e3.setInput(1, false);
		Thread.sleep(500);
		check("delay: a pulse shorter than the delay is swallowed", !e3.output(1));

		// ---- stairway: rising edge starts the time, a new edge restarts it
		final var e4 = engine();
		e4.load("n i IN 0 0 1\nn s STAIRWAY 100 0 400\nn o OUT 200 0 1\nw i s 0\nw s o 0\n");
		e4.setInput(1, true);
		check("stairway: on at the rising edge", e4.output(1));
		e4.setInput(1, false);
		check("stairway: stays on when the input goes off", e4.output(1));
		Thread.sleep(250);
		e4.setInput(1, true);
		Thread.sleep(250);
		check("stairway: a second push restarts the time (still on after 500 ms in total)", e4.output(1));
		check("stairway: off after the time", sleepUntil(() -> !e4.output(1), 1000));

		// ---- impulse relay: every rising edge flips, falling edges do nothing
		final var e5 = engine();
		e5.load("n i IN 0 0 1\nn t TOGGLE 100 0 1\nn o OUT 200 0 1\nw i t 0\nw t o 0\n");
		e5.setInput(1, true);
		check("impulse relay: first push on", e5.output(1));
		e5.setInput(1, false);
		check("impulse relay: release does nothing", e5.output(1));
		e5.setInput(1, true);
		check("impulse relay: second push off", !e5.output(1));

		// ---- edges are not triggered by the start state
		final var e6 = engine();
		e6.setInput(1, true);
		e6.load("n i IN 0 0 1\nn t TOGGLE 100 0 1\nn o OUT 200 0 1\nw i t 0\nw t o 0\n");
		check("an input that is already 1 at load does not flip the impulse relay", !e6.output(1));

		// ---- clock: toggles twice a second at 1 Hz -> fast clock 10 Hz for the test
		final var e7 = engine();
		e7.load("n c CLOCK 0 0 100\nn o OUT 200 0 1\nw c o 0\n");
		check("clock 10 Hz: the output toggles", sleepUntil(() -> e7.output(1), 1000) && sleepUntil(() -> !e7.output(1), 1000));
		e7.stop();

		// ---- SR latch from two NOR gates (feedback)
		final var e8 = engine();
		e8.load("n s IN 0 0 1\nn r IN 0 100 2\nn a NOR 100 0 2\nn b NOR 100 100 2\nn q OUT 300 0 1\n"
				+ "w r a 0\nw b a 1\nw s b 0\nw a b 1\nw a q 0\n");
		e8.setInput(1, true);
		e8.setInput(1, false);
		check("SR latch: set and release keeps Q on (memory)", e8.output(1));
		e8.setInput(2, true);
		e8.setInput(2, false);
		check("SR latch: reset and release keeps Q off", !e8.output(1));
		check("SR latch does not count as oscillating", !e8.oscillating());

		// ---- a circuit that never settles
		final var e9 = engine();
		check("NOT fed by its own output is accepted", e9.load("n g NOT 0 0 1\nn o OUT 100 0 1\nw g g 0\nw g o 0\n") == null);
		check("... and reported as oscillating instead of hanging", e9.oscillating());

		// ---- rejected circuits leave the running one alone
		final var e10 = engine();
		e10.load(gate("AND", 2));
		e10.setInput(1, true);
		e10.setInput(2, true);
		final String[] bad = {
			"n a IN 0 0 1\nn a OUT 0 0 1\n",
			"n a IN 0 0 1\nn b IN 0 0 1\n",
			"n a OUT 0 0 1\nn b OUT 0 0 1\n",
			"n a IN 0 0 17\n",
			"n a FOO 0 0 1\n",
			"n a AND 0 0 1\n",
			"n a IN 0 0 1\nn b NOT 0 0 1\nw a b 1\n",
			"n a IN 0 0 1\nn b IN 0 0 2\nn c AND 0 0 2\nw a c 0\nw b c 0\n",
			"n a IN 0 0 1\nn o OUT 0 0 1\nw o a 0\n",
			"n a IN 0 0 1\nw a zz 0\n",
			"hello\n"
		};
		for (final String b : bad)
			check("rejected: " + b.replace("\n", " | "), e10.load(b) != null);
		check("after rejected circuits the old circuit still runs", e10.output(1) && e10.nodeCount() == 4);

		// ---- limits
		final var big = new StringBuilder();
		for (int i = 0; i <= LogicEngine.MAX_NODES; i++)
			big.append("n a").append(i).append(" NOT 0 0 1\n");
		check("more than " + LogicEngine.MAX_NODES + " elements are rejected", engine().load(big.toString()) != null);
		check("a delay longer than an hour is rejected", engine().load("n d DELAY 0 0 3600000\n") != null);

		POOL.shutdownNow();
		System.out.println(failed == 0 ? "LOGIC ENGINE TEST PASSED" : failed + " FAILED");
		System.exit(failed == 0 ? 0 : 1);
	}
}
