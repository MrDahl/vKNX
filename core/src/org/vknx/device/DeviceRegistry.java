package org.vknx.device;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.vknx.product.Products;
import org.vknx.sim.Subnet;
import org.vknx.sim.Topology.Line;
import org.vknx.ui.EventLog;

/** The set of virtual devices of this simulator instance; added/removed from the control panel and remembered in devices.txt. */
public final class DeviceRegistry {
	/** Names that were allowed before 0.11.1: their state file keeps the name, so older installations still find it. */
	private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_-]{1,20}");
	/** A name must not look like the line suffix devices.txt appends ("@1.2!"). */
	private static final Pattern LOOKS_LIKE_LINE = Pattern.compile(".*@\\d{1,2}\\.\\d{1,2}!");
	public static final int MAX_NAME = 40;
	private final Path dir;
	private final Subnet net;
	private final EventLog log;
	private final List<VirtualDevice> devices = new CopyOnWriteArrayList<>();

	public DeviceRegistry(final Path dir, final Subnet net, final EventLog log) {
		this.dir = dir;
		this.net = net;
		this.log = log;
	}

	public List<VirtualDevice> devices() { return devices; }

	/** Data directory of this simulator instance (devices, floor plan, layout). */
	public Path dir() { return dir; }

	/** Restores the devices of an earlier run, or creates the defaults when there is nothing saved yet. */
	public void restoreOrDefault(final List<String> specs) throws Exception {
		final Path file = dir.resolve("devices.txt");
		final var list = new ArrayList<String>(specs);
		if (list.isEmpty() && Files.exists(file))
			list.addAll(Files.readAllLines(file).stream().map(String::trim).filter(l -> !l.isEmpty()).toList());
		// the defaults only on the very first start: an installation the user emptied on purpose stays empty
		if (list.isEmpty() && !Files.exists(file))
			list.addAll(List.of("push-button:Tryk1", "switch-actuator:Lampe1"));
		resetOldSchema();
		for (final String spec : list) {
			final String[] p = spec.split(":", 2);
			if (!Products.ALL.containsKey(p[0])) {
				log.add("device", p.length > 1 ? p[1] : p[0], "skipped: product " + p[0] + " no longer exists in this vKNX version (add the new product instead)");
				continue;
			}
			// "name" or "name@a.l!": a "!" marks a line the user chose by hand; without it the device is placed by its address.
			// (Versions 0.6.2/0.6.3 wrote "name@a.l" for every device, which pinned them to the interface line: ignored.)
			final String rest = p.length > 1 ? p[1] : p[0];
			final int at = rest.lastIndexOf('@'); // the name itself may contain an "@"
			final Line parsed = at < 0 || !rest.endsWith("!") ? null : parseLine(rest.substring(at + 1, rest.length() - 1));
			final String name = parsed == null ? rest : rest.substring(0, at);
			add(p[0], name, parsed);
		}
		save();
	}

	/** Version of the stored device state. Changed when products were redesigned: older state (download, parameters) is dropped. */
	private static final String SCHEMA = "4";

	private void resetOldSchema() throws IOException {
		final Path f = dir.resolve("state-schema.txt");
		if (Files.exists(f) && Files.readString(f).trim().equals(SCHEMA))
			return;
		boolean any = false;
		try (Stream<Path> s = Files.list(dir)) {
			for (final Path st : s.filter(x -> x.getFileName().toString().contains(".state")).toList()) {
				Files.deleteIfExists(st);
				any = true;
			}
		}
		if (any)
			log.add("device", "-", "stored devices from an older vKNX version were reset: addresses and downloads are gone, download them again from ETS");
		Files.writeString(f, SCHEMA);
	}

	public synchronized VirtualDevice add(final String productKey, final String name) throws Exception {
		return add(productKey, name, null);
	}

	/** @param where the line the device is wired to by hand; null = placed automatically by its address */
	public synchronized VirtualDevice add(final String productKey, final String name, final Line where) throws Exception {
		final var product = Products.ALL.get(productKey);
		if (product == null)
			throw new IllegalArgumentException("unknown product " + productKey);
		final String bad = checkName(name);
		if (bad != null)
			throw new IllegalArgumentException(bad);
		if (devices.stream().anyMatch(d -> d.name().equalsIgnoreCase(name)))
			throw new IllegalArgumentException("a device called " + name + " already exists");
		final var d = VirtualDevice.create(name, product, serialFor(name), dir.resolve(fileStem(name) + ".state"), net,
				(src, cat, text) -> log.add(cat, src.name(), text), where);
		devices.add(d);
		save();
		return d;
	}

	/**
	 * Any name is allowed: letters of any script, digits, spaces and special characters. Only what would break the
	 * storage is refused: empty names, line breaks and other control characters, leading or trailing blanks, more than
	 * {@value #MAX_NAME} characters. @return the problem in words, or null if the name is fine
	 */
	public static String checkName(final String name) {
		if (name == null || name.isEmpty())
			return "The name must not be empty.";
		if (!name.equals(name.strip()))
			return "The name must not start or end with a space.";
		if (name.codePointCount(0, name.length()) > MAX_NAME)
			return "The name can have at most " + MAX_NAME + " characters.";
		if (name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || c == 0xFFFD))
			return "The name must not contain line breaks or control characters.";
		if (LOOKS_LIKE_LINE.matcher(name).matches())
			return "The name must not end with a line suffix like @1.2!";
		return null;
	}

	/**
	 * Name of the state file (without ".state") for a device. Plain names (letters, digits, - and _, as before) are used
	 * unchanged; any other name gets its ASCII letters plus a short checksum, so that the file name is valid on every
	 * file system, the same name always gives the same file and different names give different files.
	 */
	public static String fileStem(final String name) {
		if (PLAIN.matcher(name).matches())
			return name;
		final var slug = new StringBuilder();
		for (final char c : name.toCharArray())
			if (slug.length() < 12 && (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'))
				slug.append(c);
		final var crc = new java.util.zip.CRC32();
		crc.update(name.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return (slug.length() == 0 ? "device" : slug) + "~" + String.format("%08x", crc.getValue());
	}

	public synchronized void remove(final String name) throws IOException {
		for (final var d : devices)
			if (d.name().equals(name)) {
				d.close();
				devices.remove(d);
				final String stem = fileStem(name);
				try (Stream<Path> s = Files.list(dir)) {
					for (final Path f : s.filter(f -> f.getFileName().toString().startsWith(stem + ".state")).toList())
						Files.deleteIfExists(f);
				}
				org.vknx.app.DataFiles.deleteBackupsOf(dir, stem);
				log.add("device", name, "removed (state files deleted)");
				save();
				return;
			}
	}

	/**
	 * Starts a new installation: every device is removed and created again in factory state (no address, no download,
	 * no keys; secure devices get a new factory key). The devices, their names and the line they are wired to stay.
	 */
	public synchronized void resetAll() throws Exception {
		final var keep = new ArrayList<Object[]>();
		for (final var d : devices)
			keep.add(new Object[] { d.product().key(), d.name(), d.location() });
		for (final var k : keep)
			remove((String) k[1]);
		for (final var k : keep)
			add((String) k[0], (String) k[1], (Line) k[2]);
		org.vknx.app.DataFiles.deleteBackups(dir, 0);
		log.add("device", "-", "new installation: " + keep.size() + " device(s) reset to factory state");
	}

	/** Wires a device to another line, as when moving it to another distribution board. */
	/** @param line the line, or null to place the device by its address again */
	public synchronized void setLocation(final String name, final Line line) throws IOException {
		for (final var d : devices)
			if (d.name().equals(name)) {
				d.setLocation(line);
				save();
				return;
			}
		throw new IllegalArgumentException("no such device");
	}

	public static Line parseLine(final String text) {
		final var m = LINE.matcher(text.trim());
		if (!m.matches())
			return null;
		final int a = Integer.parseInt(m.group(1)), l = Integer.parseInt(m.group(2));
		return a <= 15 && l <= 15 ? new Line(a, l) : null;
	}

	private static final Pattern LINE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");

	public void closeAll() { devices.forEach(VirtualDevice::close); }

	private void save() throws IOException {
		Files.write(dir.resolve("devices.txt"),
				devices.stream().map(d -> d.product().key() + ":" + d.name() + (d.location() == null ? "" : "@" + d.location() + "!")).toList());
	}

	static byte[] serialFor(final String name) {
		final int h = name.hashCode();
		return new byte[] { 0x00, (byte) 0xFA, (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h };
	}
}
