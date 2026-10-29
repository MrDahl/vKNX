package org.vknx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;
import org.vknx.ui.EventLog;
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
		boolean openBrowser = true;
		String friendly = "vKNX simulator";
		final List<String> specs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--dir" -> dir = Path.of(args[++i]);
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--web" -> web = Integer.parseInt(args[++i]);
				case "--no-browser" -> openBrowser = false;
				case "--name" -> friendly = args[++i];
				case "--device" -> specs.add(args[++i]);
				default -> {
					System.err.println("unknown option " + args[i]);
					return;
				}
			}
		}
		if (specs.isEmpty())
			specs.addAll(List.of("push-button:Tryk1", "switch-actuator:Lampe1"));

		Files.createDirectories(dir);
		final var log = new EventLog(1000);
		log.tee(dir.resolve("vknx.log"));
		final var net = Subnet.withServer(dir, friendly, port);
		net.monitor(s -> log.add("bus", "", s));

		final var devices = new ArrayList<VirtualDevice>();
		for (final String spec : specs) {
			final String[] p = spec.split(":", 2);
			final var product = Products.ALL.get(p[0]);
			if (product == null) {
				System.err.println("unknown product " + p[0] + ", available: " + Products.ALL.keySet());
				return;
			}
			final String name = p.length > 1 ? p[1] : p[0];
			devices.add(VirtualDevice.create(name, product, serialFor(name), dir.resolve(name + ".state"), net,
					(src, cat, text) -> log.add(cat, src.name(), text)));
		}

		final var ui = new WebUi(web, devices, log);
		ui.start();
		System.out.println();
		System.out.println("vKNX is running.");
		System.out.println("  KNXnet/IP interface : \"" + friendly + "\" on UDP/TCP port " + port
				+ " (visible in ETS under Connections; tunnel addresses 1.1.241-1.1.244)");
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
			devices.forEach(VirtualDevice::close);
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
