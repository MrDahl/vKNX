package org.vknx.device;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.vknx.sim.Topology;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.cemi.CEMILData;

/**
 * The coupler: one product for line couplers (x.y.0) and area couplers (x.0.0); the role follows from the address ETS
 * gave it. Routing decisions are made from exactly what ETS downloaded: the router object properties 52-55 and the
 * group filter table (see {@code docs/decisions.md}, D12). No download, no filter entries: telegrams are blocked.
 */
public final class CouplerBehavior extends DeviceBehavior implements Topology.CouplerPort {
	static final int PID_PHYS_MAIN = 52, PID_PHYS_SUB = 53, PID_GROUP_MAIN = 54, PID_GROUP_SUB = 55;

	// counters: [0] main -> sub, [1] sub -> main; routed / blocked
	private final AtomicInteger[] routed = { new AtomicInteger(), new AtomicInteger() };
	private final AtomicInteger[] blocked = { new AtomicInteger(), new AtomicInteger() };
	private final String[] lastKey = new String[2];
	private volatile String lastBlocked = "";

	CouplerBehavior(final VirtualDevice dev) { super(dev); }

	// ------------------------------------------------------------------ roles

	@Override
	public IndividualAddress address() { return dev.address(); }

	/** "area", "line" or "none" (address not x.y.0 yet, so the coupler is not part of the line structure). */
	public String role() {
		final var a = dev.address();
		if (a.getDevice() != 0 || a.getRawAddress() == 0)
			return "none";
		return a.getLine() == 0 ? "area" : "line";
	}

	static String mode(final int v) {
		return switch (v & 3) {
			case 1 -> "route all";
			case 2 -> "block";
			case 3 -> "filter";
			default -> "not set";
		};
	}

	// ------------------------------------------------------------------ routing

	@Override
	public Topology.Decision route(final CEMILData f, final boolean fromMain) {
		final int dir = fromMain ? 0 : 1;
		final var dst = f.getDestination();
		Topology.Decision d;
		if (dst instanceof final GroupAddress ga)
			d = routeGroup(ga.getRawAddress(), fromMain);
		else
			d = routePhysical((IndividualAddress) dst, fromMain);
		final String key = f.getSource() + ">" + dst + ":" + java.util.Arrays.hashCode(f.getPayload()) + ":" + f.getHopCount();
		synchronized (lastKey) {
			if (!key.equals(lastKey[dir])) {
				lastKey[dir] = key;
				(d.pass() ? routed : blocked)[dir].incrementAndGet();
				if (!d.pass()) {
					lastBlocked = f.getSource() + " -> " + dst + ": " + d.why();
					dev.log("coupler", (fromMain ? "main -> sub" : "sub -> main") + " blocked " + lastBlocked);
				}
				dev.changed();
			}
		}
		return d;
	}

	private Topology.Decision routeGroup(final int raw, final boolean fromMain) {
		final String side = fromMain ? "main -> sub" : "sub -> main";
		if (raw == 0) { // broadcast
			final int sel = dev.routerByte(fromMain ? PID_PHYS_MAIN : PID_PHYS_SUB) >> 2 & 3;
			return sel == 2 ? Topology.Decision.block("broadcast blocked (" + side + ")") : Topology.Decision.PASS;
		}
		final int cfg = dev.routerByte(fromMain ? PID_GROUP_MAIN : PID_GROUP_SUB);
		final boolean high = raw >= 0x7000; // main groups 14-31 have their own setting
		final int sel = high ? cfg >> 2 & 3 : cfg & 3;
		final String range = high ? "main groups 14-31" : "main groups 0-13";
		return switch (sel) {
			case 1 -> Topology.Decision.PASS;
			case 2 -> Topology.Decision.block(range + " set to block (" + side + ")");
			case 3 -> dev.filterBit(raw) ? Topology.Decision.PASS
					: Topology.Decision.block("not in the filter table (" + side + ", " + range + " set to filter)");
			default -> Topology.Decision.block(range + " routing not set (" + side + ")");
		};
	}

	private Topology.Decision routePhysical(final IndividualAddress dst, final boolean fromMain) {
		final String side = fromMain ? "main -> sub" : "sub -> main";
		final int sel = dev.routerByte(fromMain ? PID_PHYS_MAIN : PID_PHYS_SUB) & 3;
		switch (sel) {
			case 1:
				return Topology.Decision.PASS;
			case 2:
				return Topology.Decision.block("individually addressed telegrams blocked (" + side + ")");
			case 3: {
				final var me = dev.address();
				final boolean inSub = dst.getArea() == me.getArea() && (me.getLine() == 0 || dst.getLine() == me.getLine());
				return (fromMain == inSub) ? Topology.Decision.PASS
						: Topology.Decision.block("destination " + dst + " is " + (fromMain ? "not behind" : "behind")
								+ " this coupler (" + side + ", individually addressed set to filter)");
			}
			default:
				return Topology.Decision.block("individual routing not set (" + side + ")");
		}
	}

	// ------------------------------------------------------------------ lifecycle / view

	@Override
	void onApplicationLoaded() {
		dev.log("coupler", "download complete: " + settingsText() + "; filter table has " + dev.filterCount()
				+ " group address(es)");
	}

	private String settingsText() {
		return "group main->sub " + mode(dev.routerByte(PID_GROUP_MAIN)) + " / " + modeHigh(dev.routerByte(PID_GROUP_MAIN))
				+ ", sub->main " + mode(dev.routerByte(PID_GROUP_SUB)) + " / " + modeHigh(dev.routerByte(PID_GROUP_SUB))
				+ ", physical main->sub " + mode(dev.routerByte(PID_PHYS_MAIN)) + ", sub->main " + mode(dev.routerByte(PID_PHYS_SUB));
	}

	private static String modeHigh(final int cfg) { return mode(cfg >> 2) + " (groups 14-31)"; }

	@Override void onObjectReceived(final int go, final int value) {}

	@Override Integer valueOf(final int go) { return null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("role", role());
		m.put("loaded", dev.applicationLoaded());
		m.put("routing", dev.address().getRawAddress() != 0xFFFF);
		m.put("physMainSub", mode(dev.routerByte(PID_PHYS_MAIN)));
		m.put("physSubMain", mode(dev.routerByte(PID_PHYS_SUB)));
		m.put("groupMainSub", mode(dev.routerByte(PID_GROUP_MAIN)));
		m.put("groupMainSubHigh", mode(dev.routerByte(PID_GROUP_MAIN) >> 2));
		m.put("groupSubMain", mode(dev.routerByte(PID_GROUP_SUB)));
		m.put("groupSubMainHigh", mode(dev.routerByte(PID_GROUP_SUB) >> 2));
		m.put("filterCount", dev.filterCount());
		final var list = new ArrayList<String>();
		for (final int ga : dev.filterAddresses(60))
			list.add(new GroupAddress(ga).toString());
		m.put("filter", list);
		m.put("routedDown", routed[0].get());
		m.put("blockedDown", blocked[0].get());
		m.put("routedUp", routed[1].get());
		m.put("blockedUp", blocked[1].get());
		m.put("lastBlocked", lastBlocked);
		return m;
	}
}
