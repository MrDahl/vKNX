package org.vknx.device;

import java.util.HexFormat;
import java.util.function.BiConsumer;

import io.calimero.device.ios.InterfaceObjectServer;
import io.calimero.device.ios.SecurityObject;
import io.calimero.device.ios.SecurityObject.Pid;

/**
 * The KNX Data Secure side of a virtual device: the factory key (FDSK) and the device certificate ETS asks for, what a
 * master reset deletes and keeps, and the bridge that copies what Calimero's secure layer logs into the device's own log.
 * Taken out of {@link VirtualDevice}; it only works on the security interface object of the device.
 */
final class DeviceSecurity {
	private final InterfaceObjectServer ios;
	private final BiConsumer<String, String> log;

	/** @param log category and text, written to the device log */
	DeviceSecurity(final InterfaceObjectServer ios, final BiConsumer<String, String> log) {
		this.ios = ios;
		this.log = log;
	}

	private SecurityObject object() { return SecurityObject.lookup(ios); }

	/**
	 * A new device: the factory default setup key (FDSK) ETS needs for the first, secure download, and a sending sequence
	 * number above 1 (a device that starts at 1 first asks another device for a sync, S-A_Sync.req, before it can send a
	 * group telegram, and no such partner is known yet).
	 */
	void initFresh() {
		final byte[] fdsk = new byte[16];
		new java.security.SecureRandom().nextBytes(fdsk);
		final var sec = object();
		sec.set(Pid.Fdsk, fdsk);
		final long seq = 2 + new java.security.SecureRandom().nextInt(1_000_000);
		final byte[] seqBytes = java.nio.ByteBuffer.allocate(8).putLong(seq).array();
		final byte[] six = java.util.Arrays.copyOfRange(seqBytes, 2, 8);
		sec.set(Pid.SequenceNumberSending, six);
		sec.set(Pid.InitialSequenceNumberSending, six);
	}

	/**
	 * Master reset of a secure device (ETS does this when "Secure commissioning" is switched off or on): the tool key,
	 * group keys, group object security flags and security mode are deleted, so the factory key (FDSK) is in use again.
	 * The FDSK and serial number themselves never change.
	 */
	void reset() {
		final var sec = object();
		sec.set(Pid.SecurityMode, 1, 1, (byte) 0);
		sec.set(Pid.ToolKey, new byte[16]);
		sec.clear(Pid.GroupKeyTable);
		sec.clear(Pid.P2PKeyTable);
		sec.clear(Pid.SecurityIndividualAddressTable);
		sec.clear(Pid.GoSecurityFlags);
		log.accept("mgmt", "security reset: keys deleted, factory key (FDSK) in use again");
	}

	private static final int[] KEPT_PIDS = { Pid.GroupKeyTable, Pid.ZoneKeyTable, Pid.GoSecurityFlags, Pid.P2PKeyTable,
		Pid.SecurityIndividualAddressTable };

	/** Tool key, security mode and the key/flag tables: {pid, element count, data}. */
	java.util.List<Object[]> snapshot() {
		final var sec = object();
		final var out = new java.util.ArrayList<Object[]>();
		out.add(new Object[] { Pid.ToolKey, 1, sec.get(Pid.ToolKey) });
		out.add(new Object[] { Pid.SecurityMode, 1, sec.get(Pid.SecurityMode) });
		for (final int pid : KEPT_PIDS) {
			try {
				final byte[] n = sec.get(pid, 0, 1);
				final int count = ((n[0] & 0xff) << 8) | (n[1] & 0xff);
				out.add(new Object[] { pid, count, count == 0 ? new byte[0] : sec.get(pid) });
			}
			catch (final RuntimeException e) { /* property not there: nothing to keep */ }
		}
		return out;
	}

	/** Puts back what {@link #snapshot()} kept, where it differs. */
	void restore(final java.util.List<Object[]> kept) {
		final var sec = object();
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
			catch (final RuntimeException e) { log.accept("mgmt", "could not keep security property " + pid + ": " + e); }
		}
	}

	byte[] fdsk() { return object().get(Pid.Fdsk); }

	/** The factory default setup key as 32 hex digits. */
	String fdskHex() { return HexFormat.of().withUpperCase().formatHex(fdsk()); }

	/** True once ETS has switched on security mode (the secure download completed far enough). */
	boolean modeOn() { return (object().get(Pid.SecurityMode, 1, 1)[0] & 0xff) == 1; }

	/** True once ETS replaced the factory key with its own tool key: the FDSK is no longer used. */
	boolean toolKeyActive() {
		for (final byte b : object().get(Pid.ToolKey))
			if (b != 0)
				return true;
		return false;
	}

	/**
	 * The device certificate ETS asks for ("Add Device Certificate"): serial number (6 bytes) + FDSK (16 bytes) as
	 * Base32 (A-Z, 2-7) plus a 4-bit CRC, 36 characters in six blocks of six. ETS does not accept 0 or 1, so the hex FDSK cannot be typed.
	 * Verified against the example in the KNX Secure course (serial 0007:EE952A16 = AAD65F-JKCY6F-...).
	 */
	static String certificate(final byte[] serial, final byte[] fdsk) {
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

	// ---- what the secure layer does, in the device's own log (for finding out why ETS and a device do not agree)

	private static final java.util.Map<String, VirtualDevice> DEVICES = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean bridgeInstalled;
	private static final java.util.List<java.util.logging.Logger> LOGGERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** From now on what Calimero's secure layer logs about the device with this name goes to the device's log. */
	static void watch(final String name, final VirtualDevice device) {
		DEVICES.put(name, device);
		installLogBridge();
	}

	static void unwatch(final String name, final VirtualDevice device) { DEVICES.remove(name, device); }

	private static synchronized void installLogBridge() {
		if (bridgeInstalled)
			return;
		bridgeInstalled = true;
		final var handler = new java.util.logging.Handler() {
			@Override
			public void publish(final java.util.logging.LogRecord r) {
				final String logger = r.getLoggerName();
				if (logger == null)
					return;
				for (final var e : DEVICES.entrySet())
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
			// java.util.logging keeps loggers only weakly: without a strong reference a garbage collection can drop the
			// logger together with its level and handler, and the secure log would silently stay empty
			LOGGERS.add(l);
		}
	}
}
