package org.vknx.product;

import java.util.List;

/**
 * One ETS parameter that lives in the application-program memory segment.
 * Only byte-aligned 8-bit parameters and 1..7-bit sub-byte fields are used in the first catalogue
 * (so endianness of multi-byte parameters is not an open question yet).
 */
public record ParamDef(int id, String name, String textDa, String textEn, int byteOffset, int bitOffset, int bits,
		int defaultValue, List<Choice> choices) {

	public record Choice(int value, String textDa, String textEn) {}

	public ParamDef {
		if (bits < 1 || bits > 8 || bitOffset + bits > 8)
			throw new IllegalArgumentException("parameter " + name + ": only fields inside one byte are supported");
		choices = List.copyOf(choices);
	}

	/** Reads this parameter out of the raw parameter area. */
	public int read(final byte[] area) {
		if (byteOffset >= area.length)
			return defaultValue;
		final int mask = (1 << bits) - 1;
		return ((area[byteOffset] & 0xff) >>> bitOffset) & mask;
	}
}
