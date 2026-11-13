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
			DeviceSecurity.watch(name, logic);
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
		if (product.secure())
			security().initFresh(); // KNX Data Secure: factory key (FDSK) and sequence number
	}

	// ---------------------------------------------------------------- KNX Data Secure (see DeviceSecurity)

	private DeviceSecurity security() { return new DeviceSecurity(base.getInterfaceObjectServer(), this::log); }

	/** The factory default setup key as 32 hex digits, or "" for a device that is not a secure product. */
	public String fdskHex() { return product.secure() ? security().fdskHex() : ""; }

	/** The 6-byte KNX serial number as 12 hex digits (ETS shows and asks for it together with the FDSK). */
	public String serialHex() { return HexFormat.of().withUpperCase().formatHex(serialNumber().array()); }

	/** The device certificate ETS asks for; see {@link DeviceSecurity#certificate}. */
	public static String deviceCertificate(final byte[] serial, final byte[] fdsk) { return DeviceSecurity.certificate(serial, fdsk); }

	/** This device's certificate code (see {@link #deviceCertificate}), or "" for a plain product. */
	public String certificate() { return product.secure() ? DeviceSecurity.certificate(serialNumber().array(), security().fdsk()) : ""; }

	/** The serial number the way ETS shows it: 2 bytes, colon, 4 bytes (e.g. 00FA:00000033). */
	public String serialEts() { final String s = serialHex(); return s.substring(0, 4) + ":" + s.substring(4); }

	/** True once ETS has switched on security mode (the secure download completed far enough). */
	public boolean securityModeOn() { return product.secure() && security().modeOn(); }

	/** True once ETS replaced the factory key with its own tool key: the FDSK is no longer used. */
	public boolean toolKeyActive() { return product.secure() && security().toolKeyActive(); }

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
			etsAction("Physical address " + newAddress + " assigned");
			super.writeAddress(newAddress);
			// real devices leave programming mode after an address assignment sequence ends (ETS restarts them)
			changed();
		}
		else
			log("mgmt", "A_IndividualAddress_Write " + newAddress + " ignored: not in programming mode");
			etsAction("Address " + newAddress + " refused: the programming button was not pressed");
	}

	@Override
	public ServiceResult<java.time.Duration> restart(final boolean masterReset,
			final io.calimero.mgmt.ManagementClient.EraseCode eraseCode, final int channel) {
		log("mgmt", "A_Restart (" + (masterReset ? "master reset " + eraseCode : "basic restart") + ")");
		if (!masterReset && ets.downloadJustEnded())
			etsAction(ets.last() + ", device restarted"); // ETS ends a download with a restart: keep what was downloaded in view
		else
			etsAction(masterReset ? "Master reset (" + eraseCode + ")" : "Restart");
		// ETS sends "factory reset without address" (code 7) when it deactivates secure commissioning, and goes on talking
		// with the tool key afterwards. Calimero deletes tool key and tables on that code (after the answer has gone out),
		// so the device would no longer answer ETS. Keep what is there: snapshot now, put it back once Calimero is done.
		final boolean keepSecurity = masterReset && product.secure()
				&& eraseCode == io.calimero.mgmt.ManagementClient.EraseCode.FactoryResetWithoutIndividualAddress;
		final var kept = keepSecurity ? security().snapshot() : null;
		final var r = super.restart(masterReset, eraseCode, channel);
		if (masterReset) { // Calimero keeps the old state file at every reset: only the newest copy is worth keeping
			try { org.vknx.app.DataFiles.deleteBackups(stateFile.toAbsolutePath().getParent(), 1); }
			catch (final java.io.IOException e) { /* only a left-over file */ }
		}
		if (kept != null) {
			security().restore(kept);
			final var t = new Thread(() -> {
				for (int i = 0; i < 4; i++) {
					try { Thread.sleep(400); } catch (final InterruptedException e) { return; }
					security().restore(kept);
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
				security().reset();
				changed();
			}, "vknx-secure-reset-" + name);
			t.setDaemon(true);
			t.start();
		}
		afterTablesChanged();
		startApplication();
		changed();
		scheduleSave(SAVE_SOON_S); // after Calimero has done its part of the reset
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
			final int pdt = propertyId == PID.LOAD_STATE_CONTROL ? 0 : propertyId == RouterMemory.PID_MAX_ROUTING_APDU ? 4 : 2;
			final boolean writable = propertyId != RouterMemory.PID_MAX_ROUTING_APDU;
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

	/**
	 * Verify mode (bit 2 of the device control property): ETS reads the property and, if the bit is already set, does not
	 * write it again; every memory write is then expected to be answered with the data now in memory. Calimero keeps the
	 * mode in a field that only a property <em>write</em> sets, but the property itself is restored from the state file
	 * after vKNX has been restarted. Then ETS saw "verify mode on", the device did not answer the first memory write and
	 * the download failed ("Device does not respond") and stayed at "Download in progress". The property is the truth.
	 */
	@Override
	public boolean isVerifyModeEnabled() {
		try {
			final byte[] v = base.getInterfaceObjectServer().getProperty(0, PID.DEVICE_CONTROL, 1, 1);
			return v != null && v.length > 0 && (v[0] & 0x04) != 0;
		}
		catch (final RuntimeException e) {
			return super.isVerifyModeEnabled();
		}
	}

	@Override
	public ServiceResult<Void> writeMemory(final int startAddress, final byte[] data) {
		final var r = super.writeMemory(startAddress, data);
		if (product.coupler() && startAddress >= FILTER_TABLE && startAddress < FILTER_TABLE + FILTER_SIZE)
			changed();
		log("download", String.format("memory write 0x%06X, %d bytes -> %s", startAddress, data.length,
				r.getResult() == null ? "ok" : "ok"));
		ets.memoryWritten();
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
				// The content stays: a partial download (ETS unloads, then writes only what changed) relies on the rest
				// still being there. A full download initialises the region again when it allocates.
				allocatedSize[obj] = 0;
			}
			default -> {
				log("download", "ERROR: undefined load event " + event);
				state = LS_ERROR;
			}
		}
		ios.setProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1, (byte) state);
		if (event == LE_START_LOADING || event == LE_LOAD_COMPLETED || event == LE_UNLOAD || state == LS_ERROR)
			ets.loadEvent(obj, event == LE_START_LOADING, event == LE_LOAD_COMPLETED, state == LS_ERROR,
					event == LE_UNLOAD ? "Unloaded " + what + " (ETS is about to download it again)" : null);
		if (event == LE_LOAD_COMPLETED) {
			if (obj == OBJ_APPLICATION)
				downloadCount++;
			afterTablesChanged();
			if (obj == OBJ_APPLICATION) {
				final String other = foreignApplication();
				if (!other.isEmpty()) {
					log("download", "WRONG PRODUCT: ETS loaded the application of '" + other + "' into this '" + product.name()
							+ "'. The address of this device probably belongs to another device in your ETS project.");
					ets.warn("wrong application (that of '" + other + "')");
				}
				else
					ets.warn("");
				startApplication();
			}
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
		ets.allocated(!fill);
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
			return RouterMemory.loadState(base.deviceMemory()); // the router object keeps its load state in device memory
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
			readOnInit();
		}
	}

	/**
	 * Objects with the ETS flag "Read on init" ask the bus for their value when the application starts (a display that
	 * would otherwise show nothing until the next change). One read per group address, spread out so it does not flood.
	 */
	private void readOnInit() {
		final var t = tables;
		final var done = new java.util.HashSet<GroupAddress>();
		int n = 0;
		for (int go = 1; go <= t.goConfig().length; go++) {
			final int cfg = t.config(go);
			if ((cfg & F_COMM) == 0 || (cfg & F_READ_ON_INIT) == 0)
				continue;
			final GroupAddress ga = t.sendAddress(go);
			if (ga == null || !done.add(ga))
				continue;
			final int object = go;
			later(() -> {
				try {
					base.getDeviceLink().sendRequest(ga, Priority.LOW, new byte[] { 0x00, 0x00 });
					log("tx", "GroupValueRead " + address() + " -> " + ga + " (object " + object + " "
							+ product.object(object).map(ObjDef::name).orElse("?") + ", read on init)");
				}
				catch (final KNXException ex) {
					log("tx", "read on init failed: " + ex);
				}
			}, 1500L + 150L * n++, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		if (n > 0)
			log("config", n + " object(s) with the flag \"read on init\": asking the bus for their values");
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

	/**
	 * Name of the product whose application ETS has loaded into this device when that is not this device's own product
	 * (typically the individual address was given to the wrong device, so ETS downloaded to the wrong one), else "".
	 * ETS writes the application's id (manufacturer, application number, version) into the application object.
	 */
	public String foreignApplication() {
		try {
			if (product.coupler() || !applicationLoaded())
				return "";
			final byte[] v = base.getInterfaceObjectServer().getProperty(OBJ_APPLICATION, PID.PROGRAM_VERSION, 1, 1);
			if (v == null || v.length < 4)
				return "";
			final int app = ((v[2] & 0xff) << 8) | (v[3] & 0xff);
			if (app == 0 || app == product.appNumber())
				return "";
			for (final var p : org.vknx.product.Products.ALL.values())
				if (p.appNumber() == app)
					return p.name();
			return String.format("an unknown application (0x%04X)", app);
		} catch (final RuntimeException e) {
			return "";
		}
	}

	public int downloadCount() { return downloadCount; }

	// ---- the last thing ETS did to this device, in one line (for the device card): see EtsActivity

	private final EtsActivity ets = new EtsActivity(() -> scheduleSave(SAVE_SOON_S));

	/** One line about the most recent ETS action ("Partial download: 3 of 4 parts written"), or "" if ETS has done nothing yet. */
	public String lastEtsAction() { return ets.last(); }

	public long lastEtsAt() { return ets.lastAt(); }

	private void etsAction(final String text) { ets.action(text); }

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

	/**
	 * Overwrites one enumeration or 8 bit number parameter in the device memory. Used when a scene is stored by a learn
	 * telegram: the new value replaces the one ETS downloaded (like in a real actuator) until the next download.
	 */
	void setParam(final String paramName, final int value) {
		final ParamDef p = product.param(paramName);
		final byte[] area = parameterArea();
		if (area.length == 0 || p.isText() || p.sizeBytes() != 1 || p.byteOffset() >= area.length)
			return;
		p.write(area, value);
		base.deviceMemory().set(SLOT_ADDRESS[OBJ_APPLICATION] + p.byteOffset(), area[p.byteOffset()]);
	}

	/** Current value of a text parameter (names of keys, outputs, channels): the downloaded text or the product default. */
	public String paramText(final String paramName) {
		final ParamDef p = product.param(paramName);
		final byte[] area = parameterArea();
		final String t = area.length == 0 ? p.defaultText() : p.readText(area);
		return t.isBlank() ? p.defaultText() : t; // a channel name left empty in ETS: the product default ("Output 1")
	}

	/** True while all conditions hold (the item is shown in ETS, so it matters to the simulation). */
	public boolean visible(final java.util.List<org.vknx.product.Cond> when) {
		for (final var c : when)
			if (!c.values().contains(param(c.param())))
				return false;
		return true;
	}

	// ---------------------------------------------------------------- coupler (mask 0x0912: router object + filter table)

	static final int PID_ROUTETABLE_CONTROL = RouterMemory.PID_ROUTETABLE_CONTROL;
	static final int FILTER_TABLE = RouterMemory.FILTER_TABLE, FILTER_SIZE = RouterMemory.FILTER_SIZE;
	private int routerIndex = -1;
	/** The router settings and the filter table in the device memory (see RouterMemory); only a coupler has one. */
	private RouterMemory router;

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
		router = new RouterMemory(base.deviceMemory(), routerIndex, this::log, this::changed);
		if (fresh)
			router.factoryState(LS_UNLOADED);
		log("coupler", "router object at index " + routerIndex);
	}

	/** Raw router property value, or null if the property is not one of the coupler's. */
	private byte[] couplerProperty(final int objectIndex, final int pid) { return router == null ? null : router.property(objectIndex, pid); }

	private void setCouplerProperty(final int objectIndex, final int pid, final byte[] data) { router.setProperty(objectIndex, pid, data); }

	/** Router configuration byte for a property id (52, 53, 54, 55, 57, 59). */
	int routerByte(final int pid) { return router.settingByte(pid); }

	/** True if the filter table says the raw group address is routed. */
	boolean filterBit(final int rawGroupAddress) { return router.filterBit(rawGroupAddress); }

	/** Number of group addresses set in the filter table. */
	int filterCount() { return router.filterCount(); }

	/** The group addresses that are set in the filter table (for the panel; capped). */
	List<Integer> filterAddresses(final int max) { return router.filterAddresses(max); }

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
				etsAction("Coupler configuration downloaded");
			}
			case LE_UNLOAD -> {
				state = LS_UNLOADED;
				log("download", "load event UNLOAD for the router application");
				behavior.onStop();
				// an unloaded coupler is no longer the factory-new "route all" device: it has lost its application, so it
				// falls back to filtering with an empty table (group telegrams blocked, individually addressed ones filtered)
				router.unloaded();
				log("coupler", "unloaded: group telegrams now blocked (empty filter table), physical routing = filter");
			}
			default -> log("download", "router load event " + event + " (ignored)");
		}
		router.setLoadState(state);
		if (event == LE_LOAD_COMPLETED) {
			downloadCount++;
			startApplication();
		}
		changed();
		return (byte) state;
	}

	private ServiceResult<byte[]> routeTableControl(final byte[] command) { return router.routeTableControl(command); }

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
			behavior.onObjectReceived(go, GroupValue.decode(e.getASDU(), product.object(go).map(ObjDef::sizeBits).orElse(1)));
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
					: new ServiceResult<>(GroupValue.encode(bits, v), false);
		}
		return null;
	}

	/**
	 * Sends the value of an object to its sending group address, if ETS configured the object to do so.
	 * Gives the reason in the log if nothing is sent (this is the "why didn't it react" explanation).
	 */
	boolean transmit(final int go, final int value) { return transmit(go, value, false); }

	/** @param quiet true for cyclic sending: stay silent in the log when nothing can be sent (avoids log flooding) */
	boolean transmit(final int go, final int value, final boolean quiet) {
		final int bits = product.object(go).map(ObjDef::sizeBits).orElse(1);
		final byte[] data = GroupValue.encode(bits, value);
		final byte[] apdu = bits <= 6 ? new byte[] { 0x00, (byte) (0x80 | (value & 0x3f)) }
				: new byte[2 + data.length];
		if (bits > 6) {
			apdu[1] = (byte) 0x80;
			System.arraycopy(data, 0, apdu, 2, data.length);
		}
		return send(go, apdu, String.valueOf(value), quiet);
	}

	/** Sends a value of more than two bytes (date, time, date and time) to the sending group address of object {@code go}. */
	boolean transmitBytes(final int go, final byte[] data, final String shown, final boolean quiet) {
		final byte[] apdu = new byte[2 + data.length];
		apdu[1] = (byte) 0x80;
		System.arraycopy(data, 0, apdu, 2, data.length);
		return send(go, apdu, shown, quiet);
	}

	private boolean send(final int go, final byte[] apdu, final String shown, final boolean quiet) {
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
		try {
			// a group object ETS marked as secure is sent authenticated (and encrypted); otherwise the plain APDU goes out
			final var secured = base.secureApplicationLayer().secureGroupObject(address(), ga, apdu);
			base.getDeviceLink().sendRequest(ga, Priority.LOW, secured.orElse(apdu));
			log("tx", "GroupValueWrite " + address() + " -> " + ga + " value " + shown + " (object " + go + " " + oname
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
		scheduleSave(SAVE_SLOW_S);
	}

	/** Seconds until the state is saved: soon after something ETS did, later after a change of what is only shown. */
	private static final int SAVE_SOON_S = 3, SAVE_SLOW_S = 30;
	private final java.util.concurrent.atomic.AtomicBoolean saveSoon = new java.util.concurrent.atomic.AtomicBoolean(),
			saveSlow = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Writes the state file some seconds after a change. Calimero saves a device only when it closes, and a master reset
	 * (ETS sends one at the end of every secure download) closes it for good: everything after that was lost when vKNX
	 * restarted, and a force-stopped vKNX (update, crash) lost everything since the start. What ETS does (address,
	 * download, restart) is saved after {@value #SAVE_SOON_S} s; {@link #changed()} is also called for every live value
	 * (a ramping dimmer, a sensor that sends), so it only asks for a save within {@value #SAVE_SLOW_S} s.
	 */
	void scheduleSave(final int seconds) {
		final var pending = seconds <= SAVE_SOON_S ? saveSoon : saveSlow;
		if (scheduler.isShutdown() || !pending.compareAndSet(false, true))
			return;
		try {
			scheduler.schedule(() -> {
				pending.set(false);
				saveQuietly();
			}, seconds, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch (final java.util.concurrent.RejectedExecutionException e) {
			pending.set(false);
		}
	}

	private void saveQuietly() {
		try {
			base.saveState();
		}
		catch (final RuntimeException e) {
			log("device", "saving the state failed: " + e);
		}
	}

	/** Where a behaviour may keep its own data in the persisted device memory (above the application slots and the coupler filter table). */
	static final int USER_MEMORY = 0x4000, USER_MEMORY_SIZE = 0x8000;

	/** Reads the user data block (a 4 byte length followed by the bytes); empty if none was ever saved. */
	byte[] readUserData() {
		final var mem = base.deviceMemory();
		final byte[] h = mem.get(USER_MEMORY, 4);
		final int n = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
		if (n <= 0 || n > USER_MEMORY_SIZE - 4)
			return new byte[0];
		return mem.get(USER_MEMORY + 4, n);
	}

	/** Stores the user data block in the device memory (saved with the device). */
	void writeUserData(final byte[] data) {
		if (data.length > USER_MEMORY_SIZE - 4)
			throw new IllegalArgumentException("too large: " + data.length + " bytes, at most " + (USER_MEMORY_SIZE - 4));
		final var mem = base.deviceMemory();
		mem.set(USER_MEMORY, intBytes(data.length));
		if (data.length > 0)
			mem.set(USER_MEMORY + 4, data);
	}

	byte[] snapshotMemory(final int from, final int length) { return base.deviceMemory().get(from, length); }

	private static byte[] intBytes(final int v) {
		return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
	}

	/** Writes the state of the device (address, download, parameters, logic) to its state file now; the device keeps running. */
	public void saveState() {
		base.saveState();
	}

	@Override
	public void close() {
		if (behavior instanceof final CouplerBehavior cb && subnet != null)
			subnet.topology().unregister(cb);
		DeviceSecurity.unwatch(name, this);
		behavior.onStop();
		scheduler.shutdownNow();
		saveQuietly(); // Calimero's own save at close does nothing for a device that has had a master reset
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
