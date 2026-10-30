package org.vknx.sim;

import java.util.HexFormat;

import io.calimero.DataUnitBuilder;
import io.calimero.cemi.CEMILData;

/** Human-readable one-line rendering of a bus frame for the telegram monitor. */
public final class FrameFormatter {
	private FrameFormatter() {}

	/** Name of the service in a TPDU; transport control frames (connect, disconnect, ack) have no APCI. */
	public static String service(final byte[] tpdu, final boolean toGroup) {
		if (toGroup && tpdu.length >= 2) {
			switch (DataUnitBuilder.getAPDUService(tpdu)) {
				case 0x00 -> { return "GroupValueRead"; }
				case 0x40 -> { return "GroupValueResponse"; }
				case 0x80 -> { return "GroupValueWrite"; }
				default -> { /* fall through to the generic name */ }
			}
		}
		return service(tpdu);
	}

	public static String service(final byte[] tpdu) {
		if (tpdu.length == 1) {
			final int b = tpdu[0] & 0xff;
			if (b == 0x80)
				return "T_Connect";
			if (b == 0x81)
				return "T_Disconnect";
			if ((b & 0xC3) == 0xC2)
				return "T_ACK";
			if ((b & 0xC3) == 0xC3)
				return "T_NAK";
		}
		try {
			return DataUnitBuilder.decodeAPCI(DataUnitBuilder.getAPDUService(tpdu));
		}
		catch (final RuntimeException e) {
			return "Unknown service";
		}
	}

	public static String format(final CEMILData f) {
		final byte[] tpdu = f.getPayload();
		return String.format("%-9s -> %-9s %-28s tpdu=%s", f.getSource(), f.getDestination(),
				service(tpdu, f.getDestination() instanceof io.calimero.GroupAddress),
				HexFormat.of().formatHex(tpdu));
	}
}
