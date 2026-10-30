package org.vknx.sim;

import java.util.function.Supplier;

import io.calimero.CloseEvent;
import io.calimero.FrameEvent;
import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.KNXTimeoutException;
import io.calimero.Priority;
import io.calimero.cemi.CEMILData;
import io.calimero.link.KNXLinkClosedException;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.NetworkLinkListener;
import io.calimero.link.medium.KNXMediumSettings;

/**
 * Wraps the link of one virtual device so that it only hears the telegrams that reach its line through the couplers
 * (see {@link Topology}). Everything else is passed on to the real link.
 */
final class RoutedLink implements KNXNetworkLink {
	private final KNXNetworkLink inner;
	private final Topology topology;
	private final Supplier<IndividualAddress> address;

	RoutedLink(final KNXNetworkLink inner, final Topology topology) {
		this.inner = inner;
		this.topology = topology;
		this.address = () -> inner.getKNXMedium().getDeviceAddress();
	}

	@Override public KNXMediumSettings getKNXMedium() { return inner.getKNXMedium(); }

	@Override
	public void addLinkListener(final NetworkLinkListener l) {
		inner.addLinkListener(new NetworkLinkListener() {
			@Override
			public void indication(final FrameEvent e) {
				if (e.getFrame() instanceof final CEMILData f) {
					final CEMILData seen = topology.deliver(f, address.get());
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

	@Override public void removeLinkListener(final NetworkLinkListener l) { inner.removeLinkListener(l); }

	@Override public void setHopCount(final int count) { inner.setHopCount(count); }

	@Override public int getHopCount() { return inner.getHopCount(); }

	@Override
	public void sendRequest(final KNXAddress dst, final Priority p, final byte... nsdu)
			throws KNXTimeoutException, KNXLinkClosedException {
		inner.sendRequest(dst, p, nsdu);
	}

	@Override
	public void sendRequestWait(final KNXAddress dst, final Priority p, final byte... nsdu)
			throws KNXTimeoutException, KNXLinkClosedException {
		inner.sendRequestWait(dst, p, nsdu);
	}

	@Override
	public void send(final CEMILData msg, final boolean waitForCon) throws KNXTimeoutException, KNXLinkClosedException {
		inner.send(msg, waitForCon);
	}

	@Override public String getName() { return inner.getName(); }

	@Override public boolean isOpen() { return inner.isOpen(); }

	@Override public void close() { inner.close(); }
}
