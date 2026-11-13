package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;

import io.calimero.IndividualAddress;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * ETS downloads the application of one product into a device of another product (the address was given to the wrong
 * device): the device must say so. NOT an ETS compatibility test.
 */
public final class WrongAppTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-wrongapp");
		final Subnet net = Subnet.inProcess();
		final var display = VirtualDevice.create("display", Products.INFO_DISPLAY, new byte[] { 0, (byte) 0xFA, 0, 0, 1, 1 }, dir.resolve("a.state"), net, (s, c, t) -> {});
		final var colour = VirtualDevice.create("colour", Products.COLOUR_DIMMER, new byte[] { 0, (byte) 0xFA, 0, 0, 1, 2 }, dir.resolve("b.state"), net, (s, c, t) -> {});
		final var ets = new EtsLikeClient(net.createDeviceLink(new IndividualAddress(0, 2, 255)));
		try {
			colour.toggleProgrammingMode();
			check("address assigned", ets.assignAddress(new IndividualAddress(1, 1, 1)));
			check("nothing is wrong before a download", colour.foreignApplication().isEmpty());
			// ETS thinks 1.1.1 is the info display and downloads its application, but the address is on the colour dimmer
			ets.programVersion = Products.INFO_DISPLAY.programVersion(Products.MANUFACTURER_ID);
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[0], new int[0][], new int[0], new byte[4]);
			Thread.sleep(300);
			check("the colour actuator reports the info display application", colour.foreignApplication().equals(Products.INFO_DISPLAY.name()));
			check("the device card line says so", colour.lastEtsAction().contains("wrong application"));
			// the right application clears it
			ets.programVersion = Products.COLOUR_DIMMER.programVersion(Products.MANUFACTURER_ID);
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[0], new int[0][], new int[0], new byte[4]);
			Thread.sleep(300);
			check("the right application clears the warning", colour.foreignApplication().isEmpty());
			check("and the card line no longer mentions it", !colour.lastEtsAction().contains("wrong application"));
			check("the display itself is untouched", display.foreignApplication().isEmpty());
			System.out.println("WrongAppTest passed");
		}
		finally {
			display.close();
			colour.close();
		}
		System.exit(0);
	}
}
