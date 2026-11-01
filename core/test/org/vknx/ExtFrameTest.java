package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.ManagementClientImpl;

/** Long (extended) frames from a tunnelling client (what ETS sends to a secure device) must reach the device. */
public final class ExtFrameTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-ext");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener l = (s, c, t) -> { synchronized (trace) { trace.add(s.name() + " [" + c + "] " + t); } };
		final int port = 33675;
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		final var net = Subnet.withServer(dir, "vKNX ext", port);
		net.monitor(s -> { synchronized (trace) { trace.add("BUS " + s); } });
		final var sb = VirtualDevice.create("sb", Products.SECURE_PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 7 },
				dir.resolve("sb.state"), net, l);
		Thread.sleep(2000);
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, port), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			sb.toggleProgrammingMode();
			SelfTest.check("addressed", ets.assignAddress(new IndividualAddress(1, 1, 5)) && SelfTest.await(() -> sb.address().equals(new IndividualAddress(1, 1, 5))));
			final var mc = ets.mc;
			final var d = mc.createDestination(new IndividualAddress(1, 1, 5), true);
			mc.writeMemory(d, 0x1000, new byte[] { 1 });
			SelfTest.check("short memory write answered", true);
			try {
				mc.writeMemory(d, 0x1002, new byte[40]);
				SelfTest.check("long (extended) memory write answered", true);
			}
			catch (final Exception e) {
				SelfTest.check("long (extended) memory write answered: " + e, false);
			}
			d.destroy();
		}
		catch (final Throwable t) { t.printStackTrace(System.out); SelfTest.failed++; }
		if (SelfTest.failed > 0)
			synchronized (trace) { trace.stream().skip(Math.max(0, trace.size() - 40)).forEach(System.out::println); }
		System.out.println(SelfTest.failed == 0 ? "EXT TEST PASSED" : "EXT TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
