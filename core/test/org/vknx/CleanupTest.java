package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.vknx.app.DataFiles;
import org.vknx.device.DeviceRegistry;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

import io.calimero.IndividualAddress;

/** "Data and cleanup": what is counted, what is deleted, log rotation and the new-installation reset. No ETS involved. */
public final class CleanupTest {
	static long size(final Path f) throws Exception { return Files.isRegularFile(f) ? Files.size(f) : -1; }

	public static void main(final String[] args) throws Exception {
		final Path dir = Files.createTempDirectory("vknx-cleanup");
		// ---- file groups
		for (final String ts : new String[] { "20261101-182930-026", "20261101-223720-015", "20261102-131001-818" })
			Files.write(dir.resolve("Tryk1_" + ts + "_EC7.state"), new byte[100]);
		Files.write(dir.resolve("Lampe1_20261101-182950-494_EC7.state"), new byte[100]);
		Files.write(dir.resolve("Tryk1.state"), new byte[500]);
		Files.write(dir.resolve("plan.jpg"), new byte[1000]);
		Files.write(dir.resolve("plan-layout.json"), new byte[20]);
		Files.write(dir.resolve("vknx.log"), new byte[3000]);
		Files.write(dir.resolve("vknx.log.1"), new byte[2000]);
		Files.write(dir.resolve("console.log"), new byte[400]);
		Files.write(dir.resolve("devices.txt"), "push-button:Tryk1\n".getBytes());
		final var items = DataFiles.inspect(dir).stream().collect(java.util.stream.Collectors.toMap(DataFiles.Item::id, i -> i));
		check("logs counted: vknx.log, vknx.log.1, console.log", items.get("logs").files() == 3 && items.get("logs").bytes() == 5400);
		check("backups counted, the live state file is not one", items.get("backups").files() == 4 && items.get("state").files() == 1);
		check("plan image and layout counted together", items.get("plan").files() == 2 && items.get("plan").bytes() == 1020);

		check("keep newest backup per device: 2 of 3 Tryk1 backups deleted", DataFiles.deleteBackups(dir, 1) == 200
				&& Files.exists(dir.resolve("Tryk1_20261102-131001-818_EC7.state"))
				&& !Files.exists(dir.resolve("Tryk1_20261101-182930-026_EC7.state"))
				&& Files.exists(dir.resolve("Lampe1_20261101-182950-494_EC7.state")) && Files.exists(dir.resolve("Tryk1.state")));
		DataFiles.deleteBackupsOf(dir, "Lampe1");
		check("removing a device deletes only its own backups", !Files.exists(dir.resolve("Lampe1_20261101-182950-494_EC7.state"))
				&& Files.exists(dir.resolve("Tryk1_20261102-131001-818_EC7.state")));
		check("clearing logs empties the open logs and deletes the rotated copy", DataFiles.clearLogs(dir) == 5400
				&& size(dir.resolve("vknx.log")) == 0 && size(dir.resolve("console.log")) == 0 && size(dir.resolve("vknx.log.1")) == -1);
		check("clearing the plan deletes image and layout, nothing else", DataFiles.clearPlan(dir) == 1020
				&& !Files.exists(dir.resolve("plan.jpg")) && Files.exists(dir.resolve("devices.txt")) && Files.exists(dir.resolve("Tryk1.state")));

		// ---- log rotation
		final Path big = dir.resolve("big.log");
		try (var ch = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
			ch.position(EventLog.MAX_FILE_BYTES + 5);
			ch.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
		}
		final var log = new EventLog(10);
		log.tee(big);
		log.add("test", "x", "after the limit");
		check("a log past the limit is moved to .1 and a new one is started", Files.exists(dir.resolve("big.log.1"))
				&& size(big) > 0 && size(big) < 1000);
		log.clear();
		check("clear forgets what the monitor shows", log.since(0).isEmpty());

		// ---- new installation
		final var net = Subnet.inProcess();
		final var ets = new SelfTest.EtsLikeClient(net.createDeviceLink(new IndividualAddress(1, 1, 241)));
		final Path dir2 = Files.createTempDirectory("vknx-cleanup2");
		final var reg = new DeviceRegistry(dir2, net, new EventLog(100));
		reg.restoreOrDefault(List.of("push-button:Tryk1", "secure-switch-actuator:SecSwitch1@2.2!"));
		final var pb = reg.devices().get(0);
		final var sw = reg.devices().get(1);
		final String fdskBefore = sw.fdskHex();
		pb.toggleProgrammingMode();
		check("setup: Tryk1 gets 1.1.10", ets.assignAddress(new IndividualAddress(1, 1, 10)) && CouplerTest.until(3000, () -> reg.devices().get(0).address().getDevice() == 10));
		reg.resetAll();
		final var pb2 = reg.devices().get(0);
		final var sw2 = reg.devices().get(1);
		check("new installation: same devices in the same order", reg.devices().size() == 2 && pb2.name().equals("Tryk1")
				&& sw2.name().equals("SecSwitch1") && sw2.product().secure());
		check("... in factory state (address 15.15.255 again)", pb2.address().getRawAddress() == 0xFFFF);
		check("... the line the user wired the device to is kept", sw2.location() != null && sw2.location().toString().equals("2.2"));
		check("... a secure device gets a new factory key", !sw2.fdskHex().equals(fdskBefore) && sw2.fdskHex().matches("[0-9A-F]{32}"));
		check("... and devices.txt is intact", Files.readAllLines(dir2.resolve("devices.txt")).equals(List.of("push-button:Tryk1", "secure-switch-actuator:SecSwitch1@2.2!")));
		reg.closeAll();
		System.out.println(SelfTest.failed == 0 ? "CLEANUP TEST PASSED" : "CLEANUP TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
