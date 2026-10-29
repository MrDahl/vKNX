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
		final boolean restored = Files.exists(stateFile);
		final var logic = new VirtualDevice(name, product, stateFile);
		if (listener != null)
			logic.listeners.add(listener);
		logic.behavior = DeviceBehavior.forProduct(product, logic);

		final var device = new BaseKnxDevice(name, DD0.TYPE_07B0, logic, logic, stateFile.toUri(), new char[0]);
		logic.base = device;
		if (!restored)
			logic.initFreshIdentity(serial);

		// factory state: unprogrammed address 15.15.255; restored state: the address ETS assigned before
		final IndividualAddress startAddress = restored
				? io.calimero.device.ios.DeviceObject.lookup(device.getInterfaceObjectServer()).deviceAddress()
				: new IndividualAddress(0xFFFF);
		final KNXNetworkLink link = uplink.createDeviceLink(startAddress);
		device.setDeviceLink(link);
		logic.log("device", "created " + product.nameEn() + (restored ? " (state restored)" : " (factory state)")
				+ ", address " + device.getAddress());
		logic.afterTablesChanged();
		logic.startApplication();
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
	}

	public String name() { return name; }

	public ProductDef product() { return product; }

	public IndividualAddress address() { return base.getAddress(); }

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
		final var r = super.restart(masterReset, eraseCode, channel);
		afterTablesChanged();
		startApplication();
		changed();
		return r;
	}

	@Override
	public ServiceResult<byte[]> readProperty(final Destination remote, final int objectIndex, final int propertyId,
			final int startIndex, final int elements) {
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
		if (propertyId == PID.LOAD_STATE_CONTROL && objectIndex >= OBJ_ADDRESS_TABLE
				&& objectIndex <= OBJ_APPLICATION) {
			// function property command: first byte is the return code slot, load event follows
			final byte[] event = java.util.Arrays.copyOfRange(command, 1, command.length);
			return new ServiceResult<>(loadControl(objectIndex, event));
		}
		return super.functionPropertyCommand(remote, objectIndex, propertyId, command);
	}

	@Override
	public ServiceResult<Void> writeMemory(final int startAddress, final byte[] data) {
		final var r = super.writeMemory(startAddress, data);
		log("download", String.format("memory write 0x%06X, %d bytes -> %s", startAddress, data.length,
				r.getResult() == null ? "ok" : "ok"));
		return r;
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
		final byte[] region = new byte[size];
		if (fill)
			java.util.Arrays.fill(region, fillByte);
		if (size > 0)
			mem.set(SLOT_ADDRESS[obj], region);
		base.getInterfaceObjectServer().setProperty(obj, PID.TABLE_REFERENCE, 1, 1, intBytes(SLOT_ADDRESS[obj]));
		log("download", String.format("relative allocation obj %d: %d bytes at 0x%04X", obj, size, SLOT_ADDRESS[obj]));
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
		return base.getInterfaceObjectServer().getProperty(obj, PID.LOAD_STATE_CONTROL, 1, 1)[0] & 0xff;
	}

	/** (Re)starts the application behaviour if an application is loaded - after a download, a restart or process start. */
	private void startApplication() {
		behavior.onStop();
		if (applicationLoaded())
			behavior.onApplicationLoaded();
	}

	/** Timer thread shared by the behaviours (dimming ramps, blind travel, cyclic sending). */
	ScheduledExecutorService scheduler() { return scheduler; }

	/** Whether the application program (parameter area) is currently loaded. */
	public boolean applicationLoaded() { return loadState(OBJ_APPLICATION) == LS_LOADED; }

	public int downloadCount() { return downloadCount; }

	/** Raw parameter area as downloaded; empty when the application is not loaded. */
	byte[] parameterArea() {
		if (!applicationLoaded())
			return new byte[0];
		return base.deviceMemory().get(SLOT_ADDRESS[OBJ_APPLICATION], product.paramAreaSize());
	}

	/** Current value of a parameter: the downloaded value, or the product default when not loaded. */
	public int param(final String paramName) {
		final ParamDef p = product.param(paramName);
		final byte[] area = parameterArea();
		return area.length == 0 ? p.defaultValue() : p.read(area);
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
			base.getDeviceLink().sendRequest(ga, Priority.LOW, apdu);
			log("tx", "GroupValueWrite " + address() + " -> " + ga + " value " + value + " (object " + go + " " + oname
					+ ")");
			return true;
		}
		catch (final KNXException ex) {
			log("tx", "send failed: " + ex);
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
		behavior.onStop();
		scheduler.shutdownNow();
		base.close();
	}

	@Override
	public String toString() {
		return name + " (" + product.nameEn() + ") " + address();
	}
}
