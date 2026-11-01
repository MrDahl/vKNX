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
public final class TunnelTest {
	/**
	 * ETS changes the interface address with the tunneling feature "IndividualAddress" (Local Interface Settings). A real
	 * interface accepts any free address; the recorded ETS error was "Failed to write Feature IndividualAddress7018:
	 * E_DATA_VOID" for 7.0.24 on an interface sitting in 1.0.
	 */
	static void interfaceAddressChange(final Subnet net, final InetAddress host, final int port) throws Exception {
		final var answers = new java.util.concurrent.LinkedBlockingQueue<io.calimero.knxnetip.servicetype.TunnelingFeature>();
		try (var t = new io.calimero.knxnetip.KNXnetIPTunnel(io.calimero.knxnetip.KNXnetIPTunnel.TunnelingLayer.LinkLayer,
				new InetSocketAddress(host, 0), new InetSocketAddress(host, port), false)) {
			t.addConnectionListener(new io.calimero.knxnetip.TunnelingListener() {
				@Override public void frameReceived(final io.calimero.FrameEvent e) {}
				@Override public void featureResponse(final io.calimero.knxnetip.servicetype.TunnelingFeature f) { answers.add(f); }
				@Override public void featureInfo(final io.calimero.knxnetip.servicetype.TunnelingFeature f) {}
			});
			final var feature = io.calimero.knxnetip.servicetype.TunnelingFeature.InterfaceFeature.IndividualAddress;
			t.send(feature, new IndividualAddress(7, 0, 24).toByteArray());
			final var res = answers.poll(3, java.util.concurrent.TimeUnit.SECONDS);
			SelfTest.check("interface address 7.0.24 (outside the interface's own line) is accepted: "
					+ (res == null ? "no answer" : res.status()), res != null && res.status() == io.calimero.ReturnCode.Success);
			SelfTest.check("the server now reports the tunnel as 7.0.24", net.linkStatus().tunnels().stream()
					.anyMatch(x -> x.address().equals("7.0.24")));
			net.topology().setEnabled(true);
			SelfTest.check("the line ETS sits on follows the new address", net.topology().uplinkLine().equals(
					new org.vknx.sim.Topology.Line(7, 0)));
			t.send(feature, new IndividualAddress(1, 1, 242).toByteArray());
			final var res2 = answers.poll(3, java.util.concurrent.TimeUnit.SECONDS);
			SelfTest.check("an address the interface offers for another tunnel is refused", res2 != null
					&& res2.status() != io.calimero.ReturnCode.Success);
			net.topology().setEnabled(false);
		}
		catch (final Throwable e) {
			e.printStackTrace(System.out);
			SelfTest.failed++;
		}
	}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-tunnel");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener l = (s, c, t) -> { synchronized (trace) { trace.add(s.name() + " [" + c + "] " + t); } };
		final int port = 33671;
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
		{
			// ETS refuses to download KNX Data Secure applications when the interface reports an APDU of only 15 bytes
			final var res = new io.calimero.knxnetip.Discoverer(0, false)
					.search(new InetSocketAddress(host, port), io.calimero.knxnetip.util.Srp.withDeviceDescription(io.calimero.knxnetip.util.DIB.TunnelingInfo))
					.get(5, java.util.concurrent.TimeUnit.SECONDS).response();
			final var tdib = res.description().stream().filter(d -> d instanceof io.calimero.knxnetip.util.TunnelingDib)
					.map(d -> (io.calimero.knxnetip.util.TunnelingDib) d).findFirst();
			SelfTest.check("interface reports a long max APDU (254) to ETS in the tunnelling DIB: "
					+ tdib.map(d -> d.maxApduLength()).orElse(-1), tdib.isPresent() && tdib.get().maxApduLength() == 254);
		}
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0),
				new InetSocketAddress(host, port), false,
				new TPSettings(new IndividualAddress(0)))) {
			Thread.sleep(0); System.out.println("tunnel established, client address " + tunnel.getKNXMedium().getDeviceAddress());
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			sa.toggleProgrammingMode();
			SelfTest.check("tunnel: address sequence for actuator", ets.assignAddress(new IndividualAddress(1, 1, 2)));
			SelfTest.check("tunnel: actuator addressed", SelfTest.await(() -> sa.address().equals(new IndividualAddress(1, 1, 2))));
			pb.toggleProgrammingMode();
			SelfTest.check("tunnel: address sequence for button", ets.assignAddress(new IndividualAddress(1, 1, 1)));
			SelfTest.check("tunnel: button addressed", SelfTest.await(() -> pb.address().equals(new IndividualAddress(1, 1, 1))));
			final int[] gas = { new GroupAddress(1, 0, 1).getRawAddress(), new GroupAddress(1, 0, 2).getRawAddress() };
			ets.downloadAll(new IndividualAddress(1, 1, 2), gas, new int[][] { { 1, 1 }, { 2, 9 } },
					new int[] { 0x17, 0, 0, 0, 0, 0, 0, 0, SelfTest.FLAGS_STATUS_OUT }, Products.SWITCH_ACTUATOR.defaultParams());
			ets.downloadAll(new IndividualAddress(1, 1, 1), gas, new int[][] { { 1, 1 }, { 2, 2 } },
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
		interfaceAddressChange(net, host, port);
		if (SelfTest.failed > 0)
			synchronized (trace) { trace.forEach(System.out::println); }
		System.out.println(SelfTest.failed == 0 ? "TUNNEL TEST PASSED" : "TUNNEL TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
