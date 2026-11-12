package org.vknx;

import static org.vknx.SelfTest.check;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import io.calimero.IndividualAddress;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.DeviceRegistry;
import org.vknx.device.InstallationFile;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;

/**
 * Export and import of a whole installation. A device that ETS has downloaded to (address, application) is exported
 * from one registry and imported into another one in an empty data folder; files that are not what they claim to be
 * are refused. Own tests, NOT an ETS compatibility test.
 */
public final class InstallationFileTest {
	static byte[] zip(final Map<String, byte[]> files) throws Exception {
		final var out = new ByteArrayOutputStream();
		try (var z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (final var e : files.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue());
				z.closeEntry();
			}
		}
		return out.toByteArray();
	}

	static byte[] t(final String s) { return s.getBytes(StandardCharsets.UTF_8); }

	static String refused(final byte[] file) throws Exception {
		try {
			InstallationFile.read(file);
			return null;
		}
		catch (final IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	public static void main(final String[] args) throws Exception {
		final Path dirA = Files.createTempDirectory("vknx-export-a"), dirB = Files.createTempDirectory("vknx-export-b");
		final var addr = new IndividualAddress(2, 2, 15);
		final String weather = "Vejr Ü Привет";
		Subnet netA = Subnet.inProcess(), netB = Subnet.inProcess();
		DeviceRegistry a = new DeviceRegistry(dirA, netA, new EventLog(100)), b = null;
		try {
			a.restoreOrDefault(List.of());
			a.add(Products.WEATHER_STATION.key(), weather);
			final var wd = a.devices().stream().filter(d -> d.name().equals(weather)).findFirst().orElseThrow();
			final var link = netA.createDeviceLink(new IndividualAddress(0, 2, 255));
			final var ets = new EtsLikeClient(link);
			wd.toggleProgrammingMode();
			check("address assigned to the device", ets.assignAddress(addr));
			Thread.sleep(300);
			ets.downloadAll(addr, new int[] { 0x0B01 }, new int[][] { { 1, 4 } }, new int[5], Products.WEATHER_STATION.params("WindSpeedMode", 1));
			link.close();
			check("the device is programmed", wd.applicationLoaded() && wd.address().equals(addr));
			Files.write(dirA.resolve("plan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 });
			Files.writeString(dirA.resolve("plan-layout.json"), "{\"items\":[{\"dev\":\"" + weather + "\",\"x\":10,\"y\":20}]}");
			Files.writeString(dirA.resolve("icons.json"), "{\"icons\":{\"Lampe1\":\"lamp\"}}");
			// the device was never closed. Since 0.13.2 the state file is written a few seconds after a change, so it may or
			// may not exist here; the export must write the current state itself either way (checked after the import).
			Files.write(dirA.resolve("plan-f1.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G', 9 });
			Files.write(dirA.resolve("plan-f9.png"), new byte[] { 1 }); // not a floor (0 to 7)

			final byte[] file = InstallationFile.export(a, "9.9.9");
			check("the running device is still alive after the export", wd.applicationLoaded() && wd.address().equals(addr));
			final var names = new java.util.ArrayList<String>();
			try (var z = new ZipInputStream(new java.io.ByteArrayInputStream(file), StandardCharsets.UTF_8)) {
				for (ZipEntry e; (e = z.getNextEntry()) != null;)
					names.add(e.getName());
			}
			check("the file holds the info, the device list, the state, plan, layout and icons: " + names,
					names.containsAll(List.of("installation.properties", "devices.txt", DeviceRegistry.fileStem(weather) + ".state", "plan.png", "plan-layout.json", "icons.json")));
			check("the file is not larger than needed (state compresses well): " + file.length, file.length < 400_000);

			final var c = InstallationFile.read(file);
			check("summary: version, devices, plan, layout, icons", c.summary().vknx().equals("9.9.9") && c.summary().devices().size() == 3
					&& c.summary().plan() && c.summary().layout() && c.summary().icons() && c.summary().skipped().isEmpty());
			check("summary: the weather station has its download", c.summary().devices().stream().anyMatch(d -> d.name().equals(weather) && d.hasState()));

			// import into another installation that has its own devices
			b = new DeviceRegistry(dirB, netB, new EventLog(100));
			b.restoreOrDefault(List.of());
			b.add("switch-actuator", "Gammel lampe");
			Files.writeString(dirB.resolve("plan-layout.json"), "{\"items\":[{\"dev\":\"Gammel lampe\"}]}");
			b.importInstallation(InstallationFile.read(file));
			check("after the import the old devices are gone", b.devices().stream().noneMatch(d -> d.name().equals("Gammel lampe")));
			check("after the import the devices of the file exist", b.devices().stream().map(d -> d.name()).toList().containsAll(List.of(weather, "Tryk1", "Lampe1")) && b.devices().size() == 3);
			final var wb = b.devices().stream().filter(d -> d.name().equals(weather)).findFirst().orElse(null);
			check("the imported device has its address and its download", wb != null && wb.address().equals(addr) && wb.applicationLoaded());
			check("the image of a second floor comes along, a file that only looks like one does not", Files.exists(dirB.resolve("plan-f1.png")) && !Files.exists(dirB.resolve("plan-f9.png")));
			check("plan, layout and icons are in place", Files.exists(dirB.resolve("plan.png")) && Files.readString(dirB.resolve("plan-layout.json")).contains(weather)
					&& Files.readString(dirB.resolve("icons.json")).contains("Lampe1"));
			try (var s = Files.list(dirB)) {
				check("the old device's files are gone", s.noneMatch(p -> p.getFileName().toString().startsWith("Gammel")));
			}
			check("devices.txt lists the imported devices", Files.readAllLines(dirB.resolve("devices.txt")).stream().anyMatch(l -> l.endsWith(":" + weather)));
			b.closeAll();
			// and the import survives a restart of vKNX
			netB.close();
			netB = Subnet.inProcess();
			b = new DeviceRegistry(dirB, netB, new EventLog(100));
			b.restoreOrDefault(List.of());
			check("after a restart the imported installation is still there", b.devices().stream().anyMatch(d -> d.name().equals(weather) && d.address().equals(addr) && d.applicationLoaded()));

			// an installation without any device and without a plan
			final byte[] oneEmpty = zip(Map.of("installation.properties", t("format=1\nschema=4\nvknx=1.0\n"), "devices.txt", t("")));
			final var ce = InstallationFile.read(oneEmpty);
			check("a file without devices is valid", ce.summary().devices().isEmpty() && !ce.summary().plan());
			b.importInstallation(ce);
			check("importing it empties the installation (and no default devices appear)", b.devices().isEmpty());
			b.closeAll();
		}
		catch (final Throwable ex) {
			ex.printStackTrace(System.out);
			SelfTest.failed++;
		}
		try { a.closeAll(); } catch (final RuntimeException ignore) { }
		try { if (b != null) b.closeAll(); } catch (final RuntimeException ignore) { }

		// files that must be refused, with a reason in words
		try {
			final String ok = "format=1\nschema=4\nvknx=1.0\n";
			check("garbage is refused", refused("this is not a zip".getBytes()) != null);
			check("an empty file is refused", refused(new byte[0]) != null);
			check("another zip is refused", refused(zip(Map.of("hello.txt", t("hi")))) != null);
			check("a newer file format is refused with that reason", String.valueOf(refused(zip(Map.of("installation.properties", t("format=2\nschema=4\n"), "devices.txt", t(""))))).contains("newer"));
			check("another device layout (schema) is refused", String.valueOf(refused(zip(Map.of("installation.properties", t("format=1\nschema=3\n"), "devices.txt", t(""))))).contains("different device layout"));
			check("a device name with a line break is refused", refused(zip(Map.of("installation.properties", t(ok), "devices.txt", t("push-button:a\u0001b\n")))) != null);
			check("the same name twice is refused", refused(zip(Map.of("installation.properties", t(ok), "devices.txt", t("push-button:Abc\nswitch-actuator:abc\n")))) != null);

			// unknown product: the device is skipped, the rest is fine
			final var cu = InstallationFile.read(zip(Map.of("installation.properties", t(ok), "devices.txt", t("hologram:Spøgelse\npush-button:Tryk1\n"))));
			check("an unknown product is skipped and reported", cu.summary().skipped().size() == 1 && cu.summary().devices().size() == 1
					&& new String(cu.files().get("devices.txt"), StandardCharsets.UTF_8).equals("push-button:Tryk1\n"));

			// hostile entries are ignored: nothing outside the known names is ever taken
			final var hostile = new java.util.LinkedHashMap<String, byte[]>();
			hostile.put("installation.properties", t(ok));
			hostile.put("devices.txt", t("push-button:Tryk1\n"));
			hostile.put("../evil.state", t("x"));
			hostile.put("..\\evil2.state", t("x"));
			hostile.put("/abs.state", t("x"));
			hostile.put("sub/dir.state", t("x"));
			hostile.put("settings.properties", t("x"));
			hostile.put("plan.exe", t("x"));
			hostile.put("Other.state", t("x"));
			final var ch = InstallationFile.read(zip(hostile));
			check("only the files that belong to the listed devices are taken: " + ch.files().keySet(), ch.files().keySet().equals(java.util.Set.of("devices.txt")));

			// a zip bomb: far more data than allowed once unpacked
			final var big = new byte[1 << 20];
			final var bomb = new java.util.LinkedHashMap<String, byte[]>();
			bomb.put("installation.properties", t(ok));
			bomb.put("devices.txt", t("push-button:Tryk1\n"));
			final var out = new ByteArrayOutputStream();
			try (var z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
				z.setLevel(1);
				for (final var e : bomb.entrySet()) {
					z.putNextEntry(new ZipEntry(e.getKey()));
					z.write(e.getValue());
					z.closeEntry();
				}
				for (int i = 0; i < 300; i++) { // 300 MB of zeros, a few hundred KB packed; every one is a name that is taken
					z.putNextEntry(new ZipEntry("x" + i + ".state"));
					z.write(big);
					z.closeEntry();
				}
			}
			check("a file that unpacks to more than the limit is refused: " + out.size() + " bytes packed", refused(out.toByteArray()) != null);
		}
		catch (final Throwable ex) {
			ex.printStackTrace(System.out);
			SelfTest.failed++;
		}
		netA.close();
		netB.close();
		System.out.println(SelfTest.failed == 0 ? "INSTALLATION FILE TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
