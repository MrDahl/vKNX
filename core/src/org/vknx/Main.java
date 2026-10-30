package org.vknx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.FrameFormatter;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;
import org.vknx.device.DeviceRegistry;
import org.vknx.ui.WebUi;

/**
 * vKNX launcher: starts the KNXnet/IP server, the virtual devices and the local web UI.
 *
 * <pre>
 * java ... org.vknx.Main [--dir runtime] [--port 3671] [--web 8080] [--name "vKNX"]
 *                         [--device push-button:Tryk1] [--device switch-actuator:Lampe1] ...
 * </pre>
 */
public final class Main {
	public static void main(final String[] args) throws Exception {
		Path dir = Path.of("runtime");
		int port = 3671, web = 8080;
		boolean openBrowser = true, supervised = false, consoleLog = false;
		String lineOverride = null;
		String friendly = "vKNX simulator";
		final List<String> specs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--dir" -> dir = Path.of(args[++i]);
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--web" -> web = Integer.parseInt(args[++i]);
				case "--no-browser" -> openBrowser = false;
				case "--supervised" -> supervised = true; // started by the vKNX launcher: restart and updates are possible
				case "--console-log" -> consoleLog = true; // write console output to <dir>/console.log (GUI launcher has no console)
				case "--line" -> lineOverride = args[++i];
				case "--name" -> friendly = args[++i];
				case "--device" -> specs.add(args[++i]);
				default -> {
					System.err.println("unknown option " + args[i]);
					return;
				}
			}
		}

		Files.createDirectories(dir);
		if (consoleLog) {
			final var ps = new java.io.PrintStream(new java.io.FileOutputStream(dir.resolve("console.log").toFile(), true), true,
					java.nio.charset.StandardCharsets.UTF_8);
			System.setOut(ps);
			System.setErr(ps);
		}
		final var settings = new org.vknx.app.Settings(dir);
		if (lineOverride != null)
			settings.setLine(lineOverride);
		System.out.println("vKNX " + org.vknx.app.Version.current());
		final var log = new EventLog(3000);
		log.tee(dir.resolve("vknx.log"));
		final var net = Subnet.withServer(dir, friendly, port, settings.area(), settings.lineNo());
		net.tap((origin, f) -> {
			final String svc = FrameFormatter.service(f.getPayload());
			final var tg = new EventLog.Telegram(origin, f.getSource().toString(), f.getDestination().toString(),
					f.getDestination() instanceof io.calimero.GroupAddress, svc, java.util.HexFormat.of().formatHex(f.getPayload()),
					f.getPriority().name(), f.getHopCount());
			log.add("bus", "", (origin.equals(Subnet.ORIGIN_IP) ? "ETS/IP -> bus  " : "device -> bus   ")
					+ org.vknx.sim.FrameFormatter.format(f), tg);
		});

		final var registry = new DeviceRegistry(dir, net, log);
		for (final String spec : specs)
			if (!Products.ALL.containsKey(spec.split(":", 2)[0])) {
				System.err.println("unknown product in " + spec + ", available: " + Products.ALL.keySet());
				return;
			}
		registry.restoreOrDefault(specs);
		final var devices = registry.devices();

		final var ui = new WebUi(web, registry, log);
		ui.configure(settings, supervised, settings.line() + ".241");
		ui.setNet(net);
		ui.start();
		System.out.println();
		System.out.println("vKNX is running.");
		System.out.println("  KNXnet/IP interface : \"" + friendly + "\" on UDP/TCP port " + port
				+ " (visible in ETS under Connections; tunnel addresses " + settings.line() + ".241-244)");
		System.out.println("  Control panel       : http://localhost:" + web + "/");
		System.out.println("  Devices             : " + devices);
		System.out.println("  Local addresses     : " + localAddresses());
		if (openBrowser) {
			try {
				if (java.awt.Desktop.isDesktopSupported())
					java.awt.Desktop.getDesktop().browse(java.net.URI.create("http://localhost:" + web + "/"));
			}
			catch (final Exception | java.lang.Error e) { /* headless: just use the printed URL */ }
		}
		System.out.println("Press Ctrl+C to stop (device configuration is saved on exit).");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			registry.closeAll();
			net.close();
		}));
		Thread.currentThread().join();
	}

	static byte[] serialFor(final String name) {
		final int h = name.hashCode();
		return new byte[] { 0x00, (byte) 0xFA, (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h };
	}

	static String localAddresses() throws Exception {
		final var l = new ArrayList<String>();
		java.net.NetworkInterface.networkInterfaces().forEach(n -> {
			try {
				if (n.isUp() && !n.isLoopback())
					n.inetAddresses().filter(a -> a instanceof java.net.Inet4Address).forEach(a -> l.add(a.getHostAddress()
							+ " (" + n.getDisplayName() + ")"));
			}
			catch (final Exception ignore) {}
		});
		return String.join(", ", l);
	}
}
