package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.ByteBuffer;
import java.nio.file.Files;

import io.calimero.IndividualAddress;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.PropertyAccess.PID;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * Download to a device that was restored from its state file after vKNX was restarted. ETS reads the device control
 * property; if it says "verify mode on" ETS does not write it again and expects every memory write to be answered with
 * the data in memory. A restored device used to report the property but not answer (Calimero keeps the mode in a field
 * that only a property write sets), so the download failed with "Device does not respond" and stayed at "Download in
 * progress". Own mini-ETS, NOT an ETS compatibility test.
 */
public final class DownloadResumeTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-resume");
		final var state = dir.resolve("ws.state");
		final byte[] serial = { 0, (byte) 0xFA, 0, 0, 0, 7 };
		final var addr = new IndividualAddress(2, 2, 15);
		Subnet net = Subnet.inProcess();
		VirtualDevice ws = VirtualDevice.create("ws", Products.WEATHER_STATION, serial, state, net, (s, c, t) -> {});
		try {
			var link = net.createDeviceLink(new IndividualAddress(0, 2, 255));
			var ets = new EtsLikeClient(link);
			ws.toggleProgrammingMode();
			check("address assigned", ets.assignAddress(addr));
			Thread.sleep(300);
			ets.downloadAll(addr, new int[] { 0x0B01 }, new int[][] { { 1, 4 } }, new int[5], Products.WEATHER_STATION.params("WindSpeedMode", 1));
			// like ETS in a full download: write the device control property (verify mode on)
			// (the connection of the download may still be closing: wait and repeat, as the other tests do)
			for (int attempt = 1;; attempt++) {
				Thread.sleep(2000);
				final var d0 = ets.mc.createDestination(addr, true);
				try {
					ets.mc.rawPropertyWrite(d0, 0, PID.DEVICE_CONTROL, new byte[] { 0x04 });
					break;
				}
				catch (final io.calimero.KNXException e) {
					if (attempt >= 3)
						throw e;
				}
				finally {
					d0.destroy();
				}
			}
			link.close();

			// vKNX is restarted: the device is restored from its state file
			ws.close();
			net.close();
			net = Subnet.inProcess();
			ws = VirtualDevice.create("ws", Products.WEATHER_STATION, new byte[6], state, net, (s, c, t) -> {});
			check("restored from the state file", ws.address().equals(addr) && ws.applicationLoaded());
			link = net.createDeviceLink(new IndividualAddress(0, 2, 255));
			ets = new EtsLikeClient(link);

			// ETS only READS the device control property ...
			final var d = ets.mc.createDestination(addr, true, false, true);
			try {
				final byte[] dc = ets.mc.readProperty(d, 0, PID.DEVICE_CONTROL, 1, 1);
				check("the restored device reports verify mode on", dc.length > 0 && (dc[0] & 0x04) != 0);
				// ... and writes memory, expecting the verifying answer (a missing answer throws after 3 s)
				final int tableRef = ByteBuffer.wrap(ets.mc.readProperty(d, 4, PID.TABLE_REFERENCE, 1, 1)).getInt();
				final byte[] mem = ets.mc.readMemory(d, tableRef, 10);
				boolean answered;
				try {
					ets.mc.writeMemory(d, tableRef, mem);
					answered = true;
				}
				catch (final io.calimero.KNXTimeoutException | io.calimero.KNXRemoteException e) {
					answered = false;
				}
				check("a 10 byte memory write to the restored device is answered (verify mode)", answered);
			}
			finally {
				d.destroy();
			}
			link.close();
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		try { ws.close(); } catch (final RuntimeException ignore) { }
		net.close();
		System.out.println(SelfTest.failed == 0 ? "DOWNLOAD RESUME TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
