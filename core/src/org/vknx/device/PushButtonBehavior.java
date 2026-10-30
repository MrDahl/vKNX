package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Push button with four keys. Every key can be disabled and named in ETS and has one function:
 * <ul>
 * <li>Switch: sends on, off or alternating on/off immediately when the key is pressed;</li>
 * <li>Relative dimming: short press switches (on for "brighter", off for "darker"), long press sends the dimming
 * command, release after a long press sends STOP;</li>
 * <li>Blinds: Up, Down or Up/Down (alternating): short press sends step/stop, long press sends move; Steps: the key
 * sends a step at once.</li>
 * </ul>
 * Object numbers of key k (1..4): 5(k-1)+1 switch, +2 status, +3 dimming, +4 move, +5 step/stop.
 */
public final class PushButtonBehavior extends DeviceBehavior {
	private static final int KEYS = 4;
	private static final int[] LONG_MS = { 400, 600, 1000 };
	private final boolean[] on = new boolean[KEYS + 1];
	private final boolean[] held = new boolean[KEYS + 1];
	private final boolean[] longFired = new boolean[KEYS + 1];
	private final boolean[] nextDown = new boolean[KEYS + 1]; // alternating blind key: next move direction
	private final String[] last = new String[KEYS + 1];
	private final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[KEYS + 1];

	PushButtonBehavior(final VirtualDevice dev) {
		super(dev);
		java.util.Arrays.fill(last, "");
		java.util.Arrays.fill(nextDown, true);
	}

	private static String pre(final int k) { return "Key" + k; }

	private static int base(final int k) { return (k - 1) * 5; }

	private boolean enabled(final int k) { return dev.param(pre(k) + "Enable") == 1; }

	private String name(final int k) { return dev.paramText(pre(k) + "Name"); }

	@Override
	public synchronized void act(final String action, final int key) {
		if (key < 1 || key > KEYS) {
			super.act(action, key);
			return;
		}
		switch (action) {
			case "down" -> down(key);
			case "up" -> up(key);
			case "press" -> { down(key); up(key); }
			default -> super.act(action, key);
		}
		dev.changed();
	}

	private void down(final int k) {
		if (!dev.applicationLoaded()) {
			dev.log("button", "key " + k + " pressed, but no application is loaded: nothing happens");
			return;
		}
		if (!enabled(k)) {
			dev.log("button", "key " + k + " pressed, but parameter Key" + k + "Enable=No: nothing happens");
			return;
		}
		final String who = "key " + k + " (" + name(k) + ")";
		held[k] = true;
		longFired[k] = false;
		switch (dev.param(pre(k) + "Function")) {
			case 0 -> {
				final int mode = dev.param(pre(k) + "SwitchMode");
				final boolean value = mode == 0 || (mode == 2 && !on[k]);
				dev.log("button", who + " pressed, SwitchMode=" + (mode == 0 ? "on" : mode == 1 ? "off"
						: "alternating (current " + (on[k] ? "on" : "off") + ")") + " -> sending " + (value ? "ON" : "OFF"));
				on[k] = value;
				last[k] = value ? "On" : "Off";
				dev.transmit(base(k) + 1, value ? 1 : 0);
			}
			case 1 -> {
				final int ms = LONG_MS[Math.min(2, dev.param(pre(k) + "LongPress"))];
				dev.log("button", who + " pressed, waiting " + ms + " ms (parameter LongPress) to tell short from long press");
				pending[k] = dev.later(() -> longPress(k), ms, TimeUnit.MILLISECONDS);
			}
			default -> {
				final int mode = dev.param(pre(k) + "BlindMode");
				if (mode == 3) { // Steps: no long press
					final int dir = dev.param(pre(k) + "BlindDir");
					dev.log("button", who + " pressed, BlindMode=Steps -> sending step " + (dir == 0 ? "UP" : "DOWN"));
					last[k] = "Step " + (dir == 0 ? "up" : "down");
					dev.transmit(base(k) + 5, dir);
				}
				else {
					final int ms = LONG_MS[Math.min(2, dev.param(pre(k) + "LongPress"))];
					dev.log("button", who + " pressed, waiting " + ms + " ms (parameter LongPress) to tell short from long press");
					pending[k] = dev.later(() -> longPress(k), ms, TimeUnit.MILLISECONDS);
				}
			}
		}
	}

	/** Direction of a blind key: 0 = up, 1 = down. */
	private int blindDir(final int k) {
		return switch (dev.param(pre(k) + "BlindMode")) {
			case 0 -> 0;
			case 1 -> 1;
			default -> nextDown[k] ? 1 : 0;
		};
	}

	private synchronized void longPress(final int k) {
		if (!held[k])
			return;
		longFired[k] = true;
		if (dev.param(pre(k) + "Function") == 1) {
			final boolean brighter = dev.param(pre(k) + "DimDir") == 0;
			final int step = dev.param(pre(k) + "DimStep");
			dev.log("button", "key " + k + ": long press -> sending dim " + (brighter ? "UP" : "DOWN") + " step code " + step
					+ " (parameter DimStep)");
			last[k] = "Dim " + (brighter ? "up" : "down");
			dev.transmit(base(k) + 3, (brighter ? 8 : 0) | step);
		}
		else {
			final int dir = blindDir(k);
			dev.log("button", "key " + k + ": long press -> sending move " + (dir == 0 ? "UP" : "DOWN"));
			last[k] = "Move " + (dir == 0 ? "up" : "down");
			dev.transmit(base(k) + 4, dir);
			if (dev.param(pre(k) + "BlindMode") == 2)
				nextDown[k] = !nextDown[k];
		}
		dev.changed();
	}

	private void up(final int k) {
		if (!held[k])
			return;
		held[k] = false;
		if (pending[k] != null)
			pending[k].cancel(false);
		final int fn = dev.param(pre(k) + "Function");
		if (fn == 0 || (fn == 2 && dev.param(pre(k) + "BlindMode") == 3))
			return; // acted on press
		if (fn == 1) {
			if (longFired[k]) {
				dev.log("button", "key " + k + " released after long press -> sending dim STOP");
				last[k] = "Dim stop";
				dev.transmit(base(k) + 3, 0);
			}
			else {
				final boolean value = dev.param(pre(k) + "DimDir") == 0;
				dev.log("button", "key " + k + " released before the long-press time: short press -> sending " + (value ? "ON" : "OFF"));
				on[k] = value;
				last[k] = value ? "On" : "Off";
				dev.transmit(base(k) + 1, value ? 1 : 0);
			}
		}
		else if (!longFired[k]) {
			final int dir = blindDir(k);
			dev.log("button", "key " + k + " released before the long-press time: short press -> sending step/stop (" + (dir == 0 ? "up" : "down") + ")");
			last[k] = "Step/stop";
			dev.transmit(base(k) + 5, dir);
		}
	}

	@Override void onApplicationLoaded() {
		java.util.Arrays.fill(on, false);
		java.util.Arrays.fill(nextDown, true); // an alternating blind key starts with DOWN (blinds start open)
		java.util.Arrays.fill(last, "");
	}

	@Override
	void onStop() {
		for (int k = 1; k <= KEYS; k++) {
			held[k] = false;
			if (pending[k] != null)
				pending[k].cancel(false);
		}
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		final int k = (go - 1) / 5 + 1;
		if ((go - 1) % 5 != 1 || k > KEYS)
			return;
		if (dev.param(pre(k) + "Function") == 0 && dev.param(pre(k) + "FollowStatus") == 0) {
			dev.log("button", "key " + k + ": status received but parameter FollowStatus=No: ignored");
			return;
		}
		on[k] = (value & 1) != 0;
		dev.log("button", "key " + k + ": status received -> " + (on[k] ? "on" : "off"));
	}

	@Override
	Integer valueOf(final int go) {
		final int k = (go - 1) / 5 + 1, kind = (go - 1) % 5;
		return k <= KEYS && kind <= 1 ? (on[k] ? 1 : 0) : null;
	}

	private String describe(final int k) {
		return switch (dev.param(pre(k) + "Function")) {
			case 0 -> "Switch: " + switch (dev.param(pre(k) + "SwitchMode")) { case 0 -> "on"; case 1 -> "off"; default -> "alternating"; };
			case 1 -> "Dimming: " + (dev.param(pre(k) + "DimDir") == 0 ? "brighter" : "darker");
			default -> "Blinds: " + switch (dev.param(pre(k) + "BlindMode")) {
				case 0 -> "up"; case 1 -> "down"; case 2 -> "up / down";
				default -> "step " + (dev.param(pre(k) + "BlindDir") == 0 ? "up" : "down"); };
		};
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int k = 1; k <= KEYS; k++) {
			m.put("e" + k, enabled(k));
			m.put("n" + k, name(k));
			m.put("d" + k, describe(k));
			m.put("s" + k, on[k]);
			m.put("l" + k, last[k]);
		}
		return m;
	}
}
