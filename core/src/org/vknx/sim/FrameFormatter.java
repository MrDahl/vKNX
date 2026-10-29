package org.vknx.sim;

import java.util.HexFormat;

import io.calimero.DataUnitBuilder;
import io.calimero.cemi.CEMILData;

/** Human-readable one-line rendering of a bus frame for the telegram monitor. */
public final class FrameFormatter {
	private FrameFormatter() {}

	public static String format(final CEMILData f) {
		final byte[] tpdu = f.getPayload();
		String service;
		try {
			service = DataUnitBuilder.decodeAPCI(DataUnitBuilder.getAPDUService(tpdu));
		}
		catch (final RuntimeException e) {
			service = "?";
		}
		return String.format("%-9s -> %-9s %-28s tpdu=%s", f.getSource(), f.getDestination(), service,
				HexFormat.of().formatHex(tpdu));
	}
}
