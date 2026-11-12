package org.vknx;

import static org.vknx.SelfTest.FLAGS_RECEIVE;
import static org.vknx.SelfTest.FLAGS_STATUS_IN;
import static org.vknx.SelfTest.FLAGS_STATUS_OUT;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.ColourDimmerBehavior;
import org.vknx.device.DeviceBehavior;
import org.vknx.device.EnergyMeterBehavior;
import org.vknx.device.PresenceBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;
import org.vknx.ui.TelegramDecoder;

/**
 * Presence detector, colour dimming actuator and energy meter, driven the way ETS would: address assignment and full
 * downloads over the bus, then group telegrams. The follow-up times run 30 times faster here. NOT an ETS compatibility test.
 */
public final class MoreProductsTest {
	private static byte[] w1(final int v) { return new byte[] { 0x00, (byte) (0x80 | v) }; }

	private static byte[] wN(final int... bytes) {
		final byte[] b = new byte[2 + bytes.length];
		b[1] = (byte) 0x80;
		for (int i = 0; i < bytes.length; i++)
			b[2 + i] = (byte) bytes[i];
		return b;
	}

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static String s(final VirtualDevice d, final String key) { return String.valueOf(d.behavior().view().get(key)); }

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

	private static byte[] serial(final int n) { return new byte[] { 0, (byte) 0xFA, 0, 0, 1, (byte) n }; }

	public static void main(final String[] args) throws Exception {
		DeviceBehavior.timeScale = 30;
		final var dir = Files.createTempDirectory("vknx-moreproducts");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> { synchronized (trace) { trace.add(src.name() + " [" + cat + "] " + text); } };
		final Subnet net = Subnet.inProcess();
		final var pd = VirtualDevice.create("pd", Products.PRESENCE_DETECTOR, serial(1), dir.resolve("pd.state"), net, listener);
		final var sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, serial(2), dir.resolve("sa.state"), net, listener);
		final var cd = VirtualDevice.create("cd", Products.COLOUR_DIMMER, serial(3), dir.resolve("cd.state"), net, listener);
		final var em = VirtualDevice.create("em", Products.ENERGY_METER, serial(4), dir.resolve("em.state"), net, listener);
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new EtsLikeClient(etsLink);
		try {
			for (final var p : List.of(Products.PRESENCE_DETECTOR, Products.COLOUR_DIMMER, Products.ENERGY_METER)) {
				check(p.key() + ": ETS texts within 50 characters", p.params().stream().noneMatch(q -> q.text().length() > 50)
						&& p.objects().stream().noneMatch(o -> p.dynamicText(o).length() > 50 && o.text().length() > 50)
						&& p.params().stream().flatMap(q -> q.choices().stream()).noneMatch(c -> c.text().length() > 50));
				check(p.key() + ": object numbers are unique", p.objects().stream().map(o -> o.number()).distinct().count() == p.objects().size());
				check(p.key() + ": parameter names are unique", p.params().stream().map(q -> q.name()).distinct().count() == p.params().size());
			}
			int host = 1;
			for (final var d : List.of(pd, sa, cd, em)) {
				d.toggleProgrammingMode();
				check("address assigned to " + d.name(), ets.assignAddress(new IndividualAddress(1, 1, host++)));
				Thread.sleep(300);
			}
			final int gLight = new GroupAddress(2, 0, 1).getRawAddress(), gStatus = new GroupAddress(2, 0, 2).getRawAddress(),
					gPres = new GroupAddress(2, 0, 3).getRawAddress(), gLock = new GroupAddress(2, 0, 4).getRawAddress();
			final int gSw = new GroupAddress(2, 1, 1).getRawAddress(), gRgb = new GroupAddress(2, 1, 2).getRawAddress(),
					gBri = new GroupAddress(2, 1, 3).getRawAddress(), gSw2 = new GroupAddress(2, 1, 4).getRawAddress(),
					gCt = new GroupAddress(2, 1, 5).getRawAddress(), gRgbSt = new GroupAddress(2, 1, 6).getRawAddress();
			final int gReset = new GroupAddress(2, 2, 1).getRawAddress(), gAlarm = new GroupAddress(2, 2, 2).getRawAddress(),
					gPower = new GroupAddress(2, 2, 3).getRawAddress();

			// switch actuator: output 1 follows the detector and reports its status (which the meter monitors)
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[] { gLight, gStatus }, new int[][] { { 1, 1 }, { 2, 9 } },
					flags(17, 1, FLAGS_RECEIVE, 9, FLAGS_STATUS_OUT), Products.SWITCH_ACTUATOR.defaultParams());
			// presence detector: 10 s follow-up (0.33 s here), threshold 300 lux, presence object 1 min (2 s here), lock sends OFF
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { gLight, gPres, gLock }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 4 } },
					flags(4, 1, FLAGS_STATUS_OUT, 2, FLAGS_STATUS_OUT, 4, FLAGS_RECEIVE),
					Products.PRESENCE_DETECTOR.params("FollowTime", 0, "Threshold", 300, "HvacFollow", 0, "LuxStart", 150, "LockReaction", 1, "LuxInterval", 0));
			check("detector and switch actuator loaded", waitFor(5000, () -> pd.applicationLoaded() && sa.applicationLoaded()));
			final var pb = (PresenceBehavior) pd.behavior();

			// ---- presence detector
			pd.behavior().act("lux", 800);
			pd.behavior().act("motion", 0);
			Thread.sleep(300);
			check("presence: movement in a bright room leaves the light off", !pb.lightOn() && s(sa, "lamps").startsWith("0"));
			check("presence: but the presence object is set (it ignores the brightness)", pb.presence());
			pd.behavior().act("lux", 100);
			pd.behavior().act("motion", 0);
			check("presence: movement in a dark room switches the light on", waitFor(2000, () -> s(sa, "lamps").startsWith("1")) && pb.lightOn());
			check("presence: the light goes off after the follow-up time", waitFor(3000, () -> s(sa, "lamps").startsWith("0")) && !pb.lightOn());
			check("presence: the presence object lasts longer than the light", pb.presence());
			check("presence: and is cleared after its own time", waitFor(5000, () -> !pb.presence()));
			pd.behavior().act("occupied", 1);
			check("presence: a person in the room switches the light on", waitFor(2000, () -> s(sa, "lamps").startsWith("1")));
			Thread.sleep(1200);
			check("presence: and it stays on while the person is there (longer than the follow-up time)", s(sa, "lamps").startsWith("1"));
			etsLink.sendRequest(new GroupAddress(gLock), Priority.LOW, w1(1));
			check("presence: the lock object switches the light off (LockReaction)", waitFor(2000, () -> s(sa, "lamps").startsWith("0")) && "true".equals(s(pd, "locked")));
			Thread.sleep(600);
			check("presence: no light while locked, although the person is still there", s(sa, "lamps").startsWith("0"));
			etsLink.sendRequest(new GroupAddress(gLock), Priority.LOW, w1(0));
			check("presence: unlocked, the light comes back with the next movement", waitFor(2000, () -> s(sa, "lamps").startsWith("1")));
			pd.behavior().act("occupied", 0);
			check("presence: the person leaves, the light goes off after the follow-up time", waitFor(3000, () -> s(sa, "lamps").startsWith("0")));

			// ---- colour dimming actuator: channel 1 RGB, channel 2 tunable white 2700-6500 K
			ets.downloadAll(new IndividualAddress(1, 1, 3), new int[] { gSw, gRgb, gBri, gSw2, gCt, gRgbSt },
					new int[][] { { 1, 1 }, { 2, 4 }, { 3, 3 }, { 4, 10 }, { 5, 14 }, { 6, 8 } },
					flags(18, 1, FLAGS_RECEIVE, 4, FLAGS_RECEIVE, 3, FLAGS_RECEIVE, 10, FLAGS_RECEIVE, 14, FLAGS_RECEIVE, 8, FLAGS_STATUS_OUT),
					Products.COLOUR_DIMMER.params("Ch1Type", 0, "Ch2Type", 1, "Ch2WarmK", 27, "Ch2ColdK", 65));
			check("colour dimmer loaded", waitFor(5000, cd::applicationLoaded));
			final var cb = (ColourDimmerBehavior) cd.behavior();
			etsLink.sendRequest(new GroupAddress(gRgb), Priority.LOW, wN(0xFF, 0x80, 0x00));
			check("colour: an RGB telegram sets the colour and switches the channel on", waitFor(2000, () -> cb.rgb(0) == 0xFF8000 && cb.levelPct(0) == 100));
			check("colour: the card shows the colour", "#FF8000".equals(s(cd, "rgb1")));
			etsLink.sendRequest(new GroupAddress(gBri), Priority.LOW, wN(128));
			check("colour: the brightness dims without changing the colour", waitFor(2000, () -> cb.levelPct(0) == 50 && cb.rgb(0) == 0xFF8000));
			etsLink.sendRequest(new GroupAddress(gRgb), Priority.LOW, wN(0, 0, 0));
			check("colour: black switches off and keeps the colour", waitFor(2000, () -> cb.levelPct(0) == 0 && cb.rgb(0) == 0xFF8000));
			etsLink.sendRequest(new GroupAddress(gSw), Priority.LOW, w1(1));
			check("colour: switching on brings the last brightness and colour back", waitFor(2000, () -> cb.levelPct(0) == 50 && cb.rgb(0) == 0xFF8000));
			check("colour: the RGB status object was sent (3 bytes)", count(trace, "value 16744448 (object 8 Ch1RgbStatus)") > 0);
			etsLink.sendRequest(new GroupAddress(gSw2), Priority.LOW, w1(1));
			etsLink.sendRequest(new GroupAddress(gCt), Priority.LOW, wN(4000 >> 8, 4000 & 0xff));
			check("colour: channel 2 takes a colour temperature", waitFor(2000, () -> cb.kelvin(1) == 4000 && cb.levelPct(1) == 100));
			etsLink.sendRequest(new GroupAddress(gCt), Priority.LOW, wN(9000 >> 8, 9000 & 0xff));
			check("colour: a colour temperature outside the ETS range is limited", waitFor(2000, () -> cb.kelvin(1) == 6500));
			etsLink.sendRequest(new GroupAddress(gCt), Priority.LOW, wN(1000 >> 8, 1000 & 0xff));
			check("colour: also at the warm end", waitFor(2000, () -> cb.kelvin(1) == 2700));
			final int before = count(trace, "RGB telegram ignored");
			check("colour: decoders know the new types", "7.600".equals(TelegramDecoder.decode("00800FA0", "GroupValueWrite", Set.of("DPST-7-600")).dpt())
					&& TelegramDecoder.decode("00800FA0", "GroupValueWrite", Set.of("DPST-7-600")).value() != null
					&& TelegramDecoder.decode("0080FF8000", "GroupValueWrite", Set.of("DPST-232-600")).value() != null
					&& TelegramDecoder.decode("008043480000", "GroupValueWrite", Set.of("DPST-14-56")).value() != null
					&& TelegramDecoder.decode("0080000003E8", "GroupValueWrite", Set.of("DPST-13-10")).value() != null
					&& TelegramDecoder.decode("0080000003E8", "GroupValueWrite", Set.of("DPST-13-13")).value() != null
					&& TelegramDecoder.decode("00800C1A", "GroupValueWrite", Set.of("DPST-9-21")).value() != null
					&& TelegramDecoder.decode("0081", "GroupValueWrite", Set.of("DPST-1-18")).value() != null && before == 0);

			// ---- energy meter: base 100 W, load 1 = 60 W follows the status of output 1, limit 150 W, no fluctuation
			ets.downloadAll(new IndividualAddress(1, 1, 4), new int[] { gStatus, gReset, gAlarm, gPower },
					new int[][] { { 1, 7 }, { 2, 5 }, { 3, 6 }, { 4, 1 } },
					flags(10, 7, FLAGS_STATUS_IN, 5, FLAGS_RECEIVE, 6, FLAGS_STATUS_OUT, 1, FLAGS_STATUS_OUT),
					Products.ENERGY_METER.params("BaseLoad", 100, "Fluctuation", 0, "Load1Enable", 1, "Load1Power", 60, "Limit", 150,
							"PowerInterval", 0, "EnergyInterval", 0, "SendOnChange", 10));
			check("energy meter loaded", waitFor(5000, em::applicationLoaded));
			final var mb = (EnergyMeterBehavior) em.behavior();
			check("meter: base load only", waitFor(3000, () -> Math.round(mb.power()) == 100) && !mb.alarm());
			etsLink.sendRequest(new GroupAddress(gLight), Priority.LOW, w1(1)); // output 1 on -> its status -> load 1
			check("meter: the load is added when the actuator output reports on", waitFor(4000, () -> Math.round(mb.power()) == 160));
			check("meter: above the limit the alarm is sent", waitFor(3000, mb::alarm) && count(trace, "value 1 (object 6 LimitAlarm)") > 0);
			check("meter: the power was sent because it changed (4 byte float)", count(trace, "value 160.0 W (object 1 Power)") > 0);
			check("meter: energy is counted", waitFor(4000, () -> mb.energyWh() > 0.5));
			etsLink.sendRequest(new GroupAddress(gReset), Priority.LOW, w1(1));
			check("meter: the reset object clears the counter", waitFor(2000, () -> mb.energyWh() < 0.5));
			etsLink.sendRequest(new GroupAddress(gLight), Priority.LOW, w1(0));
			check("meter: load off, power back at the base load, alarm cleared", waitFor(4000, () -> Math.round(mb.power()) == 100 && !mb.alarm()));
			em.behavior().act("extra", 500);
			check("meter: the extra load from the card is added", waitFor(3000, () -> Math.round(mb.power()) == 600));
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		DeviceBehavior.timeScale = 1;
		if (SelfTest.failed != 0)
			synchronized (trace) {
				System.out.println("---- last log lines");
				trace.subList(Math.max(0, trace.size() - 50), trace.size()).forEach(System.out::println);
			}
		System.out.println(SelfTest.failed == 0 ? "MORE PRODUCTS TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
