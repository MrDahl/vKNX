package org.vknx.app;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/** Small persistent settings of one simulator instance (settings.properties in the data directory). */
public final class Settings {
	private static final Pattern LINE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");
	private final Path file;
	private final Properties p = new Properties();

	public Settings(final Path dir) throws IOException {
		file = dir.resolve("settings.properties");
		if (Files.exists(file))
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			}
	}

	/** Area.line of the KNXnet/IP interface, e.g. "1.1" (what ETS shows as interface address area/line). */
	public String line() {
		final String l = p.getProperty("line", "1.1");
		return valid(l) ? l : "1.1";
	}

	public static boolean valid(final String line) {
		final var m = LINE.matcher(line == null ? "" : line.trim());
		return m.matches() && Integer.parseInt(m.group(1)) <= 15 && Integer.parseInt(m.group(2)) <= 15;
	}

	public int area() { return Integer.parseInt(line().split("\\.")[0]); }

	public int lineNo() { return Integer.parseInt(line().split("\\.")[1]); }

	public void setLine(final String line) throws IOException {
		if (!valid(line))
			throw new IllegalArgumentException("line must look like 2.9 (area 0-15, line 0-15)");
		p.setProperty("line", line.trim());
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			p.store(w, "vKNX settings");
		}
	}
}
