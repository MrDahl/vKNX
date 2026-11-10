package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.vknx.device.DeviceRegistry;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

import io.calimero.IndividualAddress;

/** Renaming a device: address and state follow the new name, the plan layout and icons are updated, bad names are refused. No ETS needed. */
public final class RenameTest {
	static boolean refused(final DeviceRegistry reg, final String from, final String to) {
		try { reg.rename(from, to); return false; } catch (final IllegalArgumentException e) { return true; } catch (final Exception e) { return false; }
	}

	public static void main(final String[] args) throws Exception {
		final var net = Subnet.inProcess();
		final var ets = new SelfTest.EtsLikeClient(net.createDeviceLink(new IndividualAddress(1, 1, 241)));
		final Path dir = Files.createTempDirectory("vknx-rename");
		final var reg = new DeviceRegistry(dir, net, new EventLog(100));
		reg.restoreOrDefault(List.of("push-button:Tryk1", "switch-actuator:Lampe1"));
		reg.devices().get(0).toggleProgrammingMode();
		check("setup: Tryk1 gets 1.1.10", ets.assignAddress(new IndividualAddress(1, 1, 10)) && CouplerTest.until(3000, () -> reg.devices().get(0).address().getDevice() == 10));
		final String serial = reg.devices().get(0).serialHex();
		Files.writeString(dir.resolve("plan-layout.json"), "{\"items\":[{\"id\":\"Tryk1|Key 1\",\"x\":1,\"y\":2},{\"id\":\"Lampe1|Out 1\",\"x\":3,\"y\":4,\"label\":\"Tryk1|x\"}],\"zoom\":100}");
		Files.writeString(dir.resolve("icons.json"), "{\"v\":1,\"icons\":{\"Tryk1\":{\"n\":\"a\"},\"Tryk1|Key 1\":{\"n\":\"b\"},\"Lampe1\":{\"n\":\"c\"}}}");

		reg.rename("Tryk1", "Gang knap");
		final var d = reg.devices().get(0);
		check("the device keeps its place in the list and gets the new name", d.name().equals("Gang knap") && reg.devices().get(1).name().equals("Lampe1") && reg.devices().size() == 2);
		check("physical address and serial number are kept", d.address().equals(new IndividualAddress(1, 1, 10)) && d.serialHex().equals(serial));
		check("the state file follows the name", Files.exists(dir.resolve(DeviceRegistry.fileStem("Gang knap") + ".state")) && !Files.exists(dir.resolve("Tryk1.state")));
		check("devices.txt has the new name", Files.readString(dir.resolve("devices.txt")).contains("Gang knap") && !Files.readString(dir.resolve("devices.txt")).contains("Tryk1"));
		final String layout = Files.readString(dir.resolve("plan-layout.json"));
		check("plan layout ids are renamed, a label that only looks like an id is not", layout.contains("\"Gang knap|Key 1\"") && layout.contains("\"Lampe1|Out 1\"") && layout.contains("\"label\":\"Tryk1|x\"") && !layout.contains("\"Tryk1|Key 1\""));
		final String icons = Files.readString(dir.resolve("icons.json"));
		check("icon keys are renamed for the device and its channels", icons.contains("\"Gang knap\":") && icons.contains("\"Gang knap|Key 1\":") && !icons.contains("\"Tryk1") && icons.contains("\"Lampe1\":"));

		check("a name that is taken is refused (also in another letter case)", refused(reg, "Gang knap", "lampe1") && reg.devices().get(0).name().equals("Gang knap"));
		check("empty, padded and over-long names are refused", refused(reg, "Gang knap", "") && refused(reg, "Gang knap", " x") && refused(reg, "Gang knap", "x".repeat(41)) && refused(reg, "Gang knap", "a\nb"));
		check("renaming an unknown device is refused", refused(reg, "Nope", "Other"));
		reg.rename("Gang knap", "gang KNAP");
		check("changing only the letter case works", reg.devices().get(0).name().equals("gang KNAP") && reg.devices().get(0).address().getDevice() == 10);

		// the new name survives a restart, with the address from the state file
		reg.closeAll();
		final var reg2 = new DeviceRegistry(dir, net, new EventLog(100));
		reg2.restoreOrDefault(List.of());
		check("after a restart the renamed device is back with its address", reg2.devices().size() == 2 && reg2.devices().get(0).name().equals("gang KNAP")
				&& reg2.devices().get(0).address().getDevice() == 10 && reg2.devices().get(0).serialHex().equals(serial));
		reg2.rename("gang KNAP", "Æblegrød 1");
		check("a name with non-ASCII letters works", reg2.devices().get(0).name().equals("Æblegrød 1") && reg2.devices().get(0).address().getDevice() == 10);
		reg2.closeAll();
		System.out.println("RenameTest done");
	}
}
