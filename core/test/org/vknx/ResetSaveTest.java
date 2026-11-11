package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.IndividualAddress;
import io.calimero.mgmt.ManagementClient.EraseCode;

/** What happens after ETS's master reset (confirmed restart) is saved when vKNX closes? */
public final class ResetSaveTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-resetsave");
		final var state = dir.resolve("ws.state");
		final var addr = new IndividualAddress(2, 2, 15);
		Subnet net = Subnet.inProcess();
		VirtualDevice ws = VirtualDevice.create("ws", Products.WEATHER_STATION, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 7 }, state, net, (s, c, t) -> {});
		var link = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		var ets = new SelfTest.EtsLikeClient(link);
		ws.toggleProgrammingMode();
		check("address assigned", ets.assignAddress(addr));
		Thread.sleep(300);
		ets.downloadAll(addr, new int[] { 0x0B01 }, new int[][] { { 1, 4 } }, new int[5], Products.WEATHER_STATION.params("WindSpeedMode", 1));
		final int first = ws.param("WindSpeedMode");
		final var dest = ets.mc.createDestination(addr, true);
		try { ets.mc.restart(dest, EraseCode.ConfirmedRestart, 0); } catch (final Exception e) { System.out.println("restart: " + e); }
		finally { dest.destroy(); }
		Thread.sleep(4000);
		// a second download after the reset: another value
		ets.downloadAll(addr, new int[] { 0x0B01 }, new int[][] { { 1, 4 } }, new int[5], Products.WEATHER_STATION.params("WindSpeedMode", 0));
		final int second = ws.param("WindSpeedMode");
		System.out.println("first=" + first + " second=" + second);
		check("the second download changed the parameter", first != second);
		// a crash or a forced stop: no close at all, the state file written a few seconds after the last change must do
		Thread.sleep(4500);
		final var crashed = dir.resolve("crashed.state");
		Files.copy(state, crashed);
		final var net2 = Subnet.inProcess();
		final var fromCrash = VirtualDevice.create("crashed", Products.WEATHER_STATION, new byte[6], crashed, net2, (s, c, t) -> {});
		check("the state file kept up while vKNX was running (no close): " + fromCrash.param("WindSpeedMode"),
				fromCrash.param("WindSpeedMode") == second && fromCrash.applicationLoaded());
		fromCrash.close();
		net2.close();
		link.close();
		ws.close();
		net.close();
		net = Subnet.inProcess();
		ws = VirtualDevice.create("ws", Products.WEATHER_STATION, new byte[6], state, net, (s, c, t) -> {});
		System.out.println("restored=" + ws.param("WindSpeedMode") + " loaded=" + ws.applicationLoaded());
		check("what was downloaded AFTER a master reset survives a restart of vKNX", ws.param("WindSpeedMode") == second);
		ws.close();
		System.out.println("reset save test done");
		System.exit(SelfTest.failed > 0 ? 1 : 0);
	}
}
