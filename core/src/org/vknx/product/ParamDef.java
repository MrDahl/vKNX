package org.vknx.product;

import java.util.List;

/**
 * One ETS parameter that lives in the application-program memory segment.
 * Either an enumeration ({@code choices} not empty, 1..8 bits inside one byte) or a plain unsigned number
 * ({@code choices} empty; 8 or 16 bits on a byte boundary, big-endian as in the KNX memory model, with {@code min}/{@code max}).
 */
public record ParamDef(int id, String name, String textDa, String textEn, int byteOffset, int bitOffset, int bits,
		int defaultValue, List<Choice> choices, int min, int max) {

	public record Choice(int value, String textDa, String textEn) {}

	/** Enumeration parameter (the form used by the first two products). */
	public ParamDef(final int id, final String name, final String textDa, final String textEn, final int byteOffset,
			final int bitOffset, final int bits, final int defaultValue, final List<Choice> choices) {
		this(id, name, textDa, textEn, byteOffset, bitOffset, bits, defaultValue, choices, 0, (1 << bits) - 1);
	}

	/** Plain unsigned number parameter of 8 or 16 bits. */
	public static ParamDef number(final int id, final String name, final String textDa, final String textEn,
			final int byteOffset, final int bits, final int defaultValue, final int min, final int max) {
		return new ParamDef(id, name, textDa, textEn, byteOffset, 0, bits, defaultValue, List.of(), min, max);
	}

	public ParamDef {
		choices = List.copyOf(choices);
		if (choices.isEmpty()) {
			if (bits != 8 && bits != 16 || bitOffset != 0)
				throw new IllegalArgumentException("number parameter " + name + ": 8 or 16 bits on a byte boundary");
		}
		else if (bits < 1 || bits > 8 || bitOffset + bits > 8)
			throw new IllegalArgumentException("parameter " + name + ": enumerations must fit inside one byte");
	}

	public int sizeBytes() { return bits == 16 ? 2 : 1; }

	/** Reads this parameter out of the raw parameter area. */
	public int read(final byte[] area) {
		if (byteOffset + sizeBytes() > area.length)
			return defaultValue;
		if (bits == 16) {
			final int u = ((area[byteOffset] & 0xff) << 8) | (area[byteOffset + 1] & 0xff);
			return min < 0 ? (short) u : u; // signed 16-bit parameters have a negative minimum
		}
		final int mask = (1 << bits) - 1;
		return ((area[byteOffset] & 0xff) >>> bitOffset) & mask;
	}
}
