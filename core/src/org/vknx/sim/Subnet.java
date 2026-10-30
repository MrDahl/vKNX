package org.vknx.sim;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import io.calimero.IndividualAddress;
import io.calimero.cemi.CEMILData;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.NetworkLinkListener;
import io.calimero.FrameEvent;
import io.calimero.server.Launcher;

/**
 * One isolated virtual KNX line, optionally exposed to ETS through a KNXnet/IP tunneling server.
 * Several {@code Subnet}s (one per student) can run in one JVM only if they use different server ports;
 * the first version runs exactly one.
 */
public final class Subnet implements AutoCloseable {
	private final SharedVirtualLink line;
	private final Launcher server; // null for in-process tests
	private final Thread serverThread;

	private Subnet(final SharedVirtualLink line, final Launcher server, final Thread serverThread) {
		this.line = line;
		this.server = server;
		this.serverThread = serverThread;
	}

	/** A line without KNXnet/IP server, for tests and for the in-process "mini ETS". */
	public static Subnet inProcess() {
		SharedVirtualLink.reset();
		return new Subnet(new SharedVirtualLink(), null, null);
	}

	/**
	 * Starts the Calimero KNXnet/IP server (tunneling, discovery) bound to a fresh virtual line.
	 * ETS connects to this server exactly as it would to a real KNX IP interface.
	 *
	 * @param workDir directory for the generated server configuration
	 * @param friendlyName name shown in ETS' interface list (max. 30 ISO-8859-1 characters)
	 * @param port KNXnet/IP control port (3671 is the standard)
	 */
	public static Subnet withServer(final Path workDir, final String friendlyName, final int port) throws IOException,
			InterruptedException {
		return withServer(workDir, friendlyName, port, 1, 1);
	}

	/** @param area area of the interface's line (ETS shows tunnel addresses area.line.241 ...) @param lineNo line number */
	public static Subnet withServer(final Path workDir, final String friendlyName, final int port, final int area,
			final int lineNo) throws IOException, InterruptedException {
		System.setProperty("vknx.flatNetwork", "true"); // see third_party/patches: any area/line is reachable
		Files.createDirectories(workDir);
		final Path cfg = workDir.resolve("vknx-server.xml");
		Files.writeString(cfg, serverConfig(friendlyName, port, area, lineNo));
		SharedVirtualLink.reset();
		final var launcher = new Launcher(cfg.toUri().toString());
		final var t = new Thread(launcher, "vknx-knxnetip-server");
		t.setDaemon(true);
		t.start();
		final var line = SharedVirtualLink.awaitInstance(15_000);
		if (line == null)
			throw new IOException("KNXnet/IP server did not create the virtual line within 15 s");
		final var net = new Subnet(line, launcher, t);
		net.port = port;
		return net;
	}

	public static String serverConfig(final String friendlyName, final int port, final int area, final int line) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<knxServer name="vknx" friendlyName="%1$s">
				  <discovery listenNetIf="all" outgoingNetIf="all" activate="true" />
				  <serviceContainer activate="true" routing="false" networkMonitoring="false" udpPort="%2$d" netif="any">
				    <knxAddress type="individual">%3$d.%4$d.0</knxAddress>
				    <knxSubnet type="user" class="org.vknx.sim.SharedVirtualLink">vknx</knxSubnet>
				    <additionalAddresses>
				      <knxAddress type="individual">%3$d.%4$d.241</knxAddress>
				      <knxAddress type="individual">%3$d.%4$d.242</knxAddress>
				      <knxAddress type="individual">%3$d.%4$d.243</knxAddress>
				      <knxAddress type="individual">%3$d.%4$d.244</knxAddress>
				    </additionalAddresses>
				  </serviceContainer>
				</knxServer>
				""".formatted(friendlyName, port, area, line);
	}

	/** Attaches a new device (or an observer such as the mini ETS) to the line with the given address. */
	public KNXNetworkLink createDeviceLink(final IndividualAddress address) {
		return line.createDeviceLink(address);
	}

	/**
	 * Telegram monitor: reports every frame on the line, whether it came from a virtual device or via KNXnet/IP
	 * from ETS.
	 */
	public void monitor(final Consumer<String> sink) {
		tap((origin, f) -> sink.accept((origin.equals(ORIGIN_IP) ? "ETS/IP -> bus  " : "device -> bus   ") + FrameFormatter.format(f)));
	}

	/** Origin of a frame that entered the line through the KNXnet/IP server (ETS or another IP client). */
	public static final String ORIGIN_IP = "ip";
	/** Origin of a frame sent by a virtual device. */
	public static final String ORIGIN_DEVICE = "device";

	/** Structured variant of {@link #monitor}: the origin is {@link #ORIGIN_IP} or {@link #ORIGIN_DEVICE}. */
	public void tap(final java.util.function.BiConsumer<String, CEMILData> sink) {
		line.addInboundTap(f -> {
			lastIpFrame = System.currentTimeMillis();
			sink.accept(ORIGIN_IP, f);
		});
		line.addLinkListener(new NetworkLinkListener() {
			@Override
			public void indication(final FrameEvent e) {
				if (e.getFrame() instanceof final CEMILData d)
					sink.accept(ORIGIN_DEVICE, d);
			}

			@Override
			public void linkClosed(final io.calimero.CloseEvent e) {}
		});
	}

	private volatile long lastIpFrame;
	private int port;

	/** One KNXnet/IP data connection as reported by the server. */
	public record Tunnel(String address, long sinceMillis, String type) {}

	/** What the server can honestly tell about its state; {@code serverState} is "none" without a server. */
	public record LinkStatus(String serverState, int port, java.util.List<Tunnel> tunnels, long lastIpFrameMillis) {}

	public LinkStatus linkStatus() {
		if (server == null)
			return new LinkStatus("none", 0, java.util.List.of(), lastIpFrame);
		final var tunnels = new java.util.ArrayList<Tunnel>();
		String state = "unknown";
		try {
			final var srv = server.getGateway().getServer();
			state = srv.state().name();
			for (final var sc : srv.getServiceContainers())
				for (final var c : srv.dataConnections(sc).values())
					tunnels.add(new Tunnel(String.valueOf(c.deviceAddress()),
							c.connectedSince() == null ? 0 : c.connectedSince().toEpochMilli(), c.type().name()));
		}
		catch (final RuntimeException e) { /* server shutting down: report what we have */ }
		return new LinkStatus(state, port, tunnels, lastIpFrame);
	}

	public URI serverConfigHint() { return null; }

	@Override
	public void close() {
		if (server != null)
			server.close();
		line.close();
	}
}
