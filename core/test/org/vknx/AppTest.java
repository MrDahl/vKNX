package org.vknx;

import java.nio.file.Files;

import org.vknx.app.Settings;
import org.vknx.app.Updater;
import org.vknx.app.Version;
import org.vknx.sim.Subnet;

/** Version comparison, GitHub release parsing, settings and interface line configuration (no network, no ETS). */
public final class AppTest {
	public static void main(final String[] args) throws Exception {
		SelfTest.check("newer 0.3.0 > 0.2.9", Version.newer("v0.3.0", "0.2.9"));
		SelfTest.check("newer 0.10.0 > 0.9.0", Version.newer("v0.10.0", "v0.9.0"));
		SelfTest.check("same version is not newer", !Version.newer("v1.0.0", "1.0.0"));
		SelfTest.check("older is not newer", !Version.newer("v0.1.0", "v0.2.0"));
		SelfTest.check("any release is newer than dev", Version.newer("v0.1.0", "dev"));
		SelfTest.check("garbage tag is not newer than dev", !Version.newer("latest", "dev"));

		final String json = "{\"url\":\"x\",\"html_url\":\"https://github.com/MrDahl/vKNX/releases/tag/v0.3.0\",\"tag_name\":\"v0.3.0\","
				+ "\"body\":\"## New\\r\\n- Floor plan \\u00e9\\n- \\\"Update\\\"\","
				+ "\"assets\":[{\"browser_download_url\":\"https://github.com/MrDahl/vKNX/releases/download/v0.3.0/vKNX-0.3.0-windows.zip\"},"
				+ "{\"browser_download_url\":\"https://github.com/MrDahl/vKNX/releases/download/v0.3.0/vKNX-Setup-0.3.0.exe\"}]}";
		final var i = Updater.parse(json, "v0.2.0");
		SelfTest.check("parse tag", i.latest().equals("v0.3.0"));
		SelfTest.check("parse newer", i.newer());
		SelfTest.check("parse installer asset (not the zip)", i.asset().endsWith("vKNX-Setup-0.3.0.exe"));
		SelfTest.check("parse page", i.page().endsWith("/tag/v0.3.0"));
		SelfTest.check("parse notes unescaped", i.notes().contains("## New\n- Floor plan é\n- \"Update\""));
		SelfTest.check("current equals latest: not newer", !Updater.parse(json, "v0.3.0").newer());

		SelfTest.check("line 2.9 valid", Settings.valid("2.9"));
		SelfTest.check("line 15.15 valid", Settings.valid("15.15"));
		SelfTest.check("line 16.1 invalid", !Settings.valid("16.1"));
		SelfTest.check("line abc invalid", !Settings.valid("abc"));
		final var dir = Files.createTempDirectory("vknx-settings");
		final var s = new Settings(dir);
		SelfTest.check("default line 1.1", s.line().equals("1.1"));
		s.setLine("2.9");
		final var s2 = new Settings(dir);
		SelfTest.check("line persisted", s2.line().equals("2.9") && s2.area() == 2 && s2.lineNo() == 9);
		final String cfg = Subnet.serverConfig("vKNX simulator", 3671, 2, 9);
		SelfTest.check("server config uses 2.9.x addresses", cfg.contains(">2.9.0<") && cfg.contains(">2.9.241<") && cfg.contains("udpPort=\"3671\"")
				&& cfg.contains("friendlyName=\"vKNX simulator\"") && !cfg.contains("1.1."));
		System.out.println(SelfTest.failed == 0 ? "APP TEST PASSED" : "APP TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
