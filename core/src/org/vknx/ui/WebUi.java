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

	private void products(final HttpExchange ex) throws IOException {
		final String json = Products.ALL.values().stream().map(p -> "{\"key\":" + q(p.key()) + ",\"da\":" + q(p.nameDa())
				+ ",\"en\":" + q(p.nameEn()) + "}").collect(Collectors.joining(",", "[", "]"));
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
		final var sb = new StringBuilder("{\"devices\":[");
		boolean first = true;
		for (final VirtualDevice d : devices) {
			if (!first)
				sb.append(',');
			first = false;
			sb.append("{\"name\":").append(q(d.name())).append(",\"product\":").append(q(d.product().key()))
					.append(",\"productDa\":").append(q(d.product().nameDa())).append(",\"productEn\":")
					.append(q(d.product().nameEn())).append(",\"address\":").append(q(d.address().toString()))
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
				sb.append("{\"da\":").append(q(p.textDa())).append(",\"en\":").append(q(p.textEn()))
						.append(",\"valueDa\":").append(q(choice.map(c -> c.textDa()).orElse(String.valueOf(v))))
						.append(",\"valueEn\":").append(q(choice.map(c -> c.textEn()).orElse(String.valueOf(v))))
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
						.append(",\"da\":").append(q(o.map(x -> x.textDa()).orElse("?"))).append(",\"en\":")
						.append(q(o.map(x -> x.textEn()).orElse("?"))).append(",\"size\":").append(o.map(x -> x.sizeBits()).orElse(1))
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
