package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.vknx.device.SwitchActuatorBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.ManagementClientImpl;
import io.calimero.secure.Security;

/**
 * KNX Data Secure with the two secure products: ETS-like secure commissioning with the factory key (FDSK), then
 * authenticated and encrypted group telegrams. The "ETS" is Calimero's own secure management client, so this checks
 * that our devices accept what a Calimero tool sends; it is NOT a test against the real ETS.
 */
public final class SecureTest {
	static final int SEND = SelfTest.FLAGS_SEND, W = SelfTest.FLAGS_RECEIVE;
	static final int OBJ_TYPE_SECURITY = 17, PID_SECURITY_MODE = 51, PID_GROUP_KEY_TABLE = 53, PID_TOOL_KEY = 56,
			PID_GO_SECURITY_FLAGS = 61;

	static boolean until(final long ms, final java.util.function.BooleanSupplier c) throws InterruptedException {
		return CouplerTest.until(ms, c);
	}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-secure");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> {
			synchronized (trace) { trace.add(SelfTest.ms() + src.name() + " [" + cat + "] " + text); }
		};
		final var net = Subnet.inProcess();
		net.monitor(s -> { synchronized (trace) { trace.add(SelfTest.ms() + "BUS " + s); } });
		final var ets = new SelfTest.EtsLikeClient(net.createDeviceLink(new IndividualAddress(1, 1, 241)));
		final var secureLink = net.createDeviceLink(new IndividualAddress(1, 1, 242));
		try {
			final var pb = VirtualDevice.create("spb", Products.SECURE_PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 51 },
					dir.resolve("spb.state"), net, listener);
			final var sw = VirtualDevice.create("ssa", Products.SECURE_SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 52 },
					dir.resolve("ssa.state"), net, listener);
			final var plain = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 53 },
					dir.resolve("pb.state"), net, listener);

			check("secure products are flagged secure, plain ones are not", Products.SECURE_PUSH_BUTTON.secure()
					&& Products.SECURE_SWITCH_ACTUATOR.secure() && !Products.PUSH_BUTTON.secure());
			check("secure products have their own application numbers and generate IsSecureEnabled",
					Products.SECURE_PUSH_BUTTON.appNumber() != Products.PUSH_BUTTON.appNumber()
							&& org.vknx.tools.ProductXmlGenerator.generate(Products.SECURE_SWITCH_ACTUATOR).contains("IsSecureEnabled=\"true\"")
							&& !org.vknx.tools.ProductXmlGenerator.generate(Products.SWITCH_ACTUATOR).contains("IsSecureEnabled"));
			check("FDSK is 32 hex digits, differs per device, serial is 12 hex digits",
					pb.fdskHex().matches("[0-9A-F]{32}") && !pb.fdskHex().equals(sw.fdskHex()) && pb.serialHex().equals("00FA00000033")
							&& plain.fdskHex().isEmpty());
			check("not commissioned yet: security mode off, factory key in use", !pb.securityModeOn() && !pb.toolKeyActive());

			// ---- plain programming first (security mode is off): addresses and the application download
			pb.toggleProgrammingMode();
			check("secure push button gets 1.1.10", ets.assignAddress(new IndividualAddress(1, 1, 10))
					&& until(3000, () -> pb.address().getDevice() == 10));
			sw.toggleProgrammingMode();
			check("secure actuator gets 1.1.20", ets.assignAddress(new IndividualAddress(1, 1, 20))
					&& until(3000, () -> sw.address().getDevice() == 20));
			final int gSw = new GroupAddress(1, 0, 1).getRawAddress();
			ets.downloadAll(pb.address(), new int[] { gSw }, new int[][] { { 1, 1 }, { 1, 6 } }, new int[] { SEND, 0, 0, 0, 0, SEND },
					Products.SECURE_PUSH_BUTTON.params("Key1Function", 1, "Key1LongPress", 0, "Key2Function", 1, "Key2DimDir", 1, "Key2LongPress", 0));
			ets.downloadAll(sw.address(), new int[] { gSw }, new int[][] { { 1, 1 } }, new int[] { W },
					Products.SECURE_SWITCH_ACTUATOR.defaultParams());
			check("both secure devices loaded", until(3000, () -> pb.applicationLoaded() && sw.applicationLoaded()));
			final var lamp = (SwitchActuatorBehavior) sw.behavior();

			CouplerTest.press(pb, 1);
			check("before commissioning the secure devices still talk plain group telegrams", until(2000, () -> lamp.lampLit(1)));
			CouplerTest.press(pb, 2);
			check("... and switch off again (key 2)", until(2000, () -> !lamp.lampLit(1)));

			// ---- secure commissioning with the FDSK: tool key, group key, group object security, security mode
			final var security = Security.newSecurity();
			for (final var dev : List.of(pb, sw)) // the client copies the key map when it is created: fill it first
				security.deviceToolKeys().put(dev.address(), HexFormat.of().parseHex(dev.fdskHex()));
			final var tool = new ManagementClientImpl(secureLink, security);
			final byte[] groupKey = new byte[16];
			new SecureRandom().nextBytes(groupKey);
			for (final var dev : List.of(pb, sw)) {
				final Destination d = tool.createDestination(dev.address(), true);
				try {
					final byte[] toolKey = new byte[16];
					new SecureRandom().nextBytes(toolKey);
					tool.writeProperty(d, OBJ_TYPE_SECURITY, 1, PID_TOOL_KEY, 1, 1, toolKey);
					tool.writeProperty(d, OBJ_TYPE_SECURITY, 1, PID_GROUP_KEY_TABLE, 1, 1,
							ByteBuffer.allocate(18).putShort((short) 1).put(groupKey).array()); // entry for address-table index 1
					tool.writeProperty(d, OBJ_TYPE_SECURITY, 1, PID_GO_SECURITY_FLAGS, 1, 1, new byte[] { 3 }); // object 1: auth + conf
					tool.callFunctionProperty(d, OBJ_TYPE_SECURITY, 1, PID_SECURITY_MODE, 0, (byte) 1);
				}
				finally {
					d.destroy();
				}
			}
			check("secure commissioning done: tool key replaced the FDSK, security mode on",
					until(2000, () -> pb.securityModeOn() && sw.securityModeOn() && pb.toolKeyActive() && sw.toolKeyActive()));

			// ---- secure group communication
			CouplerTest.press(pb, 1);
			check("an authenticated + encrypted group write from the secure push button switches the secure actuator",
					until(3000, () -> lamp.lampLit(1)));
			final boolean sentSecure;
			synchronized (trace) {
				sentSecure = trace.stream().anyMatch(s -> s.contains("spb [tx]") && s.contains("KNX Data Secure"));
			}
			check("the push button's log says the telegram went out as KNX Data Secure", sentSecure);

			// a plain write to a secure group object must be ignored
			final var plainLink = net.createDeviceLink(new IndividualAddress(1, 1, 243));
			plainLink.sendRequestWait(new GroupAddress(1, 0, 1), io.calimero.Priority.LOW, new byte[] { 0x00, (byte) 0x80 }); // plain OFF
			Thread.sleep(500);
			check("a plain (unsecured) group write to the secure group object is ignored", lamp.lampLit(1));

			CouplerTest.press(pb, 2);
			check("secure switch off works too (sequence numbers advance)", until(3000, () -> !lamp.lampLit(1)));
			System.out.println("secure tests done");
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.check("no exception: " + t, false);
		}
		finally {
			if (SelfTest.failed > 0 || System.getProperty("vknx.trace") != null)
				synchronized (trace) { trace.stream().skip(Math.max(0, trace.size() - 120)).forEach(System.out::println); }
		}
		System.out.println(SelfTest.failed == 0 ? "ALL PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
