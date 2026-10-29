package org.vknx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Compares Crc16 against vectors produced by the reference C function (vectors generated with thelsing/knx bits.cpp crc16Ccitt, commit 980c047; see docs/decisions.md). */
public final class CrcCrossCheck {
	public static void main(final String[] a) throws Exception {
		final var m = Class.forName("org.vknx.device.Crc16").getDeclaredMethod("ccitt", byte[].class);
		m.setAccessible(true);
		int bad = 0;
		for (final String line : Files.readAllLines(Path.of(a.length > 0 ? a[0] : "core/test/crc-vectors.txt"))) {
			final String[] p = line.trim().split(" +");
			final byte[] data = p.length == 3 ? HexFormat.of().parseHex(p[1]) : new byte[0];
			final int expect = Integer.parseInt(p[p.length - 1], 16);
			final int got = (Integer) m.invoke(null, data);
			if (got != expect) { bad++; System.out.println("MISMATCH len=" + data.length); }
		}
		System.out.println(bad == 0 ? "CRC cross-check OK" : "CRC cross-check FAILED");
		System.exit(bad == 0 ? 0 : 1);
	}
}
