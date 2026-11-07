package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import org.vknx.product.Products;

/**
 * Test helper for UI screenshots of the thermostat, heating actuator, binary input, weather station and blind actuator:
 * acts like ETS against a RUNNING vKNX (KNXnet/IP port, web port as arguments). Devices expected: Bin1 (binary-input),
 * Therm1 (thermostat), Heat1 (heating-actuator), Weather1 (weather-station), Blind1 (blind-actuator). Not a compatibility test.
 */
public final class NewUiFixture {
	static final int W = 0x04 | 0x10 | 0x03, SEND = 0x04 | 0x40 | 0x03, STATUS_OUT = 0x04 | 0x08 | 0x40 | 0x03;
	static final HttpClient HTTP = HttpClient.newHttpClient();
	static int web;

	static void post(final String path) throws Exception {
		HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.discarding());
	}

	static int ga(final int a, final int b, final int c) { return new GroupAddress(a, b, c).getRawAddress(); }

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	public static void main(final String[] args) throws Exception {
		final int knxPort = Integer.parseInt(args[0]);
		web = Integer.parseInt(args[1]);
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, knxPort), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			final String[] names = { "Bin1", "Therm1", "Heat1", "Weather1", "Blind1" };
			int h = 1;
			final var addr = new java.util.HashMap<String, IndividualAddress>();
			for (final String n : names) {
				post("/api/prog?dev=" + n);
				final var a = new IndividualAddress(1, 1, h++);
				if (!ets.assignAddress(a))
					throw new IllegalStateException("address assignment failed for " + n);
				addr.put(n, a);
			}
			final int gWindow = ga(1, 0, 1), gHeat = ga(1, 0, 2), gWind = ga(1, 0, 3), gRain = ga(1, 0, 4);
			ets.downloadAll(addr.get("Bin1"), new int[] { gWindow }, new int[][] { { 1, 1 } }, flags(16, 1, STATUS_OUT),
					Products.BINARY_INPUT.params("In1Name", "Window", "In2Name", "Door", "In3Function", 1, "In3Name", "Dim lamp", "In4Function", 2, "In4Name", "Awning"));
			ets.downloadAll(addr.get("Therm1"), new int[] { gWindow, gHeat }, new int[][] { { 1, 5 }, { 2, 7 } }, flags(8, 5, W, 7, STATUS_OUT),
					Products.THERMOSTAT.params("WindowUsed", 1, "RoomSpeed", 60, "StartTemp", 19, "ControlCycle", 5));
			ets.downloadAll(addr.get("Heat1"), new int[] { gHeat }, new int[][] { { 1, 1 } }, flags(19, 1, W),
					Products.HEATING_ACTUATOR.params("Ch1Name", "Living room", "Ch2Name", "Bedroom", "Ch2Type", 1, "Ch2ValveType", 1, "Ch3Name", "Kitchen",
							"Ch3Monitor", 5, "Ch4Enable", 0));
			ets.downloadAll(addr.get("Weather1"), new int[] { gWind, gRain }, new int[][] { { 1, 5 }, { 2, 4 } }, flags(5, 4, STATUS_OUT, 5, STATUS_OUT),
					Products.WEATHER_STATION.params("WindSpeedMode", 1, "WindSpeedMin", 0, "WindSpeedMax", 20, "WindSpeedInterval", 3,
							"RainMode", 2, "RainChance", 40, "RainInterval", 4, "AlarmInterval", 10));
			ets.downloadAll(addr.get("Blind1"), new int[] { gWind, gRain }, new int[][] { { 1, 13 }, { 2, 14 } }, flags(14, 13, W, 14, W),
					Products.BLIND_ACTUATOR.params("Ch1Name", "Awning", "Ch1UpTime", 8, "Ch1DownTime", 8, "Ch2Name", "Venetian", "Ch2RainReaction", 1, "WindMonitor", 5));
			System.out.println("fixture ready");
			if (args.length > 2) Thread.sleep(Long.parseLong(args[2]));
		}
	}
}
