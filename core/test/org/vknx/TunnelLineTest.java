package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.vknx.device.PushButtonBehavior;
import org.vknx.device.SwitchActuatorBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;

/**
 * Same mini-ETS download as SelfTest, but through the real KNXnet/IP tunneling server on localhost.
 * Shows that server + virtual line + devices are wired correctly. Still NOT an ETS test.
 */
public final class TunnelLineTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-tunnel");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener l = (s, c, t) -> { synchronized (trace) { trace.add(s.name() + " [" + c + "] " + t); } };
		final int port = 33672;
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		System.out.println("using host address " + host);
		final var net = Subnet.withServer(dir, "vKNX test", port);
		net.monitor(s -> { synchronized (trace) { trace.add("BUS " + s); } });
		final var pb = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 1 },
				dir.resolve("pb.state"), net, l);
		final var sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 2 },
				dir.resolve("sa.state"), net, l);
		Thread.sleep(2000);
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0),
				new InetSocketAddress(host, port), false,
				new TPSettings(new IndividualAddress(0)))) {
			Thread.sleep(0); System.out.println("tunnel established, client address " + tunnel.getKNXMedium().getDeviceAddress());
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			sa.toggleProgrammingMode();
			SelfTest.check("tunnel: address sequence for actuator", ets.assignAddress(new IndividualAddress(2, 3, 4)));
			SelfTest.check("tunnel: actuator addressed", SelfTest.await(() -> sa.address().equals(new IndividualAddress(2, 3, 4))));
			pb.toggleProgrammingMode();
			SelfTest.check("tunnel: address sequence for button", ets.assignAddress(new IndividualAddress(3, 5, 6)));
			SelfTest.check("tunnel: button addressed", SelfTest.await(() -> pb.address().equals(new IndividualAddress(3, 5, 6))));
			final int[] gas = { new GroupAddress(1, 0, 1).getRawAddress(), new GroupAddress(1, 0, 2).getRawAddress() };
			ets.downloadAll(new IndividualAddress(2, 3, 4), gas, new int[][] { { 1, 1 }, { 2, 9 } },
					new int[] { 0x17, 0, 0, 0, 0, 0, 0, 0, SelfTest.FLAGS_STATUS_OUT }, Products.SWITCH_ACTUATOR.defaultParams());
			ets.downloadAll(new IndividualAddress(3, 5, 6), gas, new int[][] { { 1, 1 }, { 2, 2 } },
					new int[] { SelfTest.FLAGS_SEND, SelfTest.FLAGS_STATUS_IN }, Products.PUSH_BUTTON.defaultParams());
			SelfTest.check("tunnel: both applications loaded", SelfTest.await(() -> sa.applicationLoaded() && pb.applicationLoaded()));
			pb.behavior().act("press", 1);
			SelfTest.check("tunnel: button press lights lamp", SelfTest.await(((SwitchActuatorBehavior) sa.behavior())::lampLit));
			Thread.sleep(300);
			synchronized (trace) {
				SelfTest.check("tunnel: ETS side saw the button telegram and the status telegram",
						trace.stream().anyMatch(s -> s.startsWith("BUS device") && s.contains("1/0/1"))
								&& trace.stream().anyMatch(s -> s.startsWith("BUS device") && s.contains("1/0/2")));
			}
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		if (SelfTest.failed > 0)
			synchronized (trace) { trace.forEach(System.out::println); }
		System.out.println(SelfTest.failed == 0 ? "TUNNEL TEST PASSED" : "TUNNEL TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
