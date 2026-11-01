package org.vknx.device;

import static io.calimero.mgmt.PropertyAccess.PID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.vknx.product.ObjDef;
import org.vknx.product.ParamDef;
import org.vknx.product.ProductDef;
import org.vknx.product.Products;

import io.calimero.DeviceDescriptor.DD0;
import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.KNXException;
import io.calimero.Priority;
import io.calimero.SerialNumber;
import io.calimero.datapoint.Datapoint;
import io.calimero.device.BaseKnxDevice;
import io.calimero.device.KnxDeviceServiceLogic;
import io.calimero.device.ServiceResult;
import io.calimero.dptxlator.DPTXlator;
import io.calimero.link.KNXNetworkLink;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.TransportLayer;
import io.calimero.process.ProcessEvent;

/**
 * One virtual KNX device: a Calimero System B (mask 0x07B0) device whose application behaviour is driven
 * <em>only</em> by what ETS downloaded into its memory (address table, association table, group object
 * table and parameter area).
 *
 * <p>Calimero implements the KNX management services (individual address, load state machine, memory and
 * property access, restart). It does <em>not</em> interpret application programs, so this class adds:
 * relative-allocation handling of the load state machine, the memory-control-block checksum, parsing of the
 * downloaded tables and the product-specific behaviour.
 */
public final class VirtualDevice extends KnxDeviceServiceLogic implements AutoCloseable {

	/** Receives a human-readable trace of everything this device does and why. */
	public interface Listener {
		void log(VirtualDevice source, String category, String text);

		/** The device state visible in the UI changed (lamp, programming LED, download state ...). */
		default void changed(final VirtualDevice source) {}
	}

	// IOS object indexes of a System B device as created by Calimero (and as seen in the Zennio capture)
	static final int OBJ_ADDRESS_TABLE = 1;
	static final int OBJ_ASSOC_TABLE = 2;
	static final int OBJ_GROUP_OBJECT_TABLE = 3;
	static final int OBJ_APPLICATION = 4;

	// Fixed memory slots for relative allocation. Capacity is checked, so an oversize request fails visibly.
	private static final int[] SLOT_ADDRESS = { 0, 0x0400, 0x0800, 0x0C00, 0x1000 };
	private static final int[] SLOT_CAPACITY = { 0, 0x0400, 0x0400, 0x0400, 0x1000 };

	// load state machine states (values as defined for PID_LOAD_STATE_CONTROL)
	static final int LS_UNLOADED = 0, LS_LOADED = 1, LS_LOADING = 2, LS_ERROR = 3;

	// load events
	private static final int LE_NOOP = 0, LE_START_LOADING = 1, LE_LOAD_COMPLETED = 2, LE_ADDITIONAL = 3,
			LE_UNLOAD = 4;

	private final String name;
	private final ProductDef product;
	private final Path stateFile;
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final int[] allocatedSize = new int[5];

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread t = new Thread(r, "vknx-timer");
		t.setDaemon(true);
		return t;
	});
	private BaseKnxDevice base;
	private DeviceBehavior behavior;
	private volatile Tables tables = Tables.EMPTY;
	private volatile int downloadCount;
	private org.vknx.sim.Subnet subnet;

	private VirtualDevice(final String name, final ProductDef product, final Path stateFile) {
		this.name = name;
		this.product = product;
		this.stateFile = stateFile;
	}

	/**
	 * Creates a device, restoring its persisted state from {@code stateFile} if that file exists.
	 *
	 * @param uplink the shared virtual subnet
	 * @param serial 6-byte KNX serial number, unique per device
	 */
	public static VirtualDevice create(final String name, final ProductDef product, final byte[] serial,
			final Path stateFile, final org.vknx.sim.Subnet uplink, final Listener listener)
			throws KNXException, IOException {
		return create(name, product, serial, stateFile, uplink, listener, null);
	}

	/**
	 * @param location the line the device is wired to (for a coupler: the line it serves, its main line is the parent);
	 *        null = placed by its address (older behaviour, used by tests)
	 */
	public static VirtualDevice create(final String name, final ProductDef product, final byte[] serial,
			final Path stateFile, final org.vknx.sim.Subnet uplink, final Listener listener,
			final org.vknx.sim.Topology.Line location) throws KNXException, IOException {
		final boolean restored = Files.exists(stateFile);
		final var logic = new VirtualDevice(name, product, stateFile);
		if (product.secure()) {
			SECURE_DEVICES.put(name, logic);
			installSecureLogBridge();
		}
		logic.subnet = uplink;
		logic.location = location;
		if (listener != null)
			logic.listeners.add(listener);
		logic.behavior = DeviceBehavior.forProduct(product, logic);

		final var device = new BaseKnxDevice(name, product.coupler() ? DD0.TYPE_0912 : DD0.TYPE_07B0, logic, logic,
				stateFile.toUri(), new char[0]);
		logic.base = device;
		if (!restored)
			logic.initFreshIdentity(serial);
		if (product.coupler())
			logic.initRouter(!restored);

		// factory state: unprogrammed address 15.15.255; restored state: the address ETS assigned before
		final IndividualAddress startAddress = restored
				? io.calimero.device.ios.DeviceObject.lookup(device.getInterfaceObjectServer()).deviceAddress()
				: new IndividualAddress(0xFFFF);
		final KNXNetworkLink link = uplink.createDeviceLink(startAddress, logic::physicalLines);
		device.setDeviceLink(link);
		if (product.secure()) // secure management telegrams (key tables) are longer than the 15 bytes of a standard frame
			io.calimero.device.ios.DeviceObject.lookup(device.getInterfaceObjectServer()).set(PID.MAX_APDULENGTH, (byte) 0, (byte) 254);
		logic.log("device", "created " + product.name() + (restored ? " (state restored)" : " (factory state)")
				+ ", address " + device.getAddress());
		logic.afterTablesChanged();
		logic.startApplication();
		if (logic.behavior instanceof final CouplerBehavior cb)
			uplink.topology().register(cb);
		return logic;
	}

	// ---------------------------------------------------------------- identity

	private void initFreshIdentity(final byte[] serial) {
		final var ios = base.getInterfaceObjectServer();
		final var dev = io.calimero.device.ios.DeviceObject.lookup(ios);
		dev.set(PID.MANUFACTURER_ID, (byte) (Products.MANUFACTURER_ID >> 8), (byte) Products.MANUFACTURER_ID);
		dev.set(PID.SERIAL_NUMBER, serial);
		dev.set(78, product.hardwareType(Products.MANUFACTURER_ID)); // PID_HARDWARE_TYPE
		final byte[] order = new byte[10];
		final byte[] on = product.orderNumber().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		System.arraycopy(on, 0, order, 0, Math.min(10, on.length));
		dev.set(PID.ORDER_INFO, order);
		for (int obj = OBJ_ADDRESS_TABLE; obj <= OBJ_APPLICATION; obj++) {
			ios.setProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1, (byte) LS_UNLOADED);
			ios.setProperty(obj, PID.TABLE_REFERENCE, 1, 1, intBytes(SLOT_ADDRESS[obj]));
		}
		ios.setProperty(OBJ_APPLICATION, PID.PROGRAM_VERSION, 1, 1, new byte[5]);
		if (product.secure()) {
			// KNX Data Secure: the factory default setup key (FDSK) ETS needs for the first, secure download
			final byte[] fdsk = new byte[16];
			new java.security.SecureRandom().nextBytes(fdsk);
			final var sec = io.calimero.device.ios.SecurityObject.lookup(ios);
			sec.set(io.calimero.device.ios.SecurityObject.Pid.Fdsk, fdsk);
			// start the sending sequence number above 1: a device that starts at 1 first asks another device for a
			// sync (S-A_Sync.req) before it can send a group telegram, and no such partner is known yet
			final long seq = 2 + new java.security.SecureRandom().nextInt(1_000_000);
			final byte[] seqBytes = java.nio.ByteBuffer.allocate(8).putLong(seq).array();
			final byte[] six = java.util.Arrays.copyOfRange(seqBytes, 2, 8);
			sec.set(io.calimero.device.ios.SecurityObject.Pid.SequenceNumberSending, six);
			sec.set(io.calimero.device.ios.SecurityObject.Pid.InitialSequenceNumberSending, six);
		}
	}

	// ---------------------------------------------------------------- KNX Data Secure

	private io.calimero.device.ios.SecurityObject securityObject() {
		return io.calimero.device.ios.SecurityObject.lookup(base.getInterfaceObjectServer());
	}

	/**
	 * Master reset of a secure device (ETS does this when "Secure commissioning" is switched off or on): the tool key,
	 * group keys, group object security flags and security mode are deleted, so the factory key (FDSK) is in use again.
	 * The FDSK and serial number themselves never change.
	 */
	private void resetSecurity() {
		final var sec = securityObject();
		sec.set(io.calimero.device.ios.SecurityObject.Pid.SecurityMode, 1, 1, (byte) 0);
		sec.set(io.calimero.device.ios.SecurityObject.Pid.ToolKey, new byte[16]);
		sec.clear(io.calimero.device.ios.SecurityObject.Pid.GroupKeyTable);
		sec.clear(io.calimero.device.ios.SecurityObject.Pid.P2PKeyTable);
		sec.clear(io.calimero.device.ios.SecurityObject.Pid.SecurityIndividualAddressTable);
		sec.clear(io.calimero.device.ios.SecurityObject.Pid.GoSecurityFlags);
		log("mgmt", "security reset: keys deleted, factory key (FDSK) in use again");
	}

	private static final int[] KEPT_SECURITY_PIDS = {
			io.calimero.device.ios.SecurityObject.Pid.GroupKeyTable, io.calimero.device.ios.SecurityObject.Pid.ZoneKeyTable,
			io.calimero.device.ios.SecurityObject.Pid.GoSecurityFlags, io.calimero.device.ios.SecurityObject.Pid.P2PKeyTable,
			io.calimero.device.ios.SecurityObject.Pid.SecurityIndividualAddressTable };

	/** Tool key, security mode and the key/flag tables: {pid, element count, data}. */
	private java.util.List<Object[]> snapshotSecurity() {
		final var sec = securityObject();
		final var out = new java.util.ArrayList<Object[]>();
		out.add(new Object[] { io.calimero.device.ios.SecurityObject.Pid.ToolKey, 1, sec.get(io.calimero.device.ios.SecurityObject.Pid.ToolKey) });
		out.add(new Object[] { io.calimero.device.ios.SecurityObject.Pid.SecurityMode, 1, sec.get(io.calimero.device.ios.SecurityObject.Pid.SecurityMode) });
		for (final int pid : KEPT_SECURITY_PIDS) {
			try {
				final byte[] n = sec.get(pid, 0, 1);
				final int count = ((n[0] & 0xff) << 8) | (n[1] & 0xff);
				out.add(new Object[] { pid, count, count == 0 ? new byte[0] : sec.get(pid) });
			}
			catch (final RuntimeException e) { /* property not there: nothing to keep */ }
		}
		return out;
	}

	private void restoreSecurity(final java.util.List<Object[]> kept) {
		final var sec = securityObject();
		for (final var k : kept) {
			final int pid = (Integer) k[0];
			final int count = (Integer) k[1];
			final byte[] data = (byte[]) k[2];
			try {
				if (count == 0)
					continue;
				if (java.util.Arrays.equals(sec.get(pid), data))
					continue;
				sec.set(pid, 1, count, data);
			}
			catch (final RuntimeException e) { log("mgmt", "could not keep security property " + pid + ": " + e); }
		}
	}

	/** The factory default setup key as 32 hex digits, or "" for a device that is not a secure product. */
	public String fdskHex() {
		return product.secure() ? HexFormat.of().withUpperCase().formatHex(securityObject().get(io.calimero.device.ios.SecurityObject.Pid.Fdsk)) : "";
	}

	/** The 6-byte KNX serial number as 12 hex digits (ETS shows and asks for it together with the FDSK). */
	public String serialHex() { return HexFormat.of().withUpperCase().formatHex(serialNumber().array()); }

	/**
	 * The device certificate ETS asks for ("Add Device Certificate"): serial number (6 bytes) + FDSK (16 bytes) as
	 * Base32 (A-Z, 2-7) plus a 4-bit CRC, 36 characters in six blocks of six. ETS does not accept 0 or 1, so the hex FDSK cannot be typed.
	 * Verified against the example in the KNX Secure course (serial 0007:EE952A16 = AAD65F-JKCY6F-...).
	 */
	public static String deviceCertificate(final byte[] serial, final byte[] fdsk) {
		final byte[] all = new byte[serial.length + fdsk.length];
		System.arraycopy(serial, 0, all, 0, serial.length);
		System.arraycopy(fdsk, 0, all, serial.length, fdsk.length);
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		final var sb = new StringBuilder();
		int buffer = 0, bits = 0;
		// 4-bit CRC (x^4 + x + 1, start value 0, no reflection) over serial + FDSK, appended as the last 4 bits
		int crc = 0;
		for (final byte b : all)
			for (int i = 7; i >= 0; i--) {
				final boolean feedback = (crc >> 3 & 1 ^ b >> i & 1) != 0;
				crc = crc << 1 & 0xF;
				if (feedback)
					crc ^= 0x3;
			}
		final byte[] withCrc = java.util.Arrays.copyOf(all, all.length + 1);
		withCrc[all.length] = (byte) (crc << 4);
		for (final byte b : withCrc) {
			buffer = buffer << 8 | b & 0xff;
			bits += 8;
			while (bits >= 5) {
				sb.append(alphabet.charAt(buffer >> bits - 5 & 31));
				bits -= 5;
			}
		}
		final var out = new StringBuilder();
		for (int i = 0; i < sb.length(); i += 6) {
			if (i > 0)
				out.append(" - ");
			out.append(sb, i, Math.min(sb.length(), i + 6));
		}
		return out.toString();
	}

	/** This device's certificate code (see {@link #deviceCertificate}), or "" for a plain product. */
	public String certificate() {
		return product.secure() ? deviceCertificate(serialNumber().array(),
				securityObject().get(io.calimero.device.ios.SecurityObject.Pid.Fdsk)) : "";
	}

	// ---- what the secure layer does, in the device's own log (for finding out why ETS and a device do not agree)

	private static final java.util.Map<String, VirtualDevice> SECURE_DEVICES = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile boolean secureLogInstalled;

	private static synchronized void installSecureLogBridge() {
		if (secureLogInstalled)
			return;
		secureLogInstalled = true;
		final var handler = new java.util.logging.Handler() {
			@Override
			public void publish(final java.util.logging.LogRecord r) {
				final String logger = r.getLoggerName();
				if (logger == null)
					return;
				for (final var e : SECURE_DEVICES.entrySet())
					if (logger.endsWith(" " + e.getKey())) {
						String msg = r.getMessage();
						try {
							if (r.getParameters() != null && r.getParameters().length > 0)
								msg = java.text.MessageFormat.format(msg, r.getParameters());
						}
						catch (final RuntimeException ignore) { /* use the raw text */ }
						if (r.getThrown() != null)
							msg += " (" + r.getThrown().getMessage() + ")";
						e.getValue().log("secure", msg);
					}
			}
			@Override public void flush() {}
			@Override public void close() {}
		};
		handler.setLevel(java.util.logging.Level.ALL);
		for (final String n : new String[] { "io.calimero.device", "io.calimero.secure" }) {
			final var l = java.util.logging.Logger.getLogger(n);
			l.setLevel(java.util.logging.Level.FINE);
			l.addHandler(handler);
		}
	}

	/** The serial number the way ETS shows it: 2 bytes, colon, 4 bytes (e.g. 00FA:00000033). */
	public String serialEts() { final String s = serialHex(); return s.substring(0, 4) + ":" + s.substring(4); }

	/** True once ETS has switched on security mode (the secure download completed far enough). */
	public boolean securityModeOn() {
		return product.secure() && (securityObject().get(io.calimero.device.ios.SecurityObject.Pid.SecurityMode, 1, 1)[0] & 0xff) == 1;
	}

	/** True once ETS replaced the factory key with its own tool key: the FDSK is no longer used. */
	public boolean toolKeyActive() {
		if (!product.secure())
			return false;
		for (final byte b : securityObject().get(io.calimero.device.ios.SecurityObject.Pid.ToolKey))
			if (b != 0)
				return true;
		return false;
	}

	public String name() { return name; }

	public ProductDef product() { return product; }

	public IndividualAddress address() { return base.getAddress(); }

	// ---------------------------------------------------------------- physical placement

	private volatile org.vknx.sim.Topology.Line location;
	private volatile org.vknx.sim.Topology.Line[] autoLines;

	/** The line this device is wired to, or null if it is placed by its address. */
	public org.vknx.sim.Topology.Line location() { return location; }

	/** Wires the device to another line (a move in the building, not an address change). */
	public void setLocation(final org.vknx.sim.Topology.Line line) {
		location = line;
		if (subnet != null)
			subnet.topology().changed();
		log("device", "wired to line " + (line == null ? "(by address)" : line));
		changed();
	}

	/** The line the device sits on right now (for a coupler: its sub line). */
	public org.vknx.sim.Topology.Line placement() { return physicalLines()[0]; }

	/** The line(s) the device hears and sends on: one, or two for a coupler (its sub line and the main line). */
	org.vknx.sim.Topology.Line[] physicalLines() {
		final var l = location;
		if (l == null) {
			// placed by address; a device that loses its address stays where it was (it is not unplugged)
			final var a = address();
			if (!org.vknx.sim.Topology.unaddressed(a))
				autoLines = subnet.topology().linesOf(a);
			else if (autoLines != null)
				return autoLines;
			return subnet.topology().linesOf(a);
		}
		if (product.coupler()) {
			final var parent = l.parent();
			return parent == null ? new org.vknx.sim.Topology.Line[] { l } : new org.vknx.sim.Topology.Line[] { l, parent };
		}
		return new org.vknx.sim.Topology.Line[] { l };
	}

	/** The sub line of a coupler: where it is wired, or (without placement) the line of its x.y.0 address; null if none. */
	org.vknx.sim.Topology.Line couplerSubLine() {
		final var l = location;
		if (l != null)
			return l;
		final var a = address();
		if (org.vknx.sim.Topology.unaddressed(a) && autoLines != null && autoLines.length > 0)
			return autoLines[0];
		return a.getDevice() == 0 && a.getRawAddress() != 0 && !org.vknx.sim.Topology.unaddressed(a) ? new org.vknx.sim.Topology.Line(a.getArea(), a.getLine()) : null;
	}

	public SerialNumber serialNumber() {
		return io.calimero.device.ios.DeviceObject.lookup(base.getInterfaceObjectServer()).serialNumber();
	}

	public boolean programmingMode() { return inProgrammingMode(); }

	public void addListener(final Listener l) { listeners.add(l); }

	// ---------------------------------------------------------------- programming mode

	/** The programming button on the virtual device. */
	public void toggleProgrammingMode() {
		final boolean now = !inProgrammingMode();
		setProgrammingMode(now);
		log("programming", "programming button pressed: programming mode " + (now ? "ON" : "OFF"));
		changed();
	}

	@Override
	public void setProgrammingMode(final boolean inProgrammingMode) {
		super.setProgrammingMode(inProgrammingMode);
		changed();
	}

	// ---------------------------------------------------------------- management services (ETS side)

	@Override
	public ServiceResult<Boolean> readAddress() {
		final var r = super.readAddress();
		log("mgmt", "A_IndividualAddress_Read -> " + (Boolean.TRUE.equals(r.result()) ? "answering (programming mode)"
				: "silent (not in programming mode)"));
		return r;
	}

	@Override
	public void writeAddress(final IndividualAddress newAddress) {
		if (inProgrammingMode()) {
			log("mgmt", "A_IndividualAddress_Write " + newAddress + " accepted (programming mode on)");
			super.writeAddress(newAddress);
			// real devices leave programming mode after an address assignment sequence ends (ETS restarts them)
			changed();
		}
		else
			log("mgmt", "A_IndividualAddress_Write " + newAddress + " ignored: not in programming mode");
	}

	@Override
	public ServiceResult<java.time.Duration> restart(final boolean masterReset,
			final io.calimero.mgmt.ManagementClient.EraseCode eraseCode, final int channel) {
		log("mgmt", "A_Restart (" + (masterReset ? "master reset " + eraseCode : "basic restart") + ")");
		// ETS sends "factory reset without address" (code 7) when it deactivates secure commissioning, and goes on talking
		// with the tool key afterwards. Calimero deletes tool key and tables on that code (after the answer has gone out),
		// so the device would no longer answer ETS. Keep what is there: snapshot now, put it back once Calimero is done.
		final boolean keepSecurity = masterReset && product.secure()
				&& eraseCode == io.calimero.mgmt.ManagementClient.EraseCode.FactoryResetWithoutIndividualAddress;
		final var kept = keepSecurity ? snapshotSecurity() : null;
		final var r = super.restart(masterReset, eraseCode, channel);
		if (kept != null) {
			restoreSecurity(kept);
			final var t = new Thread(() -> {
				for (int i = 0; i < 4; i++) {
					try { Thread.sleep(400); } catch (final InterruptedException e) { return; }
					restoreSecurity(kept);
				}
				changed();
			}, "vknx-secure-keep-" + name);
			t.setDaemon(true);
			t.start();
		}
		// ETS ends a secure download with a master reset of type "confirmed restart": that only restarts and keeps the
		// keys. Factory reset without address (code 7) is what ETS sends when it deactivates secure commissioning; it
		// goes on talking with the tool key afterwards (to switch security mode off), so it keeps the keys too.
		// Only a full factory reset deletes them, and only after the answer (still sealed with the old key) has gone out.
		if (masterReset && product.secure() && eraseCode == io.calimero.mgmt.ManagementClient.EraseCode.FactoryReset) {
			final var t = new Thread(() -> {
				try { Thread.sleep(500); } catch (final InterruptedException e) { return; }
				resetSecurity();
				changed();
			}, "vknx-secure-reset-" + name);
			t.setDaemon(true);
			t.start();
		}
		afterTablesChanged();
		startApplication();
		changed();
		return r;
	}

	@Override
	public ServiceResult<byte[]> readProperty(final Destination remote, final int objectIndex, final int propertyId,
			final int startIndex, final int elements) {
		if (product.coupler()) {
			final byte[] v = couplerProperty(objectIndex, propertyId);
			if (v != null) {
				log("download", "read router property obj " + objectIndex + " pid " + propertyId + " -> "
						+ HexFormat.of().formatHex(v));
				return ServiceResult.of(startIndex == 0 ? new byte[] { 0, 1 } : v);
			}
		}
		if (propertyId == PID.MCB_TABLE && objectIndex >= OBJ_ADDRESS_TABLE && objectIndex <= OBJ_APPLICATION
				&& startIndex >= 1) {
			final byte[] mcb = memoryControlBlock(objectIndex);
			log("download", "read MCB_TABLE obj " + objectIndex + " -> " + HexFormat.of().formatHex(mcb));
			return ServiceResult.of(mcb);
		}
		return super.readProperty(remote, objectIndex, propertyId, startIndex, elements);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ServiceResult<Void> writeProperty(final Destination remote, final int objectIndex, final int propertyId,
			final int startIndex, final int elements, final byte[] data) {
		if (product.coupler() && couplerProperty(objectIndex, propertyId) != null) {
			if (objectIndex == routerIndex && propertyId == PID.LOAD_STATE_CONTROL)
				return (ServiceResult<Void>) (Object) new ServiceResult<byte[]>(couplerLoadControl(data));
			setCouplerProperty(objectIndex, propertyId, data);
			return (ServiceResult<Void>) (Object) new ServiceResult<byte[]>(new byte[0]); // empty result, the notifier echoes the data
		}
		if (propertyId == PID.LOAD_STATE_CONTROL && objectIndex >= OBJ_ADDRESS_TABLE && objectIndex <= OBJ_APPLICATION) {
			final byte state = loadControl(objectIndex, data);
			// the service notifier answers a LOAD_STATE_CONTROL write with the new state byte
			return (ServiceResult<Void>) (Object) new ServiceResult<byte[]>(state);
		}
		log("download", "A_PropertyValue_Write obj " + objectIndex + " pid " + propertyId + " ["
				+ HexFormat.of().formatHex(data) + "]");
		return super.writeProperty(remote, objectIndex, propertyId, startIndex, elements, data);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ServiceResult<byte[]> functionPropertyCommand(final Destination remote, final int objectIndex,
			final int propertyId, final byte[] command) {
		if (product.coupler() && objectIndex == routerIndex) {
			if (propertyId == PID.LOAD_STATE_CONTROL)
				return new ServiceResult<>(couplerLoadControl(java.util.Arrays.copyOfRange(command, 1, command.length)));
			if (propertyId == PID_ROUTETABLE_CONTROL)
				return routeTableControl(command);
		}
		if (propertyId == PID.LOAD_STATE_CONTROL && objectIndex >= OBJ_ADDRESS_TABLE
				&& objectIndex <= OBJ_APPLICATION) {
			// function property command: first byte is the return code slot, load event follows
			final byte[] event = java.util.Arrays.copyOfRange(command, 1, command.length);
			return new ServiceResult<>(loadControl(objectIndex, event));
		}
		return super.functionPropertyCommand(remote, objectIndex, propertyId, command);
	}

	@Override
	public ServiceResult<io.calimero.mgmt.Description> readPropertyDescription(final int objectIndex, final int propertyId,
			final int propertyIndex) {
		if (product.coupler() && propertyId > 0 && couplerProperty(objectIndex, propertyId) != null) {
			final int pdt = propertyId == PID.LOAD_STATE_CONTROL ? 0 : propertyId == PID_MAX_ROUTING_APDU ? 4 : 2;
			final boolean writable = propertyId != PID_MAX_ROUTING_APDU;
			final int type = objectIndex == routerIndex ? 6 : 0;
			return ServiceResult.of(new io.calimero.mgmt.Description(objectIndex, type, propertyId, 0, pdt, writable, 1, 1, 3, 3));
		}
		return super.readPropertyDescription(objectIndex, propertyId, propertyIndex);
	}

	@Override
	public ServiceResult<byte[]> readMemory(final int startAddress, final int bytes) {
		if (product.coupler() && startAddress >= 0 && startAddress + bytes < base.deviceMemory().size())
			return ServiceResult.of(base.deviceMemory().get(startAddress, bytes));
		return super.readMemory(startAddress, bytes);
	}

	@Override
	public ServiceResult<Void> writeMemory(final int startAddress, final byte[] data) {
		final var r = super.writeMemory(startAddress, data);
		if (product.coupler() && startAddress >= FILTER_TABLE && startAddress < FILTER_TABLE + FILTER_SIZE)
			changed();
		log("download", String.format("memory write 0x%06X, %d bytes -> %s", startAddress, data.length,
				r.getResult() == null ? "ok" : "ok"));
		return r;
	}

	// ---------------------------------------------------------------- coupler filter table, legacy services

	private static final int A_OPEN_ROUTING_TABLE = 0x3C0, A_READ_ROUTING_TABLE = 0x3C1, A_READ_ROUTING_TABLE_RES = 0x3C2,
			A_WRITE_ROUTING_TABLE = 0x3C3;

	/**
	 * ETS writes the group filter table of a 0912 coupler with A_Write_Routing_Table (0x3C3) after A_Open_Routing_Table
	 * (0x3C0), and verifies it with A_Read_Routing_Table_Res (0x3C2): the mask has VerifyMode on, so every write is
	 * answered with the data now in memory. Without this answer ETS waits, repeats the write and fails the download
	 * ("Device does not respond"), leaving the coupler in "download in progress".
	 */
	@Override
	public ServiceResult<byte[]> management(final int svcType, final byte[] asdu, final KNXAddress dst,
			final Destination respondTo, final TransportLayer tl) {
		if (!product.coupler() || svcType < A_OPEN_ROUTING_TABLE || svcType > A_WRITE_ROUTING_TABLE)
			return super.management(svcType, asdu, dst, respondTo, tl);
		if (svcType == A_OPEN_ROUTING_TABLE) {
			log("download", "A_Open_Routing_Table (filter table access)");
			return null; // no response defined
		}
		if (asdu.length < 3) {
			log("download", "routing table request too short, ignored");
			return null;
		}
		final int count = asdu[0] & 0xff;
		final int address = (asdu[1] & 0xff) << 8 | asdu[2] & 0xff;
		final byte[] data;
		if (svcType == A_WRITE_ROUTING_TABLE) {
			final int n = Math.min(count, asdu.length - 3);
			final byte[] w = java.util.Arrays.copyOfRange(asdu, 3, 3 + n);
			final var r = writeMemory(address, w);
			log("download", String.format("filter table write 0x%04X, %d byte(s)", address, n));
			data = readMemory(address, n).getResult();
			if (data == null || r == null)
				return null;
		}
		else {
			data = readMemory(address, count).getResult();
			if (data == null)
				return null;
		}
		final byte[] apdu = new byte[5 + data.length];
		apdu[0] = (byte) (A_READ_ROUTING_TABLE_RES >> 8 & 3);
		apdu[1] = (byte) A_READ_ROUTING_TABLE_RES;
		apdu[2] = (byte) count;
		apdu[3] = (byte) (address >> 8);
		apdu[4] = (byte) address;
		System.arraycopy(data, 0, apdu, 5, data.length);
		return new ServiceResult<byte[]>() {
			@Override
			public void run() {
				try {
					tl.sendData(respondTo, Priority.LOW, apdu);
				}
				catch (final KNXException e) {
					log("download", "routing table response failed: " + e.getMessage());
				}
			}
		};
	}

	// ---------------------------------------------------------------- load state machine

	private byte loadControl(final int obj, final byte[] data) {
		final var ios = base.getInterfaceObjectServer();
		final int event = data.length > 0 ? data[0] & 0xff : LE_NOOP;
		int state = ios.getProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1)[0] & 0xff;
		final String what = switch (obj) {
			case OBJ_ADDRESS_TABLE -> "address table";
			case OBJ_ASSOC_TABLE -> "association table";
			case OBJ_GROUP_OBJECT_TABLE -> "group object table";
			default -> "application";
		};

		switch (event) {
			case LE_NOOP -> { /* just report state */ }
			case LE_START_LOADING -> {
				state = LS_LOADING;
				log("download", "load event START_LOADING for " + what + " (obj " + obj + ")");
			}
			case LE_ADDITIONAL -> {
				log("download", "load event ADDITIONAL_LOAD_CONTROLS for " + what + ": " + HexFormat.of().formatHex(data));
				if (data.length >= 8 && (data[1] & 0xff) == 0x0B)
					state = allocate(obj, ((data[2] & 0xff) << 24) | ((data[3] & 0xff) << 16) | ((data[4] & 0xff) << 8)
							| (data[5] & 0xff), data[6] == 1, data[7]) ? LS_LOADING : LS_ERROR;
				else {
					log("download", "ERROR: unsupported additional load control (only 0x0B relative allocation is implemented)");
					state = LS_ERROR;
				}
			}
			case LE_LOAD_COMPLETED -> {
				state = LS_LOADED;
				log("download", "load event LOAD_COMPLETED for " + what + " -> Loaded");
			}
			case LE_UNLOAD -> {
				state = LS_UNLOADED;
				log("download", "load event UNLOAD for " + what);
				clearRegion(obj);
			}
			default -> {
				log("download", "ERROR: undefined load event " + event);
				state = LS_ERROR;
			}
		}
		ios.setProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1, (byte) state);
		if (event == LE_LOAD_COMPLETED) {
			if (obj == OBJ_APPLICATION)
				downloadCount++;
			afterTablesChanged();
			if (obj == OBJ_APPLICATION)
				startApplication();
		}
		if (event == LE_UNLOAD && obj == OBJ_APPLICATION)
			behavior.onStop();
		changed();
		return (byte) state;
	}

	private boolean allocate(final int obj, final int size, final boolean fill, final byte fillByte) {
		if (size < 0 || size > SLOT_CAPACITY[obj]) {
			log("download", "ERROR: relative allocation of " + size + " bytes exceeds capacity " + SLOT_CAPACITY[obj]
					+ " for obj " + obj);
			return false;
		}
		allocatedSize[obj] = size;
		final var mem = base.deviceMemory();
		// Mode 1 = (re)initialise with the fill byte (full download). Mode 0 = partial download: keep what is already in
		// memory, because ETS then writes only the parameters that changed. Zeroing here wiped every unchanged parameter.
		if (fill && size > 0) {
			final byte[] region = new byte[size];
			java.util.Arrays.fill(region, fillByte);
			mem.set(SLOT_ADDRESS[obj], region);
		}
		base.getInterfaceObjectServer().setProperty(obj, PID.TABLE_REFERENCE, 1, 1, intBytes(SLOT_ADDRESS[obj]));
		log("download", String.format("relative allocation obj %d: %d bytes at 0x%04X (%s)", obj, size, SLOT_ADDRESS[obj],
				fill ? "initialised" : "existing content kept, partial download"));
		return true;
	}

	private void clearRegion(final int obj) {
		base.deviceMemory().set(SLOT_ADDRESS[obj], new byte[SLOT_CAPACITY[obj]]);
		allocatedSize[obj] = 0;
	}

	/** Memory control block entry: segment size(4), CRC control(1)=0 "always valid", access(1)=0xFF, CRC-16(2). */
	byte[] memoryControlBlock(final int obj) {
		final int state = base.getInterfaceObjectServer().getProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1)[0] & 0xff;
		final byte[] out = new byte[8];
		if (state != LS_LOADED)
			return out;
		final int size = allocatedSize[obj] > 0 ? allocatedSize[obj] : sizeFromMemory(obj);
		final byte[] segment = base.deviceMemory().get(SLOT_ADDRESS[obj], size);
		System.arraycopy(intBytes(size), 0, out, 0, 4);
		out[4] = 0x00;
		out[5] = (byte) 0xFF;
		final int crc = Crc16.ccitt(segment);
		out[6] = (byte) (crc >> 8);
		out[7] = (byte) crc;
		return out;
	}

	private int sizeFromMemory(final int obj) {
		// after a restart allocatedSize is lost; derive from the table length prefix where possible
		return switch (obj) {
			case OBJ_ADDRESS_TABLE -> 2 + 2 * u16(SLOT_ADDRESS[obj]);
			case OBJ_ASSOC_TABLE -> 2 + 4 * u16(SLOT_ADDRESS[obj]);
			case OBJ_GROUP_OBJECT_TABLE -> 2 + 2 * u16(SLOT_ADDRESS[obj]);
			default -> product.paramAreaSize();
		};
	}

	// ---------------------------------------------------------------- downloaded configuration

	private int u16(final int addr) {
		final byte[] b = base.deviceMemory().get(addr, 2);
		return ((b[0] & 0xff) << 8) | (b[1] & 0xff);
	}

	/** Parsed view of the three downloaded tables. */
	record Tables(int[] groupAddresses, int[][] associations, int[] goConfig) {
		static final Tables EMPTY = new Tables(new int[0], new int[0][], new int[0]);

		int indexOf(final GroupAddress ga) {
			for (int i = 0; i < groupAddresses.length; i++)
				if (groupAddresses[i] == ga.getRawAddress())
					return i + 1;
			return 0;
		}

		/** Object numbers associated with the 1-based address-table index, in association-table order. */
		List<Integer> objectsOf(final int gaIndex) {
			final var l = new ArrayList<Integer>();
			for (final int[] a : associations)
				if (a[0] == gaIndex)
					l.add(a[1]);
			return l;
		}

		/** Sending group address of an object: the first association entry of that object. */
		GroupAddress sendAddress(final int objectNumber) {
			for (final int[] a : associations)
				if (a[1] == objectNumber && a[0] >= 1 && a[0] <= groupAddresses.length)
					return new GroupAddress(groupAddresses[a[0] - 1]);
			return null;
		}

		int config(final int objectNumber) {
			return objectNumber >= 1 && objectNumber <= goConfig.length ? goConfig[objectNumber - 1] : 0;
		}
	}

	static final int F_COMM = 0x04, F_READ = 0x08, F_WRITE = 0x10, F_READ_ON_INIT = 0x20, F_TRANSMIT = 0x40,
			F_UPDATE = 0x80;

	private void afterTablesChanged() {
		try {
			final var mem = base.deviceMemory();
			if (loadState(OBJ_ADDRESS_TABLE) != LS_LOADED || loadState(OBJ_ASSOC_TABLE) != LS_LOADED
					|| loadState(OBJ_GROUP_OBJECT_TABLE) != LS_LOADED) {
				tables = Tables.EMPTY;
				return;
			}
			final int nGa = u16(SLOT_ADDRESS[OBJ_ADDRESS_TABLE]);
			final int[] ga = new int[nGa];
			final byte[] gaBytes = mem.get(SLOT_ADDRESS[OBJ_ADDRESS_TABLE] + 2, nGa * 2);
			for (int i = 0; i < nGa; i++)
				ga[i] = ((gaBytes[2 * i] & 0xff) << 8) | (gaBytes[2 * i + 1] & 0xff);

			final int nAssoc = u16(SLOT_ADDRESS[OBJ_ASSOC_TABLE]);
			final int[][] assoc = new int[nAssoc][2];
			final byte[] ab = mem.get(SLOT_ADDRESS[OBJ_ASSOC_TABLE] + 2, nAssoc * 4);
			for (int i = 0; i < nAssoc; i++) {
				assoc[i][0] = ((ab[4 * i] & 0xff) << 8) | (ab[4 * i + 1] & 0xff);
				assoc[i][1] = ((ab[4 * i + 2] & 0xff) << 8) | (ab[4 * i + 3] & 0xff);
			}

			final int nGo = u16(SLOT_ADDRESS[OBJ_GROUP_OBJECT_TABLE]);
			final int[] cfg = new int[nGo];
			final byte[] gb = mem.get(SLOT_ADDRESS[OBJ_GROUP_OBJECT_TABLE] + 2, nGo * 2);
			for (int i = 0; i < nGo; i++)
				cfg[i] = gb[2 * i] & 0xff; // byte 0 = flags + priority, byte 1 = value type
			tables = new Tables(ga, assoc, cfg);
			log("config", "tables loaded: " + nGa + " group address(es), " + nAssoc + " association(s), " + nGo
					+ " group object(s)");
			for (int i = 0; i < nAssoc; i++)
				log("config", "  association: " + new GroupAddress(ga[Math.max(0, assoc[i][0] - 1)]) + " <-> object "
						+ assoc[i][1] + " (flags 0x" + Integer.toHexString(cfg.length >= assoc[i][1] && assoc[i][1] > 0
								? cfg[assoc[i][1] - 1] : 0) + ")");
		}
		catch (final RuntimeException e) {
			tables = Tables.EMPTY;
			log("config", "ERROR parsing downloaded tables: " + e);
		}
	}

	private int loadState(final int obj) {
		if (product.coupler())
			return base.deviceMemory().get(CFG) & 0xff; // the router object keeps its load state in device memory
		return base.getInterfaceObjectServer().getProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1)[0] & 0xff;
	}

	/** (Re)starts the application behaviour if an application is loaded - after a download, a restart or process start. */
	private void startApplication() {
		behavior.onStop();
		if (applicationLoaded()) {
			if (product.coupler()) {
				log("download", "coupler configuration active (see coupler log)");
				behavior.onApplicationLoaded();
				return;
			}
			final byte[] area = parameterArea();
			final var sb = new StringBuilder();
			for (final var pd : product.params())
				if (visible(pd.when()))
					sb.append(sb.length() == 0 ? "" : ", ").append(pd.name()).append('=')
							.append(pd.isText() ? paramText(pd.name()) : String.valueOf(param(pd.name())));
			log("download", "application started; parameter memory " + HexFormat.of().formatHex(area) + " -> " + sb);
			behavior.onApplicationLoaded();
		}
	}

	/** Timer thread shared by the behaviours (dimming ramps, blind travel, cyclic sending). */
	ScheduledExecutorService scheduler() { return scheduler; }

	/** Periodic task that survives exceptions: an exception would otherwise silently cancel all later runs. */
	java.util.concurrent.ScheduledFuture<?> every(final Runnable r, final long initial, final long period,
			final java.util.concurrent.TimeUnit unit) {
		return scheduler.scheduleAtFixedRate(guarded(r), initial, period, unit);
	}

	java.util.concurrent.ScheduledFuture<?> later(final Runnable r, final long delay, final java.util.concurrent.TimeUnit unit) {
		return scheduler.schedule(guarded(r), delay, unit);
	}

	private Runnable guarded(final Runnable r) {
		return () -> {
			try {
				r.run();
			}
			catch (final Throwable t) {
				log("error", "timer task failed: " + t);
			}
		};
	}

	/** Downloaded group-address associations for the control panel: {group address, object number, ETS flag byte}. */
	public List<int[]> associationView() {
		final var t = tables;
		final var out = new ArrayList<int[]>();
		for (final int[] a : t.associations()) {
			if (a[0] < 1 || a[0] > t.groupAddresses().length)
				continue;
			out.add(new int[] { t.groupAddresses()[a[0] - 1], a[1], t.config(a[1]) });
		}
		return out;
	}

	/** Whether the application program (parameter area) is currently loaded. */
	public boolean applicationLoaded() { return loadState(product.coupler() ? routerIndex : OBJ_APPLICATION) == LS_LOADED; }

	public int downloadCount() { return downloadCount; }

	/**
	 * What the device itself can document about its configuration, from the load states of its four tables:
	 * "empty" (nothing loaded), "loading" (a download is in progress), "partial" (some tables missing),
	 * "error" (a load failed) or "complete" (address, association and group object table and application are loaded).
	 */
	public String downloadState() {
		try {
			if (product.coupler()) {
				final int x = loadState(routerIndex);
				return x == LS_LOADING ? "loading" : x == LS_ERROR ? "error" : x == LS_LOADED ? "complete" : "empty";
			}
			final int[] st = { loadState(OBJ_ADDRESS_TABLE), loadState(OBJ_ASSOC_TABLE), loadState(OBJ_GROUP_OBJECT_TABLE),
				loadState(OBJ_APPLICATION) };
			int loaded = 0;
			for (final int x : st) {
				if (x == LS_LOADING)
					return "loading";
				if (x == LS_ERROR)
					return "error";
				if (x == LS_LOADED)
					loaded++;
			}
			return loaded == 0 ? "empty" : loaded == st.length ? "complete" : "partial";
		}
		catch (final RuntimeException e) {
			return "unknown";
		}
	}

	/** Raw parameter area as downloaded; empty when the application is not loaded. */
	byte[] parameterArea() {
		if (!applicationLoaded())
			return new byte[0];
		return base.deviceMemory().get(SLOT_ADDRESS[OBJ_APPLICATION], product.paramAreaSize());
	}

	/** Raw parameter area (test hook). */
	public byte[] parameterAreaForTest() { return parameterArea(); }

	/** Current value of a parameter: the downloaded value, or the product default when not loaded. */
	public int param(final String paramName) {
		final ParamDef p = product.param(paramName);
		final byte[] area = parameterArea();
		return area.length == 0 ? p.defaultValue() : p.read(area);
	}

	/** Current value of a text parameter (names of keys, outputs, channels): the downloaded text or the product default. */
	public String paramText(final String paramName) {
		final ParamDef p = product.param(paramName);
		final byte[] area = parameterArea();
		return area.length == 0 ? p.defaultText() : p.readText(area);
	}

	/** True while all conditions hold (the item is shown in ETS, so it matters to the simulation). */
	public boolean visible(final java.util.List<org.vknx.product.Cond> when) {
		for (final var c : when)
			if (!c.values().contains(param(c.param())))
				return false;
		return true;
	}

	// ---------------------------------------------------------------- coupler (mask 0x0912: router object + filter table)

	static final int PID_ROUTETABLE_CONTROL = 56, PID_MAX_ROUTING_APDU = 58;
	/** Router configuration kept in device memory (so it is saved with the device): lsm, pid 52, 53, 54, 55, 57, 59, run error. */
	private static final int CFG = 0x01F0;
	/** Group filter table: one bit per raw group address, set = route (MV-0912 LcFilterMemory 512, 8192 bytes). */
	static final int FILTER_TABLE = 0x0200, FILTER_SIZE = 8192;
	private static final int[] ROUTER_PIDS = { PID.LOAD_STATE_CONTROL, 52, 53, 54, 55, 57, 59 };
	private int routerIndex = -1;

	private void initRouter(final boolean fresh) {
		final var ios = base.getInterfaceObjectServer();
		for (final var o : ios.getInterfaceObjects())
			if (o.getType() == io.calimero.device.ios.InterfaceObject.ROUTER_OBJECT)
				routerIndex = o.getIndex();
		if (routerIndex < 0)
			routerIndex = ios.addInterfaceObject(io.calimero.device.ios.InterfaceObject.ROUTER_OBJECT).getIndex();
		// the management notifier looks the function property up in the object server before it calls us
		ios.setDescription(new io.calimero.mgmt.Description(routerIndex, 6, PID_ROUTETABLE_CONTROL, 0,
				io.calimero.dptxlator.PropertyTypes.PDT_FUNCTION, true, 0, 1, 3, 3), true);
		if (fresh) {
			// factory state: nothing loaded, everything routed (modelling choice, see docs/limitations.md), empty filter table
			base.deviceMemory().set(CFG, (byte) LS_UNLOADED, (byte) 0x15, (byte) 0x15, (byte) 0x15, (byte) 0x15, (byte) 0, (byte) 0, (byte) 0xFF);
		}
		log("coupler", "router object at index " + routerIndex);
	}

	/** Raw router property value, or null if the property is not one of the coupler's. */
	private byte[] couplerProperty(final int objectIndex, final int pid) {
		if (objectIndex == 0 && pid == 53)
			return new byte[] { (byte) base.deviceMemory().get(CFG + 7) };
		if (objectIndex != routerIndex)
			return null;
		if (pid == PID_MAX_ROUTING_APDU)
			return new byte[] { 0, 66 };
		for (int i = 0; i < ROUTER_PIDS.length; i++)
			if (ROUTER_PIDS[i] == pid)
				return new byte[] { (byte) base.deviceMemory().get(CFG + i) };
		return null;
	}

	private void setCouplerProperty(final int objectIndex, final int pid, final byte[] data) {
		if (data.length == 0)
			return;
		if (objectIndex == 0 && pid == 53) {
			base.deviceMemory().set(CFG + 7, data[0]);
			return;
		}
		for (int i = 1; i < ROUTER_PIDS.length; i++)
			if (ROUTER_PIDS[i] == pid) {
				base.deviceMemory().set(CFG + i, data[0]);
				log("download", "router property " + pid + " = 0x" + HexFormat.of().formatHex(new byte[] { data[0] }));
				changed();
			}
	}

	/** Router configuration byte for a property id (52, 53, 54, 55, 57, 59). */
	int routerByte(final int pid) {
		final byte[] v = couplerProperty(routerIndex, pid);
		return v == null ? 0 : v[0] & 0xff;
	}

	/** True if the filter table says the raw group address is routed. */
	boolean filterBit(final int rawGroupAddress) {
		final int b = base.deviceMemory().get(FILTER_TABLE + (rawGroupAddress >> 3)) & 0xff;
		return (b >> (rawGroupAddress & 7) & 1) != 0;
	}

	/** Number of group addresses set in the filter table. */
	int filterCount() {
		int n = 0;
		for (final byte b : base.deviceMemory().get(FILTER_TABLE, FILTER_SIZE))
			n += Integer.bitCount(b & 0xff);
		return n;
	}

	/** The group addresses that are set in the filter table (for the panel; capped). */
	List<Integer> filterAddresses(final int max) {
		final var out = new ArrayList<Integer>();
		final byte[] t = base.deviceMemory().get(FILTER_TABLE, FILTER_SIZE);
		for (int ga = 0; ga < FILTER_SIZE * 8 && out.size() < max; ga++)
			if ((t[ga >> 3] >> (ga & 7) & 1) != 0)
				out.add(ga);
		return out;
	}

	private byte couplerLoadControl(final byte[] data) {
		final int event = data.length > 0 ? data[0] & 0xff : LE_NOOP;
		int state = loadState(routerIndex);
		switch (event) {
			case LE_NOOP -> { }
			case LE_START_LOADING -> {
				state = LS_LOADING;
				log("download", "load event START_LOADING for the router application");
			}
			case LE_LOAD_COMPLETED -> {
				state = LS_LOADED;
				log("download", "load event LOAD_COMPLETED for the router application -> Loaded");
			}
			case LE_UNLOAD -> {
				state = LS_UNLOADED;
				log("download", "load event UNLOAD for the router application");
				behavior.onStop();
				// an unloaded coupler is no longer the factory-new "route all" device: it has lost its application, so it
				// falls back to filtering with an empty table (group telegrams blocked, individually addressed ones filtered)
				final var m = base.deviceMemory();
				m.set(CFG + 1, (byte) 0x77, (byte) 0x77, (byte) 0x17, (byte) 0x17);
				m.set(FILTER_TABLE, new byte[FILTER_SIZE]);
				log("coupler", "unloaded: group telegrams now blocked (empty filter table), physical routing = filter");
			}
			default -> log("download", "router load event " + event + " (ignored)");
		}
		base.deviceMemory().set(CFG, (byte) state);
		if (event == LE_LOAD_COMPLETED) {
			downloadCount++;
			startApplication();
		}
		changed();
		return (byte) state;
	}

	/** PID_ROUTETABLE_CONTROL function property: 1 = clear filter table, 2 = set all, 3 = clear range, 4 = set range. */
	private ServiceResult<byte[]> routeTableControl(final byte[] command) {
		final int service = command.length > 1 ? command[1] & 0xff : 0;
		final var mem = base.deviceMemory();
		switch (service) {
			case 1, 2 -> {
				final byte[] t = new byte[FILTER_SIZE];
				if (service == 2)
					java.util.Arrays.fill(t, (byte) 0xFF);
				mem.set(FILTER_TABLE, t);
				log("download", "filter table " + (service == 1 ? "cleared" : "set to route everything"));
				changed();
				return new ServiceResult<>((byte) 0, (byte) service);
			}
			case 3, 4 -> {
				final int from = command.length >= 4 ? ((command[2] & 0xff) << 8) | (command[3] & 0xff) : 0;
				final int to = command.length >= 6 ? ((command[4] & 0xff) << 8) | (command[5] & 0xff) : 0xFFFF;
				for (int ga = from; ga <= to; ga++) {
					final int a = FILTER_TABLE + (ga >> 3), m = 1 << (ga & 7), cur = mem.get(a);
					mem.set(a, service == 4 ? cur | m : cur & ~m);
				}
				log("download", "filter table " + (service == 3 ? "cleared" : "set") + " for raw group addresses " + from + ".." + to);
				changed();
				return new ServiceResult<>((byte) 0, (byte) service, command.length > 2 ? command[2] : 0,
						command.length > 3 ? command[3] : 0, command.length > 4 ? command[4] : (byte) 0xFF,
						command.length > 5 ? command[5] : (byte) 0xFF);
			}
			default -> {
				return ServiceResult.of(io.calimero.ReturnCode.Error, new byte[0]);
			}
		}
	}

	// ---------------------------------------------------------------- group communication (process communication)

	@Override
	public void groupWrite(final ProcessEvent e) {
		receive(e, "GroupValueWrite", false);
	}

	@Override
	public void groupResponse(final ProcessEvent e) {
		receive(e, "GroupValueResponse", true);
	}

	private void receive(final ProcessEvent e, final String service, final boolean response) {
		final GroupAddress ga = e.getDestination();
		final var t = tables;
		final int gaIdx = t.indexOf(ga);
		final String head = service + " " + e.getSourceAddr() + " -> " + ga + " [" + HexFormat.of().formatHex(e.getASDU())
				+ "]: ";
		if (!applicationLoaded()) {
			log("rx", head + "ignored, no application program loaded");
			return;
		}
		if (gaIdx == 0) {
			log("rx", head + "ignored, group address is not in the address table");
			return;
		}
		final var objs = t.objectsOf(gaIdx);
		if (objs.isEmpty()) {
			log("rx", head + "ignored, group address is not associated with any object");
			return;
		}
		for (final int go : objs) {
			final int cfg = t.config(go);
			final String oname = product.object(go).map(ObjDef::name).orElse("?");
			if ((cfg & F_COMM) == 0) {
				log("rx", head + "object " + go + " (" + oname + ") ignored: communication flag off");
				continue;
			}
			final boolean allowed = response ? (cfg & F_UPDATE) != 0 : (cfg & F_WRITE) != 0;
			if (!allowed) {
				log("rx", head + "object " + go + " (" + oname + ") ignored: "
						+ (response ? "update" : "write") + " flag off");
				continue;
			}
			log("rx", head + "-> object " + go + " (" + oname + ")");
			behavior.onObjectReceived(go, decode(e.getASDU(), product.object(go).map(ObjDef::sizeBits).orElse(1)));
		}
		changed();
	}

	@Override
	public ServiceResult<byte[]> groupReadRequest(final ProcessEvent e) {
		final GroupAddress ga = e.getDestination();
		final var t = tables;
		final int gaIdx = t.indexOf(ga);
		final String head = "GroupValueRead " + e.getSourceAddr() + " -> " + ga + ": ";
		if (!applicationLoaded() || gaIdx == 0) {
			log("rx", head + "ignored (" + (applicationLoaded() ? "address not in address table" : "no application loaded")
					+ ")");
			return null;
		}
		for (final int go : t.objectsOf(gaIdx)) {
			final int cfg = t.config(go);
			if ((cfg & F_COMM) == 0 || (cfg & F_READ) == 0) {
				log("rx", head + "object " + go + " not answering: " + ((cfg & F_COMM) == 0 ? "communication" : "read")
						+ " flag off");
				continue;
			}
			final Integer v = behavior.valueOf(go);
			if (v == null)
				continue;
			final int bits = product.object(go).map(ObjDef::sizeBits).orElse(1);
			log("tx", head + "answering from object " + go + " with " + v);
			return bits <= 6 ? new ServiceResult<>(new byte[] { (byte) (v & 0x3f) }, true)
					: new ServiceResult<>(encodeData(bits, v), false);
		}
		return null;
	}

	/** Value of a group telegram: small objects (up to 6 bit) are packed into the APCI byte, larger ones are big-endian bytes. */
	static int decode(final byte[] asdu, final int bits) {
		if (asdu.length == 0)
			return 0;
		if (bits <= 6)
			return asdu[asdu.length - 1] & 0x3f;
		int v = 0;
		for (int i = Math.max(0, asdu.length - 4); i < asdu.length; i++)
			v = (v << 8) | (asdu[i] & 0xff);
		return v;
	}

	private static byte[] encodeData(final int bits, final int v) {
		return bits == 16 ? new byte[] { (byte) (v >> 8), (byte) v } : new byte[] { (byte) v };
	}

	/**
	 * Sends the value of an object to its sending group address, if ETS configured the object to do so.
	 * Gives the reason in the log if nothing is sent (this is the "why didn't it react" explanation).
	 */
	boolean transmit(final int go, final int value) { return transmit(go, value, false); }

	/** @param quiet true for cyclic sending: stay silent in the log when nothing can be sent (avoids log flooding) */
	boolean transmit(final int go, final int value, final boolean quiet) {
		final String oname = product.object(go).map(ObjDef::name).orElse("?");
		final var t = tables;
		if (!applicationLoaded()) {
			if (!quiet)
				log("tx", "object " + go + " (" + oname + ") not sent: no application program loaded");
			return false;
		}
		final GroupAddress ga = t.sendAddress(go);
		if (ga == null) {
			if (!quiet)
				log("tx", "object " + go + " (" + oname + ") not sent: no group address associated");
			return false;
		}
		final int cfg = t.config(go);
		if ((cfg & F_COMM) == 0 || (cfg & F_TRANSMIT) == 0) {
			if (!quiet)
				log("tx", "object " + go + " (" + oname + ") not sent: " + ((cfg & F_COMM) == 0 ? "communication" : "transmit")
						+ " flag off");
			return false;
		}
		final int bits = product.object(go).map(ObjDef::sizeBits).orElse(1);
		final byte[] data = encodeData(bits, value);
		final byte[] apdu = bits <= 6 ? new byte[] { 0x00, (byte) (0x80 | (value & 0x3f)) }
				: new byte[2 + data.length];
		if (bits > 6) {
			apdu[1] = (byte) 0x80;
			System.arraycopy(data, 0, apdu, 2, data.length);
		}
		try {
			// a group object ETS marked as secure is sent authenticated (and encrypted); otherwise the plain APDU goes out
			final var secured = base.secureApplicationLayer().secureGroupObject(address(), ga, apdu);
			base.getDeviceLink().sendRequest(ga, Priority.LOW, secured.orElse(apdu));
			log("tx", "GroupValueWrite " + address() + " -> " + ga + " value " + value + " (object " + go + " " + oname
					+ (secured.isPresent() ? ", KNX Data Secure" : "") + ")");
			return true;
		}
		catch (final KNXException ex) {
			log("tx", "send failed: " + ex);
			return false;
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// the abstract datapoint callbacks of KnxDeviceServiceLogic are not used: association tables drive everything
	@Override
	public void updateDatapointValue(final Datapoint ofDp, final DPTXlator update) {}

	@Override
	public DPTXlator requestDatapointValue(final Datapoint ofDp) { return null; }

	// ---------------------------------------------------------------- plumbing

	public DeviceBehavior behavior() { return behavior; }

	void log(final String category, final String text) {
		for (final var l : listeners)
			l.log(this, category, text);
	}

	void changed() {
		for (final var l : listeners)
			l.changed(this);
	}

	byte[] snapshotMemory(final int from, final int length) { return base.deviceMemory().get(from, length); }

	private static byte[] intBytes(final int v) {
		return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
	}

	@Override
	public void close() {
		if (behavior instanceof final CouplerBehavior cb && subnet != null)
			subnet.topology().unregister(cb);
		behavior.onStop();
		scheduler.shutdownNow();
		base.close();
		// Calimero's device close() saves the state but leaves the device on the line: a removed device would go on
		// answering ETS with its old address ("address already in use", every answer twice).
		try {
			base.getDeviceLink().close();
		}
		catch (final RuntimeException e) { /* already closed */ }
	}

	@Override
	public String toString() {
		return name + " (" + product.name() + ") " + address();
	}
}
