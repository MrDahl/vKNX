package org.vknx.device;

import java.util.Map;

/** One-channel switch actuator driving a relay (and the lamp wired to it). */
public final class SwitchActuatorBehavior extends DeviceBehavior {
	private volatile int objectValue; // logical value of the Switch object (1 = "on" telegram)
	private volatile boolean initialized;

	SwitchActuatorBehavior(final VirtualDevice dev) { super(dev); }

	/** True when the relay contact is closed, i.e. the lamp is lit. Depends on the downloaded RelayLogic parameter. */
	public boolean relayClosed() {
		final boolean inverted = dev.param("RelayLogic") == 1;
		return (objectValue == 1) != inverted;
	}

	/** Lamp state shown in the UI: only meaningful when an application is loaded. */
	public boolean lampLit() { return dev.applicationLoaded() && relayClosed(); }

	@Override
	void onApplicationLoaded() {
		final int startup = dev.param("StartupState");
		switch (startup) {
			case 0 -> objectValue = 0;
			case 1 -> objectValue = 1;
			default -> { /* unchanged */ }
		}
		initialized = true;
		dev.log("relay", "application loaded, StartupState=" + switch (startup) {
			case 0 -> "off";
			case 1 -> "on";
			default -> "unchanged";
		} + " -> relay " + (relayClosed() ? "closed (lamp on)" : "open (lamp off)"));
		reportStatus();
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go != 1)
			return;
		final int v = value & 1;
		final boolean before = relayClosed();
		objectValue = v;
		final boolean after = relayClosed();
		dev.log("relay", "Switch=" + (v == 1 ? "ON" : "OFF") + ", parameter RelayLogic="
				+ (dev.param("RelayLogic") == 1 ? "inverted" : "normal") + " -> relay " + (after ? "closed" : "open")
				+ " (lamp " + (after ? "ON" : "OFF") + ")");
		if (before != after || !initialized)
			reportStatus();
		initialized = true;
	}

	private void reportStatus() {
		if (dev.param("SendStatusOnChange") == 1)
			dev.transmit(2, objectValue);
		else
			dev.log("relay", "status not sent: parameter SendStatusOnChange=No");
	}

	@Override Integer valueOf(final int go) { return go == 2 || go == 1 ? objectValue : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("relayClosed", relayClosed());
		m.put("lamp", lampLit());
		return m;
	}
}
