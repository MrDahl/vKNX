package org.vknx;

import static org.vknx.SelfTest.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.calimero.IndividualAddress;
import io.calimero.knxnetip.Discoverer;
import io.calimero.knxnetip.KNXnetIPTunnel;
import io.calimero.knxnetip.TcpConnection;
import io.calimero.knxnetip.servicetype.TunnelingFeature;
import io.calimero.knxnetip.util.DIB;
import io.calimero.knxnetip.util.Srp;
import io.calimero.knxnetip.util.TunnelingDib;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.LocalDeviceManagementIp;
import org.vknx.sim.Subnet;

/**
 * The interface's maximum APDU length has to stay at 254 whatever ETS does with its connections: ETS reads it when it
 * connects and refuses downloads that need long frames ("restricted to 15 by capabilities of the bus interface") if it
 * sees 15. Every way a client can reach the server (TCP and UDP tunnels, device management, a changed interface
 * address, connections that end abruptly, many connections in a row) is tried, and the value is read back after each.
 * Own test, NOT an ETS compatibility test.
 */
public final class ApduStaysTest {
	static int dib(final InetSocketAddress server, final InetAddress host) throws Exception {
		final var res = new Discoverer(0, false).search(server, Srp.withDeviceDescription(DIB.TunnelingInfo)).get(5, TimeUnit.SECONDS).response();
		return res.description().stream().filter(d -> d instanceof TunnelingDib).map(d -> ((TunnelingDib) d).maxApduLength()).findFirst().orElse(-1);
	}

	static int prop(final LocalDeviceManagementIp ad, final int obj, final int pid) throws Exception {
		final byte[] v = ad.getProperty(obj, pid, 1, 1);
		return ((v[0] & 0xff) << 8) | (v[1] & 0xff);
	}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-apdu");
		final int port = 33681;
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		final var net = Subnet.withServer(dir, "vKNX apdu test", port);
		final var server = new InetSocketAddress(host, port);
		final var local = new InetSocketAddress(host, 0);
		Thread.sleep(2000);
		final List<String> seen = new ArrayList<>();
		final Runnable[] noop = {};
		try {
			final java.util.function.Consumer<String> after = step -> {
				try (var ad = LocalDeviceManagementIp.newAdapter(local, server, false, false, e -> {})) {
					final int d = dib(server, host), p56 = prop(ad, 0, 56), p68 = prop(ad, 6, 68);
					seen.add(step + ": DIB " + d + ", device object " + p56 + ", cEMI server object " + p68);
					check(step + " (DIB " + d + ", pid 56 = " + p56 + ", pid 68 = " + p68 + ")", d == 254 && p56 == 254 && p68 == 254);
				}
				catch (final Exception e) {
					e.printStackTrace(System.out);
					SelfTest.failed++;
				}
			};
			after.accept("at start");

			for (int i = 0; i < 3; i++)
				try (var t = KNXNetworkLinkIP.newTunnelingLink(local, server, false, new TPSettings(new IndividualAddress(0)))) { Thread.sleep(300); }
			after.accept("after 3 UDP tunnels were opened and closed");

			for (int i = 0; i < 3; i++)
				try (var c = TcpConnection.newTcpConnection(local, server); var t = KNXNetworkLinkIP.newTunnelingLink(c, new TPSettings(new IndividualAddress(0)))) { Thread.sleep(300); }
			after.accept("after 3 TCP tunnels (ETS uses TCP) were opened and closed");

			// the TCP connection is dropped without a disconnect request
			{
				final var c = TcpConnection.newTcpConnection(local, server);
				final var t = KNXNetworkLinkIP.newTunnelingLink(c, new TPSettings(new IndividualAddress(0)));
				Thread.sleep(300);
				c.close(); // closes the socket under the tunnel
				try { t.close(); } catch (final RuntimeException ignore) { }
			}
			Thread.sleep(500);
			after.accept("after a TCP connection ended abruptly");

			// ETS gives the interface another address (tunnelling feature), several times, also back
			for (final IndividualAddress ia : new IndividualAddress[] { new IndividualAddress(7, 0, 24), new IndividualAddress(1, 1, 241), new IndividualAddress(2, 3, 9) })
				try (var c = TcpConnection.newTcpConnection(local, server); var t = KNXnetIPTunnel.newTcpTunnel(KNXnetIPTunnel.TunnelingLayer.LinkLayer, c, new IndividualAddress(0))) {
					t.send(TunnelingFeature.InterfaceFeature.IndividualAddress, ia.toByteArray());
					Thread.sleep(500);
				}
				catch (final Exception e) { seen.add("address change to " + ia + " failed: " + e); }
			after.accept("after the interface address was changed three times over TCP");

			// device management over TCP and UDP: write attempts of 15 into both properties
			for (final boolean tcp : new boolean[] { false, true }) {
				try (var c = tcp ? TcpConnection.newTcpConnection(local, server) : null;
						var ad = tcp ? LocalDeviceManagementIp.newAdapter(c, e -> {}) : LocalDeviceManagementIp.newAdapter(local, server, false, false, e -> {})) {
					for (final int[] t : new int[][] { { 0, 56 }, { 6, 68 } })
						try { ad.setProperty(t[0], t[1], 1, 1, new byte[] { 0, 15 }); }
						catch (final io.calimero.KNXException e) { /* read only */ }
					// ETS also writes element 0 / more elements, and the object instance 1 of the device object
					try { ad.setProperty(0, 56, 1, 1, new byte[] { 0, 15 }); } catch (final io.calimero.KNXException e) { }
				}
				after.accept("after device management writes of 15 over " + (tcp ? "TCP" : "UDP"));
			}

			// the interface is addressed over the bus (A_PropertyValue_Write to its own address, as a device would be)
			try (var c = TcpConnection.newTcpConnection(local, server); var t = KNXNetworkLinkIP.newTunnelingLink(c, new TPSettings(new IndividualAddress(0)));
					var mc = new SelfTest.RawClient(t)) {
				final var dst = mc.createDestination(new IndividualAddress(1, 1, 0), true);
				try { mc.rawPropertyWrite(dst, 0, 56, new byte[] { 0, 15 }); } catch (final Exception e) { seen.add("bus write to the interface: " + e.getClass().getSimpleName()); }
				finally { dst.destroy(); }
			}
			catch (final Exception e) { seen.add("bus write to the interface could not be tried: " + e); }
			after.accept("after the interface was written over the bus");

			// ETS can ask the interface to reset: the server closes every connection and starts again
			for (final boolean tcp : new boolean[] { false, true }) {
				try (var c = tcp ? TcpConnection.newTcpConnection(local, server) : null;
						var ad = tcp ? LocalDeviceManagementIp.newAdapter(c, e -> {}) : LocalDeviceManagementIp.newAdapter(local, server, false, false, e -> {})) {
					ad.reset();
				}
				catch (final Exception e) { seen.add("reset over " + (tcp ? "TCP" : "UDP") + ": " + e.getClass().getSimpleName() + " " + e.getMessage()); }
				Thread.sleep(14000);
				after.accept("after the interface was reset over " + (tcp ? "TCP" : "UDP"));
			}

			// whatever changes it (not found in any of the above): the guard puts it back and says so
			check("the guard finds nothing to repair while all is well", net.keepLongFrames() == null);
			final var ios = net.serverObjects();
			ios.setProperty(io.calimero.device.ios.InterfaceObject.CEMI_SERVER_OBJECT, 1, 68, 1, 1, new byte[] { 0, 15 });
			ios.setProperty(io.calimero.device.ios.InterfaceObject.DEVICE_OBJECT, 1, 56, 1, 1, new byte[] { 0, 15 });
			final String fixed = net.keepLongFrames();
			check("the guard notices 15 and says what it did: " + fixed, fixed != null && fixed.contains("was 15") && fixed.contains("254"));
			after.accept("after the guard repaired a value of 15");
			check("the guard is quiet again afterwards", net.keepLongFrames() == null);

			// the cable (the server's line) reports connection changes
			Thread.sleep(500);
			after.accept("a little later");
		}
		catch (final Throwable e) {
			e.printStackTrace(System.out);
			SelfTest.failed++;
		}
		seen.forEach(s -> System.out.println("  " + s));
		net.close();
		System.out.println(SelfTest.failed == 0 ? "APDU STAYS TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
