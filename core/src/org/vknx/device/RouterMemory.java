package org.vknx.device;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;

import io.calimero.device.KnxDevice.Memory;
import io.calimero.device.ServiceResult;
import io.calimero.mgmt.PropertyAccess.PID;

/**
 * What a coupler (mask 0x0912) keeps in its device memory, so that it is saved with the device: the settings of the
 * router object (how group telegrams and individually addressed telegrams are routed in each direction) and the group
 * filter table (one bit per group address). The load state machine of the router application stays in
 * {@link VirtualDevice}; this class only reads and writes the memory.
 */
final class RouterMemory {
	static final int PID_ROUTETABLE_CONTROL = 56, PID_MAX_ROUTING_APDU = 58;
	/** Router configuration kept in device memory: lsm, pid 52, 53, 54, 55, 57, 59, run error. */
	private static final int CFG = 0x01F0;
	/** Group filter table: one bit per raw group address, set = route (MV-0912 LcFilterMemory 512, 8192 bytes). */
	static final int FILTER_TABLE = 0x0200, FILTER_SIZE = 8192;
	private static final int[] ROUTER_PIDS = { PID.LOAD_STATE_CONTROL, 52, 53, 54, 55, 57, 59 };

	private final Memory mem;
	private final int routerIndex;
	private final BiConsumer<String, String> log;
	private final Runnable changed;

	/**
	 * @param routerIndex index of the router object in the interface object server
	 * @param log category and text, written to the device log
	 * @param changed called when a setting or the filter table changed
	 */
	RouterMemory(final Memory mem, final int routerIndex, final BiConsumer<String, String> log, final Runnable changed) {
		this.mem = mem;
		this.routerIndex = routerIndex;
		this.log = log;
		this.changed = changed;
	}

	/** Load state of the router application as stored in the device memory. */
	static int loadState(final Memory mem) { return mem.get(CFG) & 0xff; }

	void setLoadState(final int state) { mem.set(CFG, (byte) state); }

	/** Factory state: nothing loaded, everything routed (modelling choice, see docs/limitations.md), empty filter table. */
	void factoryState(final int unloadedState) {
		mem.set(CFG, (byte) unloadedState, (byte) 0x15, (byte) 0x15, (byte) 0x15, (byte) 0x15, (byte) 0, (byte) 0, (byte) 0xFF);
	}

	/**
	 * An unloaded coupler is no longer the factory-new "route all" device: it has lost its application, so it falls back
	 * to filtering with an empty table (group telegrams blocked, individually addressed ones filtered).
	 */
	void unloaded() {
		mem.set(CFG + 1, (byte) 0x77, (byte) 0x77, (byte) 0x17, (byte) 0x17);
		mem.set(FILTER_TABLE, new byte[FILTER_SIZE]);
	}

	/** Raw router property value, or null if the property is not one of the coupler's. */
	byte[] property(final int objectIndex, final int pid) {
		if (objectIndex == 0 && pid == 53)
			return new byte[] { (byte) mem.get(CFG + 7) };
		if (objectIndex != routerIndex)
			return null;
		if (pid == PID_MAX_ROUTING_APDU)
			return new byte[] { 0, 66 };
		for (int i = 0; i < ROUTER_PIDS.length; i++)
			if (ROUTER_PIDS[i] == pid)
				return new byte[] { (byte) mem.get(CFG + i) };
		return null;
	}

	void setProperty(final int objectIndex, final int pid, final byte[] data) {
		if (data.length == 0)
			return;
		if (objectIndex == 0 && pid == 53) {
			mem.set(CFG + 7, data[0]);
			return;
		}
		for (int i = 1; i < ROUTER_PIDS.length; i++)
			if (ROUTER_PIDS[i] == pid) {
				mem.set(CFG + i, data[0]);
				log.accept("download", "router property " + pid + " = 0x" + HexFormat.of().formatHex(new byte[] { data[0] }));
				changed.run();
			}
	}

	/** Router configuration byte for a property id (52, 53, 54, 55, 57, 59). */
	int settingByte(final int pid) {
		final byte[] v = property(routerIndex, pid);
		return v == null ? 0 : v[0] & 0xff;
	}

	/** True if the filter table says the raw group address is routed. */
	boolean filterBit(final int rawGroupAddress) {
		final int b = mem.get(FILTER_TABLE + (rawGroupAddress >> 3)) & 0xff;
		return (b >> (rawGroupAddress & 7) & 1) != 0;
	}

	/** Number of group addresses set in the filter table. */
	int filterCount() {
		int n = 0;
		for (final byte b : mem.get(FILTER_TABLE, FILTER_SIZE))
			n += Integer.bitCount(b & 0xff);
		return n;
	}

	/** The group addresses that are set in the filter table (for the panel; capped). */
	List<Integer> filterAddresses(final int max) {
		final var out = new ArrayList<Integer>();
		final byte[] t = mem.get(FILTER_TABLE, FILTER_SIZE);
		for (int ga = 0; ga < FILTER_SIZE * 8 && out.size() < max; ga++)
			if ((t[ga >> 3] >> (ga & 7) & 1) != 0)
				out.add(ga);
		return out;
	}

	/** PID_ROUTETABLE_CONTROL function property: 1 = clear filter table, 2 = set all, 3 = clear range, 4 = set range. */
	ServiceResult<byte[]> routeTableControl(final byte[] command) {
		final int service = command.length > 1 ? command[1] & 0xff : 0;
		switch (service) {
			case 1, 2 -> {
				final byte[] t = new byte[FILTER_SIZE];
				if (service == 2)
					java.util.Arrays.fill(t, (byte) 0xFF);
				mem.set(FILTER_TABLE, t);
				log.accept("download", "filter table " + (service == 1 ? "cleared" : "set to route everything"));
				changed.run();
				return new ServiceResult<>((byte) 0, (byte) service);
			}
			case 3, 4 -> {
				final int from = command.length >= 4 ? ((command[2] & 0xff) << 8) | (command[3] & 0xff) : 0;
				final int to = command.length >= 6 ? ((command[4] & 0xff) << 8) | (command[5] & 0xff) : 0xFFFF;
				for (int ga = from; ga <= to; ga++) {
					final int a = FILTER_TABLE + (ga >> 3), m = 1 << (ga & 7), cur = mem.get(a);
					mem.set(a, service == 4 ? cur | m : cur & ~m);
				}
				log.accept("download", "filter table " + (service == 3 ? "cleared" : "set") + " for raw group addresses " + from + ".." + to);
				changed.run();
				return new ServiceResult<>((byte) 0, (byte) service, command.length > 2 ? command[2] : 0,
						command.length > 3 ? command[3] : 0, command.length > 4 ? command[4] : (byte) 0xFF,
						command.length > 5 ? command[5] : (byte) 0xFF);
			}
			default -> {
				return ServiceResult.of(io.calimero.ReturnCode.Error, new byte[0]);
			}
		}
	}
}
