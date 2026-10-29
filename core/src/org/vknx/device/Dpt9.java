package org.vknx.device;

/** KNX DPT 9.xxx 2-byte float: value = 0.01 * mantissa(11 bit, signed) * 2^exponent(4 bit). */
public final class Dpt9 {
	private Dpt9() {}

	/** Encodes {@code value} to the 16-bit raw representation (resolution is lost for large values, as on real devices). */
	public static int encode(final double value) {
		long m = Math.round(value * 100.0);
		int e = 0;
		while ((m < -2048 || m > 2047) && e < 15) {
			m = Math.round(m / 2.0);
			e++;
		}
		m = Math.max(-2048, Math.min(2047, m));
		return ((m < 0 ? 1 : 0) << 15) | (e << 11) | (int) (m & 0x7ff);
	}

	public static double decode(final int raw) {
		int m = raw & 0x7ff;
		if ((raw & 0x8000) != 0)
			m -= 2048;
		final int e = (raw >> 11) & 0x0f;
		return 0.01 * m * (1 << e);
	}
}
