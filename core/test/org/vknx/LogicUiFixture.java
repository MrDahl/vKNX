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
import org.vknx.product.Products;

/**
 * Test helper for UI screenshots of the logic module: acts like ETS against a RUNNING vKNX (KNXnet/IP port, web port as
 * arguments) and programs the device "Logic1" (inputs Window and Door, outputs Light and Alarm, two timers, time master),
 * saves an example circuit and switches the inputs. Not a compatibility test.
 */
public final class LogicUiFixture {
	public static void main(final String[] args) throws Exception {
		final int knxPort = Integer.parseInt(args[0]), web = Integer.parseInt(args[1]);
		final var http = HttpClient.newHttpClient();
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, knxPort), false,
				new TPSettings(new IndividualAddress(0)))) {
			final var ets = new SelfTest.EtsLikeClient(tunnel);
			http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web + "/api/prog?dev=Logic1")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
			if (!ets.assignAddress(new IndividualAddress(1, 1, 1)))
				throw new IllegalStateException("address assignment failed");
			final int g1 = UiFixture.ga(6, 0, 1), g2 = UiFixture.ga(6, 0, 2), g3 = UiFixture.ga(6, 0, 3), g4 = UiFixture.ga(6, 0, 4), g5 = UiFixture.ga(6, 0, 5);
			final int[] fl = new int[43];
			fl[0] = fl[1] = UiFixture.STATUS_IN;
			fl[16] = fl[17] = UiFixture.STATUS_OUT;
			fl[Products.timerObject(1) - 1] = UiFixture.STATUS_OUT;
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { g1, g2, g3, g4, g5 },
					new int[][] { { 1, 1 }, { 2, 2 }, { 3, 17 }, { 4, 18 }, { 5, Products.timerObject(1) } }, fl,
					Products.LOGIC.params("In1Enable", 1, "In1Name", "Window", "In2Enable", 1, "In2Name", "Door", "In3Enable", 1,
							"Out1Enable", 1, "Out1Name", "Light", "Out2Enable", 1, "Out2Name", "Alarm",
							"TimeObjects", 1, "TimeInterval", 5, "Tm1Enable", 1, "Tm1Name", "Garden", "Tm2Enable", 1, "Tm2Name", "Night"));
			final String circuit = "v1\nn n1 IN 20 20 1\nn n2 IN 20 100 2\nn n3 OR 200 40 2\nn n4 DELAY 360 40 2000\nn n5 OUT 520 40 1\n"
					+ "n n6 AND 200 140 2\nn n7 STAIRWAY 360 140 8000\nn n8 OUT 520 140 2\nn n9 IN 20 200 3\n"
					+ "w n1 n3 0\nw n2 n3 1\nw n3 n4 0\nw n4 n5 0\nw n1 n6 0\nw n2 n6 1\nw n6 n7 0\nw n7 n8 0\n";
			final var rsp = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web + "/api/logic?dev=Logic1")).POST(HttpRequest.BodyPublishers.ofString(circuit)).build(), HttpResponse.BodyHandlers.ofString());
			System.out.println("save circuit: " + rsp.statusCode() + " " + rsp.body());
			final var pc = new ProcessCommunicatorImpl(tunnel);
			pc.write(new GroupAddress(g1), true);
			Thread.sleep(300);
			System.out.println("fixture ready");
			if (args.length > 2) Thread.sleep(Long.parseLong(args[2]));
		}
	}
}
