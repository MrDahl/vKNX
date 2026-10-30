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
import org.vknx.ui.EventLog;

/** The set of virtual devices of this simulator instance; added/removed from the control panel and remembered in devices.txt. */
public final class DeviceRegistry {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,20}");
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
		if (list.isEmpty())
			list.addAll(List.of("push-button:Tryk1", "switch-actuator:Lampe1"));
		resetOldSchema();
		for (final String spec : list) {
			final String[] p = spec.split(":", 2);
			if (!Products.ALL.containsKey(p[0])) {
				log.add("device", p.length > 1 ? p[1] : p[0], "skipped: product " + p[0] + " no longer exists in this vKNX version (add the new product instead)");
				continue;
			}
			add(p[0], p.length > 1 ? p[1] : p[0]);
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
		final var product = Products.ALL.get(productKey);
		if (product == null)
			throw new IllegalArgumentException("unknown product " + productKey);
		if (!NAME.matcher(name).matches())
			throw new IllegalArgumentException("name must be 1-20 letters, digits, - or _");
		if (devices.stream().anyMatch(d -> d.name().equalsIgnoreCase(name)))
			throw new IllegalArgumentException("a device called " + name + " already exists");
		final var d = VirtualDevice.create(name, product, serialFor(name), dir.resolve(name + ".state"), net,
				(src, cat, text) -> log.add(cat, src.name(), text));
		devices.add(d);
		save();
		return d;
	}

	public synchronized void remove(final String name) throws IOException {
		for (final var d : devices)
			if (d.name().equals(name)) {
				d.close();
				devices.remove(d);
				try (Stream<Path> s = Files.list(dir)) {
					for (final Path f : s.filter(f -> f.getFileName().toString().startsWith(name + ".state")).toList())
						Files.deleteIfExists(f);
				}
				log.add("device", name, "removed (state files deleted)");
				save();
				return;
			}
	}

	public void closeAll() { devices.forEach(VirtualDevice::close); }

	private void save() throws IOException {
		Files.write(dir.resolve("devices.txt"),
				devices.stream().map(d -> d.product().key() + ":" + d.name()).toList());
	}

	static byte[] serialFor(final String name) {
		final int h = name.hashCode();
		return new byte[] { 0x00, (byte) 0xFA, (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h };
	}
}
