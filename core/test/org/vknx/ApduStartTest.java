package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import io.calimero.knxnetip.Discoverer;
import io.calimero.knxnetip.util.DIB;
import io.calimero.knxnetip.util.Srp;
import io.calimero.knxnetip.util.TunnelingDib;
import org.vknx.sim.Subnet;

/**
 * The interface's maximum APDU length right after the server has started, many starts in a row. Calimero's server writes
 * the default (15) into the interface objects while it starts; if vKNX sets 254 too early the 15 comes afterwards and
 * stays (the property is read only for clients), so ETS refuses downloads that need long frames until vKNX is restarted.
 * Own test, NOT an ETS compatibility test.
 */
public final class ApduStartTest {
	public static void main(final String[] args) throws Exception {
		final int runs = args.length > 0 ? Integer.parseInt(args[0]) : 40;
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		int bad = 0;
		for (int i = 0; i < runs; i++) {
			final int port = 33700 + i % 90;
			final var net = Subnet.withServer(Files.createTempDirectory("vknx-start"), "vKNX start test", port);
			final var server = new InetSocketAddress(host, port);
			int dib = -1;
			for (int attempt = 0; attempt < 4 && dib < 0; attempt++) {
				try {
					final var res = new Discoverer(0, false).search(server, Srp.withDeviceDescription(DIB.TunnelingInfo)).get(3, TimeUnit.SECONDS).response();
					dib = res.description().stream().filter(d -> d instanceof TunnelingDib).map(d -> ((TunnelingDib) d).maxApduLength()).findFirst().orElse(-1);
				}
				catch (final Exception e) { Thread.sleep(300); }
			}
			Thread.sleep(500);
			final byte[] p68 = net.serverObjects().getProperty(8, 1, 68, 1, 1);
			final int later = ((p68[0] & 0xff) << 8) | (p68[1] & 0xff);
			final boolean ok = dib == 254 && later == 254;
			if (!ok)
				bad++;
			System.out.println((ok ? "PASS" : "FAIL") + "  start " + (i + 1) + ": right after start " + dib + ", half a second later " + later);
			net.close();
			Thread.sleep(300);
		}
		System.out.println(bad == 0 ? "APDU START TEST PASSED" : bad + " of " + runs + " starts ended with the wrong maximum APDU length");
		System.exit(bad == 0 ? 0 : 1);
	}
}
