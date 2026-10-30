package org.vknx.product;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One ETS parameter that lives in the application-program memory segment.
 * Either an enumeration ({@code choices} not empty, 1..8 bits inside one byte), a plain number ({@code choices} empty;
 * 8 or 16 bits on a byte boundary, big-endian as in the KNX memory model, with {@code min}/{@code max}; a negative
 * {@code min} makes a 16-bit parameter signed) or a text ({@code defaultText} not null; {@code bits} = 8 x characters,
 * ISO-8859-1, padded with zero bytes).
 *
 * <p>{@code group} is the ETS parameter page the parameter is shown on ("" = "General"). {@code when} (empty = always
 * visible) makes ETS show the parameter only while all of its conditions hold.
 *
 * <p>Bit order: ETS counts {@code bitOffset} from the most significant bit (offset 0 = bit 7).
 */
public record ParamDef(int id, String name, String text, int byteOffset, int bitOffset, int bits, int defaultValue,
		List<Choice> choices, int min, int max, String group, List<Cond> when, String defaultText) {

	public record Choice(int value, String text) {}

	/** Enumeration parameter. */
	public ParamDef(final int id, final String name, final String text, final int byteOffset, final int bitOffset,
			final int bits, final int defaultValue, final List<Choice> choices) {
		this(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, 0, (1 << bits) - 1, "", List.of(), null);
	}

	/** Plain number parameter of 8 or 16 bits. */
	public static ParamDef number(final int id, final String name, final String text, final int byteOffset,
			final int bits, final int defaultValue, final int min, final int max) {
		return new ParamDef(id, name, text, byteOffset, 0, bits, defaultValue, List.of(), min, max, "", List.of(), null);
	}

	/** Text parameter of {@code chars} characters (names of keys, outputs, channels). */
	public static ParamDef text(final int id, final String name, final String label, final int byteOffset, final int chars,
			final String defaultText) {
		return new ParamDef(id, name, label, byteOffset, 0, chars * 8, 0, List.of(), 0, 0, "", List.of(), defaultText);
	}

	public boolean isText() { return defaultText != null; }

	/** Same parameter, shown on the named ETS parameter page. */
	public ParamDef inGroup(final String page) {
		return new ParamDef(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, min, max, page, when, defaultText);
	}

	/** Same parameter, shown only while parameter {@code other} has one of {@code values} (and all earlier conditions hold). */
	public ParamDef showWhen(final String other, final int... values) {
		final var l = new ArrayList<>(when);
		l.add(Cond.of(other, values));
		return new ParamDef(id, name, text, byteOffset, bitOffset, bits, defaultValue, choices, min, max, group, l, defaultText);
	}

	public ParamDef { 
		choices = List.copyOf(choices);
		when = List.copyOf(when);
		if (defaultText != null) {
			if (bits < 8 || bits % 8 != 0 || bitOffset != 0)
				throw new IllegalArgumentException("text parameter " + name + ": whole bytes");
		}
		else if (choices.isEmpty()) {
			if (bits != 8 && bits != 16 || bitOffset != 0)
				throw new IllegalArgumentException("number parameter " + name + ": 8 or 16 bits on a byte boundary");
		}
		else if (bits < 1 || bits > 8 || bitOffset + bits > 8)
			throw new IllegalArgumentException("parameter " + name + ": enumerations must fit inside one byte");
	}

	public int sizeBytes() { return isText() ? bits / 8 : bits == 16 ? 2 : 1; }

	/**
	 * Reads this parameter out of the raw parameter area. A value that is not one of the defined choices (for example
	 * because the memory was never written) falls back to the default instead of producing nonsense.
	 */
	public int read(final byte[] area) {
		if (isText() || byteOffset + sizeBytes() > area.length)
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

	/** Reads a text parameter: bytes up to the first zero byte, trailing blanks removed; empty means "use the default". */
	public String readText(final byte[] area) {
		if (!isText() || byteOffset + sizeBytes() > area.length)
			return defaultText == null ? "" : defaultText;
		int n = 0;
		while (n < sizeBytes() && area[byteOffset + n] != 0)
			n++;
		final String s = new String(area, byteOffset, n, StandardCharsets.ISO_8859_1).stripTrailing();
		return s.isEmpty() ? defaultText : s;
	}

	/** Writes a value into a parameter area (used for defaults and by tests; the inverse of {@link #read}). */
	public void write(final byte[] area, final int v) {
		if (bits == 16) {
			area[byteOffset] = (byte) (v >> 8);
			area[byteOffset + 1] = (byte) v;
			return;
		}
		final int shift = 8 - bitOffset - bits, mask = ((1 << bits) - 1) << shift;
		area[byteOffset] = (byte) ((area[byteOffset] & ~mask) | ((v << shift) & mask));
	}

	public void writeText(final byte[] area, final String s) {
		final byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
		for (int i = 0; i < sizeBytes(); i++)
			area[byteOffset + i] = i < b.length ? b[i] : 0;
	}
}
