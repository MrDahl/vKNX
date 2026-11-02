package org.vknx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Bounded in-memory log shared by the telegram monitor and the device explanations. */
public final class EventLog {
	/** Structured fields of a real bus telegram (so the UI never has to parse display text). */
	public record Telegram(String origin, String source, String destination, boolean group, String service, String tpdu,
			String priority, int hops) {}

	public record Entry(long seq, long ts, String time, String category, String device, String text, Telegram telegram) {}

	private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private final int capacity;
	private final ArrayList<Entry> entries = new ArrayList<>();
	private long seq;
	private Path file;
	private long fileBytes;
	/** The text log is moved to {@code .1} when it passes this size, so it can never fill the disk (at most twice this). */
	public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

	/** Also appends every entry to a text file (for collecting logs during ETS tests). */
	public synchronized void tee(final Path f) {
		file = f;
		try {
			fileBytes = java.nio.file.Files.isRegularFile(f) ? java.nio.file.Files.size(f) : 0;
		}
		catch (final IOException e) { fileBytes = 0; }
	}

	/** Forgets the entries shown in the monitor (the numbering goes on, so open pages simply see nothing new). */
	public synchronized void clear() { entries.clear(); }

	public EventLog(final int capacity) { this.capacity = capacity; }

	public void add(final String category, final String device, final String text) { add(category, device, text, null); }

	public synchronized void add(final String category, final String device, final String text, final Telegram tg) {
		final var e = new Entry(++seq, System.currentTimeMillis(), LocalTime.now().format(T), category, device, text, tg);
		entries.add(e);
		if (file != null) {
			try {
				final String line = e.time() + " [" + category + "] " + device + " " + text + System.lineSeparator();
				if (fileBytes > MAX_FILE_BYTES) {
					org.vknx.app.DataFiles.rotate(file, MAX_FILE_BYTES);
					fileBytes = java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.size(file) : 0;
				}
				Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				fileBytes += line.length();
			}
			catch (final IOException ex) { file = null; }
		}
		if (entries.size() > capacity)
			entries.subList(0, entries.size() - capacity).clear();
	}

	public synchronized List<Entry> since(final long after) {
		return entries.stream().filter(e -> e.seq() > after).toList();
	}
}
