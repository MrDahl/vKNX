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
import io.calimero.process.ProcessCommunicatorImpl;

/**
 * Test helper for UI screenshots: acts like ETS against a RUNNING vKNX (KNXnet/IP port, web port as arguments) and gives
 * every device an address and a download, then produces some bus traffic. Not a compatibility test.
 * Devices expected: Button1 Dimmer1 Switch1 Sensor1 Blind1 Slider1 (see tools/ui-test.sh).
 */
public final class UiFixture {
	static final int W = 0x04 | 0x10 | 0x03, SEND = 0x04 | 0x40 | 0x03, STATUS_OUT = 0x04 | 0x08 | 0x40 | 0x03, STATUS_IN = 0x04 | 0x10 | 0x80 | 0x03;
	static final HttpClient HTTP = HttpClient.newHttpClient();
	static int web;

	static void post(final String path) throws Exception {
		HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.discarding());
	}

	static int ga(final int a, final int b, final int c) { return new GroupAddress(a, b, c).getRawAddress(); }

	public static void main(final String[] args) throws Exception {
		final int knxPort = Integer.parseInt(args[0]);
		web = Integer.parseInt(args[1]);
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, knxPort), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			final String[] names = { "Button1", "Dimmer1", "Switch1", "Sensor1", "Blind1", "Slider1" };
			int h = 1;
			final var addr = new java.util.HashMap<String, IndividualAddress>();
			for (final String n : names) {
				post("/api/prog?dev=" + n);
				final var a = new IndividualAddress(1, 1, h++);
				if (!ets.assignAddress(a))
					throw new IllegalStateException("address assignment failed for " + n);
				addr.put(n, a);
			}
			final int gSw = ga(1, 0, 1), gDim = ga(1, 0, 2), gVal = ga(1, 0, 3), gSwSt = ga(1, 0, 4), gValSt = ga(1, 0, 5);
			ets.downloadAll(addr.get("Button1"), new int[] { gSw, gSwSt }, new int[][] { { 1, 1 }, { 2, 2 } }, new int[] { SEND, STATUS_IN }, org.vknx.product.Products.PUSH_BUTTON.params("Key2Name", "Hall", "Key3Name", "Stairs"));
			ets.downloadAll(addr.get("Dimmer1"), new int[] { gSw, gDim, gVal, gSwSt, gValSt }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 }, { 4, 4 }, { 5, 5 } },
					new int[] { W, W, W, STATUS_OUT, STATUS_OUT }, org.vknx.product.Products.DIMMER.params("Ch1OnLevel", 1, "Ch1DimTime", 0, "Ch2Name", "Desk"));
			ets.downloadAll(addr.get("Slider1"), new int[] { gVal, gSw, gValSt }, new int[][] { { 1, 2 }, { 2, 1 }, { 3, 3 } }, new int[] { SEND, SEND, STATUS_IN }, new byte[] { 0, 0, 0, 0 });
			final int gC1 = ga(2, 0, 1), gC2 = ga(2, 0, 2), gC3 = ga(2, 0, 3);
			ets.downloadAll(addr.get("Switch1"), new int[] { gC1, gC2, gC3 }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 } }, new int[] { W, W, W, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, org.vknx.product.Products.SWITCH_ACTUATOR.params("Out2Name", "Window", "Out6Enable", 0));
			final int gTemp = ga(4, 0, 4), gCo2 = ga(4, 0, 7), gLux = ga(4, 0, 5);
			final byte[] ms = org.vknx.product.Products.MULTISENSOR.params("CO2Interval", 3, "CO2Value", 600, "IlluminanceInterval", 4,
					"IlluminanceValue", 300, "TemperatureInterval", 2, "TemperatureValue", 224, "PressureEnable", 0);
			ets.downloadAll(addr.get("Sensor1"), new int[] { gTemp, gCo2, gLux }, new int[][] { { 1, 4 }, { 2, 1 }, { 3, 3 } }, new int[] { SEND, 0, 0, SEND, 0, 0 }, ms);
			final int gMove = ga(3, 0, 1), gStop = ga(3, 0, 2), gPos = ga(3, 0, 3);
			ets.downloadAll(addr.get("Blind1"), new int[] { gMove, gStop, gPos }, new int[][] { { 1, 1 }, { 2, 2 }, { 3, 3 } },
					new int[] { W, W, STATUS_OUT }, org.vknx.product.Products.BLIND_ACTUATOR.params("Ch1UpTime", 20, "Ch1DownTime", 20, "Ch3Enable", 0));
			final var pc = new ProcessCommunicatorImpl(tunnel);
			System.out.println("fixture ready");
			// some traffic: switch lamps, dim, group read of unknown address
			for (int i = 0; i < 4; i++) {
				pc.write(new GroupAddress(gSw), i % 2 == 0);
				Thread.sleep(400);
			}
			pc.write(new GroupAddress(gC1), true);
			pc.write(new GroupAddress(gC3), true);
			pc.write(new GroupAddress(gVal), 65, io.calimero.dptxlator.DPTXlator8BitUnsigned.DPT_SCALING.getID());
			pc.write(new GroupAddress(gMove), true); // blind 1 starts moving down
			pc.write(new GroupAddress(ga(7, 7, 7)), true); // address no device has configured
			Thread.sleep(500);
			System.out.println("fixture traffic done");
			if (args.length > 2) Thread.sleep(Long.parseLong(args[2]));
		}
	}
}
