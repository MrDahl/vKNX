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
	public record Entry(long seq, String time, String category, String device, String text) {}

	private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private final int capacity;
	private final ArrayList<Entry> entries = new ArrayList<>();
	private long seq;
	private Path file;

	/** Also appends every entry to a text file (for collecting logs during ETS tests). */
	public synchronized void tee(final Path f) { file = f; }

	public EventLog(final int capacity) { this.capacity = capacity; }

	public synchronized void add(final String category, final String device, final String text) {
		final var e = new Entry(++seq, LocalTime.now().format(T), category, device, text);
		entries.add(e);
		if (file != null) {
			try {
				Files.writeString(file, e.time() + " [" + category + "] " + device + " " + text + System.lineSeparator(),
						StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
