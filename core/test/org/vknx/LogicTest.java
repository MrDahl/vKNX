package org.vknx;

import static org.vknx.SelfTest.FLAGS_SEND;
import static org.vknx.SelfTest.FLAGS_STATUS_IN;
import static org.vknx.SelfTest.FLAGS_STATUS_OUT;
import static org.vknx.SelfTest.await;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.link.NetworkLinkListener;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.LogicBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * The logic module and time master driven the way ETS would (address assignment, full downloads): enabled inputs and outputs,
 * a circuit saved from the editor, persistence over a download and a restart, the date/time telegrams and the weekday timers.
 * Own test (mini-ETS); it says nothing about what a real ETS does with the product.
 */
public final class LogicTest {
	private static byte[] w1(final int v) { return new byte[] { 0x00, (byte) (0x80 | v) }; }

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static String v(final VirtualDevice d, final String key) { return String.valueOf(d.behavior().view().get(key)); }

	/** A group write seen on the bus: destination and the data bytes after the 2 byte APCI. */
	record Seen(String dst, byte[] data, int small) {}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-logictest");
		final List<String> trace = new CopyOnWriteArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> trace.add(src.name() + " [" + cat + "] " + text);
		final Subnet net = Subnet.inProcess();
		final var state = dir.resolve("lg.state");
		final var lg = VirtualDevice.create("lg", Products.LOGIC, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 7 }, state, net, listener);
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final List<Seen> seen = new CopyOnWriteArrayList<>();
		etsLink.addLinkListener(new NetworkLinkListener() {
			@Override public void indication(final io.calimero.FrameEvent e) {
				if (e.getFrame() instanceof final io.calimero.cemi.CEMILData f && f.getDestination() instanceof final GroupAddress g) {
					final byte[] t = f.getPayload();
					if (t.length >= 2 && (t[0] & 0x03) == 0 && (t[1] & 0xC0) == 0x80)
						seen.add(new Seen(g.toString(), t.length > 2 ? java.util.Arrays.copyOfRange(t, 2, t.length) : new byte[0], t[1] & 0x3f));
				}
			}
			@Override public void linkClosed(final io.calimero.CloseEvent e) {}
		});
		final var ets = new EtsLikeClient(etsLink);
		try {
			// ---- the product
			check("product: 16 inputs + 16 outputs + date, time, date and time + 8 timer outputs = 43 objects", Products.LOGIC.objects().size() == 43);
			check("product: nothing is enabled by default (inputs, outputs, timers all hidden)",
					Products.LOGIC.params().stream().filter(p -> p.name().endsWith("Enable") && !p.name().contains("OnEnable") && !p.name().contains("OffEnable"))
							.allMatch(p -> p.defaultValue() == 0)
							&& Products.LOGIC.param("TimeObjects").defaultValue() == 0);
			check("product: ETS texts stay within 50 characters", Products.LOGIC.params().stream().noneMatch(p -> p.text().length() > 50)
					&& Products.LOGIC.objects().stream().noneMatch(o -> o.text().length() > 50)
					&& Products.LOGIC.params().stream().flatMap(p -> p.choices().stream()).noneMatch(c -> c.text().length() > 50));
			check("product: parameters do not overlap",
					Products.LOGIC.params().stream().map(p -> p.byteOffset() * 10 + p.bitOffset()).distinct().count() > 100
							&& Products.LOGIC.params().stream().allMatch(p -> p.byteOffset() + p.sizeBytes() <= Products.LOGIC.paramAreaSize()));
			check("product: object numbers are unique and sequential",
					java.util.stream.IntStream.rangeClosed(1, 43).allMatch(n -> Products.LOGIC.object(n).isPresent()));

			lg.toggleProgrammingMode();
			check("address assigned", ets.assignAddress(new IndividualAddress(1, 1, 9)));
			Thread.sleep(300);

			final var gIn1 = new GroupAddress(6, 0, 1);
			final var gIn2 = new GroupAddress(6, 0, 2);
			final var gOut1 = new GroupAddress(6, 0, 3);
			final var gOut2 = new GroupAddress(6, 0, 4);
			final var gTimer = new GroupAddress(6, 0, 5);
			final var gTime = new GroupAddress(6, 0, 6);
			final var gDate = new GroupAddress(6, 0, 7);
			final var gDT = new GroupAddress(6, 0, 8);
			final int[] gas = { gIn1.getRawAddress(), gIn2.getRawAddress(), gOut1.getRawAddress(), gOut2.getRawAddress(),
				gTimer.getRawAddress(), gTime.getRawAddress(), gDate.getRawAddress(), gDT.getRawAddress() };
			final int[][] assoc = { { 1, 1 }, { 2, 2 }, { 3, 17 }, { 4, 18 }, { 5, Products.timerObject(1) }, { 6, Products.OBJ_TIME },
				{ 7, Products.OBJ_DATE }, { 8, Products.OBJ_DATETIME } };
			final int[] fl = flags(43, 1, FLAGS_STATUS_IN, 2, FLAGS_STATUS_IN, 17, FLAGS_STATUS_OUT, 18, FLAGS_STATUS_OUT,
					Products.timerObject(1), FLAGS_STATUS_OUT, Products.OBJ_TIME, FLAGS_SEND, Products.OBJ_DATE, FLAGS_SEND, Products.OBJ_DATETIME, FLAGS_SEND);
			final byte[] params = Products.LOGIC.params(
					"In1Enable", 1, "In1Name", "Window", "In2Enable", 1, "In2Name", "Door",
					"Out1Enable", 1, "Out1Name", "Light", "Out2Enable", 1,
					"TimeObjects", 3, "TimeAtStart", 1, "TimeInterval", 0,
					"Tm1Enable", 1, "Tm1Name", "Garden", "Tm1Day6", 0);
			ets.downloadAll(new IndividualAddress(1, 1, 9), gas, assoc, fl, params);
			check("loaded", await(lg::applicationLoaded));
			check("enabled inputs/outputs show with their ETS names",
					"true".equals(v(lg, "ie1")) && "Window".equals(v(lg, "in1")) && "Door".equals(v(lg, "in2")) && "Light".equals(v(lg, "on1"))
							&& "Output 2".equals(v(lg, "on2")));
			check("inputs and outputs that were not enabled stay hidden", "false".equals(v(lg, "ie3")) && "false".equals(v(lg, "oe3")) && "".equals(v(lg, "in3")));
			check("no circuit yet: 0 elements", "0".equals(v(lg, "elements")));

			// ---- send the time right after the download (TimeAtStart): 3 s later, time and date objects
			check("time and date are sent after the download", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/6") && s.data().length == 3)
					&& seen.stream().anyMatch(s -> s.dst().equals("6/0/7") && s.data().length == 3)));
			for (int i = 0; i < 100 && seen.stream().noneMatch(s -> s.dst().equals("6/0/6")); i++)
				Thread.sleep(100);
			check("time telegram of 3 bytes seen after the start", seen.stream().anyMatch(s -> s.dst().equals("6/0/6") && s.data().length == 3));

			// ---- the circuit: output 1 = input 1 AND input 2, output 2 = NOT input 1
			final var beh = (LogicBehavior) lg.behavior();
			final String circuit = "n i1 IN 20 20 1\nn i2 IN 20 80 2\nn a AND 140 40 2\nn n NOT 140 120 1\nn o1 OUT 260 40 1\nn o2 OUT 260 120 2\n"
					+ "w i1 a 0\nw i2 a 1\nw a o1 0\nw i1 n 0\nw n o2 0\n";
			check("the circuit is accepted", beh.setCircuit(circuit) == null);
			check("a rejected circuit gives a reason and changes nothing", beh.setCircuit("n a IN 0 0 99\n") != null && beh.circuit().equals(circuit.strip()));
			check("circuit has 6 elements", "6".equals(v(lg, "elements")));
			seen.clear();
			etsLink.sendRequestWait(gIn1, Priority.LOW, w1(1));
			check("input 1 = 1: shown on the card", await(() -> "true".equals(v(lg, "iv1"))));
			check("NOT output 2 goes to 0 and is sent to 6/0/4", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/4") && s.small() == 0)));
			check("AND still 0: nothing sent to 6/0/3", seen.stream().noneMatch(s -> s.dst().equals("6/0/3")));
			etsLink.sendRequestWait(gIn2, Priority.LOW, w1(1));
			check("both inputs 1: AND output sent to 6/0/3 with value 1", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/3") && s.small() == 1)));
			check("the card shows output 1 on", await(() -> "true".equals(v(lg, "ov1"))));
			etsLink.sendRequestWait(gIn2, Priority.LOW, w1(0));
			check("input 2 back to 0: output 1 off again", await(() -> "false".equals(v(lg, "ov1"))));
			check("live values for the editor include every element", beh.live().size() == 6 && beh.live().get("i1"));

			// ---- the circuit survives an ETS download of the same parameters (it is not part of the ETS data)
			ets.downloadAll(new IndividualAddress(1, 1, 9), gas, assoc, fl, params);
			check("reloaded after the second download", await(() -> lg.applicationLoaded() && lg.downloadCount() >= 2));
			check("circuit still there after a new ETS download", "6".equals(v(lg, "elements")));
			etsLink.sendRequestWait(gIn1, Priority.LOW, w1(1));
			etsLink.sendRequestWait(gIn2, Priority.LOW, w1(1));
			check("...and still works", await(() -> "true".equals(v(lg, "ov1"))));

			// ---- the circuit survives a restart of vKNX (state file)
			lg.close();
			final VirtualDevice lg2 = VirtualDevice.create("lg", Products.LOGIC, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 7 }, state, net, listener);
			check("restarted device is still programmed", await(lg2::applicationLoaded));
			check("circuit still there after a restart", await(() -> "6".equals(v(lg2, "elements"))));

			// ---- time master with a clock that stands still
			final var beh2 = (LogicBehavior) lg2.behavior();
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 6, 23, 41, 10)); // a Friday
			seen.clear();
			beh2.act("sendTime", 0);
			check("time sent: Friday 23:41:10 = 0xB7 0x29 0x0A (weekday 5 in the top 3 bits)", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/6")
					&& s.data().length == 3 && (s.data()[0] & 0xff) == 0xB7 && s.data()[1] == 41 && s.data()[2] == 10)));
			check("date sent: 6.11.26", seen.stream().anyMatch(s -> s.dst().equals("6/0/7") && s.data().length == 3 && s.data()[0] == 6 && s.data()[1] == 11 && s.data()[2] == 26));
			check("the card shows the simulated clock", "2026-11-06".equals(v(lg2, "date")) && "23:41:10".equals(v(lg2, "time")) && "4".equals(v(lg2, "dow")));

			final var tdec = org.vknx.ui.TelegramDecoder.decode("0080b7290a", "GroupValueWrite", java.util.Set.of("DPST-10-1"));
			final var ddec = org.vknx.ui.TelegramDecoder.decode("0080060b1a", "GroupValueWrite", java.util.Set.of("DPST-11-1"));
			final var dtdec = org.vknx.ui.TelegramDecoder.decode("0080" + "7e0b06b7290a40" + "80", "GroupValueWrite", java.util.Set.of("DPST-19-1"));
			System.out.println("monitor decodes: time=" + tdec.value() + "  date=" + ddec.value() + "  date/time=" + dtdec.value());
			check("the telegram monitor decodes the time (10.001), date (11.001) and date/time (19.001) objects",
					tdec.value() != null && tdec.value().contains("23:41:10") && ddec.value() != null && ddec.value().contains("2026") && dtdec.value() != null);

			// date and time in one object (DPT 19.001), a second download
			final byte[] params2 = Products.LOGIC.params("In1Enable", 1, "In2Enable", 1, "Out1Enable", 1, "Out2Enable", 1,
					"TimeObjects", 4, "TimeAtStart", 0, "TimeInterval", 0, "Tm1Enable", 1, "Tm1Name", "Garden", "Tm1Day6", 0);
			ets.downloadAll(new IndividualAddress(1, 1, 9), gas, assoc, fl, params2);
			check("reloaded with date/time object", await(() -> lg2.applicationLoaded() && "4".equals(v(lg2, "timeMode"))));
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 6, 23, 41, 10));
			seen.clear();
			beh2.act("sendTime", 0);
			check("DPT 19.001: 8 bytes year-1900, month, day, weekday+hour, minute, second, flags, quality", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/8")
					&& s.data().length == 8 && (s.data()[0] & 0xff) == 126 && s.data()[1] == 11 && s.data()[2] == 6 && (s.data()[3] & 0xff) == 0xB7
					&& s.data()[4] == 41 && s.data()[5] == 10 && (s.data()[6] & 0x40) != 0 /* working day */ && (s.data()[7] & 0x80) != 0)));

			// ---- weekday timers: on 07:00, off 22:00 (the defaults), not on Sundays; timer 2 is not enabled
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 6, 6, 59, 0));
			beh2.tick();
			seen.clear();
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 6, 7, 0, 0));
			beh2.tick();
			check("timer 1 switches on at 07:00 on a Friday", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/5") && s.small() == 1)));
			check("the card shows timer 1 on and its name", "true".equals(v(lg2, "tv1")) && "Garden".equals(v(lg2, "tn1")));
			seen.clear();
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 6, 22, 0, 0));
			beh2.tick();
			check("timer 1 switches off at 22:00", await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/5") && s.small() == 0)));
			seen.clear();
			beh2.setClockForTest(LocalDateTime.of(2026, 11, 8, 7, 0, 0)); // Sunday: Tm1Day6 = No
			beh2.tick();
			Thread.sleep(400);
			check("no timer telegram on a Sunday", seen.stream().noneMatch(s -> s.dst().equals("6/0/5")));
			check("a fast clock does not skip minutes: 06:58 -> 07:02 in one step fires 07:00",
					fastForward(beh2, seen, lg2));

			// ---- clock controls on the card
			beh2.act("pcClock", 0);
			check("PC clock again", "true".equals(v(lg2, "pcClock")) && "1".equals(v(lg2, "speed")));
			beh2.act("setDay", 6);
			beh2.act("setTime", 7 * 60 + 30);
			check("set to Sunday 07:30", "6".equals(v(lg2, "dow")) && v(lg2, "time").startsWith("07:30") && "false".equals(v(lg2, "pcClock")));
			beh2.act("speed", 60);
			check("speed 60x", "60".equals(v(lg2, "speed")));
			beh2.act("speed", 99999);
			check("a speed out of range is ignored", "60".equals(v(lg2, "speed")));
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		finally {
			if (SelfTest.failed > 0) {
				System.out.println("---- trace ----");
				trace.forEach(System.out::println);
			}
		}
		System.out.println(SelfTest.failed == 0 ? "LOGIC TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}

	/** Sets the clock to 06:58, lets it run at 3600x for a moment: the 07:00 event must fire although the ticks are seconds apart. */
	private static boolean fastForward(final LogicBehavior b, final List<Seen> seen, final VirtualDevice d) throws Exception {
		b.setClockForTest(LocalDateTime.of(2026, 11, 9, 6, 58, 0)); // Monday
		b.tick();
		seen.clear();
		b.act("speed", 3600);
		final boolean ok = await(() -> seen.stream().anyMatch(s -> s.dst().equals("6/0/5") && s.small() == 1));
		b.act("pcClock", 0);
		return ok;
	}
}
