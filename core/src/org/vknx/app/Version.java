package org.vknx.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Version of this build, written into version.txt by the release workflow ("dev" for local builds). */
public final class Version {
	private Version() {}

	public static String current() {
		try (var in = Version.class.getResourceAsStream("/version.txt")) {
			return in == null ? "dev" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		catch (final IOException e) {
			return "dev";
		}
	}

	/** True when {@code candidate} (e.g. "v0.3.0") is a newer numeric version than {@code current}. "dev" is older than anything. */
	public static boolean newer(final String candidate, final String current) {
		if (current == null || !current.matches("v?\\d+(\\.\\d+)*.*"))
			return candidate != null && candidate.matches("v?\\d+(\\.\\d+)*.*");
		final int[] a = nums(candidate), b = nums(current);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			final int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
			if (x != y)
				return x > y;
		}
		return false;
	}

	private static int[] nums(final String v) {
		final var m = java.util.regex.Pattern.compile("\\d+").matcher(v.split("[-+]", 2)[0]);
		final var l = new java.util.ArrayList<Integer>();
		while (m.find())
			l.add(Integer.parseInt(m.group()));
		return l.stream().mapToInt(Integer::intValue).toArray();
	}
}
