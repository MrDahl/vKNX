package org.vknx.sim;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.cemi.CEMILData;

/**
 * The line structure of the simulated installation. Lines form a tree: the backbone line 0.0 is the root, the main
 * line of area a is (a,0) and a line is (a,l). The coupler between a line and its parent has the address a.l.0
 * (area coupler a.0.0 between main line a.0 and the backbone, line coupler a.l.0 between line a.l and main line a.0).
 *
 * <p>Every device sits on a <em>physical</em> line, as in a real building: it is wired there, whatever address ETS
 * gives it. A telegram starts on the line of the sender and only reaches another line if every coupler on the way is
 * wired in and lets it pass. There is no shortcut for ETS: the KNXnet/IP interface sits on a line as well. A device
 * without a placement (older code paths and tests) is placed by its address, as before.
 * When routing is switched off, every device reaches every other device (one flat line).
 */
public final class Topology {

	/** One line: area and line number. */
	public record Line(int area, int line) {
		/** The line this line is connected to through its coupler, or null for the backbone. */
		public Line parent() {
			if (line != 0)
				return new Line(area, 0);
			return area == 0 ? null : new Line(0, 0);
		}

		public IndividualAddress couplerAddress() { return new IndividualAddress(area, line, 0); }

		@Override
		public String toString() { return area + "." + line; }
	}

	/** Result of a coupler looking at a telegram. */
	public record Decision(boolean pass, String why) {
		public static final Decision PASS = new Decision(true, "");

		public static Decision block(final String why) { return new Decision(false, why); }
	}

	/** A coupler as the topology sees it. {@code route} is called when a telegram wants to cross the coupler. */
	public interface CouplerPort {
		IndividualAddress address();

		/** The line on the sub side of the coupler (where it is wired), or null if it is not part of the line structure. */
		Line subLine();

		/** @param fromMain true when the telegram comes from the main side (the parent line) and wants to go to the sub side */
		Decision route(CEMILData frame, boolean fromMain);
	}

	private final CopyOnWriteArrayList<CouplerPort> couplers = new CopyOnWriteArrayList<>();
	private final java.util.concurrent.ConcurrentHashMap<Object, java.util.function.Supplier<Line[]>> senders =
			new java.util.concurrent.ConcurrentHashMap<>();
	private volatile boolean enabled;
	private volatile Line uplink = new Line(1, 1);

	private record Cached(String key, long time, Map<Line, Integer> reach) {}

	private volatile Cached last;

	public boolean enabled() { return enabled; }

	public void setEnabled(final boolean on) {
		enabled = on;
		last = null;
	}

	private volatile java.util.function.Supplier<java.util.List<IndividualAddress>> tunnels = java.util.List::of;

	/**
	 * Where to find the addresses the KNXnet/IP interface currently uses. ETS may change the interface address at any
	 * time (as on a real interface); the line the interface sits on follows the area and line of that address.
	 */
	public void setTunnelSource(final java.util.function.Supplier<java.util.List<IndividualAddress>> s) { tunnels = s; }

	/** The line the KNXnet/IP interface (ETS) sits on: that of its current address, or the configured one. */
	public Line uplinkLine() {
		try {
			for (final var a : tunnels.get())
				if (a != null && a.getRawAddress() != 0 && a.getRawAddress() != 0xFFFF)
					return new Line(a.getArea(), a.getLine());
		}
		catch (final RuntimeException ignore) { /* server going down: use the configured line */ }
		return uplink;
	}

	public void setUplinkLine(final int area, final int line) {
		uplink = new Line(area, line);
		last = null;
	}

	public void register(final CouplerPort p) {
		couplers.addIfAbsent(p);
		last = null;
	}

	public void unregister(final CouplerPort p) {
		couplers.remove(p);
		last = null;
	}

	/** Tells the topology on which line(s) the device behind {@code link} sits (asked again for every telegram). */
	public void attach(final Object link, final java.util.function.Supplier<Line[]> where) {
		senders.put(link, where);
		last = null;
	}

	public void detach(final Object link) {
		senders.remove(link);
		last = null;
	}

	/** Forget cached paths after a device was moved or a coupler changed. */
	public void changed() { last = null; }

	/** Where a telegram that came from {@code link} starts: the sender's line, or the interface line for ETS. */
	Line[] originOf(final Object link) {
		final var s = link == null ? null : senders.get(link);
		if (s != null)
			return s.get();
		return new Line[] { uplinkLine() };
	}

	/** Line of an individual address; devices without an address are assumed to sit on the interface line. */
	public Line lineOf(final IndividualAddress a) {
		return unaddressed(a) ? uplinkLine() : new Line(a.getArea(), a.getLine());
	}

	/** 15.15.x is the factory range (15.15.255; ETS may also leave 15.15.0 after an unload): not a place in the topology. */
	public static boolean unaddressed(final IndividualAddress a) {
		return a.getArea() == 15 && a.getLine() == 15;
	}

	static boolean isCoupler(final IndividualAddress a) {
		return a.getDevice() == 0 && a.getRawAddress() != 0 && !unaddressed(a);
	}

	/** The lines a device with this address is connected to (a coupler is connected to two lines). */
	public Line[] linesOf(final IndividualAddress a) {
		if (isCoupler(a)) {
			final Line child = new Line(a.getArea(), a.getLine());
			final Line parent = child.parent();
			return parent == null ? new Line[] { child } : new Line[] { child, parent };
		}
		return new Line[] { lineOf(a) };
	}

	/**
	 * The lines a telegram reaches and the hop count it has there, starting on the lines {@code origin}. Group telegrams
	 * spread to every line behind couplers that let them pass, individually addressed telegrams only follow couplers
	 * that route them.
	 */
	public Map<Line, Integer> reach(final CEMILData f, final Line[] origin) {
		final KNXAddress dst = f.getDestination();
		final String key = Arrays.toString(origin) + f.getSource() + ">" + dst + ":" + Arrays.hashCode(f.getPayload()) + ":"
				+ f.getHopCount();
		final long now = System.nanoTime();
		final Cached c = last;
		if (c != null && c.key.equals(key) && now - c.time < 30_000_000L)
			return c.reach;
		final var hop = new HashMap<Line, Integer>();
		final var queue = new ArrayDeque<Line>();
		final int h0 = f.getHopCount();
		for (final Line o : origin) {
			hop.put(o, h0);
			queue.add(o);
		}
		while (!queue.isEmpty()) {
			final Line x = queue.poll();
			final int h = hop.get(x);
			for (final CouplerPort p : couplers) {
				final Line child = p.subLine();
				if (child == null)
					continue; // not wired into the line structure
				final Line parent = child.parent();
				if (parent == null)
					continue;
				final Line other;
				final boolean fromMain;
				if (x.equals(parent)) {
					other = child;
					fromMain = true;
				}
				else if (x.equals(child)) {
					other = parent;
					fromMain = false;
				}
				else
					continue;
				if (hop.containsKey(other) || h == 0)
					continue;
				if (!p.route(f, fromMain).pass())
					continue;
				hop.put(other, h == 7 ? 7 : h - 1);
				queue.add(other);
			}
		}
		last = new Cached(key, now, hop);
		return hop;
	}

	/**
	 * The telegram as seen by a receiver on the given lines, or null if it never gets there (no coupler, blocked,
	 * filtered, hop count used up). {@code from} is the link the telegram was sent on. The hop count is lowered by every
	 * coupler crossed, as on a real installation.
	 */
	public CEMILData deliver(final CEMILData f, final Object from, final Line[] receiverLines) {
		if (!enabled)
			return f;
		return deliverToLines(f, originOf(from), receiverLines);
	}

	/** Same for the KNXnet/IP interface (ETS), which sits on {@link #uplinkLine()}. */
	public CEMILData deliverToUplink(final CEMILData f, final Object from) {
		if (!enabled)
			return f;
		return deliverToLines(f, originOf(from), new Line[] { uplinkLine() });
	}

	private CEMILData deliverToLines(final CEMILData f, final Line[] origin, final Line[] lines) {
		final var reach = reach(f, origin);
		Integer best = null;
		for (final Line l : lines) {
			final Integer h = reach.get(l);
			if (h != null && (best == null || h > best))
				best = h;
		}
		if (best == null)
			return null;
		if (best == f.getHopCount())
			return f;
		return new CEMILData(f.getMessageCode(), f.getSource(), f.getDestination(), f.getPayload(), f.getPriority(),
				f.isRepetition(), best);
	}
}
