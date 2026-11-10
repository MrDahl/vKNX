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
			final var sp = parseSpec(spec);
			if (!Products.ALL.containsKey(sp.product())) {
				log.add("device", sp.name(), "skipped: product " + sp.product() + " no longer exists in this vKNX version (add the new product instead)");
				continue;
			}
			add(sp.product(), sp.name(), sp.line());
		}
		save();
	}

	/** One line of devices.txt: the product, the name and the line the device was wired to by hand (null = placed by its address). */
	record Spec(String product, String name, Line line) {}

	/**
	 * Reads "product:name" or "product:name@a.l!": a "!" marks a line the user chose by hand; without it the device is
	 * placed by its address. (Versions 0.6.2/0.6.3 wrote "name@a.l" for every device, which pinned them to the interface
	 * line: ignored.)
	 */
	static Spec parseSpec(final String spec) {
		final String[] p = spec.split(":", 2);
		final String rest = p.length > 1 ? p[1] : p[0];
		final int at = rest.lastIndexOf('@'); // the name itself may contain an "@"
		final Line parsed = at < 0 || !rest.endsWith("!") ? null : parseLine(rest.substring(at + 1, rest.length() - 1));
		return new Spec(p[0], parsed == null ? rest : rest.substring(0, at), parsed);
	}

	static String specLine(final String product, final String name, final Line line) {
		return product + ":" + name + (line == null ? "" : "@" + line + "!");
	}

	/** Version of the stored device state. Changed when products were redesigned: older state (download, parameters) is dropped. */
	static final String SCHEMA = "4";

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
	 * Gives a device another name. Address, download, parameters and keys are kept (they are in the state file, which is
	 * moved to the new name); the device is closed and started again, exactly as at a restart, so what is only live
	 * (lamp on or off, blind position) starts from its default. The place on the floor plan and the chosen icons follow
	 * the new name. If anything goes wrong the device is put back under its old name.
	 */
	public synchronized void rename(final String oldName, final String newName) throws Exception {
		final var d = devices.stream().filter(x -> x.name().equals(oldName)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("there is no device called " + oldName));
		if (oldName.equals(newName))
			return;
		final String bad = checkName(newName);
		if (bad != null)
			throw new IllegalArgumentException(bad);
		if (devices.stream().anyMatch(x -> x != d && x.name().equalsIgnoreCase(newName)))
			throw new IllegalArgumentException("a device called " + newName + " already exists");
		final var product = d.product();
		final var where = d.location();
		final int index = devices.indexOf(d);
		final String oldStem = fileStem(oldName), newStem = fileStem(newName);
		d.saveState();
		d.close();
		devices.remove(d);
		boolean moved = false;
		try {
			if (!oldStem.equals(newStem)) {
				if (!oldStem.equalsIgnoreCase(newStem)) {
					try (Stream<Path> s = Files.list(dir)) { // leftovers under the new name must not be mistaken for this device
						for (final Path f : s.filter(f -> f.getFileName().toString().startsWith(newStem + ".state")).toList())
							Files.deleteIfExists(f);
					}
					org.vknx.app.DataFiles.deleteBackupsOf(dir, newStem);
				}
				moveState(oldStem, newStem);
				moved = true;
			}
			final var nd = VirtualDevice.create(newName, product, serialFor(newName), dir.resolve(newStem + ".state"), net,
					(src, cat, text) -> log.add(cat, src.name(), text), where);
			devices.add(index, nd);
		}
		catch (final Exception e) { // put the device back under its old name
			if (moved)
				moveState(newStem, oldStem);
			devices.add(index, VirtualDevice.create(oldName, product, serialFor(oldName), dir.resolve(oldStem + ".state"), net,
					(src, cat, text) -> log.add(cat, src.name(), text), where));
			throw e;
		}
		renameInJson(dir.resolve("plan-layout.json"), oldName, newName, false);
		renameInJson(dir.resolve("icons.json"), oldName, newName, true);
		save();
		log.add("device", newName, "renamed from " + oldName);
	}

	private void moveState(final String from, final String to) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			for (final Path f : s.filter(f -> f.getFileName().toString().startsWith(from + ".state")).toList())
				Files.move(f, dir.resolve(to + f.getFileName().toString().substring(from.length())),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		org.vknx.app.DataFiles.renameBackups(dir, from, to);
	}

	/**
	 * The floor plan layout and the icons refer to a device by its name ("name|channel", icons also "name" alone).
	 * Both files are kept as text for the browser, so the names are replaced in the text.
	 */
	private static void renameInJson(final Path file, final String from, final String to, final boolean exactKeys) throws IOException {
		if (!Files.exists(file))
			return;
		final String qf = java.util.regex.Pattern.quote(jsonEscape(from));
		final String qt = java.util.regex.Matcher.quoteReplacement(jsonEscape(to));
		String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
		// layout: only the value of "id" (a label the user typed that looks like an id stays); icons: only the keys
		text = exactKeys
				? text.replaceAll("\"" + qf + "((?:\\|(?:[^\"\\\\]|\\\\.)*)?\"\\s*:)", "\"" + qt + "$1")
				: text.replaceAll("(\"id\"\\s*:\\s*\")" + qf + "\\|", "$1" + qt + "|");
		Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static String jsonEscape(final String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

	/** The lines of devices.txt for the devices as they are now. */
	List<String> specLines() {
		return devices.stream().map(d -> specLine(d.product().key(), d.name(), d.location())).toList();
	}

	/** Writes the state of every running device to its state file (used when the installation is exported). */
	public synchronized void saveAll() {
		devices.forEach(VirtualDevice::saveState);
	}

	/**
	 * Replaces the whole installation (devices with their state, floor plan, layout, icons) by the content of an
	 * installation file. The current devices are closed and their files deleted first; the imported devices are then
	 * restored from the imported state files, exactly as after a restart.
	 */
	public synchronized void importInstallation(final InstallationFile.Content c) throws Exception {
		for (final var d : devices)
			d.close();
		devices.clear();
		try (Stream<Path> s = Files.list(dir)) {
			for (final Path f : s.filter(Files::isRegularFile).toList()) {
				final String n = f.getFileName().toString();
				if (n.contains(".state") || n.equals("devices.txt") || org.vknx.app.DataFiles.isPlan(n))
					Files.delete(f);
			}
		}
		for (final var e : c.files().entrySet())
			Files.write(dir.resolve(e.getKey()), e.getValue());
		Files.writeString(dir.resolve("state-schema.txt"), SCHEMA);
		restoreOrDefault(List.of());
		log.add("device", "-", "installation imported: " + devices.size() + " device(s)");
	}


	private void save() throws IOException {
		Files.write(dir.resolve("devices.txt"), specLines());
	}

	static byte[] serialFor(final String name) {
		final int h = name.hashCode();
		return new byte[] { 0x00, (byte) 0xFA, (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h };
	}
}
