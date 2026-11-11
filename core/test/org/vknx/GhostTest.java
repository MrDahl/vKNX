package org.vknx;

import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.link.KNXNetworkLink;
import io.calimero.process.ProcessCommunicatorImpl;

/** A closed (removed) device must not take part in the bus any more: no log lines, no state writes. */
public final class GhostTest {
	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-ghost");
		final var net = Subnet.inProcess();
		final List<String> seen = new ArrayList<>();
		final var d = VirtualDevice.create("old", Products.SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 71 },
				dir.resolve("old.state"), net, (s, c, t) -> { synchronized (seen) { seen.add(s.name() + " " + c + " " + t); } });
		d.close();
		synchronized (seen) { seen.clear(); }
		final KNXNetworkLink l = net.createDeviceLink(new IndividualAddress(1, 1, 241));
		final var pc = new ProcessCommunicatorImpl(l);
		pc.write(new GroupAddress(0, 0, 2), false);
		Thread.sleep(500);
		synchronized (seen) { check("a closed device sees no group telegram: " + seen, seen.isEmpty()); }
		System.out.println("ghost test done");
		System.exit(SelfTest.failed > 0 ? 1 : 0);
	}
}
