package org.vknx.ui;

import java.util.HexFormat;
import java.util.Set;

import io.calimero.DataUnitBuilder;
import io.calimero.dptxlator.DptId;
import io.calimero.dptxlator.TranslatorTypes;

/**
 * Decodes the value of a group telegram, but only when the datapoint type is known from a reliable source (the product
 * definitions of the devices that have the group address associated). The type is never guessed from the data length.
 */
public final class TelegramDecoder {
	private TelegramDecoder() {}

	public record Result(String dpt, String value) {}

	/** @param knownDpts distinct DPT ids ("DPST-9-1") declared by the associated objects; decoded only if exactly one */
	public static Result decode(final String tpduHex, final String service, final Set<String> knownDpts) {
		if (!service.startsWith("GroupValue") || service.endsWith("Read") || knownDpts.size() != 1)
			return new Result(knownDpts.size() == 1 ? pretty(knownDpts.iterator().next()) : null, null);
		final String dpt = knownDpts.iterator().next();
		try {
			final byte[] tpdu = HexFormat.of().parseHex(tpduHex);
			final byte[] asdu = DataUnitBuilder.extractASDU(tpdu);
			final var t = TranslatorTypes.createTranslator(DptId.from(pretty(dpt)));
			if (asdu.length != Math.max(1, t.getTypeSize()))
				return new Result(pretty(dpt), null);
			t.setData(asdu);
			return new Result(pretty(dpt), tidy(t.getValue()));
		}
		catch (final Exception e) {
			return new Result(pretty(dpt), null);
		}
	}

	/** Removes floating point noise ("22.400000000000002 \u00b0C" -> "22.4 \u00b0C"); DPT values have at most 2 decimals here. */
	public static String tidy(final String v) {
		final var m = java.util.regex.Pattern.compile("^(-?\\d+\\.\\d{3,})(.*)$").matcher(v);
		if (!m.matches())
			return v;
		return new java.math.BigDecimal(m.group(1)).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + m.group(2);
	}

	/** "DPST-9-1" -> "9.001" */
	public static String pretty(final String dpt) {
		final String[] p = dpt.replace("DPST-", "").replace("DPT-", "").split("-");
		return p.length == 2 ? p[0] + "." + String.format("%03d", Integer.parseInt(p[1])) : p[0];
	}
}
