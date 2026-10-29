package org.vknx.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.vknx.device.DeviceRegistry;
import org.vknx.product.Products;
import org.vknx.device.VirtualDevice;
import org.vknx.product.ParamDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Local control panel (HTTP on 127.0.0.1 only). The page polls /api/state and /api/log; actions are plain POSTs.
 * No external libraries: the JDK's built-in HTTP server is enough for a classroom-local UI.
 */
public final class WebUi {
	private final HttpServer server;
	private final DeviceRegistry registry;
	private final List<VirtualDevice> devices;
	private final EventLog log;
	private org.vknx.app.Settings settings;
	private boolean supervised;
	private String iface = "";

	public WebUi(final int port, final DeviceRegistry registry, final EventLog log) throws IOException {
		this.registry = registry;
		this.devices = registry.devices();
		this.log = log;
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/", this::page);
		server.createContext("/api/state", this::state);
		server.createContext("/api/log", this::logs);
		server.createContext("/api/prog", ex -> action(ex, d -> d.toggleProgrammingMode()));
		server.createContext("/api/act", ex -> {
			final String a = param(ex.getRequestURI(), "a", "");
			final int v = Integer.parseInt(param(ex.getRequestURI(), "v", "0"));
			action(ex, d -> d.behavior().act(a, v));
		});
		server.createContext("/api/press", ex -> action(ex, d -> d.behavior().act("press", 0)));
		server.createContext("/api/products", this::products);
		server.createContext("/api/plan", this::plan);
		server.createContext("/api/settings", this::settingsEndpoint);
		server.createContext("/api/restart", this::restartEndpoint);
		server.createContext("/api/shutdown", ex -> {
			if (!supervised || !"POST".equals(ex.getRequestMethod())) {
				send(ex, 409, "text/plain", utf8("only for the launcher"));
				return;
			}
			send(ex, 200, "text/plain", utf8("ok"));
			new Thread(() -> System.exit(0)).start(); // shutdown hook saves the devices
		});
		server.createContext("/api/update/check", this::updateCheck);
		server.createContext("/api/update/install", this::updateInstall);
		server.createContext("/api/update/status", this::updateStatus);
		server.createContext("/api/layout", this::layout);
		server.createContext("/api/add", this::add);
		server.createContext("/api/remove", ex -> {
			final String n = param(ex.getRequestURI(), "dev", "");
			try {
				registry.remove(n);
				send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
			}
			catch (final Exception e) {
				send(ex, 500, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
			}
		});
	}

	private static final java.util.Map<String, String> PLAN_TYPES = java.util.Map.of("png", "image/png", "jpg", "image/jpeg",
			"jpeg", "image/jpeg", "svg", "image/svg+xml", "webp", "image/webp", "gif", "image/gif");
	private static final int PLAN_MAX = 12 * 1024 * 1024;

	private java.nio.file.Path planFile() throws IOException {
		try (var s = java.nio.file.Files.list(registry.dir())) {
			return s.filter(f -> f.getFileName().toString().startsWith("plan.")
					&& PLAN_TYPES.containsKey(f.getFileName().toString().substring(5))).findFirst().orElse(null);
		}
	}

	/** Floor plan image: GET returns it, POST ?ext=png stores it (replacing the old one), POST ?remove=1 deletes it. */
	private void plan(final HttpExchange ex) throws IOException {
		try {
			if ("GET".equals(ex.getRequestMethod())) {
				final var f = planFile();
				if (f == null) {
					send(ex, 404, "text/plain", "no plan".getBytes(StandardCharsets.UTF_8));
					return;
				}
				final String ext = f.getFileName().toString().substring(5);
				send(ex, 200, PLAN_TYPES.get(ext), java.nio.file.Files.readAllBytes(f));
				return;
			}
			if (!"POST".equals(ex.getRequestMethod())) {
				send(ex, 405, "text/plain", "GET or POST".getBytes(StandardCharsets.UTF_8));
				return;
			}
			final var old = planFile();
			if ("1".equals(param(ex.getRequestURI(), "remove", ""))) {
				if (old != null)
					java.nio.file.Files.delete(old);
				send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
				return;
			}
			final String ext = param(ex.getRequestURI(), "ext", "").toLowerCase();
			if (!PLAN_TYPES.containsKey(ext)) {
				send(ex, 400, "text/plain", "unsupported image type".getBytes(StandardCharsets.UTF_8));
				return;
			}
			final byte[] body = ex.getRequestBody().readNBytes(PLAN_MAX + 1);
			if (body.length > PLAN_MAX || body.length == 0) {
				send(ex, 400, "text/plain", "image missing or larger than 12 MB".getBytes(StandardCharsets.UTF_8));
				return;
			}
			if (old != null)
				java.nio.file.Files.delete(old);
			java.nio.file.Files.write(registry.dir().resolve("plan." + ext), body);
			send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
		}
		catch (final IOException e) {
			send(ex, 500, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
		}
	}

	/** Placement of inputs/outputs on the floor plan, stored as opaque JSON text in plan-layout.json. */
	private void layout(final HttpExchange ex) throws IOException {
		final var f = registry.dir().resolve("plan-layout.json");
		if ("POST".equals(ex.getRequestMethod())) {
			final byte[] body = ex.getRequestBody().readNBytes(1_000_001);
			if (body.length > 1_000_000) {
				send(ex, 400, "text/plain", "too large".getBytes(StandardCharsets.UTF_8));
				return;
			}
			java.nio.file.Files.write(f, body);
			send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
		}
		else
			send(ex, 200, "application/json; charset=utf-8",
					java.nio.file.Files.exists(f) ? java.nio.file.Files.readAllBytes(f) : "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
	}

	/** Settings of this instance, whether a launcher supervises the process (restart/update possible) and the shown interface address. */
	public void configure(final org.vknx.app.Settings settings, final boolean supervised, final String iface) {
		this.settings = settings;
		this.supervised = supervised;
		this.iface = iface;
	}

	private static byte[] utf8(final String s) { return s.getBytes(StandardCharsets.UTF_8); }

	private void settingsEndpoint(final HttpExchange ex) throws IOException {
		if (!"POST".equals(ex.getRequestMethod())) {
			send(ex, 405, "text/plain", utf8("POST only"));
			return;
		}
		try {
			settings.setLine(param(ex.getRequestURI(), "line", ""));
			send(ex, 200, "text/plain", utf8("ok"));
		}
		catch (final Exception e) {
			send(ex, 400, "text/plain", utf8(String.valueOf(e.getMessage())));
		}
	}

	private void restartEndpoint(final HttpExchange ex) throws IOException {
		if (!supervised) {
			send(ex, 409, "text/plain", utf8("not started by the vKNX launcher: close and start the program again"));
			return;
		}
		send(ex, 200, "text/plain", utf8("ok"));
		new Thread(() -> {
			try {
				Thread.sleep(300);
			}
			catch (final InterruptedException ignore) {}
			System.exit(75); // exit code 75 = "restart me" for the launcher
		}).start();
	}

	private void updateCheck(final HttpExchange ex) throws IOException {
		try {
			final var i = org.vknx.app.Updater.check();
			send(ex, 200, "application/json; charset=utf-8", utf8("{\"current\":" + q(i.current()) + ",\"latest\":" + q(i.latest())
					+ ",\"newer\":" + i.newer() + ",\"notes\":" + q(i.notes()) + ",\"page\":" + q(i.page()) + ",\"asset\":" + q(i.asset())
					+ ",\"canInstall\":" + (supervised && !i.asset().isEmpty()) + "}"));
		}
		catch (final Throwable e) { // also Errors: an uncaught one would drop the connection and show "Failed to fetch"
			send(ex, 502, "text/plain", utf8(String.valueOf(e.getMessage() != null ? e.getMessage() : e)));
		}
	}

	private void updateInstall(final HttpExchange ex) throws IOException {
		if (!supervised || !"POST".equals(ex.getRequestMethod())) {
			send(ex, 409, "text/plain", utf8("updates can only be installed from the installed vKNX program"));
			return;
		}
		try {
			org.vknx.app.Updater.downloadAndInstall(param(ex.getRequestURI(), "asset", ""), () -> System.exit(77));
			send(ex, 200, "text/plain", utf8("ok"));
		}
		catch (final Exception e) {
			send(ex, 400, "text/plain", utf8(String.valueOf(e.getMessage())));
		}
	}

	private void updateStatus(final HttpExchange ex) throws IOException {
		send(ex, 200, "application/json; charset=utf-8", utf8("{\"state\":" + q(org.vknx.app.Updater.state) + ",\"bytes\":"
				+ org.vknx.app.Updater.bytes + ",\"total\":" + org.vknx.app.Updater.total + ",\"error\":" + q(org.vknx.app.Updater.error) + "}"));
	}

	private void products(final HttpExchange ex) throws IOException {
		final String json = Products.ALL.values().stream().map(p -> "{\"key\":" + q(p.key()) + ",\"name\":" + q(p.name()) + "}").collect(Collectors.joining(",", "[", "]"));
		send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
	}

	private void add(final HttpExchange ex) throws IOException {
		if (!"POST".equals(ex.getRequestMethod())) {
			send(ex, 405, "text/plain", "POST only".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try {
			registry.add(param(ex.getRequestURI(), "product", ""), param(ex.getRequestURI(), "name", ""));
			send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
		}
		catch (final Exception e) {
			send(ex, 400, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
		}
	}

	public void start() { server.start(); }

	public void stop() { server.stop(0); }

	private void page(final HttpExchange ex) throws IOException {
		try (InputStream in = WebUi.class.getResourceAsStream("/web/index.html")) {
			final byte[] b = in == null ? "missing index.html".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
			send(ex, 200, "text/html; charset=utf-8", b);
		}
	}

	private void state(final HttpExchange ex) throws IOException {
		final var sb = new StringBuilder("{\"version\":" + q(org.vknx.app.Version.current()) + ",\"line\":" + q(settings == null ? "1.1" : settings.line())
				+ ",\"iface\":" + q(iface) + ",\"supervised\":" + supervised + ",\"devices\":[");
		boolean first = true;
		for (final VirtualDevice d : devices) {
			if (!first)
				sb.append(',');
			first = false;
			sb.append("{\"name\":").append(q(d.name())).append(",\"product\":").append(q(d.product().key()))
					.append(",\"productName\":").append(q(d.product().name())).append(",\"address\":").append(q(d.address().toString()))
					.append(",\"unassigned\":").append(d.address().getRawAddress() == 0xFFFF)
					.append(",\"programming\":").append(d.programmingMode()).append(",\"loaded\":")
					.append(d.applicationLoaded()).append(",\"downloads\":").append(d.downloadCount())
					.append(",\"params\":[");
			boolean fp = true;
			for (final ParamDef p : d.product().params()) {
				if (!fp)
					sb.append(',');
				fp = false;
				final int v = d.param(p.name());
				final var choice = p.choices().stream().filter(c -> c.value() == v).findFirst();
				final boolean visible = p.showWhenParam().isEmpty() || d.param(p.showWhenParam()) == p.showWhenValue();
				sb.append("{\"text\":").append(q(p.text())).append(",\"group\":").append(q(p.group()))
						.append(",\"visible\":").append(visible)
						.append(",\"value\":").append(q(choice.map(c -> c.text()).orElse(String.valueOf(v))))
						.append('}');
			}
			sb.append("],\"assoc\":[");
			boolean fa = true;
			for (final int[] a : d.associationView()) {
				if (!fa)
					sb.append(',');
				fa = false;
				final var o = d.product().object(a[1]);
				sb.append("{\"ga\":").append(q(new io.calimero.GroupAddress(a[0]).toString())).append(",\"obj\":").append(a[1])
						.append(",\"text\":").append(q(o.map(x -> x.text()).orElse("?"))).append(",\"size\":").append(o.map(x -> x.sizeBits()).orElse(1))
						.append(",\"flags\":").append(a[2]).append('}');
			}
			sb.append("],\"view\":{");
			sb.append(d.behavior().view().entrySet().stream().map(e -> q(e.getKey()) + ":" + val(e.getValue()))
					.collect(Collectors.joining(",")));
			sb.append("}}");
		}
		sb.append("]}");
		send(ex, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void logs(final HttpExchange ex) throws IOException {
		final long since = Long.parseLong(param(ex.getRequestURI(), "since", "0"));
		final var entries = log.since(since);
		final String json = entries.stream().map(e -> "{\"seq\":" + e.seq() + ",\"t\":" + q(e.time()) + ",\"cat\":"
				+ q(e.category()) + ",\"dev\":" + q(e.device()) + ",\"text\":" + q(e.text()) + "}")
				.collect(Collectors.joining(",", "{\"entries\":[", "]}"));
		send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
	}

	private void action(final HttpExchange ex, final java.util.function.Consumer<VirtualDevice> a) throws IOException {
		if (!"POST".equals(ex.getRequestMethod())) {
			send(ex, 405, "text/plain", "POST only".getBytes(StandardCharsets.UTF_8));
			return;
		}
		final String name = param(ex.getRequestURI(), "dev", "");
		final var dev = devices.stream().filter(d -> d.name().equals(name)).findFirst();
		if (dev.isEmpty()) {
			send(ex, 404, "text/plain", "no such device".getBytes(StandardCharsets.UTF_8));
			return;
		}
		a.accept(dev.get());
		send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
	}

	private static String param(final URI uri, final String key, final String def) {
		final String q = uri.getRawQuery();
		if (q == null)
			return def;
		for (final String kv : q.split("&")) {
			final String[] p = kv.split("=", 2);
			if (p[0].equals(key) && p.length == 2)
				return java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8);
		}
		return def;
	}

	private static void send(final HttpExchange ex, final int code, final String type, final byte[] body) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, body.length);
		try (var os = ex.getResponseBody()) {
			os.write(body);
		}
	}

	private static String val(final Object o) {
		return o instanceof Boolean || o instanceof Number ? String.valueOf(o) : q(String.valueOf(o));
	}

	static String q(final String s) {
		final var sb = new StringBuilder("\"");
		for (final char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20)
						sb.append(String.format("\\u%04x", (int) c));
					else
						sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

}
