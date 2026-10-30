package org.vknx.device;

import java.util.Map;

/**
 * Switch actuator with 8 outputs; every output can be disabled and named in ETS. Objects: 1..8 switch, 9..16 status.
 * ETS parameters: Out1Enable.., Out1Logic.. (relay logic), StartupState, SendStatus.
 */
public final class SwitchActuatorBehavior extends DeviceBehavior {
	static final int N = 8;
	private final int[] obj = new int[N]; // logical value of each Switch object

	SwitchActuatorBehavior(final VirtualDevice dev) { super(dev); }

	private boolean enabled(final int ch) { return dev.param("Out" + (ch + 1) + "Enable") == 1; }

	private boolean closed(final int ch) { return enabled(ch) && (obj[ch] == 1) != (dev.param("Out" + (ch + 1) + "Logic") == 1); }

	@Override
	void onApplicationLoaded() {
		final int startup = dev.param("StartupState");
		if (startup < 2)
			java.util.Arrays.fill(obj, startup);
		dev.log("relay", "application started, StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged"));
		for (int c = 0; c < N; c++)
			status(c);
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go < 1 || go > N)
			return;
		final int c = go - 1;
		if (!enabled(c)) {
			dev.log("relay", "output " + go + ": telegram ignored, parameter Out" + go + "Enable=No");
			return;
		}
		final boolean before = closed(c);
		obj[c] = value & 1;
		dev.log("relay", "output " + go + " (" + dev.paramText("Out" + go + "Name") + "): Switch=" + (obj[c] == 1 ? "ON" : "OFF")
				+ ", parameter Out" + go + "Logic=" + (dev.param("Out" + go + "Logic") == 1 ? "inverted" : "normal")
				+ " -> relay " + (closed(c) ? "closed" : "open"));
		if (before != closed(c))
			status(c);
	}

	private void status(final int c) {
		if (enabled(c) && dev.param("SendStatus") == 1)
			dev.transmit(N + 1 + c, obj[c]);
	}

	/** True while output {@code out} (1..8) has its relay closed (lamp lit). */
	public boolean lampLit(final int out) { return dev.applicationLoaded() && closed(out - 1); }

	/** Output 1 (tests). */
	public boolean lampLit() { return lampLit(1); }

	@Override Integer valueOf(final int go) { return go >= 1 && go <= 2 * N ? obj[(go - 1) % N] : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		final var lamps = new StringBuilder();
		for (int c = 0; c < N; c++) {
			lamps.append(dev.applicationLoaded() && closed(c) ? '1' : '0');
			m.put("e" + (c + 1), enabled(c));
			m.put("n" + (c + 1), dev.paramText("Out" + (c + 1) + "Name"));
		}
		m.put("lamps", lamps.toString());
		return m;
	}
}
