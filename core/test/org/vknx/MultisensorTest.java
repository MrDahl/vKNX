package org.vknx;

import static org.vknx.SelfTest.await;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;

import io.calimero.IndividualAddress;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/** The multisensor values as ETS sets them: whole degrees plus tenths, random ranges in whole degrees. Not an ETS test. */
public final class MultisensorTest {
	private static String v(final VirtualDevice d, final String key) { return String.valueOf(d.behavior().view().get(key)); }

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-mstest");
		final Subnet net = Subnet.inProcess();
		final var ms = VirtualDevice.create("ms", Products.MULTISENSOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 2 }, dir.resolve("ms.state"), net, (s, c, t) -> {});
		final var ets = new EtsLikeClient(net.createDeviceLink(new IndividualAddress(0, 2, 255)));
		try {
			ms.toggleProgrammingMode();
			check("address assigned", ets.assignAddress(new IndividualAddress(1, 1, 2)));
			Thread.sleep(300);

			// default parameters: 21 degrees and 5 tenths = 21.5 (before: the parameter was in tenths, 20 showed as 2.0)
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[0], new int[0][], new int[6], Products.MULTISENSOR.params("CO2Enable", 1));
			check("default temperature is 21.5 and default wind speed 3.0", await(() -> ms.applicationLoaded() && "21.5".equals(v(ms, "s3")) && "3.0".equals(v(ms, "s5"))));

			// 22 degrees + 4 tenths
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[0], new int[0][], new int[6],
					Products.MULTISENSOR.params("TemperatureValue", 22, "TemperatureTenths", 4, "WindSpeedValue", 12, "WindSpeedTenths", 7));
			check("22 and 4 tenths show as 22.4 degrees, wind 12 and 7 tenths as 12.7 m/s", await(() -> "22.4".equals(v(ms, "s3")) && "12.7".equals(v(ms, "s5"))));

			// negative: the tenths go away from zero
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[0], new int[0][], new int[6],
					Products.MULTISENSOR.params("TemperatureValue", -3, "TemperatureTenths", 4));
			check("-3 and 4 tenths show as -3.4 degrees", await(() -> "-3.4".equals(v(ms, "s3"))));

			// the case from the bug report: a whole-degree value of 20 shows 20, not 2.0
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[0], new int[0][], new int[6],
					Products.MULTISENSOR.params("TemperatureValue", 20, "TemperatureTenths", 0));
			check("20 degrees show as 20.0, not 2.0", await(() -> "20.0".equals(v(ms, "s3"))));

			// random range 20..31 degrees: every value in range, with a resolution of 0.1, and more than whole degrees occur
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[0], new int[0][], new int[6],
					Products.MULTISENSOR.params("TemperatureMode", 1, "TemperatureMin", 20, "TemperatureMax", 31, "TemperatureInterval", 1));
			check("random mode applied", await(() -> ms.applicationLoaded() && ms.param("TemperatureMode") == 1));
			boolean inRange = true, fraction = false;
			for (int i = 0; i < 12; i++) {
				Thread.sleep(500);
				final double t = Double.parseDouble(v(ms, "s3"));
				inRange &= t >= 20.0 && t <= 31.0;
				fraction |= Math.abs(t - Math.rint(t)) > 0.001;
			}
			check("random temperatures stay between 20 and 31 degrees", inRange);
			check("random temperatures are not only whole degrees (resolution 0.1)", fraction);
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		System.out.println(SelfTest.failed == 0 ? "MULTISENSOR TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
