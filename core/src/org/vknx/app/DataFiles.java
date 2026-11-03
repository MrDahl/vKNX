package org.vknx.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What vKNX keeps in its data directory, and how to clear the parts that are not needed. Logs and the automatic
 * backups of device state are never needed to run an installation; the device state, the floor plan and its layout
 * belong to the installation itself.
 */
public final class DataFiles {
	private DataFiles() {}

	/** Calimero keeps the old state file when ETS resets a device: {@code Name_20261101-182930-026_EC7.state}. */
	static final Pattern BACKUP = Pattern.compile("(.+)_(\\d{8}-\\d{6}-\\d{3})_EC\\d+\\.state");
	private static final List<String> LOGS = List.of("vknx.log", "vknx.log.1", "console.log", "console.log.1");
	private static final List<String> PLAN_TYPES = List.of("png", "jpg", "jpeg", "svg", "webp", "gif");

	/** A group of files in the data directory: {@code id} is one of logs, backups, plan, state. */
	public record Item(String id, int files, long bytes) {}

	public static List<Item> inspect(final Path dir) throws IOException {
		final var logs = new long[2];
		final var backups = new long[2];
		final var plan = new long[2];
		final var state = new long[2];
		for (final Path f : list(dir)) {
			final String n = f.getFileName().toString();
			final long size = Files.size(f);
			final long[] t = LOGS.contains(n) ? logs : BACKUP.matcher(n).matches() ? backups : isPlan(n) ? plan
					: n.endsWith(".state") ? state : null;
			if (t != null) {
				t[0]++;
				t[1] += size;
			}
		}
		return List.of(new Item("logs", (int) logs[0], logs[1]), new Item("backups", (int) backups[0], backups[1]),
				new Item("plan", (int) plan[0], plan[1]), new Item("state", (int) state[0], state[1]));
	}

	private static boolean isPlan(final String n) {
		if (n.equals("plan-layout.json") || n.equals("icons.json"))
			return true;
		return n.startsWith("plan.") && PLAN_TYPES.contains(n.substring(5));
	}

	private static List<Path> list(final Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(Files::isRegularFile).toList();
		}
	}

	/**
	 * Empties the log files. The running program keeps them open, so they are emptied and not deleted (a deleted
	 * open file cannot be re-created on Windows); rotated copies are deleted.
	 *
	 * @return bytes freed
	 */
	public static long clearLogs(final Path dir) throws IOException {
		long freed = 0;
		for (final String n : LOGS) {
			final Path f = dir.resolve(n);
			if (!Files.isRegularFile(f))
				continue;
			final long size = Files.size(f);
			if (n.endsWith(".1"))
				Files.delete(f);
			else
				try (FileChannel c = FileChannel.open(f, StandardOpenOption.WRITE)) {
					c.truncate(0);
				}
			freed += size;
		}
		return freed;
	}

	/**
	 * Deletes the automatic state backups, except the newest {@code keep} of each device.
	 *
	 * @return bytes freed
	 */
	public static long deleteBackups(final Path dir, final int keep) throws IOException {
		final Map<String, List<Path>> byDevice = new TreeMap<>();
		for (final Path f : list(dir)) {
			final var m = BACKUP.matcher(f.getFileName().toString());
			if (m.matches())
				byDevice.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(f);
		}
		long freed = 0;
		for (final var files : byDevice.values()) {
			files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()); // the timestamp sorts as text
			for (final Path f : files.subList(Math.min(keep, files.size()), files.size())) {
				final long size = Files.size(f);
				Files.deleteIfExists(f);
				freed += size;
			}
		}
		return freed;
	}

	/** Deletes every state backup of one device (when the device is removed). */
	public static void deleteBackupsOf(final Path dir, final String device) throws IOException {
		for (final Path f : list(dir)) {
			final var m = BACKUP.matcher(f.getFileName().toString());
			if (m.matches() && m.group(1).equals(device))
				Files.deleteIfExists(f);
		}
	}

	/** Deletes the floor plan image, the placement of the devices on it and the icons the user chose. */
	public static long clearPlan(final Path dir) throws IOException {
		long freed = 0;
		for (final Path f : list(dir))
			if (isPlan(f.getFileName().toString())) {
				freed += Files.size(f);
				Files.delete(f);
			}
		return freed;
	}

	/** Moves a log that has grown past {@code max} bytes to {@code <name>.1} (replacing an older copy). */
	public static void rotate(final Path file, final long max) {
		try {
			if (Files.isRegularFile(file) && Files.size(file) > max)
				Files.move(file, file.resolveSibling(file.getFileName() + ".1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		catch (final IOException e) { /* in use: it is rotated at the next start instead */ }
	}
}
