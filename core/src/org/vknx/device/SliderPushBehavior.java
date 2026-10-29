package org.vknx.device;

import java.util.Map;

/** Push button with a slider: the button switches, the slider sends a brightness value (1 byte, 0..100 % = 0..255). */
public final class SliderPushBehavior extends DeviceBehavior {
	private volatile boolean on;
	private volatile int levelPct;
	private volatile long lastLive;

	SliderPushBehavior(final VirtualDevice dev) { super(dev); }

	private static int toByte(final int pct) { return (int) Math.round(Math.max(0, Math.min(100, pct)) * 255.0 / 100.0); }

	private static int toPct(final int raw) { return (int) Math.round((raw & 0xff) * 100.0 / 255.0); }

	@Override
	public void act(final String action, final int value) {
		if (!dev.applicationLoaded()) {
			dev.log("button", "operated, but no application is loaded: nothing happens");
			return;
		}
		switch (action) {
			case "press" -> {
				final int f = dev.param("ButtonFunction");
				final boolean v = f == 1 ? true : f == 2 ? false : !on;
				dev.log("button", "button pressed, parameter ButtonFunction=" + (f == 1 ? "on only" : f == 2 ? "off only"
						: "toggle (current " + (on ? "on" : "off") + ")") + " -> sending " + (v ? "ON" : "OFF"));
				on = v;
				dev.transmit(1, v ? 1 : 0);
			}
			case "slider", "sliderLive" -> {
				final boolean live = action.equals("sliderLive");
				levelPct = Math.max(0, Math.min(100, value));
				if (live && dev.param("SliderSend") == 0)
					break; // parameter says: only send when the slider is released
				if (live) {
					final long now = System.currentTimeMillis();
					if (now - lastLive < 250)
						break; // limit the telegram rate while sliding
					lastLive = now;
				}
				dev.log("button", "slider " + (live ? "moving" : "released") + " at " + levelPct + " % -> sending value "
						+ toByte(levelPct) + " (0..255)");
				dev.transmit(2, toByte(levelPct));
			}
			default -> super.act(action, value);
		}
		dev.changed();
	}

	@Override void onApplicationLoaded() { on = false; levelPct = 0; }

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go == 3) {
			levelPct = toPct(value);
			on = levelPct > 0;
			dev.log("button", "brightness status received: " + levelPct + " %");
		}
	}

	@Override Integer valueOf(final int go) { return go == 1 ? (on ? 1 : 0) : go == 2 || go == 3 ? toByte(levelPct) : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("state", on);
		m.put("level", levelPct);
		return m;
	}
}
