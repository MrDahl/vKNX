package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HexFormat;

import io.calimero.IndividualAddress;
import io.calimero.mgmt.ManagementClientImpl;
import io.calimero.mgmt.Destination;
import io.calimero.secure.Security;
import io.calimero.mgmt.ManagementClient.EraseCode;

/** A secure device that was downloaded (ETS ends the download with a confirmed-restart master reset) must come back
 *  with its application after vKNX is closed and started again, like a plain device does. */
public final class SecureRestartTest {
	static void one(final String label, final org.vknx.product.ProductDef product, final boolean masterReset) throws Exception {
		final var dir = Files.createTempDirectory("vknx-secrestart");
		final var state = dir.resolve("d.state");
		Subnet net = Subnet.inProcess();
		VirtualDevice d = VirtualDevice.create("d", product, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 61 }, state, net, (s, c, t) -> {});
		final var addr = new IndividualAddress(1, 1, 10);
		final var link = net.createDeviceLink(new IndividualAddress(1, 1, 241));
		final var ets = new SelfTest.EtsLikeClient(link);
		d.toggleProgrammingMode();
		check(label + ": address assigned", ets.assignAddress(addr));
		Thread.sleep(300);
		ets.downloadAll(addr, new int[] { 0x0B01 }, new int[][] { { 1, 1 } }, new int[] { SelfTest.FLAGS_SEND }, new int[4]);
		check(label + ": loaded", SecureTest.until(3000, d::applicationLoaded));
		if (product.secure() && masterReset) { // secure commissioning as in SecureTest: tool key, group key, security flags, security mode on
			final var security = Security.newSecurity();
			security.deviceToolKeys().put(addr, HexFormat.of().parseHex(d.fdskHex()));
			final var tool = new ManagementClientImpl(net.createDeviceLink(new IndividualAddress(1, 1, 242)), security);
			final byte[] groupKey = new byte[16]; new SecureRandom().nextBytes(groupKey);
			final byte[] toolKey = new byte[16]; new SecureRandom().nextBytes(toolKey);
			final Destination dd = tool.createDestination(addr, true);
			try {
				tool.writeProperty(dd, 17, 1, 56, 1, 1, toolKey);
				tool.writeProperty(dd, 17, 1, 53, 1, 1, ByteBuffer.allocate(18).putShort((short) 1).put(groupKey).array());
				tool.writeProperty(dd, 17, 1, 61, 1, 1, new byte[] { 3 });
				tool.callFunctionProperty(dd, 17, 1, 51, 0, (byte) 1);
			} finally { dd.destroy(); }
			check(label + ": security mode on", SecureTest.until(2000, d::securityModeOn));
			tool.detach();
		}
		if (masterReset) {
			final var dest = ets.mc.createDestination(addr, true);
			try { ets.mc.restart(dest, EraseCode.ConfirmedRestart, 0); } catch (final Exception e) { System.out.println("restart: " + e); }
			finally { dest.destroy(); }
			Thread.sleep(1000);
			check(label + ": still loaded after the master reset", d.applicationLoaded());
		}
		link.close();
		d.close();
		net.close();
		net = Subnet.inProcess();
		d = VirtualDevice.create("d", product, new byte[6], state, net, (s, c, t) -> {});
		check(label + ": restored with address", d.address().equals(addr));
		check(label + ": restored with application", d.applicationLoaded());
		d.close();
	}

	public static void main(final String[] args) throws Exception {
		one("plain", Products.PUSH_BUTTON, false);
		one("secure", Products.SECURE_PUSH_BUTTON, false);
		one("plain+reset", Products.PUSH_BUTTON, true);
		one("secure+reset", Products.SECURE_PUSH_BUTTON, true);
		System.out.println("secure restart tests done");
		System.exit(SelfTest.failed > 0 ? 1 : 0);
	}
}
