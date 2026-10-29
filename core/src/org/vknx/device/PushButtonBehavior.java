package org.vknx.device;

import java.util.Map;

/** One-button sensor: toggle / on-only / off-only, optionally following its status object. */
public final class PushButtonBehavior extends DeviceBehavior {
	private volatile boolean state;

	PushButtonBehavior(final VirtualDevice dev) { super(dev); }

	/** Operates the button. The value sent depends on the downloaded "ButtonFunction" parameter. */
	public void press() {
		if (!dev.applicationLoaded()) {
			dev.log("button", "button pressed, but no application is loaded: nothing happens");
			return;
		}
		final int function = dev.param("ButtonFunction");
		final boolean value = switch (function) {
			case 1 -> true;
			case 2 -> false;
			default -> !state;
		};
		dev.log("button", "button pressed, parameter ButtonFunction=" + switch (function) {
			case 1 -> "on only";
			case 2 -> "off only";
			default -> "toggle (current " + (state ? "on" : "off") + ")";
		} + " -> sending " + (value ? "ON" : "OFF"));
		state = value;
		dev.transmit(1, value ? 1 : 0);
		dev.changed();
	}

	@Override
	public void act(final String action, final int value) {
		if ("press".equals(action))
			press();
		else
			super.act(action, value);
	}

	@Override void onApplicationLoaded() { state = false; }

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go == 2 && dev.param("FollowStatus") == 1) {
			state = (value & 1) != 0;
			dev.log("button", "status object received, next toggle will send " + (state ? "OFF" : "ON"));
		}
		else if (go == 2)
			dev.log("button", "status object received but parameter FollowStatus=No: ignored");
	}

	@Override Integer valueOf(final int go) { return go == 1 || go == 2 ? (state ? 1 : 0) : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("state", state);
		return m;
	}
}
