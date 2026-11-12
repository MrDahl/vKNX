package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.vknx.device.DeviceRegistry;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

import io.calimero.IndividualAddress;

/** A removed device can be put back with its address and place in the list, until the next removal or the next start. */
public final class UndoRemoveTest {
	static boolean refused(final DeviceRegistry reg) {
		try { reg.restoreRemoved(); return false; } catch (final IllegalStateException | IllegalArgumentException e) { return true; } catch (final Exception e) { return false; }
	}

	public static void main(final String[] args) throws Exception {
		final var net = Subnet.inProcess();
		final var ets = new SelfTest.EtsLikeClient(net.createDeviceLink(new IndividualAddress(1, 1, 241)));
		final Path dir = Files.createTempDirectory("vknx-undo");
		final var reg = new DeviceRegistry(dir, net, new EventLog(100));
		reg.restoreOrDefault(List.of("push-button:Tryk1", "switch-actuator:Lampe1", "dimmer:Dim1"));
		check("nothing to put back at first", reg.removedName() == null && refused(reg));
		reg.devices().get(1).toggleProgrammingMode();
		check("setup: Lampe1 gets 1.1.20", ets.assignAddress(new IndividualAddress(1, 1, 20)) && CouplerTest.until(3000, () -> reg.devices().get(1).address().getDevice() == 20));
		final String serial = reg.devices().get(1).serialHex();

		reg.remove("Lampe1");
		check("removed: gone from the list and from devices.txt, the state file is not in the data folder",
				reg.devices().size() == 2 && !Files.readString(dir.resolve("devices.txt")).contains("Lampe1") && !Files.exists(dir.resolve("Lampe1.state")));
		check("it can be put back", "Lampe1".equals(reg.removedName()));
		reg.restoreRemoved();
		final var d = reg.devices().get(1);
		check("put back at its old place in the list", d.name().equals("Lampe1") && reg.devices().size() == 3 && reg.devices().get(2).name().equals("Dim1"));
		check("with its address and serial number", d.address().equals(new IndividualAddress(1, 1, 20)) && d.serialHex().equals(serial));
		check("devices.txt has it again, the trash is empty", Files.readString(dir.resolve("devices.txt")).contains("Lampe1") && !Files.exists(dir.resolve("trash")));
		check("only once", reg.removedName() == null && refused(reg));

		reg.remove("Tryk1");
		reg.remove("Dim1");
		check("only the device removed last can be put back", "Dim1".equals(reg.removedName()));
		reg.add("dimmer", "Dim1");
		check("not when a new device has taken the name", refused(reg));
		reg.closeAll();

		final var net2 = Subnet.inProcess();
		final var reg2 = new DeviceRegistry(dir, net2, new EventLog(100));
		reg2.restoreOrDefault(List.of());
		check("after a restart the removal is final", reg2.removedName() == null && !Files.exists(dir.resolve("trash")) && reg2.devices().size() == 2);
		reg2.closeAll();
		System.out.println("undo remove tests done");
		System.exit(SelfTest.failed > 0 ? 1 : 0);
	}
}
