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
 * A telegram only reaches another line if every coupler on the way exists in the installation and lets it pass.
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

		/** @param fromMain true when the telegram comes from the main side (the parent line) and wants to go to the sub side */
		Decision route(CEMILData frame, boolean fromMain);
	}

	private final CopyOnWriteArrayList<CouplerPort> couplers = new CopyOnWriteArrayList<>();
	private volatile boolean enabled;
	private volatile Line uplink = new Line(1, 1);

	private record Cached(String key, long time, Map<Line, Integer> reach) {}

	private volatile Cached last;

	public boolean enabled() { return enabled; }

	public void setEnabled(final boolean on) {
		enabled = on;
		last = null;
	}

	/** The line the KNXnet/IP interface (ETS) sits on. */
	public Line uplinkLine() { return uplink; }

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

	/** Line of an individual address; devices without an address are assumed to sit on the interface line. */
	public Line lineOf(final IndividualAddress a) {
		return a.getRawAddress() == 0xFFFF ? uplink : new Line(a.getArea(), a.getLine());
	}

	static boolean isCoupler(final IndividualAddress a) {
		return a.getDevice() == 0 && a.getRawAddress() != 0;
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
	 * The lines a telegram reaches and the hop count it has there. Group telegrams spread to every line behind couplers
	 * that let them pass, individually addressed telegrams only follow couplers that route them.
	 */
	public Map<Line, Integer> reach(final CEMILData f) {
		final KNXAddress dst = f.getDestination();
		final String key = f.getSource() + ">" + dst + ":" + Arrays.hashCode(f.getPayload()) + ":" + f.getHopCount();
		final long now = System.nanoTime();
		final Cached c = last;
		if (c != null && c.key.equals(key) && now - c.time < 30_000_000L)
			return c.reach;
		final var hop = new HashMap<Line, Integer>();
		final var queue = new ArrayDeque<Line>();
		final int h0 = f.getHopCount();
		for (final Line o : linesOf(f.getSource())) {
			hop.put(o, h0);
			queue.add(o);
		}
		while (!queue.isEmpty()) {
			final Line x = queue.poll();
			final int h = hop.get(x);
			for (final CouplerPort p : couplers) {
				final IndividualAddress pa = p.address();
				if (!isCoupler(pa))
					continue; // not placed on x.y.0 yet: not part of the topology
				final Line child = new Line(pa.getArea(), pa.getLine());
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
	 * The telegram as seen by a receiver with the given address, or null if it never gets there (no coupler, blocked,
	 * filtered, hop count used up). The hop count is lowered by every coupler crossed, as on a real installation.
	 */
	public CEMILData deliver(final CEMILData f, final IndividualAddress receiver) {
		if (!enabled || management(f))
			return f;
		return deliverToLines(f, linesOf(receiver));
	}

	/** Same for the KNXnet/IP interface (ETS). */
	public CEMILData deliverToUplink(final CEMILData f) {
		if (!enabled || management(f))
			return f;
		return deliverToLines(f, new Line[] { uplink });
	}

	/**
	 * ETS' own management traffic (addressing, programming mode, downloads: broadcasts and individually addressed
	 * telegrams from or to a tunnel address) is not stopped by couplers. Reason: in a simulator a device "moves" to its
	 * line as soon as it has an address, before ETS could read the address back; ETS could then never finish. Group
	 * telegrams and telegrams between devices are routed for real.
	 */
	private static boolean management(final CEMILData f) {
		final KNXAddress dst = f.getDestination();
		if (dst instanceof final io.calimero.GroupAddress g)
			return g.getRawAddress() == 0;
		return isTunnel(f.getSource()) || isTunnel((IndividualAddress) dst);
	}

	private static boolean isTunnel(final IndividualAddress a) { return a.getDevice() >= 241 && a.getDevice() <= 244; }

	private CEMILData deliverToLines(final CEMILData f, final Line[] lines) {
		final var reach = reach(f);
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
