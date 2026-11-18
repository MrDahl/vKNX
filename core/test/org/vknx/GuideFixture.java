package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import org.vknx.product.Products;

/**
 * Builds the installation of {@code docs/ets-step-by-step.md} against a RUNNING vKNX (arguments: KNXnet/IP port, web port)
 * the way ETS would: two couplers (1.1.0, 1.2.0), Panel1, Switch1, Dimmer1, Blinds1 on line 1.1 and Colour1, Display1,
 * Master on line 1.2, with the group addresses, parameters and links of that guide, then the couplers with their filter
 * tables. vKNX must be started with {@code --line 1.0} and the nine devices added (the names below). Used for the
 * screenshots in the guide and as a check that the guide's table works. Not a compatibility test.
 */
public final class GuideFixture {
	static final int W = NewUiFixture.W, SEND = NewUiFixture.SEND, OUT = NewUiFixture.STATUS_OUT, IN = NewUiFixture.W | 0x80;

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static int ga(final int a, final int b, final int c) { return NewUiFixture.ga(a, b, c); }

	public static void main(final String[] args) throws Exception {
		final int knxPort = Integer.parseInt(args[0]);
		NewUiFixture.web = Integer.parseInt(args[1]);
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, knxPort), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			final String[] names = { "Coupler11", "Coupler12", "Panel1", "Switch1", "Dimmer1", "Blinds1", "Colour1", "Display1", "Master" };
			final String[] addrs = { "1.1.0", "1.2.0", "1.1.1", "1.1.2", "1.1.3", "1.1.4", "1.2.1", "1.2.2", "1.2.3" };
			final var addr = new java.util.HashMap<String, IndividualAddress>();
			final java.util.List<String> order = java.util.List.of(names);
			final int from = args.length > 2 ? ("couplers".equals(args[2]) ? names.length : order.indexOf(args[2])) : 0; // optional: continue at this device (the earlier ones are done)
			for (int i = 0; i < names.length; i++) {
				if (i < from) { addr.put(names[i], new IndividualAddress(addrs[i])); continue; }
				NewUiFixture.post("/api/prog?dev=" + names[i]);
				final var a = new IndividualAddress(addrs[i]);
				if (!ets.assignAddress(a))
					throw new IllegalStateException("address assignment failed for " + names[i]);
				addr.put(names[i], a);
				System.out.println("addressed " + names[i] + " " + a);
			}
			// Panel1: key 1 switch, key 2 dim brighter, key 3 dim darker, key 4 blind
			if (order.indexOf("Panel1") >= from) ets.downloadAll(addr.get("Panel1"), new int[] { ga(0, 0, 1), ga(0, 0, 2), ga(0, 1, 1), ga(0, 1, 3), ga(0, 1, 2), ga(1, 0, 1), ga(1, 0, 2) },
					new int[][] { { 1, 1 }, { 2, 2 }, { 3, 6 }, { 3, 11 }, { 4, 7 }, { 4, 12 }, { 5, 8 }, { 5, 13 }, { 6, 19 }, { 7, 20 } },
					flags(20, 1, SEND, 2, IN, 6, SEND, 7, IN, 8, SEND, 11, SEND, 12, IN, 13, SEND, 19, SEND, 20, SEND),
					Products.PUSH_BUTTON.params("Key1Name", "Ceiling light", "Key1Function", 0, "Key2Name", "Table lamp brighter", "Key2Function", 1, "Key2DimDir", 0,
							"Key3Name", "Table lamp darker", "Key3Function", 1, "Key3DimDir", 1, "Key4Name", "Blind south", "Key4Function", 2, "Key4BlindMode", 2));
			// Switch1: output 1 only; own address first, All off second
			final Object[] swp = new Object[2 + 7 * 2];
			swp[0] = "Out1Name"; swp[1] = "Ceiling light";
			for (int o = 2; o <= 8; o++) { swp[2 * (o - 1)] = "Out" + o + "Enable"; swp[2 * (o - 1) + 1] = 0; }
			if (order.indexOf("Switch1") >= from) ets.downloadAll(addr.get("Switch1"), new int[] { ga(0, 0, 1), ga(0, 0, 2), ga(2, 0, 1) },
					new int[][] { { 1, 1 }, { 3, 1 }, { 2, 9 } }, flags(9, 1, W, 9, OUT), Products.SWITCH_ACTUATOR.params(swp));
			// Dimmer1: channel 1 only
			if (order.indexOf("Dimmer1") >= from) ets.downloadAll(addr.get("Dimmer1"), new int[] { ga(0, 1, 1), ga(0, 1, 2), ga(0, 1, 3), ga(0, 1, 4), ga(2, 0, 1) },
					new int[][] { { 1, 1 }, { 5, 1 }, { 2, 2 }, { 3, 4 }, { 4, 5 } }, flags(5, 1, W, 2, W, 3, W, 4, OUT, 5, OUT),
					Products.DIMMER.params("Ch1Name", "Table lamp", "Ch2Enable", 0, "Ch3Enable", 0, "Ch4Enable", 0));
			// Blinds1: two blinds
			if (order.indexOf("Blinds1") >= from) ets.downloadAll(addr.get("Blinds1"), new int[] { ga(1, 0, 1), ga(1, 0, 2), ga(1, 0, 3), ga(1, 1, 1), ga(2, 0, 2), ga(2, 0, 3) },
					new int[][] { { 1, 1 }, { 5, 1 }, { 2, 2 }, { 6, 2 }, { 3, 3 }, { 5, 4 }, { 6, 5 }, { 4, 6 } },
					flags(6, 1, W, 2, W, 3, OUT, 4, W, 5, W, 6, OUT),
					Products.BLIND_ACTUATOR.params("Ch1Name", "Blind south", "Ch1UpTime", 10, "Ch1DownTime", 10, "Ch2Name", "Blind west", "Ch2UpTime", 10, "Ch2DownTime", 10,
							"Ch3Enable", 0, "Ch4Enable", 0));
			// Colour1: channel 1 RGB, channel 2 tunable white
			if (order.indexOf("Colour1") >= from) ets.downloadAll(addr.get("Colour1"),
					new int[] { ga(3, 0, 1), ga(3, 0, 2), ga(3, 0, 3), ga(3, 0, 4), ga(3, 0, 5), ga(3, 0, 6), ga(3, 1, 1), ga(3, 1, 2), ga(3, 1, 3), ga(3, 1, 4), ga(3, 1, 5), ga(3, 1, 6), ga(2, 0, 1) },
					new int[][] { { 1, 1 }, { 13, 1 }, { 2, 3 }, { 3, 4 }, { 4, 6 }, { 5, 7 }, { 6, 8 }, { 7, 10 }, { 13, 10 }, { 8, 12 }, { 9, 14 }, { 10, 15 }, { 11, 16 }, { 12, 18 } },
					flags(18, 1, W, 3, W, 4, W, 6, OUT, 7, OUT, 8, OUT, 10, W, 12, W, 14, W, 15, OUT, 16, OUT, 18, OUT),
					Products.COLOUR_DIMMER.params("Ch1Name", "Mood light", "Ch2Name", "Desk light"));
			// Display1: screen 1 values, screen 2 light control RGB, screen 3 light control tunable white
			final int L2 = Products.displayLightObject(2, 0), L3 = Products.displayLightObject(3, 0);
			if (order.indexOf("Display1") >= from) ets.downloadAll(addr.get("Display1"),
					new int[] { ga(0, 0, 2), ga(0, 1, 4), ga(1, 0, 3), ga(1, 1, 1), ga(2, 0, 4),
						ga(3, 0, 1), ga(3, 0, 2), ga(3, 0, 3), ga(3, 0, 4), ga(3, 0, 5), ga(3, 0, 6), ga(3, 1, 1), ga(3, 1, 2), ga(3, 1, 3), ga(3, 1, 4), ga(3, 1, 5), ga(3, 1, 6) },
					new int[][] { { 5, 1 }, { 1, Products.displayObject(1, 1, 0) }, { 2, Products.displayObject(1, 2, 1) }, { 3, Products.displayObject(1, 3, 1) }, { 4, Products.displayObject(1, 4, 1) },
						{ 6, L2 }, { 7, L2 + 1 }, { 8, L2 + 2 }, { 9, L2 + 4 }, { 10, L2 + 5 }, { 11, L2 + 6 },
						{ 12, L3 }, { 13, L3 + 1 }, { 14, L3 + 3 }, { 15, L3 + 4 }, { 16, L3 + 5 }, { 17, L3 + 7 } },
					flags(L3 + 7, 1, W, Products.displayObject(1, 1, 0), IN, Products.displayObject(1, 2, 1), IN, Products.displayObject(1, 3, 1), IN, Products.displayObject(1, 4, 1), IN,
							L2, SEND, L2 + 1, SEND, L2 + 2, SEND, L2 + 4, IN, L2 + 5, IN, L2 + 6, IN, L3, SEND, L3 + 1, SEND, L3 + 3, SEND, L3 + 4, IN, L3 + 5, IN, L3 + 7, IN),
					Products.INFO_DISPLAY.params("S1Name", "Overview", "S1R1Type", 1, "S1R1Label", "Ceiling", "S1R2Type", 2, "S1R2Label", "Table lamp",
							"S1R3Type", 2, "S1R3Label", "Blind S", "S1R4Type", 2, "S1R4Label", "Blind W",
							"S2Enable", 1, "S2Name", "Mood light", "S2Kind", 1, "S2Colour", 1, "S3Enable", 1, "S3Name", "Desk light", "S3Kind", 1, "S3Colour", 2));
			// Master: all off, blinds down, blinds up, next screen
			if (order.indexOf("Master") >= from) ets.downloadAll(addr.get("Master"), new int[] { ga(2, 0, 1), ga(2, 0, 2), ga(2, 0, 3), ga(2, 0, 4) },
					new int[][] { { 1, 1 }, { 2, 9 }, { 3, 10 }, { 2, 14 }, { 3, 15 }, { 4, 16 } },
					flags(16, 1, SEND, 9, SEND, 10, SEND, 14, SEND, 15, SEND, 16, SEND),
					Products.PUSH_BUTTON.params("Key1Name", "All off", "Key1Function", 0, "Key1SwitchMode", 1,
							"Key2Name", "Blinds down", "Key2Function", 2, "Key2BlindMode", 1, "Key2BlindShort", 1,
							"Key3Name", "Blinds up", "Key3Function", 2, "Key3BlindMode", 0, "Key3BlindShort", 1,
							"Key4Name", "Next screen", "Key4Function", 0, "Key4SwitchMode", 0));
			// the couplers last: the group addresses that have members on both sides of a line (what ETS puts in the filter table)
			final Set<Integer> crossing = Set.of(ga(0, 0, 2), ga(0, 1, 4), ga(1, 0, 3), ga(1, 1, 1), ga(2, 0, 1), ga(2, 0, 2), ga(2, 0, 3));
			CouplerTest.couplerDownload(ets, addr.get("Coupler11"), CouplerTest.FILTER, CouplerTest.FILTER, crossing, true);
			CouplerTest.couplerDownload(ets, addr.get("Coupler12"), CouplerTest.FILTER, CouplerTest.FILTER, crossing, false);
			Thread.sleep(1500);
			System.out.println("guide fixture done");
		}
		System.exit(0);
	}
}
