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
		// the real (long) release JSON of v0.3.0: the old regular expression overflowed the stack on it
		final String real = java.nio.file.Files.readString(java.nio.file.Path.of("core/test/data/release-v0.3.0.json"));
		final var same = Updater.parse(real, "v0.3.0");
		SelfTest.check("real release JSON: up to date on v0.3.0", same.latest().equals("v0.3.0") && !same.newer());
		SelfTest.check("real release JSON: long notes parsed", same.notes().length() > 500 && same.notes().contains("Multisensor rework"));
		SelfTest.check("real release JSON: installer asset found", same.asset().endsWith("vKNX-Setup-0.3.0.exe"));
		SelfTest.check("real release JSON: newer than v0.2.0", Updater.parse(real, "v0.2.0").newer());
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
		// ---- telegram decoding: only with a DPT known from the product data, never guessed from the length
		final var on = org.vknx.ui.TelegramDecoder.decode("0081", "GroupValueWrite", java.util.Set.of("DPST-1-1"));
		SelfTest.check("DPT 1.001 write 1 decodes to on", on.dpt().equals("1.001") && "on".equalsIgnoreCase(on.value()));
		final var scaled = org.vknx.ui.TelegramDecoder.decode("008040", "GroupValueWrite", java.util.Set.of("DPST-5-1"));
		SelfTest.check("DPT 5.001 0x40 decodes to ~25 %", scaled.value() != null && scaled.value().startsWith("25"));
		final var temp = org.vknx.ui.TelegramDecoder.decode("00800c33", "GroupValueWrite", java.util.Set.of("DPST-9-1"));
		SelfTest.check("DPT 9.001 0x0C33 decodes to 21.5 \u00b0C without float noise", temp.value() != null && temp.value().startsWith("21.5"));
		SelfTest.check("no DPT known: no value (raw data only)", org.vknx.ui.TelegramDecoder.decode("0081", "GroupValueWrite", java.util.Set.of()).value() == null);
		SelfTest.check("devices disagree on the DPT: no value", org.vknx.ui.TelegramDecoder.decode("0081", "GroupValueWrite", java.util.Set.of("DPST-1-1", "DPST-5-1")).value() == null);
		SelfTest.check("data length does not fit the DPT: no value", org.vknx.ui.TelegramDecoder.decode("00800c33", "GroupValueWrite", java.util.Set.of("DPST-1-1")).value() == null);
		SelfTest.check("group read has no value", org.vknx.ui.TelegramDecoder.decode("0000", "GroupValueRead", java.util.Set.of("DPST-1-1")).value() == null);
		SelfTest.check("tidy removes float noise", org.vknx.ui.TelegramDecoder.tidy("22.400000000000002 \u00b0C").equals("22.4 \u00b0C"));
		SelfTest.check("service names: group write/read/response, transport control",
				org.vknx.sim.FrameFormatter.service(new byte[] { 0, (byte) 0x81 }, true).equals("GroupValueWrite")
						&& org.vknx.sim.FrameFormatter.service(new byte[] { 0, 0 }, true).equals("GroupValueRead")
						&& org.vknx.sim.FrameFormatter.service(new byte[] { 0, 0x41 }, true).equals("GroupValueResponse")
						&& org.vknx.sim.FrameFormatter.service(new byte[] { (byte) 0x80 }).equals("T_Connect")
						&& org.vknx.sim.FrameFormatter.service(new byte[] { (byte) 0xC2 }).equals("T_ACK"));
		System.out.println(SelfTest.failed == 0 ? "APP TEST PASSED" : "APP TEST FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
