package org.vknx;

import static org.vknx.SelfTest.FLAGS_RECEIVE;
import static org.vknx.SelfTest.FLAGS_SEND;
import static org.vknx.SelfTest.FLAGS_STATUS_OUT;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.DeviceBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * Binary input, room thermostat with heating actuator (a closed loop over the bus), and weather station with blind actuator
 * (wind and rain alarm, monitoring), driven the way ETS would: address assignment and full downloads over the bus.
 * The supervision times of the actuators run 30 times faster here. This is NOT an ETS compatibility test.
 */
public final class NewProductsTest {
	private static byte[] w1(final int v) { return new byte[] { 0x00, (byte) (0x80 | v) }; }

	private static byte[] w8(final int v) { return new byte[] { 0x00, (byte) 0x80, (byte) v }; }

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static Object v(final VirtualDevice d, final String key) { return d.behavior().view().get(key); }

	private static String s(final VirtualDevice d, final String key) { return String.valueOf(v(d, key)); }

	private static double num(final VirtualDevice d, final String key) { return ((Number) v(d, key)).doubleValue(); }

	private static boolean waitFor(final long ms, final BooleanSupplier c) throws InterruptedException {
		for (long t = 0; t < ms; t += 50) {
			if (c.getAsBoolean())
				return true;
			Thread.sleep(50);
		}
		return c.getAsBoolean();
	}

	private static int count(final List<String> trace, final String what) {
		synchronized (trace) {
			return (int) trace.stream().filter(t -> t.contains(what)).count();
		}
	}

	private static byte[] serial(final int n) { return new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) n }; }

	public static void main(final String[] args) throws Exception {
		DeviceBehavior.timeScale = 30;
		final var dir = Files.createTempDirectory("vknx-newproducts");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> { synchronized (trace) { trace.add(src.name() + " [" + cat + "] " + text); } };
		final Subnet net = Subnet.inProcess();
		final var bi = VirtualDevice.create("bi", Products.BINARY_INPUT, serial(1), dir.resolve("bi.state"), net, listener);
		final var sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, serial(2), dir.resolve("sa.state"), net, listener);
		final var th = VirtualDevice.create("th", Products.THERMOSTAT, serial(3), dir.resolve("th.state"), net, listener);
		final var ha = VirtualDevice.create("ha", Products.HEATING_ACTUATOR, serial(4), dir.resolve("ha.state"), net, listener);
		final var ws = VirtualDevice.create("ws", Products.WEATHER_STATION, serial(5), dir.resolve("ws.state"), net, listener);
		final var bl = VirtualDevice.create("bl", Products.BLIND_ACTUATOR, serial(6), dir.resolve("bl.state"), net, listener);
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new EtsLikeClient(etsLink);
		try {
			// ---- ETS texts stay within 50 characters, objects and parameters are unique
			for (final var p : List.of(Products.BINARY_INPUT, Products.THERMOSTAT, Products.HEATING_ACTUATOR, Products.WEATHER_STATION, Products.BLIND_ACTUATOR)) {
				check(p.key() + ": ETS texts within 50 characters", p.params().stream().noneMatch(q -> q.text().length() > 50)
						&& p.objects().stream().noneMatch(o -> o.text().length() > 50)
						&& p.params().stream().flatMap(q -> q.choices().stream()).noneMatch(c -> c.text().length() > 50));
				check(p.key() + ": object numbers are unique", p.objects().stream().map(o -> o.number()).distinct().count() == p.objects().size());
				check(p.key() + ": parameter names are unique", p.params().stream().map(q -> q.name()).distinct().count() == p.params().size());
			}

			int host = 1;
			for (final var d : List.of(bi, sa, th, ha, ws, bl)) {
				d.toggleProgrammingMode();
				check("address assigned to " + d.name(), ets.assignAddress(new IndividualAddress(1, 1, host++)));
				Thread.sleep(300);
			}

			binaryInput(ets, bi, sa, trace);
			heating(ets, etsLink, th, ha);
			weather(ets, etsLink, ws, bl, trace);
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		DeviceBehavior.timeScale = 1;
		if (SelfTest.failed != 0)
			synchronized (trace) {
				System.out.println("---- last log lines");
				trace.subList(Math.max(0, trace.size() - 60), trace.size()).forEach(System.out::println);
			}
		System.out.println(SelfTest.failed == 0 ? "NEW PRODUCTS TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------------------------------------------ binary input

	private static void binaryInput(final EtsLikeClient ets, final VirtualDevice bi, final VirtualDevice sa, final List<String> trace) throws Exception {
		final var g1 = new GroupAddress(1, 0, 1).getRawAddress(); // input 1 switch -> output 1
		final var g2 = new GroupAddress(1, 0, 2).getRawAddress(); // input 2 switch -> output 2
		final var g3 = new GroupAddress(1, 0, 3).getRawAddress(); // input 3 switch (dimming input: short press)
		final var g4 = new GroupAddress(1, 0, 4).getRawAddress(); // input 3 relative dimming
		final var g5 = new GroupAddress(1, 0, 5).getRawAddress(); // input 4 switch -> output 3, cyclic
		ets.downloadAll(new IndividualAddress(1, 1, 2), new int[] { g1, g2, g5 }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 } },
				flags(17, 1, FLAGS_RECEIVE, 2, FLAGS_RECEIVE, 3, FLAGS_RECEIVE), Products.SWITCH_ACTUATOR.defaultParams());
		ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { g1, g2, g3, g4, g5 }, new int[][] { { 1, 1 }, { 2, 5 }, { 3, 9 }, { 4, 10 }, { 5, 13 } },
				flags(16, 1, FLAGS_STATUS_OUT, 5, FLAGS_STATUS_OUT, 9, FLAGS_STATUS_OUT, 10, FLAGS_SEND, 13, FLAGS_STATUS_OUT),
				Products.BINARY_INPUT.params("In1Function", 0, "In1Closing", 1, "In1Opening", 2,
						"In2Closing", 3, "In2Opening", 0,
						"In3Function", 1, "In3DimDir", 0, "In3DimStep", 3, "In3LongPress", 1,
						"In4Closing", 1, "In4Opening", 2, "In4Cyclic", 1));
		check("binary input and switch actuator loaded", waitFor(5000, () -> bi.applicationLoaded() && sa.applicationLoaded()));

		// input 1: switching, closing = on, opening = off
		bi.behavior().act("toggle", 1);
		check("input 1: closing the contact switches output 1 on", waitFor(3000, () -> s(sa, "lamps").startsWith("1")));
		bi.behavior().act("toggle", 1);
		check("input 1: opening the contact switches output 1 off", waitFor(3000, () -> s(sa, "lamps").startsWith("0")));
		check("input 1: the card shows the contact state", "false".equals(s(bi, "c1")));

		// input 2: closing = toggle, opening = nothing (a push button): every press toggles, releasing sends nothing
		bi.behavior().act("down", 2);
		check("input 2: first press toggles output 2 on", waitFor(3000, () -> s(sa, "lamps").charAt(1) == '1'));
		bi.behavior().act("up", 2);
		Thread.sleep(300);
		check("input 2: releasing sends nothing (output stays on)", s(sa, "lamps").charAt(1) == '1');
		bi.behavior().act("down", 2);
		bi.behavior().act("up", 2);
		check("input 2: second press toggles output 2 off", waitFor(3000, () -> s(sa, "lamps").charAt(1) == '0'));
		bi.behavior().act("down", 2);
		bi.behavior().act("toggle", 2);
		check("input 2: the latch button is ignored while the press button holds the contact", "true".equals(s(bi, "c2")));
		bi.behavior().act("up", 2);
		check("input 2: releasing the press button opens the contact", "false".equals(s(bi, "c2")));

		// input 3: relative dimming. long press (0.6 s) sends dim up (step code 3 -> 0x0B = 11), release sends stop (0)
		bi.behavior().act("down", 3);
		check("input 3: holding the contact closed sends dim up after the long-press time", waitFor(3000, () -> count(trace, "-> 1/0/4 value 11") >= 1));
		check("input 3: no switch telegram while the long press is running", count(trace, "-> 1/0/3 value") == 0);
		bi.behavior().act("up", 3);
		check("input 3: opening after a long press sends STOP", waitFor(3000, () -> count(trace, "-> 1/0/4 value 0") >= 1));
		bi.behavior().act("down", 3);
		Thread.sleep(100);
		bi.behavior().act("up", 3);
		check("input 3: a short press switches on (brighter)", waitFor(3000, () -> count(trace, "-> 1/0/3 value 1") >= 1));

		// input 4: closing = on, cyclic every second
		bi.behavior().act("toggle", 4);
		check("input 4: closing switches output 3 on", waitFor(3000, () -> s(sa, "lamps").charAt(2) == '1'));
		Thread.sleep(3300);
		check("input 4: the state is sent cyclically (at least 3 telegrams in 3 seconds)", count(trace, "-> 1/0/5 value 1") >= 3);
		bi.behavior().act("toggle", 4);
		check("input 4: opening switches output 3 off", waitFor(3000, () -> s(sa, "lamps").charAt(2) == '0'));

		// a disabled input does nothing
		ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { g1 }, new int[][] { { 1, 1 } }, flags(16, 1, FLAGS_STATUS_OUT),
				Products.BINARY_INPUT.params("In1Enable", 0));
		check("binary input reloaded with input 1 disabled", waitFor(5000, () -> bi.applicationLoaded() && "false".equals(s(bi, "e1"))));
		bi.behavior().act("toggle", 1);
		Thread.sleep(500);
		check("input 1 disabled: closing the contact does nothing", s(sa, "lamps").startsWith("0"));
		check("input 1 disabled: the log says why", count(trace, "parameter In1Enable=No") >= 1);
	}

	// ------------------------------------------------------------------------------------------------ thermostat + heating actuator

	private static void heating(final EtsLikeClient ets, final io.calimero.link.KNXNetworkLink etsLink, final VirtualDevice th, final VirtualDevice ha) throws Exception {
		final var gBit = new GroupAddress(2, 0, 1).getRawAddress(); // thermostat on/off control value -> valve 1
		final var gByte = new GroupAddress(2, 0, 2).getRawAddress(); // thermostat continuous control value -> valve 2
		final var gWin = new GroupAddress(2, 0, 3);
		final var gMode = new GroupAddress(2, 0, 4);
		final var gVal3 = new GroupAddress(2, 0, 6); // valve 3, fed by hand
		final int[] gas = { gBit, gByte, gWin.getRawAddress(), gMode.getRawAddress(), gVal3.getRawAddress() };
		ets.downloadAll(new IndividualAddress(1, 1, 4), new int[] { gBit, gByte, gVal3.getRawAddress() }, new int[][] { { 1, 1 }, { 2, 5 }, { 3, 7 } },
				flags(19, 1, FLAGS_RECEIVE, 5, FLAGS_RECEIVE, 7, FLAGS_RECEIVE),
				Products.HEATING_ACTUATOR.params("Ch2Type", 1, "Ch2Cycle", 1, "Ch3Type", 0, "Ch3ValveType", 1, "Ch3Monitor", 5, "Ch3Emergency", 40));
		// two-point controller, simulated room 20 degrees at 240 times speed, comfort 21
		ets.downloadAll(new IndividualAddress(1, 1, 3), gas, new int[][] { { 1, 7 }, { 2, 8 }, { 3, 5 }, { 4, 3 } },
				flags(8, 7, FLAGS_STATUS_OUT, 8, FLAGS_STATUS_OUT, 5, FLAGS_RECEIVE, 3, FLAGS_RECEIVE),
				Products.THERMOSTAT.params("TempSource", 0, "StartTemp", 20, "Ambient", 15, "RoomSpeed", 240, "ControlType", 0,
						"Hysteresis", 5, "WindowUsed", 1, "ControlCycle", 2));
		check("thermostat and heating actuator loaded", waitFor(5000, () -> th.applicationLoaded() && ha.applicationLoaded()));
		check("thermostat starts in comfort with the comfort setpoint 21.0 and the start temperature 20.0",
				"1".equals(s(th, "mode")) && num(th, "sp") == 21.0 && num(th, "temp") >= 19.9);

		// the room is colder than setpoint minus half the hysteresis: the heating comes on, the valve opens, the room warms up
		check("heating: the thermostat's control value opens valve 1", waitFor(8000, () -> "true".equals(s(ha, "open1"))));
		check("heating: the room warms up above the setpoint plus half the hysteresis and the valve closes again",
				waitFor(40000, () -> num(th, "temp") >= 21.2 && "false".equals(s(ha, "open1"))));
		check("heating: the thermostat reports heating off", "false".equals(s(th, "heat")));
		check("valve 1 normally closed: drive energised only while open", "false".equals(s(ha, "energised1")));

		// keys on the card
		th.behavior().act("mode", 3);
		check("key economy: setpoint 17.0", "3".equals(s(th, "mode")) && num(th, "sp") == 17.0);
		th.behavior().act("mode", 1);
		th.behavior().act("shift", 1);
		check("key + : setpoint shifted to 21.5", num(th, "sp") == 21.5);
		th.behavior().act("shift", -1);
		th.behavior().act("shift", -1);
		check("key - twice: setpoint 20.5", num(th, "sp") == 20.5);
		for (int i = 0; i < 12; i++)
			th.behavior().act("shift", -1);
		check("the shift stops at 3 K: setpoint 18.0", num(th, "sp") == 18.0);
		th.behavior().act("mode", 1); // a mode key clears the shift
		check("a mode key clears the shift", num(th, "sp") == 21.0);

		// window contact object and operating mode object
		etsLink.sendRequestWait(gWin, Priority.LOW, w1(1));
		check("window open: building protection, setpoint 7.0", waitFor(3000, () -> "4".equals(s(th, "mode")) && num(th, "sp") == 7.0 && "true".equals(s(th, "win"))));
		etsLink.sendRequestWait(gWin, Priority.LOW, w1(0));
		check("window closed: back to comfort", waitFor(3000, () -> "1".equals(s(th, "mode")) && num(th, "sp") == 21.0));
		etsLink.sendRequestWait(gMode, Priority.LOW, w8(3));
		check("operating mode object 3 (economy)", waitFor(3000, () -> "3".equals(s(th, "mode"))));
		etsLink.sendRequestWait(gMode, Priority.LOW, w8(9));
		Thread.sleep(300);
		check("an undefined operating mode value is ignored", "3".equals(s(th, "mode")));
		etsLink.sendRequestWait(gMode, Priority.LOW, w8(0));
		check("operating mode 0 (auto) is treated as comfort", waitFor(3000, () -> "1".equals(s(th, "mode"))));

		// fixed temperature 18.5 and a P controller with proportional band 4 K: (21 - 18.5) / 4 = 62.5 %
		ets.downloadAll(new IndividualAddress(1, 1, 3), gas, new int[][] { { 1, 7 }, { 2, 8 }, { 3, 5 }, { 4, 3 } },
				flags(8, 7, FLAGS_STATUS_OUT, 8, FLAGS_STATUS_OUT, 5, FLAGS_RECEIVE, 3, FLAGS_RECEIVE),
				Products.THERMOSTAT.params("TempSource", 1, "FixedTemp", 18, "FixedTenths", 5, "ControlType", 1, "PBand", 4, "IntTime", 0,
						"ControlCycle", 2, "RoomSpeed", 1));
		check("thermostat reloaded with a fixed temperature and a P controller", waitFor(5000, () -> th.applicationLoaded() && num(th, "temp") == 18.5));
		check("P controller: control value 62-63 %", waitFor(5000, () -> num(th, "ctrl") >= 62 && num(th, "ctrl") <= 63));
		check("heating actuator valve 2 receives the continuous control value (63 %)", waitFor(8000, () -> num(ha, "pct2") >= 62 && num(ha, "pct2") <= 64));
		boolean sawOpen = false, sawClosed = false;
		for (int i = 0; i < 60; i++) {
			Thread.sleep(100);
			if ("true".equals(s(ha, "open2")))
				sawOpen = true;
			else
				sawClosed = true;
		}
		check("PWM: valve 2 is open for part of the cycle and closed for the rest (" + sawOpen + "/" + sawClosed + ")", sawOpen && sawClosed);

		// monitoring: valve 3 gets no control value, after 5 minutes (10 s here) it goes to the emergency position
		check("monitoring: valve 3 goes to the emergency position 40 % after the timeout", waitFor(25000, () -> "true".equals(s(ha, "em3")) && num(ha, "pct3") == 40));
		check("monitoring: the alarm object is on", "true".equals(s(ha, "fault")));
		etsLink.sendRequestWait(gVal3, Priority.LOW, w1(0));
		check("monitoring: a control value ends the emergency position and the alarm", waitFor(3000, () -> "false".equals(s(ha, "em3")) && "false".equals(s(ha, "fault"))));
		check("valve 3 (on/off) closes with control value 0", waitFor(3000, () -> "false".equals(s(ha, "open3")) && "true".equals(s(ha, "energised3"))));
	}

	// ------------------------------------------------------------------------------------------------ weather station + blinds

	private static void weather(final EtsLikeClient ets, final io.calimero.link.KNXNetworkLink etsLink, final VirtualDevice ws, final VirtualDevice bl, final List<String> trace) throws Exception {
		final var gWind = new GroupAddress(3, 0, 1);
		final var gRain = new GroupAddress(3, 0, 2);
		final var gMove1 = new GroupAddress(3, 0, 3);
		final var gMove2 = new GroupAddress(3, 0, 4);
		final int[] gas = { gWind.getRawAddress(), gRain.getRawAddress(), gMove1.getRawAddress(), gMove2.getRawAddress() };
		final int[] blFlags = flags(14, 1, FLAGS_RECEIVE, 4, FLAGS_RECEIVE, 13, FLAGS_RECEIVE, 14, FLAGS_RECEIVE);
		final int[][] blAssoc = { { 1, 13 }, { 2, 14 }, { 3, 1 }, { 4, 4 } };
		final int[] wsFlags = flags(5, 4, FLAGS_STATUS_OUT, 5, FLAGS_STATUS_OUT);
		final int[][] wsAssoc = { { 1, 5 }, { 2, 4 } };
		// blind 1 retracts on wind (the default), blind 2 only on rain
		final byte[] blParams = Products.BLIND_ACTUATOR.params("Ch1UpTime", 4, "Ch1DownTime", 4, "Ch2UpTime", 4, "Ch2DownTime", 4,
				"Ch2WindReaction", 0, "Ch2RainReaction", 1);
		ets.downloadAll(new IndividualAddress(1, 1, 6), gas, blAssoc, blFlags, blParams);
		// calm and dry: wind 5 m/s, threshold 12
		final byte[] calm = Products.WEATHER_STATION.params("WindSpeedValue", 5, "WindSpeedInterval", 0, "RainMode", 0, "RainInterval", 0,
				"AlarmThreshold", 12, "AlarmHysteresis", 2, "AlarmInterval", 0, "TemperatureInterval", 0, "IlluminanceInterval", 0);
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, calm);
		check("weather station and blind actuator loaded", waitFor(5000, () -> ws.applicationLoaded() && bl.applicationLoaded()));
		check("weather station: calm and dry, no alarm", "5.0".equals(s(ws, "s1")) && "false".equals(s(ws, "alarm")) && "false".equals(s(ws, "rain")));

		etsLink.sendRequestWait(gMove1, Priority.LOW, w1(1));
		etsLink.sendRequestWait(gMove2, Priority.LOW, w1(1));
		check("both blinds are lowered", waitFor(5000, () -> num(bl, "pos1") >= 40 && num(bl, "pos2") >= 40));

		// storm: wind 15 m/s reaches the threshold 12 -> alarm -> blind 1 retracts and is locked, blind 2 ignores the wind
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, Products.WEATHER_STATION.params("WindSpeedValue", 15,
				"WindSpeedInterval", 0, "RainMode", 0, "RainInterval", 0, "AlarmThreshold", 12, "AlarmHysteresis", 2, "AlarmInterval", 0,
				"TemperatureInterval", 0, "IlluminanceInterval", 0));
		check("wind 15 m/s >= 12: wind alarm on", waitFor(5000, () -> "true".equals(s(ws, "alarm"))));
		check("the blind actuator receives the wind alarm", waitFor(5000, () -> "true".equals(s(bl, "windAlarm"))));
		check("blind 1 retracts (moves up) and is locked, blind 2 is not locked",
				waitFor(3000, () -> num(bl, "dir1") < 0 || num(bl, "pos1") == 0) && "true".equals(s(bl, "lock1")) && "false".equals(s(bl, "lock2")));
		etsLink.sendRequestWait(gMove1, Priority.LOW, w1(1));
		Thread.sleep(300);
		check("a Move down telegram is ignored while the wind alarm is on", count(trace, "Move ignored, locked by the wind alarm") >= 1);
		check("blind 1 reaches the retracted position (0 %) and stays locked", waitFor(8000, () -> num(bl, "pos1") == 0) && "true".equals(s(bl, "lock1")));

		// calm again: alarm off, blind 1 stays where it is but can be operated again
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, calm);
		check("calm again: the alarm is off and the lock ends", waitFor(5000, () -> "false".equals(s(ws, "alarm")) && "false".equals(s(bl, "windAlarm")) && "false".equals(s(bl, "lock1"))));
		etsLink.sendRequestWait(gMove1, Priority.LOW, w1(1));
		check("after the alarm blind 1 can be lowered again", waitFor(5000, () -> num(bl, "pos1") > 20));

		// rain: blind 2 retracts, blind 1 ignores the rain (default)
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, Products.WEATHER_STATION.params("WindSpeedValue", 5,
				"WindSpeedInterval", 0, "RainMode", 1, "RainInterval", 0, "AlarmInterval", 0, "TemperatureInterval", 0, "IlluminanceInterval", 0));
		check("rain sensor reports rain", waitFor(5000, () -> "true".equals(s(ws, "rain"))));
		check("blind 2 is locked by the rain alarm and retracts, blind 1 is not locked",
				waitFor(5000, () -> "true".equals(s(bl, "lock2")) && "true".equals(s(bl, "rainAlarm"))) && "false".equals(s(bl, "lock1")));
		check("blind 2 reaches 0 % (up)", waitFor(8000, () -> num(bl, "pos2") == 0));

		// dry again
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, calm);
		check("dry again: the rain alarm ends", waitFor(5000, () -> "false".equals(s(bl, "rainAlarm")) && "false".equals(s(bl, "lock2"))));

		// hysteresis: random wind 0..20 m/s every second, alarm at 12, off below 10. Whatever the sequence, the alarm must be on
		// at 12 or more, off below 10, and unchanged between
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, Products.WEATHER_STATION.params("WindSpeedMode", 1, "WindSpeedMin", 0,
				"WindSpeedMax", 20, "WindSpeedInterval", 1, "RainMode", 0, "RainInterval", 0, "AlarmThreshold", 12, "AlarmHysteresis", 2,
				"AlarmInterval", 0, "TemperatureInterval", 0, "IlluminanceInterval", 0));
		check("random wind loaded", waitFor(5000, () -> ws.applicationLoaded() && "true".equals(s(ws, "r1"))));
		boolean ok = true;
		boolean prev = false;
		int changes = 0, holds = 0;
		for (int i = 0; i < 100; i++) {
			Thread.sleep(150);
			final var m = ws.behavior().view();
			final double w = ((Number) m.get("s1")).doubleValue();
			final boolean a = (Boolean) m.get("alarm");
			if (w >= 12)
				ok &= a;
			else if (w < 10)
				ok &= !a;
			else {
				ok &= a == prev; // hysteresis band: the alarm keeps its state
				holds++;
			}
			if (a != prev)
				changes++;
			prev = a;
		}
		check("wind alarm with hysteresis: on at 12 m/s or more, off below 10 m/s, unchanged in between (" + changes + " changes, " + holds + " samples in the band)", ok);

		// monitoring: no telegram from the station for 5 minutes (10 s here) means alarm
		ets.downloadAll(new IndividualAddress(1, 1, 5), gas, wsAssoc, wsFlags, calm);
		Thread.sleep(2000);
		ets.downloadAll(new IndividualAddress(1, 1, 6), gas, blAssoc, blFlags, Products.BLIND_ACTUATOR.params("Ch1UpTime", 4, "Ch1DownTime", 4,
				"Ch2UpTime", 4, "Ch2DownTime", 4, "WindMonitor", 5, "Ch2WindReaction", 0, "Ch2RainReaction", 1));
		check("blind actuator reloaded with wind alarm monitoring", waitFor(5000, () -> bl.applicationLoaded() && "false".equals(s(bl, "windAlarm"))));
		check("the station stays silent: after the timeout the alarm is assumed (fail-safe)", waitFor(25000, () -> "true".equals(s(bl, "windAlarm")) && "true".equals(s(bl, "windSilent"))));
		check("the blind is locked by the assumed alarm", "true".equals(s(bl, "lock1")));
		etsLink.sendRequestWait(gWind, Priority.LOW, w1(0));
		check("a telegram from the station ends the assumed alarm", waitFor(3000, () -> "false".equals(s(bl, "windAlarm")) && "false".equals(s(bl, "lock1")) && "false".equals(s(bl, "windSilent"))));
	}
}
