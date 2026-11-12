package org.vknx.device;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.vknx.app.DataFiles;
import org.vknx.product.Products;

/**
 * An installation as one file (a zip, extension {@value #EXTENSION}) that can be sent to somebody else: the devices
 * with their full state (address, what ETS downloaded, parameters, logic circuit), the floor plan with the placement
 * of the devices and the icons that were chosen. Whoever imports it sees the same simulation, with the same
 * addresses and group addresses, and can operate it without ETS.
 *
 * <p>The file is read as untrusted input: only the known file names are taken, nothing is ever written to a path
 * from the file, and the sizes are limited.
 */
public final class InstallationFile {
	private InstallationFile() {}

	public static final String EXTENSION = ".vknx";
	/** Version of this file layout; a newer one is refused. */
	public static final int FORMAT = 1;
	public static final long MAX_COMPRESSED = 64L << 20, MAX_TOTAL = 256L << 20;
	private static final int MAX_ENTRIES = 1000, MAX_DEVICES = 200, MAX_PLAN = 12 << 20, MAX_JSON = 2_000_000;
	private static final String INFO = "installation.properties", DEVICES = "devices.txt", LAYOUT = "plan-layout.json", ICONS = "icons.json";
	private static final Pattern STATE_NAME = Pattern.compile("[A-Za-z0-9_~-]{1,64}\\.state");

	/** A device in the file. */
	public record Entry(String product, String productName, String name, String line, boolean secure, boolean hasState) {}

	/** What is in a file, for the confirmation dialog. {@code skipped}: devices whose product this vKNX does not know. */
	public record Summary(String vknx, String created, List<Entry> devices, List<String> skipped, boolean plan, boolean layout,
			boolean icons) {}

	/** A checked file: {@code files} are the files to put in the data directory, by their names there. */
	public record Content(Summary summary, Map<String, byte[]> files) {}

	/** Packs the installation as it is now (the state of the running devices is saved first). */
	public static byte[] export(final DeviceRegistry registry, final String version) throws IOException {
		registry.saveAll();
		final Path dir = registry.dir();
		final var out = new ByteArrayOutputStream();
		try (var z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			final var info = new Properties();
			info.setProperty("format", Integer.toString(FORMAT));
			info.setProperty("schema", DeviceRegistry.SCHEMA);
			info.setProperty("vknx", version);
			info.setProperty("created", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
			final var w = new StringWriter();
			info.store(w, "vKNX installation file. Open it in vKNX: Settings > Share.");
			put(z, INFO, w.toString().getBytes(StandardCharsets.UTF_8));
			put(z, DEVICES, String.join("\n", registry.specLines()).concat("\n").getBytes(StandardCharsets.UTF_8));
			for (final var d : registry.devices()) {
				final Path f = dir.resolve(DeviceRegistry.fileStem(d.name()) + ".state");
				if (Files.isRegularFile(f))
					put(z, f.getFileName().toString(), Files.readAllBytes(f));
			}
			try (var s = Files.list(dir)) {
				for (final Path f : s.filter(Files::isRegularFile).sorted().toList()) {
					final String n = f.getFileName().toString();
					if (DataFiles.isPlan(n))
						put(z, n, Files.readAllBytes(f));
				}
			}
		}
		return out.toByteArray();
	}

	private static void put(final ZipOutputStream z, final String name, final byte[] data) throws IOException {
		z.putNextEntry(new ZipEntry(name));
		z.write(data);
		z.closeEntry();
	}

	/**
	 * Checks a file and returns what it contains.
	 *
	 * @throws IllegalArgumentException with the reason in words if it is not a usable installation file
	 */
	public static Content read(final byte[] zip) throws IOException {
		if (zip.length > MAX_COMPRESSED)
			throw new IllegalArgumentException("The file is too large (more than " + (MAX_COMPRESSED >> 20) + " MB).");
		final var raw = new LinkedHashMap<String, byte[]>();
		long total = 0;
		int entries = 0;
		try (var z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			ZipEntry e;
			while ((e = z.getNextEntry()) != null) {
				if (++entries > MAX_ENTRIES)
					throw new IllegalArgumentException("The file contains too many files.");
				final String n = e.getName();
				if (e.isDirectory() || !known(n))
					continue; // anything else is ignored, never extracted
				final byte[] b = readLimited(z, MAX_TOTAL - total);
				total += b.length;
				raw.put(n, b);
			}
		}
		catch (final ZipException e) {
			throw notOurs();
		}
		if (!raw.containsKey(INFO) || !raw.containsKey(DEVICES))
			throw notOurs();

		final var info = new Properties();
		info.load(new StringReader(new String(raw.get(INFO), StandardCharsets.UTF_8)));
		final int format;
		try {
			format = Integer.parseInt(info.getProperty("format", "0").trim());
		}
		catch (final NumberFormatException e) {
			throw notOurs();
		}
		if (format < 1)
			throw notOurs();
		if (format > FORMAT)
			throw new IllegalArgumentException("This file was made by a newer vKNX (file format " + format + "). Update vKNX and try again.");
		final String schema = info.getProperty("schema", "").trim();
		if (!schema.equals(DeviceRegistry.SCHEMA))
			throw new IllegalArgumentException("This file was made by a vKNX with a different device layout (" + schema + ", this vKNX uses "
					+ DeviceRegistry.SCHEMA + "). Open it in a vKNX of the same release, or ask for a new export from the current version.");

		final var files = new LinkedHashMap<String, byte[]>();
		final var kept = new ArrayList<String>();
		final var devices = new ArrayList<Entry>();
		final var skipped = new ArrayList<String>();
		final var seen = new java.util.HashSet<String>();
		for (final String l : new String(raw.get(DEVICES), StandardCharsets.UTF_8).split("\n")) {
			final String line = l.strip();
			if (line.isEmpty())
				continue;
			if (devices.size() + skipped.size() >= MAX_DEVICES)
				throw new IllegalArgumentException("The file contains more than " + MAX_DEVICES + " devices.");
			final var sp = DeviceRegistry.parseSpec(line);
			final String bad = DeviceRegistry.checkName(sp.name());
			if (bad != null)
				throw new IllegalArgumentException("The file contains a device with an invalid name (" + sp.name() + "): " + bad);
			if (!seen.add(sp.name().toLowerCase(java.util.Locale.ROOT)))
				throw new IllegalArgumentException("The file contains the device name " + sp.name() + " twice.");
			final var product = Products.ALL.get(sp.product());
			if (product == null) {
				skipped.add(sp.name() + " (" + sp.product() + ")");
				continue;
			}
			final String stateName = DeviceRegistry.fileStem(sp.name()) + ".state";
			final byte[] state = STATE_NAME.matcher(stateName).matches() ? raw.get(stateName) : null;
			if (state != null)
				files.put(stateName, state);
			kept.add(DeviceRegistry.specLine(sp.product(), sp.name(), sp.line()));
			devices.add(new Entry(sp.product(), product.name(), sp.name(), sp.line() == null ? null : sp.line().toString(), product.secure(), state != null));
		}
		files.put(DEVICES, String.join("\n", kept).concat(kept.isEmpty() ? "" : "\n").getBytes(StandardCharsets.UTF_8));

		boolean plan = false;
		final var floors = new java.util.HashSet<Integer>(); // one image per floor
		for (final var e : raw.entrySet()) {
			final int floor = DataFiles.planFloor(e.getKey());
			if (floor >= 0 && floors.add(floor)) {
				if (e.getValue().length > MAX_PLAN)
					throw new IllegalArgumentException("A floor plan image in the file is larger than " + (MAX_PLAN >> 20) + " MB.");
				files.put(e.getKey(), e.getValue());
				plan = true;
			}
		}
		final boolean layout = text(raw, LAYOUT, files), icons = text(raw, ICONS, files);
		return new Content(new Summary(info.getProperty("vknx", "?"), info.getProperty("created", ""), List.copyOf(devices), List.copyOf(skipped), plan, layout, icons), files);
	}

	private static boolean text(final Map<String, byte[]> raw, final String name, final Map<String, byte[]> files) {
		final byte[] b = raw.get(name);
		if (b == null)
			return false;
		if (b.length > MAX_JSON)
			throw new IllegalArgumentException("The file contains a too large " + name + ".");
		files.put(name, b);
		return true;
	}

	private static final Set<String> FIXED = Set.of(INFO, DEVICES, LAYOUT, ICONS);

	private static boolean known(final String n) {
		return FIXED.contains(n) || STATE_NAME.matcher(n).matches() || DataFiles.planFloor(n) >= 0;
	}

	private static IllegalArgumentException notOurs() {
		return new IllegalArgumentException("This is not a vKNX installation file (" + EXTENSION + ").");
	}

	private static byte[] readLimited(final InputStream in, final long room) throws IOException {
		final var out = new ByteArrayOutputStream();
		final byte[] buf = new byte[16384];
		long n = 0;
		for (int r; (r = in.read(buf)) > 0;) {
			n += r;
			if (n > room)
				throw new IllegalArgumentException("The file is too large when unpacked (more than " + (MAX_TOTAL >> 20) + " MB).");
			out.write(buf, 0, r);
		}
		return out.toByteArray();
	}
}
