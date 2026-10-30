package org.vknx;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.vknx.device.CouplerBehavior;
import org.vknx.device.DimmerBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.cemi.CEMILData;
import io.calimero.mgmt.Destination;

import static org.vknx.SelfTest.check;

/**
 * Line routing and the coupler as ETS downloads it (mask 0x0912 procedure, router object, filter table). Follows
 * the MV-0912 procedure from knx_master.xml. NOT an ETS test: it shows that the simulator reacts correctly to that
 * procedure, not that ETS performs it exactly like this.
 */
public final class CouplerTest {
	static final int SEND = SelfTest.FLAGS_SEND, W = SelfTest.FLAGS_RECEIVE;
	static final int ROUTE = 1, BLOCK = 2, FILTER = 3;

	static int routerIndex(final SelfTest.EtsLikeClient ets, final Destination d) throws Exception {
		for (int i = 0; i < 20; i++) {
			try {
				final byte[] t = ets.mc.readProperty(d, i, 1, 1, 1);
				if (t.length >= 2 && (((t[0] & 0xff) << 8) | (t[1] & 0xff)) == 6)
					return i;
			}
			catch (final io.calimero.KNXException e) {
				break;
			}
		}
		throw new IllegalStateException("no router object found");
	}

	static int cfg(final int mode) { return mode << 2 | mode | 0x10; }

	/** The MV-0912 "load all" procedure: unload, load, properties 52-55, clear filter table, write it, load completed. */
	static void couplerDownload(final SelfTest.EtsLikeClient ets, final IndividualAddress addr, final int mainSub,
			final int subMain, final Set<Integer> routedGroups, final boolean fullWrite) throws Exception {
		final Destination d = ets.mc.createDestination(addr, true);
		try {
			final int idx = routerIndex(ets, d);
			ets.loadEvent(d, idx, (byte) 4);
			ets.loadEvent(d, idx, (byte) 1);
			ets.mc.rawPropertyWrite(d, idx, 52, new byte[] { 0x77 });
			ets.mc.rawPropertyWrite(d, idx, 53, new byte[] { 0x77 });
			ets.mc.rawPropertyWrite(d, idx, 54, new byte[] { (byte) cfg(mainSub) });
			ets.mc.rawPropertyWrite(d, idx, 55, new byte[] { (byte) cfg(subMain) });
			ets.mc.callFunctionProperty(d, idx, 56, (byte) 0, (byte) 1); // ClearLCFilterTable
			final byte[] table = new byte[8192];
			for (final int ga : routedGroups)
				table[ga >> 3] |= (byte) (1 << (ga & 7));
			for (int off = 0; off < table.length; off += 10) {
				final int n = Math.min(10, table.length - off);
				boolean any = false;
				for (int i = 0; i < n; i++)
					any |= table[off + i] != 0;
				if (fullWrite || any)
					ets.mc.writeMemory(d, 0x200 + off, java.util.Arrays.copyOfRange(table, off, off + n));
			}
			final int st = ets.loadEvent(d, idx, (byte) 2);
			if (st != 1)
				throw new IllegalStateException("router not Loaded after LOAD_COMPLETED: " + st);
		}
		finally {
			d.destroy();
		}
	}

	/** The MV-0912 "unload" procedure as ETS runs it: unload, load, inline defaults 0x77/0x17, clear table, load completed, unload. */
	static void couplerUnload(final SelfTest.EtsLikeClient ets, final IndividualAddress addr) throws Exception {
		final Destination d = ets.mc.createDestination(addr, true);
		try {
			final int idx = routerIndex(ets, d);
			ets.loadEvent(d, idx, (byte) 4);
			ets.loadEvent(d, idx, (byte) 1);
			ets.mc.rawPropertyWrite(d, idx, 52, new byte[] { 0x77 });
			ets.mc.rawPropertyWrite(d, idx, 53, new byte[] { 0x77 });
			ets.mc.rawPropertyWrite(d, idx, 54, new byte[] { 0x17 });
			ets.mc.rawPropertyWrite(d, idx, 55, new byte[] { 0x17 });
			ets.mc.callFunctionProperty(d, idx, 56, (byte) 0, (byte) 1);
			ets.loadEvent(d, idx, (byte) 2);
			ets.loadEvent(d, idx, (byte) 4);
		}
		finally {
			d.destroy();
		}
	}

	static boolean until(final long ms, final java.util.function.BooleanSupplier c) throws InterruptedException {
		final long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (c.getAsBoolean())
				return true;
			Thread.sleep(25);
		}
		return c.getAsBoolean();
	}

	static void press(final VirtualDevice pb, final int key) throws Exception {
		pb.behavior().act("down", key);
		Thread.sleep(250);
		pb.behavior().act("up", key);
	}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-coupler");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> {
			synchronized (trace) { trace.add(SelfTest.ms() + src.name() + " [" + cat + "] " + text); }
		};
		final var net = Subnet.inProcess();
		net.monitor(s -> { synchronized (trace) { trace.add(SelfTest.ms() + "BUS " + s); } });
		net.topology().setEnabled(true); // lines are real from the start
		final var ets = new SelfTest.EtsLikeClient(net.createDeviceLink(new IndividualAddress(1, 1, 250)));
		int serial = 40;
		try {
			final var lc11 = VirtualDevice.create("lc11", Products.COUPLER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("lc11.state"), net, listener);
			final var lc12 = VirtualDevice.create("lc12", Products.COUPLER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("lc12.state"), net, listener);
			final var pb = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("pb.state"), net, listener);
			final var dm = VirtualDevice.create("dm", Products.DIMMER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("dm.state"), net, listener);

			check("coupler is a TP/TP coupler for ETS: mask 0x0912 and a coupler product",
					Products.COUPLER.coupler() && org.vknx.tools.ProductXmlGenerator.generate(Products.COUPLER).contains("MaskVersion=\"MV-0912\"")
							&& org.vknx.tools.ProductXmlGenerator.generate(Products.COUPLER).contains("IsCoupler=\"true\"")
							&& !org.vknx.tools.ProductXmlGenerator.generate(Products.COUPLER).contains("RelativeSegment"));
			check("coupler without a placed address is not part of the line structure", "none".equals(((CouplerBehavior) lc11.behavior()).role()));

			// ---- placement: roles follow the address
			lc11.toggleProgrammingMode();
			check("coupler accepts 1.1.0 (line coupler for line 1.1)", ets.assignAddress(new IndividualAddress(1, 1, 0))
					&& until(3000, () -> lc11.address().equals(new IndividualAddress(1, 1, 0))));
			lc12.toggleProgrammingMode();
			check("coupler accepts 1.2.0 through coupler 1.1.0", ets.assignAddress(new IndividualAddress(1, 2, 0))
					&& until(3000, () -> lc12.address().equals(new IndividualAddress(1, 2, 0))));
			check("role of 1.1.0 is line, role of 1.2.0 is line", "line".equals(((CouplerBehavior) lc11.behavior()).role())
					&& "line".equals(((CouplerBehavior) lc12.behavior()).role()));

			// ---- devices: pb on line 1.1, dimmer on line 1.2 (downloaded through the factory-state couplers)
			pb.toggleProgrammingMode();
			check("push button 1.1.10", ets.assignAddress(new IndividualAddress(1, 1, 10)) && until(3000, () -> pb.address().getDevice() == 10));
			dm.toggleProgrammingMode();
			check("dimmer 1.2.20 (address assignment crosses the factory-state couplers)",
					ets.assignAddress(new IndividualAddress(1, 2, 20)) && until(3000, () -> dm.address().getDevice() == 20));
			final int gSw = SelfTest_ga(1, 0, 1);
			ets.downloadAll(pb.address(), new int[] { gSw }, new int[][] { { 1, 1 }, { 1, 6 } },
					new int[] { SEND, 0, 0, 0, 0, SEND },
					Products.PUSH_BUTTON.params("Key1Function", 1, "Key1LongPress", 0, "Key2Function", 1, "Key2DimDir", 1, "Key2LongPress", 0));
			ets.downloadAll(dm.address(), new int[] { gSw }, new int[][] { { 1, 1 } }, new int[] { W },
					Products.DIMMER.params("Ch1DimTime", 0, "Ch1OnLevel", 1));
			check("both devices loaded (download to 1.2.20 had to cross lines)", until(3000, () -> pb.applicationLoaded() && dm.applicationLoaded()));
			final var dim = (DimmerBehavior) dm.behavior();

			press(pb, 1);
			check("factory-state couplers route everything: ON from line 1.1 reaches the dimmer on line 1.2", until(2000, () -> dim.levelPct() == 100));
			press(pb, 2);
			check("OFF reaches it as well", until(2000, () -> dim.levelPct() == 0));

			// ---- the real procedure, group filter mode, filter table EMPTY (a group address nobody put in the table)
			couplerDownload(ets, lc11.address(), FILTER, FILTER, Set.of(), true); // full 8192-byte write once
			couplerDownload(ets, lc12.address(), FILTER, FILTER, Set.of(), false);
			check("couplers report loaded", lc11.applicationLoaded() && lc12.applicationLoaded() && "complete".equals(lc12.downloadState()));
			check("settings downloaded: filter, filter table empty", "filter".equals(lc12.behavior().view().get("groupMainSub"))
					&& Integer.valueOf(0).equals(lc12.behavior().view().get("filterCount")));
			final int blockedBefore = (Integer) lc11.behavior().view().get("blockedUp");
			press(pb, 1);
			Thread.sleep(400);
			check("filter mode with an empty table blocks the group telegram", dim.levelPct() == 0);
			check("the coupler counts and explains the blocked telegram", (Integer) lc11.behavior().view().get("blockedUp") > blockedBefore
					&& String.valueOf(lc11.behavior().view().get("lastBlocked")).contains("filter table"));

			// ---- only the second coupler knows the group: still blocked, now at the first coupler
			couplerDownload(ets, lc12.address(), FILTER, FILTER, Set.of(gSw), false);
			press(pb, 1);
			Thread.sleep(400);
			check("one coupler missing the filter entry is enough to block (coupler 1.1.0 has none)", dim.levelPct() == 0
					&& Integer.valueOf(1).equals(lc12.behavior().view().get("filterCount")));

			// ---- both know it
			couplerDownload(ets, lc11.address(), FILTER, FILTER, Set.of(gSw), false);
			press(pb, 1);
			check("with the group address in both filter tables the telegram passes", until(2000, () -> dim.levelPct() == 100));
			check("filter table content is readable in the panel view", ((List<?>) lc11.behavior().view().get("filter")).contains("1/0/1"));

			// ---- the user's experiment: unload the coupler completely, leave only the individual address
			couplerUnload(ets, lc11.address());
			check("after ETS unload the application is gone", !lc11.applicationLoaded() && "empty".equals(lc11.downloadState())
					&& "filter".equals(lc11.behavior().view().get("groupSubMain")));
			press(pb, 2);
			Thread.sleep(400);
			check("unloaded coupler (ETS leaves filter + empty table) blocks group telegrams: dimmer stays ON", dim.levelPct() == 100);

			// ---- block mode and route-all mode
			couplerDownload(ets, lc11.address(), BLOCK, BLOCK, Set.of(gSw), false);
			press(pb, 2);
			Thread.sleep(400);
			check("block mode blocks even though the group address is in the table", dim.levelPct() == 100);
			couplerDownload(ets, lc11.address(), ROUTE, ROUTE, Set.of(), false);
			press(pb, 2);
			check("route mode passes everything, the table is not consulted", until(2000, () -> dim.levelPct() == 0));

			// ---- physical filter decision (ETS inline default 0x77) and persistence
			final var lc12b = (CouplerBehavior) lc12.behavior();
			final var toDimmer = new CEMILData(CEMILData.MC_LDATA_IND, new IndividualAddress(1, 1, 250), new IndividualAddress(1, 2, 20),
					new byte[] { 0x00 }, Priority.LOW, false, 6);
			final var toOwnLine = new CEMILData(CEMILData.MC_LDATA_IND, new IndividualAddress(1, 1, 250), new IndividualAddress(1, 1, 99),
					new byte[] { 0x00 }, Priority.LOW, false, 6);
			check("physical filter: main to sub passes for an address behind the coupler, not for another line",
					lc12b.route(toDimmer, true).pass() && !lc12b.route(toOwnLine, true).pass());
			check("physical filter: sub to main passes for addresses outside, blocks addresses behind it",
					lc12b.route(toOwnLine, false).pass() && !lc12b.route(toDimmer, false).pass());

			lc12.close();
			final var again = VirtualDevice.create("lc12", Products.COUPLER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 41 },
					dir.resolve("lc12.state"), net, listener);
			check("coupler keeps address, settings and filter table across a restart of vKNX", again.address().equals(new IndividualAddress(1, 2, 0))
					&& again.applicationLoaded() && Integer.valueOf(1).equals(again.behavior().view().get("filterCount"))
					&& "filter".equals(again.behavior().view().get("groupMainSub")));

			// ---- routing switched off = old flat behaviour
			couplerDownload(ets, lc11.address(), BLOCK, BLOCK, Set.of(), false);
			net.topology().setEnabled(false);
			press(pb, 1);
			check("routing off (flat line): couplers have no effect", until(2000, () -> dim.levelPct() == 100));
		}
		finally {
			if (SelfTest.failed > 0 || System.getProperty("vknx.trace") != null)
				synchronized (trace) { trace.stream().skip(Math.max(0, trace.size() - 120)).forEach(System.out::println); }
		}
		System.out.println(SelfTest.failed == 0 ? "ALL PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}

	static int SelfTest_ga(final int a, final int b, final int c) { return new GroupAddress(a, b, c).getRawAddress(); }
}
