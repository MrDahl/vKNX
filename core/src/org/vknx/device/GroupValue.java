package org.vknx.device;

/** How the value of a group object travels in a group telegram. */
final class GroupValue {
	private GroupValue() {}

	/** Value of a group telegram: small objects (up to 6 bit) are packed into the APCI byte, larger ones are big-endian bytes. */
	static int decode(final byte[] asdu, final int bits) {
		if (asdu.length == 0)
			return 0;
		if (bits <= 6)
			return asdu[asdu.length - 1] & 0x3f;
		int v = 0;
		for (int i = Math.max(0, asdu.length - 4); i < asdu.length; i++)
			v = (v << 8) | (asdu[i] & 0xff);
		return v;
	}

	/** Big-endian bytes of an object value: 1, 2, 3 or 4 bytes by the object size. */
	static byte[] encode(final int bits, final int v) {
		final int len = Math.max(1, Math.min(4, bits / 8));
		final byte[] b = new byte[len];
		for (int i = 0; i < len; i++)
			b[i] = (byte) (v >>> (8 * (len - 1 - i)));
		return b;
	}
}
