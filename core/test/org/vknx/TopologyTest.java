package org.vknx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.vknx.sim.SharedVirtualLink;
import org.vknx.sim.Topology;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.cemi.CEMILData;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.NetworkLinkListener;

/** In-process test of the line topology and coupler routing. Not an ETS test. */
public final class TopologyTest {
	static final class Port implements Topology.CouplerPort {
		final IndividualAddress addr;
		volatile boolean pass = true;
		volatile int hops;
		Port(final String a) throws Exception { addr = new IndividualAddress(a); }
		@Override public IndividualAddress address() { return addr; }
		@Override public Topology.Decision route(final CEMILData f, final boolean fromMain) {
			hops++;
			return pass ? Topology.Decision.PASS : Topology.Decision.block("test");
		}
	}

	record Node(KNXNetworkLink link, List<CEMILData> got) {}

	static Node node(final SharedVirtualLink line, final String addr) throws Exception {
		final var link = line.createDeviceLink(new IndividualAddress(addr));
		final List<CEMILData> got = new CopyOnWriteArrayList<>();
		link.addLinkListener(new NetworkLinkListener() {
			@Override public void indication(final io.calimero.FrameEvent e) { got.add((CEMILData) e.getFrame()); }
			@Override public void linkClosed(final io.calimero.CloseEvent e) {}
		});
		return new Node(link, got);
	}

	static void send(final Node n, final String dst, final int hop) throws Exception {
		final var src = n.link.getKNXMedium().getDeviceAddress();
		n.link.send(new CEMILData(CEMILData.MC_LDATA_REQ, src, new GroupAddress(dst), new byte[] { 0, (byte) 0x81 },
				Priority.LOW, false, hop), false);
		Thread.sleep(50);
	}

	public static void main(final String[] args) throws Exception {
		final var line = new SharedVirtualLink();
		final var topo = line.topology();
		topo.setUplinkLine(1, 1);
		final var a = node(line, "1.1.5");   // line 1.1
		final var b = node(line, "1.2.5");   // line 1.2
		final var c = node(line, "2.1.5");   // line 2.1
		final var d = node(line, "1.1.6");   // line 1.1 too

		// flat: everyone hears everything
		send(a, "1/1/1", 6);
		SelfTest.check("flat: other line hears", b.got.size() == 1 && c.got.size() == 1);
		b.got.clear(); c.got.clear(); d.got.clear();

		topo.setEnabled(true);
		send(a, "1/1/1", 6);
		SelfTest.check("routed, same line hears", d.got.size() == 1);
		SelfTest.check("routed, no couplers: other line deaf", b.got.isEmpty() && c.got.isEmpty());
		d.got.clear();

		final Port lc12 = new Port("1.2.0"), mc1 = new Port("1.0.0"), lc11 = new Port("1.1.0");
		final Port ac2 = new Port("2.0.0"), lc21 = new Port("2.1.0");
		for (final var p : List.of(lc12, mc1, lc11, ac2, lc21)) topo.register(p);

		// 1.1 -> 1.2: line coupler 1.1.0 up to main 1.0, then 1.2.0 down. Backbone not needed.
		send(a, "1/1/1", 6);
		SelfTest.check("1.1 -> 1.2 through two line couplers", b.got.size() == 1);
		SelfTest.check("hop count 6 -> 4 after two couplers", b.got.get(0).getHopCount() == 4);
		SelfTest.check("1.1 -> 2.1 through area coupler, hop 6 -> 2", c.got.size() == 1 && c.got.get(0).getHopCount() == 2);
		b.got.clear(); c.got.clear(); d.got.clear();

		lc12.pass = false;
		send(a, "1/1/1", 6);
		SelfTest.check("blocked at 1.2.0: 1.2 deaf, 2.1 still hears", b.got.isEmpty() && c.got.size() == 1);
		lc12.pass = true; b.got.clear(); c.got.clear();

		topo.unregister(lc21);
		send(a, "1/1/1", 6);
		SelfTest.check("missing coupler 2.1.0: 2.1 deaf", c.got.isEmpty() && b.got.size() == 1);
		topo.register(lc21); b.got.clear(); c.got.clear();

		send(a, "1/1/1", 3);
		SelfTest.check("hop count 3 dies before area 2", c.got.isEmpty());
		b.got.clear(); c.got.clear();
		send(a, "1/1/1", 7);
		SelfTest.check("hop count 7 = unlimited, stays 7", c.got.size() == 1 && c.got.get(0).getHopCount() == 7);
		System.out.println("topology tests done");
	}
}
