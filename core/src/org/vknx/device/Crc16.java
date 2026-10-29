package org.vknx.device;

/**
 * CRC-16 as used in the KNX memory control block (polynomial 0x1021, initial value 0xFFFF, message followed by
 * 16 zero bits, MSB first). Cross-checked against the reference C implementation of thelsing/knx in
 * {@code core/test} (see CrcCrossCheck), but written independently.
 */
final class Crc16 {
	private Crc16() {}

	static int ccitt(final byte[] data) {
		int reg = 0xFFFF;
		final long totalBits = 8L * (data.length + 2);
		for (long i = 0; i < totalBits; i++) {
			final int idx = (int) (i / 8);
			final int bit = idx < data.length ? ((data[idx] >> (7 - (int) (i % 8))) & 1) : 0;
			reg = ((reg << 1) | bit);
			if ((reg & 0x10000) != 0)
				reg ^= 0x1021;
			reg &= 0x1FFFF;
		}
		return reg & 0xFFFF;
	}
}
