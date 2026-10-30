package org.vknx;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;

/** Test helper: keeps a KNXnet/IP tunnel to a running vKNX open for N seconds (to check the connection status in the UI). */
public final class TunnelHold {
	public static void main(final String[] args) throws Exception {
		final InetAddress host = java.net.NetworkInterface.networkInterfaces().filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (Exception e) { return false; } })
				.flatMap(n -> n.inetAddresses()).filter(a -> a instanceof java.net.Inet4Address).findFirst().orElseThrow();
		try (var tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(host, 0), new InetSocketAddress(host, Integer.parseInt(args[0])), false,
				new TPSettings(new IndividualAddress(0)))) {
			System.out.println("tunnel open");
			Thread.sleep(Long.parseLong(args[1]) * 1000);
		}
		System.out.println("tunnel closed");
	}
}
