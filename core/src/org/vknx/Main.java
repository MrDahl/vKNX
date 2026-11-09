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
	private static java.util.logging.Logger serverLog;

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
		org.vknx.app.DataFiles.rotate(dir.resolve("console.log"), org.vknx.ui.EventLog.MAX_FILE_BYTES);
		org.vknx.app.DataFiles.deleteBackups(dir, 1);
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
		settings.onLineRouting(net.topology()::setEnabled);
		net.tap((origin, f) -> {
			final boolean group = f.getDestination() instanceof io.calimero.GroupAddress;
			final String svc = FrameFormatter.service(f.getPayload(), group);
			final var tg = new EventLog.Telegram(origin, f.getSource().toString(), f.getDestination().toString(),
					group, svc, java.util.HexFormat.of().formatHex(f.getPayload()),
					f.getPriority().name(), f.getHopCount());
			log.add("bus", "", (origin.equals(Subnet.ORIGIN_IP) ? "ETS/IP -> bus  " : "device -> bus   ")
					+ org.vknx.sim.FrameFormatter.format(f), tg);
		});

		// what the KNXnet/IP server itself says about resets and refused writes (it only logs to the console): into vknx.log too
		serverLog = java.util.logging.Logger.getLogger("io.calimero.server"); // kept in a field: java.util.logging only holds loggers weakly
		serverLog.addHandler(new java.util.logging.Handler() {
			@Override public void publish(final java.util.logging.LogRecord r) {
				final String m = r.getMessage() == null ? "" : r.getMessage();
				if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
						|| m.contains("reset request") || m.contains("refuse write") || m.contains("restarting"))
					log.add("interface", "", "server: " + java.text.MessageFormat.format(m, r.getParameters() == null ? new Object[0] : r.getParameters()));
			}
			@Override public void flush() {}
			@Override public void close() {}
		});

		// ETS connections come and go without a trace in the telegram log: write them to the log (what a disconnect looked like)
		final var watcher = new Thread(() -> {
			final var known = new java.util.HashMap<String, Long>();
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(400);
				}
				catch (final InterruptedException e) {
					return;
				}
				final String repaired = net.keepLongFrames();
				if (repaired != null)
					log.add("interface", "", repaired + " (an ETS download that needs long frames would have failed with 'restricted to 15')");
				final var now = new java.util.HashMap<String, Subnet.Tunnel>();
				for (final var t : net.linkStatus().tunnels())
					now.put(t.address() + " " + t.type(), t);
				for (final var e : now.entrySet())
					if (known.putIfAbsent(e.getKey(), System.currentTimeMillis()) == null)
						log.add("connection", "", "KNXnet/IP connection opened: " + e.getKey() + " (" + now.size() + " open)");
				for (final var it = known.entrySet().iterator(); it.hasNext();) {
					final var e = it.next();
					if (!now.containsKey(e.getKey())) {
						log.add("connection", "", "KNXnet/IP connection closed: " + e.getKey() + " after "
								+ (System.currentTimeMillis() - e.getValue()) / 1000 + " s (" + now.size() + " open)");
						it.remove();
					}
				}
			}
		}, "vknx-connection-log");
		watcher.setDaemon(true);
		watcher.start();

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
