package org.vknx.product;

import java.util.List;

/**
 * One ETS parameter that lives in the application-program memory segment.
 * Either an enumeration ({@code choices} not empty, 1..8 bits inside one byte) or a plain number
 * ({@code choices} empty; 8 or 16 bits on a byte boundary, big-endian as in the KNX memory model, with {@code min}/{@code max};
 * a negative {@code min} makes a 16-bit parameter signed).
 *
 * <p>{@code group} is the ETS parameter page the parameter is shown on ("" = "General"). {@code showWhenParam}
 * (empty = always visible) makes ETS show the parameter only while that other parameter has the value {@code showWhenValue}.
 */
public record ParamDef(int id, String name, String text, int byteOffset, int bitOffset, int bits, int defaultValue,
		List<Choice> choices, int min, int max, String group, String showWhenParam, int showWhenValue) {

	public record Choice(int value, String text) {}

	/** Enumeration parameter. */
	public ParamDef(final int id, final String name, final String text, final int byteOffset, final int bitOffset,
			final int bits, final int defaultValue, final List<Choice> choices) {
		this(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, 0, (1 << bits) - 1, "", "", 0);
	}

	/** Plain number parameter of 8 or 16 bits. */
	public static ParamDef number(final int id, final String name, final String text, final int byteOffset,
			final int bits, final int defaultValue, final int min, final int max) {
		return new ParamDef(id, name, text, byteOffset, 0, bits, defaultValue, List.of(), min, max, "", "", 0);
	}

	/** Same parameter, shown on the named ETS parameter page. */
	public ParamDef inGroup(final String page) {
		return new ParamDef(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, min, max, page,
				showWhenParam, showWhenValue);
	}

	/** Same parameter, shown only while parameter {@code other} equals {@code value}. */
	public ParamDef showWhen(final String other, final int value) {
		return new ParamDef(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, min, max, group, other, value);
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

	/**
	 * Reads this parameter out of the raw parameter area. A value that is not one of the defined choices (for example
	 * because the memory was never written) falls back to the default instead of producing nonsense.
	 */
	public int read(final byte[] area) {
		if (byteOffset + sizeBytes() > area.length)
			return defaultValue;
		if (bits == 16) {
			final int u = ((area[byteOffset] & 0xff) << 8) | (area[byteOffset + 1] & 0xff);
			return min < 0 ? (short) u : u;
		}
		// ETS counts BitOffset from the most significant bit: offset 0 = bit 7. Proven by a real ETS download log
		// (dimmer default 0x44 = DimTime 01 at bits 7-6, SendStatus 1 at bit 2); counting from bit 0 read every flag wrong.
		final int mask = (1 << bits) - 1;
		final int v = ((area[byteOffset] & 0xff) >>> (8 - bitOffset - bits)) & mask;
		if (!choices.isEmpty() && choices.stream().noneMatch(c -> c.value() == v))
			return defaultValue;
		return v;
	}
}
