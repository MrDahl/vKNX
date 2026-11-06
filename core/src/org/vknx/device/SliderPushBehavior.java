package org.vknx.device;

import java.util.Map;

/**
 * Push button with a slider: the button switches, the slider sends a brightness value (1 byte, 0..100 % = 0..255).
 * Objects: 5 scene (optional second key: call, long press can store), 1 switch, 2 brightness value, 3 brightness status (input), 4 switch status (input). A received switch status
 * (parameter FollowStatus) sets the state the next press toggles from.
 */
public final class SliderPushBehavior extends DeviceBehavior {
	private volatile boolean on;
	private volatile int levelPct;
	private volatile long lastLive;
	private volatile String lastScene = "";
	private boolean sceneHeld, sceneLongFired;
	private java.util.concurrent.ScheduledFuture<?> scenePending;

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
				on = levelPct > 0; // the next press must toggle relative to what the slider just set
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
			case "down" -> sceneDown();
			case "up" -> sceneUp();
			default -> super.act(action, value);
		}
		dev.changed();
	}

	private static final int[] LONG_MS = { 400, 600, 1000 };

	private boolean sceneKey() { return dev.param("SceneEnable") == 1; }

	private void sendScene(final boolean store, final String why) {
		final int n = dev.param("Scene");
		dev.log("button", "scene key" + why + (store ? ": long press -> sending STORE scene " : " -> sending CALL scene ") + n
				+ " (DPT 18.001 value 0x" + Integer.toHexString((store ? 0x80 : 0) | (n - 1)) + ")");
		lastScene = (store ? "Store scene " : "Scene ") + n;
		dev.transmit(5, (store ? 0x80 : 0) | (n - 1));
	}

	private synchronized void sceneDown() {
		if (!sceneKey()) {
			dev.log("button", "scene key operated, but parameter SceneEnable=No: nothing happens");
			return;
		}
		if (sceneHeld)
			return;
		sceneHeld = true;
		sceneLongFired = false;
		if (dev.param("SceneStore") == 1) {
			final int ms = LONG_MS[Math.min(2, dev.param("SceneLongPress"))];
			dev.log("button", "scene key pressed, waiting " + ms + " ms (parameter SceneLongPress) to tell call from store");
			scenePending = dev.later(() -> {
				synchronized (this) {
					if (!sceneHeld)
						return;
					sceneLongFired = true;
					sendScene(true, "");
				}
				dev.changed();
			}, ms, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		else
			sendScene(false, " pressed");
	}

	private synchronized void sceneUp() {
		if (!sceneHeld)
			return;
		sceneHeld = false;
		if (scenePending != null)
			scenePending.cancel(false);
		if (sceneKey() && dev.param("SceneStore") == 1 && !sceneLongFired)
			sendScene(false, " released before the long-press time: short press");
	}

	@Override void onApplicationLoaded() { on = false; levelPct = 0; }

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go == 3) {
			levelPct = toPct(value);
			on = levelPct > 0;
			dev.log("button", "brightness status received: " + levelPct + " %");
		}
		else if (go == 4) {
			if (dev.param("FollowStatus") == 0) {
				dev.log("button", "switch status received but parameter FollowStatus=No: ignored");
				return;
			}
			on = (value & 1) != 0;
			dev.log("button", "switch status received -> " + (on ? "on" : "off"));
		}
	}

	@Override Integer valueOf(final int go) { return go == 1 || go == 4 ? (on ? 1 : 0) : go == 2 || go == 3 ? toByte(levelPct) : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("state", on);
		m.put("level", levelPct);
		m.put("sceneKey", dev.applicationLoaded() && sceneKey() ? dev.param("Scene") : 0);
		m.put("scene", lastScene);
		return m;
	}
}
