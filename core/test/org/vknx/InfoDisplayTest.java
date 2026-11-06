package org.vknx;

import static org.vknx.SelfTest.FLAGS_STATUS_IN;
import static org.vknx.SelfTest.FLAGS_STATUS_OUT;
import static org.vknx.SelfTest.await;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.Dpt9;
import org.vknx.device.InfoDisplayBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * The info display: screens, rows, formatting of every row type, paging (card and bus objects) and "read on init",
 * driven the way ETS would (address assignment and full downloads over the bus). Not an ETS compatibility test.
 */
public final class InfoDisplayTest {
	private static final int FLAGS_STATUS_IN_INIT = FLAGS_STATUS_IN | 0x20;

	private static byte[] w1(final int v) { return new byte[] { 0x00, (byte) (0x80 | v) }; }

	private static byte[] w8(final int v) { return new byte[] { 0x00, (byte) 0x80, (byte) v }; }

	private static byte[] w16(final int v) { return new byte[] { 0x00, (byte) 0x80, (byte) (v >> 8), (byte) v }; }

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	private static String v(final VirtualDevice d, final String key) { return String.valueOf(d.behavior().view().get(key)); }

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-displaytest");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> { synchronized (trace) { trace.add(src.name() + " [" + cat + "] " + text); } };
		final Subnet net = Subnet.inProcess();
		final var id = VirtualDevice.create("id", Products.INFO_DISPLAY, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 1 }, dir.resolve("id.state"), net, listener);
		final var ms = VirtualDevice.create("ms", Products.MULTISENSOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 2 }, dir.resolve("ms.state"), net, listener);
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new EtsLikeClient(etsLink);
		try {
			check("product has 2 paging objects and 4 objects per row of 5 screens x 6 rows",
					Products.INFO_DISPLAY.objects().size() == 2 + 5 * 6 * 4);
			check("ETS texts of the display stay within 50 characters", Products.INFO_DISPLAY.params().stream().noneMatch(p -> p.text().length() > 50)
					&& Products.INFO_DISPLAY.objects().stream().noneMatch(o -> o.text().length() > 50)
					&& Products.ROW_TYPES.stream().noneMatch(t -> t.text().length() > 50));

			int host = 1;
			for (final var d : List.of(id, ms)) {
				d.toggleProgrammingMode();
				check("address assigned to " + d.name(), ets.assignAddress(new IndividualAddress(1, 1, host++)));
				Thread.sleep(300);
			}
			final var gaLamp = new GroupAddress(8, 0, 1);
			final var gaDim = new GroupAddress(8, 0, 2);
			final var gaScene = new GroupAddress(8, 0, 3);
			final var gaTemp = new GroupAddress(8, 0, 4);
			final var gaPress = new GroupAddress(8, 0, 5);
			final var gaCo2 = new GroupAddress(8, 0, 6);
			final var gaNext = new GroupAddress(8, 0, 7);
			final var gaPrev = new GroupAddress(8, 0, 8);

			// ---- the multisensor answers reads of its temperature (21.5 degrees, no cyclic sending)
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[] { gaTemp.getRawAddress() }, new int[][] { { 1, 4 } },
					flags(6, 4, FLAGS_STATUS_OUT), Products.MULTISENSOR.params("TemperatureInterval", 0));
			check("multisensor loaded", await(ms::applicationLoaded));

			// ---- display: screen 1 "Living" with five rows, screen 2 "Air" with CO2, screen 3 off
			final int tempObj = Products.displayObject(1, 4, 3);
			final byte[] params = Products.INFO_DISPLAY.params(
					"S1Enable", 1, "S1Name", "Living",
					"S1R1Type", 1, "S1R1Label", "Lamp", "S1R2Type", 2, "S1R2Label", "Dimmer", "S1R3Type", 3, "S1R3Label", "Scene",
					"S1R4Type", 4, "S1R4Label", "Temperature", "S1R5Type", 8, "S1R5Label", "Pressure",
					"S2Enable", 1, "S2Name", "Air", "S2R1Type", 6, "S2R1Label", "CO2",
					"S3Enable", 0, "S3Name", "Hidden");
			final int[] gas = { gaLamp.getRawAddress(), gaDim.getRawAddress(), gaScene.getRawAddress(), gaTemp.getRawAddress(),
					gaPress.getRawAddress(), gaCo2.getRawAddress(), gaNext.getRawAddress(), gaPrev.getRawAddress() };
			final int[][] assoc = { { 1, Products.displayObject(1, 1, 0) }, { 2, Products.displayObject(1, 2, 1) }, { 3, Products.displayObject(1, 3, 2) },
					{ 4, tempObj }, { 5, Products.displayObject(1, 5, 3) }, { 6, Products.displayObject(2, 1, 3) }, { 7, 1 }, { 8, 2 } };
			ets.downloadAll(new IndividualAddress(1, 1, 1), gas, assoc,
					flags(2 + 120, 1, FLAGS_STATUS_IN, 2, FLAGS_STATUS_IN, Products.displayObject(1, 1, 0), FLAGS_STATUS_IN_INIT,
							Products.displayObject(1, 2, 1), FLAGS_STATUS_IN, Products.displayObject(1, 3, 2), FLAGS_STATUS_IN, tempObj, FLAGS_STATUS_IN_INIT,
							Products.displayObject(1, 5, 3), FLAGS_STATUS_IN, Products.displayObject(2, 1, 3), FLAGS_STATUS_IN),
					params);
			check("display loaded", await(id::applicationLoaded));
			final var disp = (InfoDisplayBehavior) id.behavior();
			check("starts on screen 1 named Living with 2 enabled screens", "1".equals(v(id, "cur")) && "Living".equals(v(id, "name")) && "2".equals(v(id, "count")));
			check("rows show the ETS labels", "Lamp".equals(v(id, "r1l")) && "Temperature".equals(v(id, "r4l")) && "".equals(v(id, "r6l")));

			// ---- read on init: the temperature row asks the bus and the multisensor answers
			check("read on init: the temperature row shows 21.5 °C without any telegram having been sent to it", await(() -> "21.5 °C".equals(v(id, "r4v"))));
			check("rows without a value yet show a dash", "–".equals(v(id, "r1v")) && "–".equals(v(id, "r2v")));

			// ---- every row type
			etsLink.sendRequestWait(gaLamp, Priority.LOW, w1(1));
			check("on/off status: On", await(() -> "On".equals(v(id, "r1v"))));
			etsLink.sendRequestWait(gaLamp, Priority.LOW, w1(0));
			check("on/off status: Off", await(() -> "Off".equals(v(id, "r1v"))));
			etsLink.sendRequestWait(gaDim, Priority.LOW, w8(128));
			check("percent: 128/255 shows 50 %", await(() -> "50 %".equals(v(id, "r2v"))));
			etsLink.sendRequestWait(gaScene, Priority.LOW, w8(5));
			check("scene: value 5 shows Scene 6", await(() -> "Scene 6".equals(v(id, "r3v"))));
			etsLink.sendRequestWait(gaScene, Priority.LOW, w8(0x80 | 8));
			Thread.sleep(300);
			check("a scene STORE telegram does not change the shown scene", "Scene 6".equals(v(id, "r3v")));
			etsLink.sendRequestWait(gaTemp, Priority.LOW, w16(Dpt9.encode(-3.4)));
			check("temperature: -3.4 °C", await(() -> "-3.4 °C".equals(v(id, "r4v"))));
			etsLink.sendRequestWait(gaPress, Priority.LOW, w16(Dpt9.encode(101300)));
			check("pressure in Pa is shown in hPa", await(() -> v(id, "r5v").startsWith("10") && v(id, "r5v").endsWith("hPa")));
			System.out.println("pressure row: " + v(id, "r5v"));

			// ---- paging: card buttons and bus objects, disabled screens are skipped
			disp.act("next", 0);
			check("next: screen 2 (Air)", "2".equals(v(id, "cur")) && "Air".equals(v(id, "name")) && "2".equals(v(id, "pos")));
			etsLink.sendRequestWait(gaCo2, Priority.LOW, w16(Dpt9.encode(812)));
			check("CO2: 812 ppm", await(() -> "812 ppm".equals(v(id, "r1v"))) && "CO2".equals(v(id, "r1l")));
			check("screen 2 shows only its own row", "".equals(v(id, "r2l")));
			disp.act("next", 0);
			check("next wraps to screen 1 and skips the disabled screen 3", "1".equals(v(id, "cur")));
			etsLink.sendRequestWait(gaNext, Priority.LOW, w1(1));
			check("next screen object (value 1) pages to screen 2", await(() -> "2".equals(v(id, "cur"))));
			etsLink.sendRequestWait(gaNext, Priority.LOW, w1(0));
			Thread.sleep(300);
			check("next screen object with value 0 does nothing", "2".equals(v(id, "cur")));
			etsLink.sendRequestWait(gaPrev, Priority.LOW, w1(1));
			check("previous screen object pages back", await(() -> "1".equals(v(id, "cur"))));
			check("screen 1 still holds its values after paging", "Scene 6".equals(v(id, "r3v")) && "-3.4 °C".equals(v(id, "r4v")));

			// ---- reload with no enabled screen
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[0], new int[0][], flags(122), Products.INFO_DISPLAY.params("S1Enable", 0));
			check("no enabled screen: the card has nothing to show", await(() -> id.applicationLoaded() && "0".equals(v(id, "cur"))));
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
		System.out.println(SelfTest.failed == 0 ? "INFO DISPLAY TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
