package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-key blind rocker: a short press sends step/stop (object 2, DPT 1.007), a long press sends move up/down
 * (object 1, DPT 1.008). 0 = up, 1 = down. The long-press time and which key means "up" are ETS parameters.
 */
public final class BlindRockerBehavior extends DeviceBehavior {
	private static final int[] LONG_MS = { 400, 600, 1000 };
	private volatile String last = "";
	private final boolean[] held = new boolean[3];
	private final boolean[] longFired = new boolean[3];
	private final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[3];

	BlindRockerBehavior(final VirtualDevice dev) { super(dev); }

	private boolean upperIsUp() { return dev.param("Orientation") == 0; }

	@Override
	public synchronized void act(final String action, final int key) {
		if (key < 1 || key > 2)
			return;
		if (!dev.applicationLoaded()) {
			dev.log("button", "key pressed, but no application is loaded: nothing happens");
			return;
		}
		final boolean up = (key == 1) == upperIsUp();
		final String keyName = key == 1 ? "upper key" : "lower key";
		if ("down".equals(action)) {
			held[key] = true;
			longFired[key] = false;
			final int ms = LONG_MS[Math.min(2, dev.param("LongPressTime"))];
			dev.log("button", keyName + " pressed, waiting " + ms + " ms (parameter LongPressTime) to tell short from long press");
			pending[key] = dev.later(() -> longPress(key, up), ms, TimeUnit.MILLISECONDS);
		}
		else if ("up".equals(action) && held[key]) {
			held[key] = false;
			if (pending[key] != null)
				pending[key].cancel(false);
			if (longFired[key])
				dev.log("button", keyName + " released after long press: the blind keeps moving until stopped or at its end");
			else {
				dev.log("button", keyName + " released before the long-press time: short press -> sending step/stop "
						+ (up ? "(up)" : "(down)"));
				last = "Step/stop " + (up ? "up" : "down");
				dev.transmit(2, up ? 0 : 1);
			}
		}
		dev.changed();
	}

	private synchronized void longPress(final int key, final boolean up) {
		if (!held[key])
			return;
		longFired[key] = true;
		dev.log("button", "long press detected -> sending move " + (up ? "UP" : "DOWN"));
		last = "Move " + (up ? "up" : "down");
		dev.transmit(1, up ? 0 : 1);
		dev.changed();
	}

	@Override
	void onStop() {
		for (int i = 0; i < 3; i++) {
			held[i] = false;
			if (pending[i] != null)
				pending[i].cancel(false);
		}
	}

	@Override void onApplicationLoaded() { last = ""; }

	@Override void onObjectReceived(final int go, final int value) { /* output-only device */ }

	@Override Integer valueOf(final int go) { return null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("last", last);
		return m;
	}
}
