package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.vknx.device.BlindBehavior;
import org.vknx.device.DeviceRegistry;
import org.vknx.device.DimmerBehavior;
import org.vknx.device.Dpt9;
import org.vknx.device.MultisensorBehavior;
import org.vknx.device.SwitchActuatorNBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.ProductDef;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;

/**
 * Backend test of the products beyond button + relay (rocker, dimmer, blinds, 4-channel relay, multisensor, registry),
 * driven by the mini ETS of {@link SelfTest}. NOT an ETS compatibility test.
 */
public final class ProductsTest {
	static final int W = 0x04 | 0x10 | 0x03, SEND = 0x04 | 0x40 | 0x03, STATUS_OUT = 0x04 | 0x08 | 0x40 | 0x03,
			STATUS_IN = 0x04 | 0x10 | 0x80 | 0x03;

	static boolean await(final long ms, final BooleanSupplier c) throws InterruptedException {
		for (long t = 0; t < ms; t += 50) {
			if (c.getAsBoolean())
				return true;
			Thread.sleep(50);
		}
		return c.getAsBoolean();
	}

	static byte[] w1(final int v) { return new byte[] { 0x00, (byte) (0x80 | v) }; }

	static byte[] w8(final int v) { return new byte[] { 0x00, (byte) 0x80, (byte) v }; }

	static int ga(final int a, final int b, final int c) { return new GroupAddress(a, b, c).getRawAddress(); }

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-products");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> {
			synchronized (trace) { trace.add(SelfTest.ms() + src.name() + " [" + cat + "] " + text); }
		};
		final var net = Subnet.inProcess();
		net.monitor(s -> { synchronized (trace) { trace.add(SelfTest.ms() + "BUS " + s); } });
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new SelfTest.EtsLikeClient(etsLink);
		int serial = 10;
		try {
			// Bytes copied from a real ETS 6 download log (defaults of our products): ETS counts bits from the top.
			final byte[] etsDimmer = { 0x44 };
			check("real ETS dimmer parameter byte 0x44: DimTime 5 s, last level, startup off, send status YES",
					Products.DIMMER.param("DimTime").read(etsDimmer) == 1 && Products.DIMMER.param("OnLevel").read(etsDimmer) == 0
							&& Products.DIMMER.param("StartupState").read(etsDimmer) == 0
							&& Products.DIMMER.param("SendStatus").read(etsDimmer) == 1);
			final byte[] etsRocker = { 0x20, 0x20 };
			check("real ETS rocker bytes 0x20 0x20: upper key on, long press 0.6 s, dimming step 100 %",
					Products.PUSH_ROCKER.param("Orientation").read(etsRocker) == 0
							&& Products.PUSH_ROCKER.param("LongPressTime").read(etsRocker) == 1
							&& Products.PUSH_ROCKER.param("DimStep").read(etsRocker) == 1);
			// DPT 9 helper
			check("DPT9 21.0 -> 0x0C1A", Dpt9.encode(21.0) == 0x0C1A);
			check("DPT9 round trip -5 / 21 / 101325", Dpt9.decode(Dpt9.encode(-5)) == -5.0
					&& Math.abs(Dpt9.decode(Dpt9.encode(21)) - 21) < 0.1
					&& Math.abs(Dpt9.decode(Dpt9.encode(101325)) - 101325) < 100);

			// ---------------- create + address all devices
			final var rocker = VirtualDevice.create("rk", Products.PUSH_ROCKER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("rk.state"), net, listener);
			final var slider = VirtualDevice.create("sl", Products.PUSH_SLIDER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("sl.state"), net, listener);
			final var dimmer = VirtualDevice.create("dm", Products.DIMMER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("dm.state"), net, listener);
			final var blind = VirtualDevice.create("bl", Products.BLIND_2, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("bl.state"), net, listener);
			final var sa4 = VirtualDevice.create("s4", Products.SWITCH_ACTUATOR_4, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("s4.state"), net, listener);
			final var ms = VirtualDevice.create("ms", Products.MULTISENSOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("ms.state"), net, listener);
			final var brk = VirtualDevice.create("br", Products.PUSH_BLIND_ROCKER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("br.state"), net, listener);
			int host = 1;
			for (final var d : List.of(rocker, slider, dimmer, blind, sa4, ms, brk)) {
				d.toggleProgrammingMode();
				final var addr = new IndividualAddress(1, 1, host++);
				check(d.name() + " address assignment", ets.assignAddress(addr) && await(3000, () -> d.address().equals(addr)));
			}
			check("every product has a catalogue entry with unique app number", Products.ALL.values().stream()
					.map(ProductDef::appNumber).distinct().count() == Products.ALL.size());

			// ---------------- rocker -> dimmer
			final int gSw = ga(1, 0, 1), gDim = ga(1, 0, 2), gVal = ga(1, 0, 3), gSwSt = ga(1, 0, 4), gValSt = ga(1, 0, 5);
			final int[] gas = { gSw, gDim, gVal, gSwSt, gValSt };
			// rocker: Orientation 0, LongPressTime 0 (0.4 s), DimStep 1 ; objects: 1 switch(send) 2 dim(send) 3 status
			ets.downloadAll(rocker.address(), new int[] { gSw, gDim, gSwSt }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 } },
					new int[] { SEND, SEND, STATUS_IN }, new byte[] { 0x00, 0x20 /* DimStep=1 in bits 7-5 */ });
			// dimmer: DimTime 0 (2 s), OnLevel 1 (100 %), Startup 0, SendStatus 1
			ets.downloadAll(dimmer.address(), gas, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 }, { 4, 4 }, { 5, 5 } },
					new int[] { W, W, W, STATUS_OUT, STATUS_OUT }, new byte[] { 0b0010_0100 });
			check("rocker + dimmer applications loaded", await(3000, () -> rocker.applicationLoaded() && dimmer.applicationLoaded()));
			final var dim = (DimmerBehavior) dimmer.behavior();
			rocker.behavior().act("down", 1);
			Thread.sleep(250);
			rocker.behavior().act("up", 1); // short press upper -> ON
			check("rocker short press ON switches dimmer to 100 % (OnLevel=100 %)", await(2000, () -> dim.levelPct() == 100));
			rocker.behavior().act("down", 2);
			Thread.sleep(250);
			rocker.behavior().act("up", 2); // short press lower -> OFF
			check("rocker short press OFF switches dimmer off", await(2000, () -> dim.levelPct() == 0));
			rocker.behavior().act("down", 1);
			Thread.sleep(1100); // long press: dim up starts after 0.4 s, 2 s for 0-100 %
			rocker.behavior().act("up", 1);
			final int afterStop = dim.levelPct();
			check("long press ramps up and release stops it (level " + afterStop + " %)", afterStop > 10 && afterStop < 60);
			Thread.sleep(500);
			check("ramp really stopped", dim.levelPct() == afterStop);
			etsLink.sendRequestWait(new GroupAddress(gVal), Priority.LOW, w8(128));
			check("brightness value 128 -> 50 %", await(2000, () -> dim.levelPct() == 50));
			etsLink.sendRequestWait(new GroupAddress(gDim), Priority.LOW, w1(0x0F & 0x09 | 0)); // up, step 1 (start)
			Thread.sleep(300);
			etsLink.sendRequestWait(new GroupAddress(gDim), Priority.LOW, w1(0)); // stop
			check("relative dimming telegram from the bus moves the dimmer", dim.levelPct() > 50);
			synchronized (trace) {
				check("dimmer sent status telegrams on its status addresses",
						trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.3") && s.contains("1/0/4"))
								&& trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.3") && s.contains("1/0/5")));
			}

			// ---------------- slider push
			ets.downloadAll(slider.address(), new int[] { gVal, gSw, gValSt }, new int[][] { { 1, 2 }, { 2, 1 }, { 3, 3 } },
					new int[] { SEND, SEND, STATUS_IN }, new byte[] { 0 });
			check("slider application loaded", await(3000, slider::applicationLoaded));
			slider.behavior().act("slider", 25);
			check("slider 25 % sends value 64 to the dimmer", await(2000, () -> dim.levelPct() == 25));
			check("slider follows the dimmer status (25 %)", await(2000, () -> ((Number) slider.behavior().view().get("level")).intValue() == 25));
			etsLink.sendRequestWait(new GroupAddress(gSw), Priority.LOW, w1(0)); // switch the dimmer off from the bus
			check("dimmer off from the bus: slider goes to 0 %", await(2000, () -> dim.levelPct() == 0
					&& ((Number) slider.behavior().view().get("level")).intValue() == 0));
			slider.behavior().act("press", 0);
			check("slider button press after that switches the dimmer on again", await(2000, () -> dim.levelPct() > 0));

			// ---------------- blinds
			final int gMove = ga(2, 0, 1), gStop = ga(2, 0, 2), gPos = ga(2, 0, 3);
			ets.downloadAll(blind.address(), new int[] { gMove, gStop, gPos }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 } },
					new int[] { W, W, STATUS_OUT, W, W, STATUS_OUT }, new byte[] { 0b0000_1000 /* both 10 s, SendPosition (ETS counts bits from the top) */ });
			check("blind application loaded", await(3000, blind::applicationLoaded));
			final var bl = (BlindBehavior) blind.behavior();
			etsLink.sendRequestWait(new GroupAddress(gMove), Priority.LOW, w1(1)); // down
			Thread.sleep(1500);
			etsLink.sendRequestWait(new GroupAddress(gStop), Priority.LOW, w1(1));
			final int p1 = (Integer) bl.view().get("pos1");
			check("blind 1 moved down ~15 % in 1.5 s at 10 s travel (pos " + p1 + " %)", p1 >= 10 && p1 <= 22);
			check("blind 2 untouched", (Integer) bl.view().get("pos2") == 0);
			Thread.sleep(300);
			check("blind stopped", (Integer) bl.view().get("pos1") == p1);
			synchronized (trace) {
				check("blind sent position status", trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.4") && s.contains("2/0/3")));
			}

			// ---------------- blind rocker -> blind actuator: long press = move, short press = step/stop
			ets.downloadAll(brk.address(), new int[] { gMove, gStop }, new int[][] { { 1, 1 }, { 2, 2 } }, new int[] { SEND, SEND },
					new byte[] { 0b0010_0000 /* upper key = up, long press 0.6 s */ });
			check("blind rocker application loaded", await(3000, brk::applicationLoaded));
			final int before = (Integer) bl.view().get("pos1");
			brk.behavior().act("down", 2); // lower key held
			Thread.sleep(1200);
			brk.behavior().act("up", 2);
			check("blind rocker lower key held: move DOWN sent, the blind actuator moves", await(3000, () -> (Integer) bl.view().get("pos1") > before));
			check("blind rocker shows the last command", "Move down".equals(brk.behavior().view().get("last")));
			brk.behavior().act("down", 2);
			brk.behavior().act("up", 2); // short press = step/stop
			final int stopped = (Integer) bl.view().get("pos1");
			Thread.sleep(500);
			check("blind rocker short press sends step/stop: the blind stops", "Step/stop down".equals(brk.behavior().view().get("last"))
					&& (Integer) bl.view().get("pos1") - stopped <= 1);
			check("blind rocker objects are 1.008 and 1.007 (1 bit)", Products.PUSH_BLIND_ROCKER.object(1).get().dpt().equals("DPST-1-8")
					&& Products.PUSH_BLIND_ROCKER.object(2).get().dpt().equals("DPST-1-7"));

			// ---------------- 4-channel switch actuator, channel 2 inverted
			final int gC3 = ga(3, 0, 3), gC2 = ga(3, 0, 2);
			ets.downloadAll(sa4.address(), new int[] { gC3, gC2 }, new int[][] { { 1, 3 }, { 2, 2 } },
					new int[] { 0, W, W, 0, 0, 0, 0, 0 }, new byte[] { 0b0100_0000 /* Ch2 inverted */, 0b0010_0000 /* SendStatus */ });
			check("4-fold actuator application loaded", await(3000, sa4::applicationLoaded));
			etsLink.sendRequestWait(new GroupAddress(gC3), Priority.LOW, w1(1));
			etsLink.sendRequestWait(new GroupAddress(gC2), Priority.LOW, w1(1));
			final var s4 = (SwitchActuatorNBehavior) sa4.behavior();
			check("channel 3 on, channel 2 (inverted) open: lamps 0010", await(2000, () -> "0010".equals(s4.view().get("lamps"))));

			// ---------------- multisensor: temperature fixed 21.5 degC every 1 s, CO2 random 400..500 every 1 s
			final int gTemp = ga(4, 0, 4), gCo2 = ga(4, 0, 7);
			final byte[] msParams = new byte[60];
			msParams[0] = 0b0100_0000; // CO2 mode = random (value 1 in the top two bits)
			msParams[2] = 0; msParams[3] = 1; // CO2 interval 1 s
			msParams[6] = 0x01; msParams[7] = (byte) 0x90; // CO2 min 400
			msParams[8] = 0x01; msParams[9] = (byte) 0xF4; // CO2 max 500
			msParams[30] = 0; // temperature mode = fixed value
			msParams[32] = 0; msParams[33] = 1; // temperature interval 1 s
			msParams[34] = 0; msParams[35] = (byte) 215; // temperature value 215 = 21.5 degC
			ets.downloadAll(ms.address(), new int[] { gTemp, gCo2 }, new int[][] { { 1, 4 }, { 2, 1 } },
					new int[] { SEND, 0, 0, SEND, 0, 0 }, msParams);
			check("multisensor application loaded with per-channel ETS parameters",
					await(3000, ms::applicationLoaded) && ms.param("CO2Mode") == 1 && ms.param("CO2Min") == 400
							&& ms.param("CO2Max") == 500 && ms.param("TemperatureInterval") == 1
							&& ms.param("TemperatureValue") == 215);
			final var mb = (MultisensorBehavior) ms.behavior();
			check("multisensor sends temperature 21.5 degC as DPT9 0x0C33 to 4/0/4", await(6000, () -> {
				synchronized (trace) {
					return trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.6") && s.contains("4/0/4")
							&& s.toLowerCase().contains("0c33"));
				}
			}));
			check("multisensor sends CO2 to its own group address 4/0/7", await(6000, () -> {
				synchronized (trace) {
					return trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.6") && s.contains("4/0/7"));
				}
			}));
			final double co2 = (Double) mb.view().get("s0");
			check("random CO2 within 400..500 (" + co2 + ")", co2 >= 400 && co2 <= 500);
			mb.act("s3", 305);
			check("the panel cannot change a multisensor value (ETS only)", Math.abs((Double) mb.view().get("s3") - 21.5) < 0.01);

			// ---------------- couplers: addresses x.y.0 / x.0.0, ETS parameters decoded, no group objects
			final var lc = VirtualDevice.create("lc", Products.COUPLER_LINE, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("lc.state"), net, listener);
			final var ac = VirtualDevice.create("ac", Products.COUPLER_AREA, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("ac.state"), net, listener);
			lc.toggleProgrammingMode();
			final var lcAddr = new IndividualAddress(1, 1, 0);
			check("line coupler accepts address 1.1.0", ets.assignAddress(lcAddr) && await(3000, () -> lc.address().equals(lcAddr)));
			ac.toggleProgrammingMode();
			final var acAddr = new IndividualAddress(2, 0, 0);
			check("area coupler accepts address 2.0.0", ets.assignAddress(acAddr) && await(3000, () -> ac.address().equals(acAddr)));
			// GroupDown=filter(2) GroupUp=route(1) PhysDown=block(0) PhysUp=filter(2) | Repeat=no Confirm=yes
			final byte[] cpl = { (byte) 0b10_01_00_10, 0b0100_0000 };
			ets.downloadAll(lc.address(), new int[0], new int[0][], new int[0], cpl);
			ets.downloadAll(ac.address(), new int[0], new int[0][], new int[0], cpl);
			check("couplers load an application without group objects", await(3000, () -> lc.applicationLoaded() && ac.applicationLoaded()));
			final var lcv = lc.behavior().view();
			check("line coupler settings decoded from the ETS parameter area", "line".equals(lcv.get("kind"))
					&& "filter".equals(lcv.get("groupDown")) && "route all".equals(lcv.get("groupUp"))
					&& "block".equals(lcv.get("physDown")) && "filter".equals(lcv.get("physUp"))
					&& Boolean.FALSE.equals(lcv.get("repeat")) && Boolean.TRUE.equals(lcv.get("confirm")));
			check("area coupler reports kind area; routing is honestly reported as not active",
					"area".equals(ac.behavior().view().get("kind")) && Boolean.FALSE.equals(ac.behavior().view().get("routing")));
			check("coupler products are flagged and produce IsCoupler hardware XML",
					Products.COUPLER_LINE.coupler() && !Products.DIMMER.coupler()
							&& org.vknx.tools.ProductXmlGenerator.generate(Products.COUPLER_LINE).contains("IsCoupler=\"true\"")
							&& !org.vknx.tools.ProductXmlGenerator.generate(Products.DIMMER).contains("IsCoupler")
							&& !org.vknx.tools.ProductXmlGenerator.generate(Products.COUPLER_AREA).contains("<ComObjectTable>"));

			// ---------------- partial download keeps parameters that did not change
			ets.partialWrite(rocker.address(), 4, 2, 1, new byte[] { (byte) (4 << 5) }); // ETS changes only DimStep -> 12.5 % (top three bits)
			check("partial download writes the changed parameter (DimStep 12.5 % = 4)", await(2000, () -> rocker.param("DimStep") == 4));
			final int pLong = rocker.param("LongPressTime");
			ets.partialWrite(rocker.address(), 4, 2, 0, new byte[] { (byte) (rocker.parameterAreaForTest()[0] | 0x80) }); // then only Orientation (top bit)
			check("a second partial download keeps the parameters that did not change (DimStep stays 4)",
					await(2000, () -> rocker.param("Orientation") == 1) && rocker.param("DimStep") == 4
							&& rocker.param("LongPressTime") == pLong);

			// ---------------- registry
			final var log = new EventLog(100);
			final var regDir = Files.createTempDirectory("vknx-registry");
			final var reg = new DeviceRegistry(regDir, Subnet.inProcess(), log);
			reg.add("dimmer", "Dimmer1");
			boolean dup = false;
			try { reg.add("dimmer", "dimmer1"); } catch (final IllegalArgumentException e) { dup = true; }
			boolean bad = false;
			try { reg.add("dimmer", "bad name!"); } catch (final IllegalArgumentException e) { bad = true; }
			check("registry rejects duplicate and invalid names", dup && bad);
			check("registry remembers devices", Files.readAllLines(regDir.resolve("devices.txt")).equals(List.of("dimmer:Dimmer1")));
			reg.remove("Dimmer1");
			check("registry remove deletes state", reg.devices().isEmpty()
					&& Files.list(regDir).noneMatch(f -> f.getFileName().toString().startsWith("Dimmer1.state")));
			reg.add("dimmer", "Dimmer1");
			check("same name can be added again after removal", reg.devices().size() == 1);
			reg.closeAll();
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		finally {
			if (SelfTest.failed > 0) {
				System.out.println("---- trace ----");
				synchronized (trace) { trace.forEach(System.out::println); }
			}
		}
		System.out.println(SelfTest.failed == 0 ? "ALL PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
