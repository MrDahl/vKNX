package org.vknx;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.vknx.device.PushButtonBehavior;
import org.vknx.device.SwitchActuatorBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.ManagementClientImpl;
import io.calimero.mgmt.PropertyAccess.PID;

/**
 * Backend self-test with a "mini ETS": a Calimero management client that performs the download sequence recorded
 * from a real ETS download of a System B device (docs/ets-download-sequence.md).
 *
 * <p><b>This is NOT an ETS test.</b> It proves that the simulator backend implements the services in the
 * sequence. Whether real ETS 6.4 accepts the devices is reported separately (docs/ets-test-guide.md).
 */
public final class SelfTest {
	private static final long T0 = System.nanoTime();
	static String ms() { return String.format("%6d ", (System.nanoTime() - T0) / 1_000_000); }

	static int failed;
	static final List<String> LOG = new ArrayList<>();

	static void check(final String what, final boolean ok) {
		System.out.println((ok ? "PASS  " : "FAIL  ") + what);
		if (!ok)
			failed++;
	}

	static boolean await(final BooleanSupplier c) throws InterruptedException {
		for (int i = 0; i < 60; i++) {
			if (c.getAsBoolean())
				return true;
			Thread.sleep(50);
		}
		return c.getAsBoolean();
	}

	static final int FLAGS_SEND = 0x04 | 0x40 | 0x03; // comm + transmit, low priority
	static final int FLAGS_RECEIVE = 0x04 | 0x10 | 0x03; // comm + write
	static final int FLAGS_STATUS_IN = 0x04 | 0x10 | 0x80 | 0x03; // comm + write + update
	static final int FLAGS_STATUS_OUT = 0x04 | 0x08 | 0x40 | 0x03; // comm + read + transmit

	/** Mini ETS client; exposes what ETS needs beyond Calimero's high-level calls. */
	static final class RawClient extends ManagementClientImpl {
		RawClient(final io.calimero.link.KNXNetworkLink link) throws Exception { super(link); }

		/** A_PropertyValue_Write without Calimero's "echo length must equal data length" check (ETS does not apply it). */
		byte[] rawPropertyWrite(final Destination d, final int obj, final int pid, final byte[] data) throws Exception {
			final byte[] asdu = new byte[4 + data.length];
			asdu[0] = (byte) obj;
			asdu[1] = (byte) pid;
			asdu[2] = 0x10;
			asdu[3] = 1;
			System.arraycopy(data, 0, asdu, 4, data.length);
			final byte[] apdu = io.calimero.DataUnitBuilder.createAPDU(0x3D7, asdu);
			return sendWait(d, Priority.LOW, apdu, 0x3D6, 4, 254, Duration.ofSeconds(3));
		}
	}

	static final class EtsLikeClient {
		final RawClient mc;
		EtsLikeClient(final io.calimero.link.KNXNetworkLink link) throws Exception {
			mc = new RawClient(link);
		}

		/** One load-state-machine step, like ETS' A_PropertyValue_Write on PID_LOAD_STATE_CONTROL. */
		/** ETS' individual-address sequence (as recorded): read, write, read to verify, connect, descriptor, restart. */
		boolean assignAddress(final IndividualAddress newAddr) throws Exception {
			final IndividualAddress[] before = mc.readAddress(false);
			if (before.length != 1)
				return false;
			mc.writeAddress(newAddr);
			Thread.sleep(200);
			final IndividualAddress[] after = mc.readAddress(false);
			final Destination d = mc.createDestination(newAddr, true);
			try {
				mc.readDeviceDesc(d, 0);
				mc.restart(d);
			}
			finally {
				d.destroy();
			}
			return after.length == 1;
		}

		int loadEvent(final Destination d, final int obj, final byte... event) throws Exception {
			final byte[] resp = mc.rawPropertyWrite(d, obj, PID.LOAD_STATE_CONTROL, event);
			return resp[resp.length - 1] & 0xff;
		}

		void downloadSegment(final Destination d, final int obj, final byte[] data) throws Exception {
			loadEvent(d, obj, (byte) 4); // unload
			loadEvent(d, obj, (byte) 1); // start loading
			final var alloc = ByteBuffer.allocate(8).put((byte) 3).put((byte) 0x0B).putInt(data.length).put((byte) 1)
					.put((byte) 0).array();
			loadEvent(d, obj, alloc);
			final byte[] ref = mc.readProperty(d, obj, PID.TABLE_REFERENCE, 1, 1);
			final int addr = ByteBuffer.wrap(ref).getInt();
			// ETS splits writes according to the device's PID_MAX_APDU_LENGTH (Calimero default 15 -> 10 data bytes per write)
			final int piece = 10;
			for (int off = 0; off < data.length; off += piece) {
				final int n = Math.min(piece, data.length - off);
				mc.writeMemory(d, addr + off, java.util.Arrays.copyOfRange(data, off, off + n));
			}
			final int st = loadEvent(d, obj, (byte) 2); // load completed
			if (st != 1)
				throw new IllegalStateException("obj " + obj + " not Loaded after LOAD_COMPLETED: state " + st);
			mc.readProperty(d, obj, PID.MCB_TABLE, 1, 1);
		}

		void downloadAll(final IndividualAddress dev, final int[] gas, final int[][] assoc, final int[] goFlags,
				final int[] params) throws Exception {
			final byte[] app = new byte[4];
			for (int i = 0; i < params.length; i++)
				app[i] = (byte) params[i];
			downloadAll(dev, gas, assoc, goFlags, app);
		}

		void downloadAll(final IndividualAddress dev, final int[] gas, final int[][] assoc, final int[] goFlags,
				final byte[] app) throws Exception {
			final Destination d = mc.createDestination(dev, true);
			try {
				final var at = ByteBuffer.allocate(2 + 2 * gas.length).putShort((short) gas.length);
				for (final int ga : gas)
					at.putShort((short) ga);
				final var asc = ByteBuffer.allocate(2 + 4 * assoc.length).putShort((short) assoc.length);
				for (final int[] a : assoc)
					asc.putShort((short) a[0]).putShort((short) a[1]);
				final var go = ByteBuffer.allocate(2 + 2 * goFlags.length).putShort((short) goFlags.length);
				for (final int f : goFlags)
					go.put((byte) f).put((byte) 0); // value type 0 = 1 bit
				downloadSegment(d, 4, app);
				downloadSegment(d, 3, go.array());
				downloadSegment(d, 1, at.array());
				downloadSegment(d, 2, asc.array());
				mc.readProperty(d, 4, PID.PROGRAM_VERSION, 1, 1);
				mc.restart(d);
			}
			finally {
				d.destroy();
			}
		}
	}

	static byte[] groupWrite(final int value) { return new byte[] { 0x00, (byte) (0x80 | value) }; }

	public static void main(final String[] args) throws Exception {
		final Path dir = Files.createTempDirectory("vknx-selftest");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> {
			synchronized (trace) { trace.add(ms() + src.name() + " [" + cat + "] " + text); }
		};

		Subnet net = Subnet.inProcess();
		net.monitor(s -> { synchronized (trace) { trace.add(ms() + "BUS " + s); } });
		final var pbState = dir.resolve("pb.state");
		final var saState = dir.resolve("sa.state");
		VirtualDevice pb = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 1 },
				pbState, net, listener);
		VirtualDevice sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 2 },
				saState, net, listener);

		final VirtualDevice pbF = pb, saF = sa;
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new EtsLikeClient(etsLink);

		try {
			// --- 3/4: only the device in programming mode takes part in address assignment
			sa.toggleProgrammingMode();
			check("only the device in programming mode answers; ETS-style address sequence works",
					ets.assignAddress(new IndividualAddress(1, 1, 2)));
			check("actuator took address 1.1.2", await(() -> saF.address().equals(new IndividualAddress(1, 1, 2))));
			check("push button kept factory address", pb.address().getRawAddress() == 0xFFFF);
			check("actuator left programming mode after the sequence (restart)", await(() -> !saF.programmingMode()));
			pb.toggleProgrammingMode();
			check("second address sequence on the button", ets.assignAddress(new IndividualAddress(1, 1, 1)));
			check("push button took address 1.1.1", await(() -> pbF.address().equals(new IndividualAddress(1, 1, 1))));
			check("actuator address unchanged by second assignment", sa.address().equals(new IndividualAddress(1, 1, 2)));

			// --- device identification as ETS reads it
			final Destination d = ets.mc.createDestination(new IndividualAddress(1, 1, 2), true);
			final byte[] dd = ets.mc.readDeviceDesc(d, 0);
			check("device descriptor is 0x07B0 (System B)", dd[0] == 0x07 && (dd[1] & 0xff) == 0xB0);
			d.destroy();

			// --- no download yet: telegrams do nothing
			final var gaSwitch = new GroupAddress(1, 0, 1);
			final var gaStatus = new GroupAddress(1, 0, 2);
			final var sw = (SwitchActuatorBehavior) sa.behavior();
			final var btn = (PushButtonBehavior) pb.behavior();
			etsLink.sendRequestWait(gaSwitch, Priority.LOW, groupWrite(1));
			Thread.sleep(300);
			check("unprogrammed actuator ignores GroupValueWrite", !sw.lampLit());

			// --- full download from "ETS": GA 1/0/1 switch, GA 1/0/2 status; normal relay logic, status on change
			final int[] gas = { gaSwitch.getRawAddress(), gaStatus.getRawAddress() };
			ets.downloadAll(new IndividualAddress(1, 1, 2), gas, new int[][] { { 1, 1 }, { 2, 2 } },
					new int[] { 0x04 | 0x10 | 0x03, FLAGS_STATUS_OUT },
					new int[] { 0b0000_1000 /* RelayLogic=0, Startup=0, SendStatus=1 */ });
			ets.downloadAll(new IndividualAddress(1, 1, 1), gas, new int[][] { { 1, 1 }, { 2, 2 } },
					new int[] { FLAGS_SEND, FLAGS_STATUS_IN }, new int[] { 0b0000_0100 /* Toggle, FollowStatus=1 */ });
			check("actuator application loaded", await(saF::applicationLoaded));
			check("push button application loaded", await(pbF::applicationLoaded));

			// --- 6/8/9: pressing the button sends from the button's address; the actuator reacts
			btn.press();
			check("button press switches lamp on (via bus, from 1.1.1)", await(sw::lampLit));
			synchronized (trace) {
				check("telegram on the bus came from 1.1.1 to 1/0/1",
						trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.1") && s.contains("1/0/1")));
			}
			btn.press();
			check("second press (toggle; status followed) switches lamp off", await(() -> !sw.lampLit()));

			// --- 10: status feedback reaches the button (FollowStatus=Yes) via 1/0/2
			btn.press(); // ON again
			await(sw::lampLit);
			Thread.sleep(300);
			check("status telegram sent by actuator from 1.1.2 to 1/0/2",
					trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.2") && s.contains("1/0/2")));

			// --- 7: a downloaded parameter changes behaviour (RelayLogic = inverted)
			ets.downloadAll(new IndividualAddress(1, 1, 2), gas, new int[][] { { 1, 1 }, { 2, 2 } },
					new int[] { 0x04 | 0x10 | 0x03, FLAGS_STATUS_OUT }, new int[] { 0b0000_1001 });
			Thread.sleep(300);
			etsLink.sendRequestWait(gaSwitch, Priority.LOW, groupWrite(1));
			check("inverted relay logic: ON telegram now opens the relay (lamp off)", await(() -> !sw.lampLit()));
			etsLink.sendRequestWait(gaSwitch, Priority.LOW, groupWrite(0));
			check("inverted relay logic: OFF telegram closes the relay (lamp on)", await(sw::lampLit));

			// --- 6: changed association changes real communication (move actuator to 1/0/3)
			final var gaNew = new GroupAddress(1, 0, 3);
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[] { gaNew.getRawAddress(), gaStatus.getRawAddress() },
					new int[][] { { 1, 1 }, { 2, 2 } }, new int[] { 0x04 | 0x10 | 0x03, FLAGS_STATUS_OUT },
					new int[] { 0b0000_1000 });
			Thread.sleep(300);
			etsLink.sendRequestWait(gaSwitch, Priority.LOW, groupWrite(1));
			Thread.sleep(300);
			check("old group address no longer reaches the actuator", !sw.lampLit());
			etsLink.sendRequestWait(gaNew, Priority.LOW, groupWrite(1));
			check("new group address now switches the actuator", await(sw::lampLit));

			// --- 11: restart persistence
			pb.close();
			sa.close();
			net.close();
			net = Subnet.inProcess();
			net.monitor(s -> { synchronized (trace) { trace.add(ms() + "BUS " + s); } });
			pb = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[6], pbState, net, listener);
			sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, new byte[6], saState, net, listener);
			check("restart: individual addresses kept", pb.address().equals(new IndividualAddress(1, 1, 1))
					&& sa.address().equals(new IndividualAddress(1, 1, 2)));
			check("restart: applications still loaded", pb.applicationLoaded() && sa.applicationLoaded());
			check("restart: downloaded parameter kept (RelayLogic normal, status on)", sa.param("RelayLogic") == 0
					&& sa.param("SendStatusOnChange") == 1);
			final var etsLink2 = net.createDeviceLink(new IndividualAddress(0, 2, 255));
			final var sw2 = (SwitchActuatorBehavior) sa.behavior();
			etsLink2.sendRequestWait(gaNew, Priority.LOW, groupWrite(1));
			check("restart: downloaded association still drives the actuator", await(sw2::lampLit));
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			failed++;
		}
		finally {
			if (failed > 0 || args.length > 0) {
				System.out.println("---- trace ----");
				synchronized (trace) { trace.forEach(System.out::println); }
			}
		}
		System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
		System.exit(failed == 0 ? 0 : 1);
	}
}
