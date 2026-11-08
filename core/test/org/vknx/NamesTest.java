package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.nio.file.Path;

import org.vknx.device.DeviceRegistry;
import org.vknx.product.ParamDef;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

/** Free device names (spaces, other alphabets, special characters) and text parameters in other scripts. Own tests, not ETS. */
public final class NamesTest {
	public static void main(final String[] args) throws Exception {
		final String[] good = { "Lampe1", "Stue lys", "Køkken – spot", "Лампа 1", "Ванная комната", "客厅灯", "Ünïcödé", "a'b\"c<d>&e", "50% #1 (nord)", "x@y", "😀 smiley" };
		for (final String n : good)
			check("name accepted: " + n, DeviceRegistry.checkName(n) == null);
		final String[] bad = { "", " leading", "trailing ", "line\nbreak", "tab\there", "x".repeat(41), "name@1.2!" };
		for (final String n : bad)
			check("name refused: " + n.replace("\n", "\\n").replace("\t", "\\t"), DeviceRegistry.checkName(n) != null);
		check("a name of exactly 40 characters is fine", DeviceRegistry.checkName("ж".repeat(40)) == null);
		check("plain names keep their file name (older installations)", DeviceRegistry.fileStem("Lampe-1_a").equals("Lampe-1_a"));
		final var stems = new java.util.HashSet<String>();
		for (final String n : good)
			stems.add(DeviceRegistry.fileStem(n));
		check("every name gets its own file name", stems.size() == good.length);
		check("file names are plain ASCII", stems.stream().allMatch(s -> s.matches("[A-Za-z0-9_~-]+")));
		check("the same name gives the same file name", DeviceRegistry.fileStem("Лампа 1").equals(DeviceRegistry.fileStem("Лампа 1")));

		// registry round trip: add, restart (devices.txt), remove
		final Path dir = Files.createTempDirectory("vknx-names");
		var reg = new DeviceRegistry(dir, Subnet.inProcess(), new EventLog(100));
		reg.restoreOrDefault(java.util.List.of());
		reg.add("switch-actuator", "Лампа 1 å", null);
		reg.add("push-button", "Stue @ lys", null);
		final var line = new org.vknx.sim.Topology.Line(2, 3);
		reg.add("push-button", "Gang 2", line);
		check("a device with a Cyrillic name and spaces exists", reg.devices().stream().anyMatch(d -> d.name().equals("Лампа 1 å")));
		boolean dup = false;
		try { reg.add("switch-actuator", "лампа 1 Å", null); } catch (final IllegalArgumentException e) { dup = true; }
		check("the same name in another case is refused", dup);
		check("state file named from the checksum name", Files.exists(dir.resolve(DeviceRegistry.fileStem("Лампа 1 å") + ".state")));
		reg.closeAll();
		reg = new DeviceRegistry(dir, Subnet.inProcess(), new EventLog(100));
		reg.restoreOrDefault(java.util.List.of());
		check("after a restart the names are the same", reg.devices().stream().map(d -> d.name()).toList().equals(java.util.List.of("Лампа 1 å", "Stue @ lys", "Gang 2")));
		check("a hand-wired line is still remembered (name with an @ in it is not confused)", reg.devices().get(2).location() != null
				&& reg.devices().get(1).location() == null);
		reg.remove("Лампа 1 å");
		try (var s = Files.list(dir)) {
			check("removing deletes the state files", s.noneMatch(p -> p.getFileName().toString().startsWith(DeviceRegistry.fileStem("Лампа 1 å"))));
		}
		reg.closeAll();

		// text parameters (ETS names of channels): Western letters as one byte, other scripts as UTF-8
		final var p = ParamDef.text(1, "Ch1Name", "Name", 0, 12, "x");
		final byte[] area = new byte[12];
		for (final String t : new String[] { "Køkken", "Æblegrød å", "Лампа 1", "Ванная", "Wohnzimmer Ü", "客厅灯" }) {
			p.writeText(area, t);
			check("text parameter round trip: " + t, p.readText(area).equals(t));
		}
		final var p7 = ParamDef.text(2, "Ch2Name", "Name", 0, 7, "x");
		final byte[] cut = new byte[7];
		p7.writeText(cut, "Ванная комната"); // longer than the field: the last character is cut in half
		check("a character cut in half at the end of the field is dropped", p7.readText(cut).equals("Ван"));
		System.out.println(SelfTest.failed == 0 ? "NAMES TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
