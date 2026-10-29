package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-key rocker (double push button): short press switches, long press dims (relative dimming, 4 bit),
 * release after a long press sends the stop telegram. The long-press time is an ETS parameter.
 */
public final class RockerBehavior extends DeviceBehavior {
	private static final int[] LONG_MS = { 400, 600, 1000 };
	private volatile boolean statusOn;
	private final boolean[] held = new boolean[3];
	private final boolean[] longFired = new boolean[3];
	private final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[3];

	RockerBehavior(final VirtualDevice dev) { super(dev); }

	/** True when the upper key sends ON / brighter (depends on parameter Orientation). */
	private boolean upperIsOn() { return dev.param("Orientation") == 0; }

	@Override
	public synchronized void act(final String action, final int key) {
		if (key < 1 || key > 2)
			return;
		if (!dev.applicationLoaded()) {
			dev.log("button", "key pressed, but no application is loaded: nothing happens");
			return;
		}
		final boolean on = (key == 1) == upperIsOn(); // does this key mean ON/brighter?
		final String keyName = key == 1 ? "upper key" : "lower key";
		if ("down".equals(action)) {
			held[key] = true;
			longFired[key] = false;
			final int ms = LONG_MS[Math.min(2, dev.param("LongPressTime"))];
			dev.log("button", keyName + " pressed, waiting " + ms + " ms (parameter LongPressTime) to tell short from long press");
			pending[key] = dev.later(() -> longPress(key, on), ms, TimeUnit.MILLISECONDS);
		}
		else if ("up".equals(action) && held[key]) {
			held[key] = false;
			if (pending[key] != null)
				pending[key].cancel(false);
			if (longFired[key]) {
				dev.log("button", keyName + " released after long press -> sending dim STOP");
				dev.transmit(2, 0);
			}
			else {
				dev.log("button", keyName + " released before the long-press time: short press -> sending " + (on ? "ON" : "OFF"));
				dev.transmit(1, on ? 1 : 0);
			}
		}
		dev.changed();
	}

	private synchronized void longPress(final int key, final boolean brighter) {
		if (!held[key])
			return;
		longFired[key] = true;
		final int step = dev.param("DimStep");
		dev.log("button", "long press detected -> sending dim " + (brighter ? "UP" : "DOWN") + " step code " + step
				+ " (parameter DimStep)");
		dev.transmit(2, (brighter ? 8 : 0) | step);
		dev.changed();
	}

	@Override void onApplicationLoaded() { statusOn = false; }

	@Override
	void onStop() {
		for (int i = 0; i < 3; i++) {
			held[i] = false;
			if (pending[i] != null)
				pending[i].cancel(false);
		}
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go == 3) {
			statusOn = (value & 1) != 0;
			dev.log("button", "status object received -> indicator " + (statusOn ? "on" : "off"));
		}
	}

	@Override Integer valueOf(final int go) { return go == 3 ? (statusOn ? 1 : 0) : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("state", statusOn);
		return m;
	}
}
