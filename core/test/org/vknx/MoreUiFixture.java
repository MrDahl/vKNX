package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import org.vknx.product.Products;

/**
 * Test helper for UI screenshots of the presence detector, the colour dimming actuator and the energy meter: acts like
 * ETS against a RUNNING vKNX (KNXnet/IP port, web port as arguments). Devices expected: Presence1 (presence-detector),
 * Colour1 (colour-dimmer), Meter1 (energy-meter), Lamp (switch-actuator). Not a compatibility test.
 */
public final class MoreUiFixture {
	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static byte[] wN(final int... bytes) {
		final byte[] b = new byte[2 + bytes.length];
		b[1] = (byte) 0x80;
		for (int i = 0; i < bytes.length; i++)
			b[2 + i] = (byte) bytes[i];
		return b;
	}

	public static void main(final String[] args) throws Exception {
		final int knxPort = Integer.parseInt(args[0]);
		NewUiFixture.web = Integer.parseInt(args[1]);
		final int W = NewUiFixture.W, OUT = NewUiFixture.STATUS_OUT;
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, knxPort), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			final String[] names = { "Presence1", "Colour1", "Meter1", "Lamp" };
			int h = 1;
			final var addr = new java.util.HashMap<String, IndividualAddress>();
			for (final String n : names) {
				NewUiFixture.post("/api/prog?dev=" + n);
				final var a = new IndividualAddress(1, 1, h++);
				if (!ets.assignAddress(a))
					throw new IllegalStateException("address assignment failed for " + n);
				addr.put(n, a);
			}
			final int gLight = NewUiFixture.ga(2, 0, 1), gStatus = NewUiFixture.ga(2, 0, 2), gRgb = NewUiFixture.ga(2, 1, 1), gSw2 = NewUiFixture.ga(2, 1, 2),
					gCt = NewUiFixture.ga(2, 1, 3), gBri = NewUiFixture.ga(2, 1, 4);
			ets.downloadAll(addr.get("Lamp"), new int[] { gLight, gStatus }, new int[][] { { 1, 1 }, { 2, 9 } }, flags(17, 1, W, 9, OUT),
					Products.SWITCH_ACTUATOR.params("Out1Name", "Ceiling lamp"));
			ets.downloadAll(addr.get("Presence1"), new int[] { gLight }, new int[][] { { 1, 1 } }, flags(4, 1, OUT),
					Products.PRESENCE_DETECTOR.params("FollowTime", 2, "Threshold", 300));
			ets.downloadAll(addr.get("Colour1"), new int[] { gRgb, gSw2, gCt, gBri }, new int[][] { { 1, 4 }, { 2, 10 }, { 3, 14 }, { 4, 3 } },
					flags(18, 4, W, 10, W, 14, W, 3, W), Products.COLOUR_DIMMER.params("Ch1Name", "Sofa RGB", "Ch2Name", "Desk white"));
			ets.downloadAll(addr.get("Meter1"), new int[] { gStatus }, new int[][] { { 1, 7 } }, flags(10, 7, NewUiFixture.W | 0x80),
					Products.ENERGY_METER.params("Load1Name", "Ceiling lamp", "Load1Power", 60, "Load2Enable", 1, "Load2Name", "Oven", "Load2Power", 2000, "Limit", 400));
			Thread.sleep(1500);
			tunnel.sendRequest(new GroupAddress(gRgb), Priority.LOW, wN(0x20, 0x90, 0xFF));
			tunnel.sendRequest(new GroupAddress(gBri), Priority.LOW, wN(200));
			tunnel.sendRequest(new GroupAddress(gSw2), Priority.LOW, new byte[] { 0, (byte) 0x81 });
			tunnel.sendRequest(new GroupAddress(gCt), Priority.LOW, wN(2700 >> 8, 2700 & 0xff));
			NewUiFixture.post("/api/act?dev=Presence1&a=lux&v=120");
			NewUiFixture.post("/api/act?dev=Presence1&a=motion&v=0");
			NewUiFixture.post("/api/act?dev=Meter1&a=extra&v=350");
			Thread.sleep(2000);
			System.out.println("fixture done");
		}
		System.exit(0);
	}
}
