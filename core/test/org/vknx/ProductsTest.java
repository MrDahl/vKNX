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
import org.vknx.device.SwitchActuatorBehavior;
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
			// Layout check (documented in Products): dimmer channel 1 default byte = Enable(bit 7) + DimTime 01 (bits 6-5) + SendStatus (bit 1).
			// ETS counts bits from the top; this was proven for the old layout by a real ETS log, the new layout is checked here only.
			final byte[] dmDefaults = Products.DIMMER.defaultParams();
			check("dimmer channel 1 default parameter byte is 0xA2", (dmDefaults[0] & 0xff) == 0xA2);
			boolean allDefaults = true;
			for (final ProductDef pd : Products.ALL.values()) {
				final byte[] area = pd.defaultParams();
				for (final var prm : pd.params())
					if (prm.isText() ? !prm.readText(area).equals(prm.defaultText()) : prm.read(area) != prm.defaultValue()) {
						allDefaults = false;
						System.out.println("  default round trip failed: " + pd.key() + "." + prm.name());
					}
			}
			check("every parameter of every product reads back its default from the default parameter area", allDefaults);
			final byte[] named = Products.PUSH_BUTTON.params("Key2Name", "Hall light", "Key3Function", 2);
			check("text and enumeration parameters round-trip", Products.PUSH_BUTTON.param("Key2Name").readText(named).equals("Hall light")
					&& Products.PUSH_BUTTON.param("Key3Function").read(named) == 2
					&& Products.PUSH_BUTTON.param("Key1Name").readText(named).equals("Key 1"));
			// DPT 9 helper
			check("DPT9 21.0 -> 0x0C1A", Dpt9.encode(21.0) == 0x0C1A);
			check("DPT9 round trip -5 / 21 / 101325", Dpt9.decode(Dpt9.encode(-5)) == -5.0
					&& Math.abs(Dpt9.decode(Dpt9.encode(21)) - 21) < 0.1
					&& Math.abs(Dpt9.decode(Dpt9.encode(101325)) - 101325) < 100);

			// ---------------- create + address all devices
			final var rocker = VirtualDevice.create("rk", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("rk.state"), net, listener);
			final var slider = VirtualDevice.create("sl", Products.PUSH_SLIDER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("sl.state"), net, listener);
			final var dimmer = VirtualDevice.create("dm", Products.DIMMER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("dm.state"), net, listener);
			final var blind = VirtualDevice.create("bl", Products.BLIND_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("bl.state"), net, listener);
			final var sa4 = VirtualDevice.create("s4", Products.SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("s4.state"), net, listener);
			final var ms = VirtualDevice.create("ms", Products.MULTISENSOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
					dir.resolve("ms.state"), net, listener);
			final var brk = VirtualDevice.create("br", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, (byte) serial++ },
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
			// push button: key 1 = relative dimming "brighter" (objects 1 switch, 2 status, 3 dimming), key 2 = dimming "darker" (6, 7, 8)
			ets.downloadAll(rocker.address(), new int[] { gSw, gDim, gSwSt }, new int[][] { { 1, 1 }, { 2, 3 }, { 3, 2 }, { 1, 6 }, { 2, 8 }, { 3, 7 } },
					new int[] { SEND, STATUS_IN, SEND, 0, 0, SEND, STATUS_IN, SEND },
					Products.PUSH_BUTTON.params("Key1Function", 1, "Key1LongPress", 0, "Key1DimStep", 1,
							"Key2Function", 1, "Key2DimDir", 1, "Key2LongPress", 0, "Key2DimStep", 1));
			// dimmer channel 1: DimTime 2 s, OnLevel 100 %, startup off, send status
			ets.downloadAll(dimmer.address(), gas, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 }, { 4, 4 }, { 5, 5 } },
					new int[] { W, W, W, STATUS_OUT, STATUS_OUT }, Products.DIMMER.params("Ch1DimTime", 0, "Ch1OnLevel", 1));
			check("push button + dimmer applications loaded", await(3000, () -> rocker.applicationLoaded() && dimmer.applicationLoaded()));
			final var dim = (DimmerBehavior) dimmer.behavior();
			rocker.behavior().act("down", 1);
			Thread.sleep(250);
			rocker.behavior().act("up", 1); // short press upper -> ON
			check("push button key 1 short press ON switches dimmer channel 1 to 100 % (OnLevel=100 %)", await(2000, () -> dim.levelPct() == 100));
			rocker.behavior().act("down", 2);
			Thread.sleep(250);
			rocker.behavior().act("up", 2); // short press lower -> OFF
			check("push button key 2 short press OFF switches dimmer off", await(2000, () -> dim.levelPct() == 0));
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
					new int[] { W, W, STATUS_OUT }, Products.BLIND_ACTUATOR.params("Ch1UpTime", 10, "Ch1DownTime", 10));
			check("blind application loaded", await(3000, blind::applicationLoaded));
			final var bl = (BlindBehavior) blind.behavior();
			etsLink.sendRequestWait(new GroupAddress(gMove), Priority.LOW, w1(1)); // down
			Thread.sleep(1500);
			etsLink.sendRequestWait(new GroupAddress(gStop), Priority.LOW, w1(1));
			final int p1 = (Integer) bl.view().get("pos1");
			check("blind 1 moved down ~15 % in 1.5 s at 10 s travel (pos " + p1 + " %)", p1 >= 10 && p1 <= 22);
			check("blind 2 untouched", (Integer) bl.view().get("pos2") == 0);
			check("blind 3 and 4 are enabled by default and named", Boolean.TRUE.equals(bl.view().get("e3")) && "Blind 4".equals(bl.view().get("n4")));
			Thread.sleep(300);
			check("blind stopped", (Integer) bl.view().get("pos1") == p1);
			synchronized (trace) {
				check("blind sent position status", trace.stream().anyMatch(s -> s.contains("BUS device") && s.contains("1.1.4") && s.contains("2/0/3")));
			}

			// ---------------- push button as blind keys -> blind actuator: long press = move, short press = step/stop
			// key 1: blinds "Down", key 2: blinds "Up", key 3: "Up / Down" alternating, key 4: "Steps" (step up)
			ets.downloadAll(brk.address(), new int[] { gMove, gStop }, new int[][] { { 1, 4 }, { 2, 5 }, { 1, 9 }, { 2, 10 }, { 1, 14 }, { 2, 15 }, { 1, 19 }, { 2, 20 } },
					new int[] { 0, 0, 0, SEND, SEND, 0, 0, 0, SEND, SEND, 0, 0, 0, SEND, SEND, 0, 0, 0, SEND, SEND },
					Products.PUSH_BUTTON.params("Key1Function", 2, "Key1BlindMode", 1, "Key1LongPress", 1,
							"Key2Function", 2, "Key2BlindMode", 0, "Key2LongPress", 1,
							"Key3Function", 2, "Key3BlindMode", 2, "Key3LongPress", 0,
							"Key4Function", 2, "Key4BlindMode", 3, "Key4BlindDir", 0, "Key4Name", "Roof window",
							"Key2Enable", 1));
			check("blind keys application loaded", await(3000, brk::applicationLoaded));
			final int before = (Integer) bl.view().get("pos1");
			brk.behavior().act("down", 1); // key 1 held
			Thread.sleep(1200);
			brk.behavior().act("up", 1);
			check("blind key 1 held: move DOWN sent, the blind actuator moves", await(3000, () -> (Integer) bl.view().get("pos1") > before));
			check("push button shows the last command", "Move down".equals(brk.behavior().view().get("l1")));
			brk.behavior().act("down", 1);
			brk.behavior().act("up", 1); // short press = step/stop
			final int stopped = (Integer) bl.view().get("pos1");
			Thread.sleep(500);
			check("blind key short press sends step/stop: the blind stops", "Step/stop".equals(brk.behavior().view().get("l1"))
					&& (Integer) bl.view().get("pos1") - stopped <= 1);
			brk.behavior().act("down", 2);
			Thread.sleep(1200);
			brk.behavior().act("up", 2);
			check("blind key 2 (Up) held: move UP sent, the blind moves back", "Move up".equals(brk.behavior().view().get("l2"))
					&& await(3000, () -> (Integer) bl.view().get("pos1") < stopped));
			brk.behavior().act("down", 3);
			Thread.sleep(700);
			brk.behavior().act("up", 3);
			brk.behavior().act("down", 3);
			Thread.sleep(700);
			brk.behavior().act("up", 3);
			check("alternating blind key sends DOWN first, then UP", "Move up".equals(brk.behavior().view().get("l3")));
			brk.behavior().act("press", 4);
			check("blind key in Steps mode sends a step at once, and has its own name", "Step up".equals(brk.behavior().view().get("l4"))
					&& "Roof window".equals(brk.behavior().view().get("n4")));
			check("push button blind objects are 1.008 and 1.007 (1 bit)", Products.PUSH_BUTTON.object(4).get().dpt().equals("DPST-1-8")
					&& Products.PUSH_BUTTON.object(5).get().dpt().equals("DPST-1-7"));

			// ---------------- 8-channel switch actuator: output 2 inverted, output 4 disabled
			final int gC3 = ga(3, 0, 3), gC2 = ga(3, 0, 2), gC4 = ga(3, 0, 4);
			ets.downloadAll(sa4.address(), new int[] { gC3, gC2, gC4 }, new int[][] { { 1, 3 }, { 2, 2 }, { 3, 4 } },
					new int[] { 0, W, W, W, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
					Products.SWITCH_ACTUATOR.params("Out2Logic", 1, "Out4Enable", 0, "Out5Name", "Kitchen"));
			check("8-fold actuator application loaded", await(3000, sa4::applicationLoaded));
			etsLink.sendRequestWait(new GroupAddress(gC3), Priority.LOW, w1(1));
			etsLink.sendRequestWait(new GroupAddress(gC2), Priority.LOW, w1(1));
			etsLink.sendRequestWait(new GroupAddress(gC4), Priority.LOW, w1(1));
			final var s4 = (SwitchActuatorBehavior) sa4.behavior();
			check("output 3 on, output 2 (inverted) open, output 4 (disabled) ignores the telegram: lamps 00100000",
					await(2000, () -> "00100000".equals(s4.view().get("lamps"))));
			Thread.sleep(300);
			check("disabled output is reported and names come from ETS text parameters", Boolean.FALSE.equals(s4.view().get("e4"))
					&& Boolean.TRUE.equals(s4.view().get("e3")) && "Kitchen".equals(s4.view().get("n5")) && "Output 6".equals(s4.view().get("n6")));

			// ---------------- multisensor: temperature fixed 21.5 degC every 1 s, CO2 random 400..500 every 1 s
			final int gTemp = ga(4, 0, 4), gCo2 = ga(4, 0, 7);
			final byte[] msParams = Products.MULTISENSOR.params("CO2Mode", 1, "CO2Interval", 1, "CO2Min", 400, "CO2Max", 500,
					"TemperatureMode", 0, "TemperatureInterval", 1, "TemperatureValue", 215, "WindSpeedEnable", 0);
			ets.downloadAll(ms.address(), new int[] { gTemp, gCo2 }, new int[][] { { 1, 4 }, { 2, 1 } },
					new int[] { SEND, 0, 0, SEND, 0, 0 }, msParams);
			check("multisensor application loaded with per-channel ETS parameters",
					await(3000, ms::applicationLoaded) && ms.param("CO2Mode") == 1 && ms.param("CO2Min") == 400
							&& ms.param("CO2Max") == 500 && ms.param("TemperatureInterval") == 1
							&& ms.param("TemperatureValue") == 215);
			final var mb = (MultisensorBehavior) ms.behavior();
			check("a disabled multisensor channel is reported as disabled", Boolean.FALSE.equals(mb.view().get("e5")) && Boolean.TRUE.equals(mb.view().get("e3")));
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
			ets.partialWrite(rocker.address(), 4, 80, 1, new byte[] { (byte) ((rocker.parameterAreaForTest()[1] & ~7) | 4) }); // ETS changes only Key1DimStep -> 12.5 % (last three bits of byte 1)
			check("partial download writes the changed parameter (Key1DimStep 12.5 % = 4)", await(2000, () -> rocker.param("Key1DimStep") == 4));
			final int pLong = rocker.param("Key1LongPress");
			ets.partialWrite(rocker.address(), 4, 80, 20, new byte[] { (byte) (rocker.parameterAreaForTest()[20] & ~0x80) }); // then only Key2Enable (top bit of byte 20)
			check("a second partial download keeps the parameters that did not change (Key1DimStep stays 4)",
					await(2000, () -> rocker.param("Key2Enable") == 0) && rocker.param("Key1DimStep") == 4
							&& rocker.param("Key1LongPress") == pLong);

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
