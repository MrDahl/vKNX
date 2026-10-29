package org.vknx.device;

import java.util.Map;

/**
 * N-channel switch actuator (4 or 8 outputs). Objects: 1..N switch, N+1..2N status.
 * ETS parameters: Ch1Logic.. (relay logic per channel), StartupState, SendStatus.
 */
public final class SwitchActuatorNBehavior extends DeviceBehavior {
	private final int n;
	private final int[] obj; // logical value of each Switch object

	SwitchActuatorNBehavior(final VirtualDevice dev, final int channels) {
		super(dev);
		n = channels;
		obj = new int[n];
	}

	private boolean closed(final int ch) { return (obj[ch] == 1) != (dev.param("Ch" + (ch + 1) + "Logic") == 1); }

	@Override
	void onApplicationLoaded() {
		final int startup = dev.param("StartupState");
		if (startup < 2)
			java.util.Arrays.fill(obj, startup);
		dev.log("relay", "application started, StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged"));
		for (int c = 0; c < n; c++)
			status(c);
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go < 1 || go > n)
			return;
		final int c = go - 1;
		final boolean before = closed(c);
		obj[c] = value & 1;
		dev.log("relay", "output " + go + ": Switch=" + (obj[c] == 1 ? "ON" : "OFF") + ", parameter Ch" + go + "Logic="
				+ (dev.param("Ch" + go + "Logic") == 1 ? "inverted" : "normal") + " -> relay " + (closed(c) ? "closed" : "open"));
		if (before != closed(c))
			status(c);
	}

	private void status(final int c) {
		if (dev.param("SendStatus") == 1)
			dev.transmit(n + 1 + c, obj[c]);
	}

	@Override Integer valueOf(final int go) { return go >= 1 && go <= 2 * n ? obj[(go - 1) % n] : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		final var lamps = new StringBuilder();
		for (int c = 0; c < n; c++)
			lamps.append(dev.applicationLoaded() && closed(c) ? '1' : '0');
		m.put("lamps", lamps.toString());
		return m;
	}
}
