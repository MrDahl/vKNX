package org.vknx.sim;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.calimero.CloseEvent;
import io.calimero.FrameEvent;
import io.calimero.IndividualAddress;
import io.calimero.cemi.CEMILData;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.NetworkLinkListener;
import io.calimero.link.medium.TPSettings;
import io.calimero.server.VirtualLink;

/**
 * The virtual KNX line. Calimero's {@link VirtualLink} is the uplink; every virtual device attaches through
 * {@link #createDeviceLink}. The KNXnet/IP server instantiates this class by name (subnet type "user") and the
 * simulator picks the instance up from {@link #awaitInstance}.
 *
 * <p>The only addition to Calimero's class is a tap on frames that arrive from the KNXnet/IP side (i.e. from ETS),
 * so the telegram monitor can show both directions.
 */
public final class SharedVirtualLink extends VirtualLink {
	private static volatile SharedVirtualLink instance;
	private static final Object LOCK = new Object();

	private final CopyOnWriteArrayList<Consumer<CEMILData>> inboundTaps = new CopyOnWriteArrayList<>();
	private final Topology topology = new Topology();

	/** Line structure and couplers of this installation. */
	public Topology topology() { return topology; }

	/**
	 * Listeners added here (the KNXnet/IP server forwarding to ETS) only get the telegrams that reach the interface line
	 * through the couplers. The telegram monitor uses {@link #addMonitor} and sees everything.
	 */
	@Override
	public void addLinkListener(final NetworkLinkListener l) {
		super.addLinkListener(new NetworkLinkListener() {
			@Override
			public void indication(final FrameEvent e) {
				if (e.getFrame() instanceof final CEMILData f) {
					final CEMILData seen = topology.deliverToUplink(f);
					if (seen == null)
						return;
					l.indication(seen == f ? e : new FrameEvent(e.getSource(), seen));
				}
				else
					l.indication(e);
			}

			@Override public void confirmation(final FrameEvent e) { l.confirmation(e); }

			@Override public void linkClosed(final CloseEvent e) { l.linkClosed(e); }
		});
	}

	/** A listener that sees every telegram on the whole installation, regardless of couplers. */
	public void addMonitor(final NetworkLinkListener l) { super.addLinkListener(l); }

	/** The link of a device, hearing only what the topology lets through. */
	@Override
	public KNXNetworkLink createDeviceLink(final IndividualAddress address) {
		return new RoutedLink(super.createDeviceLink(address), topology);
	}

	/** Used by the KNXnet/IP server (reflection, signature fixed by Calimero) and by tests. */
	public SharedVirtualLink(final Object... args) {
		super("vknx-line", new TPSettings(new IndividualAddress(0)));
		synchronized (LOCK) {
			instance = this;
			LOCK.notifyAll();
		}
	}

	public static SharedVirtualLink awaitInstance(final long timeoutMillis) throws InterruptedException {
		final long end = System.currentTimeMillis() + timeoutMillis;
		synchronized (LOCK) {
			while (instance == null) {
				final long left = end - System.currentTimeMillis();
				if (left <= 0)
					return null;
				LOCK.wait(left);
			}
			return instance;
		}
	}

	/** Forgets the static instance (tests create several lines). */
	public static void reset() {
		synchronized (LOCK) {
			instance = null;
		}
	}

	public void addInboundTap(final Consumer<CEMILData> tap) { inboundTaps.add(tap); }

	@Override
	protected void onSend(final CEMILData msg, final boolean waitForCon) {
		inboundTaps.forEach(t -> t.accept(msg));
		super.onSend(msg, waitForCon);
	}
}
