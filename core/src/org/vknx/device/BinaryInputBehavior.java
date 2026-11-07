package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Binary input with four inputs for potential-free contacts. The contact of every input is simulated on the control panel
 * (a latching switch or a push button you hold). What a contact does is set in ETS per input:
 * <ul>
 * <li>Switching: a command (on, off, toggle or nothing) when the contact closes and another when it opens; the state of
 * the switch object can also be sent cyclically;</li>
 * <li>Relative dimming: short press (contact closed and opened again within the long-press time) switches, long press sends
 * the dimming command, opening after a long press sends STOP;</li>
 * <li>Blinds: short press sends step/stop, long press sends move up or down.</li>
 * </ul>
 * Object numbers of input k (1..4): 4(k-1)+1 switch, +2 relative dimming, +3 blind move, +4 blind step/stop.
 */
public final class BinaryInputBehavior extends DeviceBehavior {
	private static final int N = Products.BI_INPUTS;
	private static final int[] LONG_MS = { 400, 600, 1000 };
	private final boolean[] closed = new boolean[N + 1]; // state of the simulated contact
	private final boolean[] byPress = new boolean[N + 1]; // closed by the press-and-hold button (opens again on release)
	private final boolean[] on = new boolean[N + 1]; // value of the switch object
	private final boolean[] longFired = new boolean[N + 1];
	private final boolean[] nextDown = new boolean[N + 1];
	private final String[] last = new String[N + 1];
	private final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[N + 1];
	private final ScheduledFuture<?>[] cyclic = new ScheduledFuture<?>[N + 1];

	BinaryInputBehavior(final VirtualDevice dev) {
		super(dev);
		java.util.Arrays.fill(last, "");
		java.util.Arrays.fill(nextDown, true);
	}

	private static String pre(final int k) { return "In" + k; }

	private static int base(final int k) { return (k - 1) * 4; }

	private boolean enabled(final int k) { return dev.param(pre(k) + "Enable") == 1; }

	private String name(final int k) { return dev.paramText(pre(k) + "Name"); }

	private int function(final int k) { return dev.param(pre(k) + "Function"); }

	private String who(final int k) { return "input " + k + " (" + name(k) + ")"; }

	@Override
	public synchronized void act(final String action, final int key) {
		if (key < 1 || key > N) {
			super.act(action, key);
			return;
		}
		switch (action) {
			case "down" -> { // press-and-hold button
				if (closed[key]) {
					dev.log("input", who(key) + ": contact is already closed, the press button does nothing");
					break;
				}
				byPress[key] = true;
				setContact(key, true);
			}
			case "up" -> {
				if (byPress[key]) {
					byPress[key] = false;
					setContact(key, false);
				}
			}
			case "toggle" -> {
				if (byPress[key])
					break;
				setContact(key, !closed[key]);
			}
			case "close" -> setContact(key, true);
			case "open" -> setContact(key, false);
			default -> super.act(action, key);
		}
		dev.changed();
	}

	/** The simulated contact changes: closing or opening edge. */
	private void setContact(final int k, final boolean close) {
		if (closed[k] == close)
			return;
		closed[k] = close;
		if (!dev.applicationLoaded()) {
			dev.log("input", "input " + k + " contact " + (close ? "closed" : "opened") + ", but no application is loaded: nothing happens");
			return;
		}
		if (!enabled(k)) {
			dev.log("input", "input " + k + " contact " + (close ? "closed" : "opened") + ", but parameter " + pre(k) + "Enable=No: nothing happens");
			return;
		}
		switch (function(k)) {
			case 0 -> switching(k, close);
			default -> pressRelease(k, close);
		}
	}

	private void switching(final int k, final boolean close) {
		final int cmd = dev.param(pre(k) + (close ? "Closing" : "Opening"));
		final String edge = close ? "closes (parameter " + pre(k) + "Closing" : "opens (parameter " + pre(k) + "Opening";
		if (cmd == 0) {
			dev.log("input", who(k) + " contact " + edge + "=Nothing): nothing is sent");
			return;
		}
		final boolean value = cmd == 1 || (cmd == 3 && !on[k]);
		dev.log("input", who(k) + " contact " + edge + "=" + (cmd == 1 ? "On" : cmd == 2 ? "Off" : "Toggle") + ") -> sending " + (value ? "ON" : "OFF"));
		on[k] = value;
		last[k] = value ? "On" : "Off";
		dev.transmit(base(k) + 1, value ? 1 : 0);
	}

	/** Dimming and blinds: closing = key pressed, opening = key released. */
	private void pressRelease(final int k, final boolean pressed) {
		if (pressed) {
			longFired[k] = false;
			final int ms = LONG_MS[Math.min(2, dev.param(pre(k) + "LongPress"))];
			dev.log("input", who(k) + " contact closed, waiting " + ms + " ms (parameter LongPress) to tell short from long press");
			pending[k] = dev.later(() -> longPress(k), ms, TimeUnit.MILLISECONDS);
			return;
		}
		if (pending[k] != null)
			pending[k].cancel(false);
		if (function(k) == 1) {
			if (longFired[k]) {
				dev.log("input", who(k) + " contact opened after long press -> sending dim STOP");
				last[k] = "Dim stop";
				dev.transmit(base(k) + 2, 0);
			}
			else {
				final boolean value = dev.param(pre(k) + "DimDir") == 0;
				dev.log("input", who(k) + " contact opened before the long-press time: short press -> sending " + (value ? "ON" : "OFF"));
				on[k] = value;
				last[k] = value ? "On" : "Off";
				dev.transmit(base(k) + 1, value ? 1 : 0);
			}
		}
		else if (!longFired[k]) {
			final int dir = blindDir(k);
			dev.log("input", who(k) + " contact opened before the long-press time: short press -> sending step/stop (" + (dir == 0 ? "up" : "down") + ")");
			last[k] = "Step/stop";
			dev.transmit(base(k) + 4, dir);
		}
	}

	private int blindDir(final int k) {
		return switch (dev.param(pre(k) + "BlindMode")) {
			case 0 -> 0;
			case 1 -> 1;
			default -> nextDown[k] ? 1 : 0;
		};
	}

	private synchronized void longPress(final int k) {
		if (!closed[k])
			return;
		longFired[k] = true;
		if (function(k) == 1) {
			final boolean brighter = dev.param(pre(k) + "DimDir") == 0;
			final int step = dev.param(pre(k) + "DimStep");
			dev.log("input", who(k) + ": long press -> sending dim " + (brighter ? "UP" : "DOWN") + " step code " + step + " (parameter DimStep)");
			last[k] = "Dim " + (brighter ? "up" : "down");
			dev.transmit(base(k) + 2, (brighter ? 8 : 0) | step);
		}
		else {
			final int dir = blindDir(k);
			dev.log("input", who(k) + ": long press -> sending move " + (dir == 0 ? "UP" : "DOWN"));
			last[k] = "Move " + (dir == 0 ? "up" : "down");
			dev.transmit(base(k) + 3, dir);
			if (dev.param(pre(k) + "BlindMode") == 2)
				nextDown[k] = !nextDown[k];
		}
		dev.changed();
	}

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		java.util.Arrays.fill(on, false);
		java.util.Arrays.fill(nextDown, true);
		java.util.Arrays.fill(last, "");
		for (int k = 1; k <= N; k++) {
			if (!enabled(k) || function(k) != 0)
				continue;
			final int secs = dev.param(pre(k) + "Cyclic");
			if (secs > 0) {
				final int kk = k;
				cyclic[k] = dev.every(() -> sendCyclic(kk), secs, secs, TimeUnit.SECONDS);
				dev.log("input", who(k) + ": the state of the switch object is sent every " + secs + " s (parameter " + pre(k) + "Cyclic)");
			}
		}
		dev.changed();
	}

	private synchronized void sendCyclic(final int k) {
		dev.transmit(base(k) + 1, on[k] ? 1 : 0, true);
		dev.log("input", who(k) + " cyclic: " + (on[k] ? "ON" : "OFF") + " sent");
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		for (int k = 1; k <= N; k++) {
			if (pending[k] != null)
				pending[k].cancel(false);
			if (cyclic[k] != null)
				cyclic[k].cancel(false);
		}
	}

	@Override
	void onObjectReceived(final int go, final int value) { /* inputs only send */ }

	@Override
	Integer valueOf(final int go) {
		final int k = (go - 1) / 4 + 1;
		return k <= N && (go - 1) % 4 == 0 && go >= 1 ? (on[k] ? 1 : 0) : null;
	}

	private String describe(final int k) {
		return switch (function(k)) {
			case 0 -> "Switching: closes " + edge(dev.param(pre(k) + "Closing")) + ", opens " + edge(dev.param(pre(k) + "Opening"))
					+ (dev.param(pre(k) + "Cyclic") > 0 ? ", cyclic " + dev.param(pre(k) + "Cyclic") + " s" : "");
			case 1 -> "Dimming: " + (dev.param(pre(k) + "DimDir") == 0 ? "brighter" : "darker");
			default -> "Blinds: " + switch (dev.param(pre(k) + "BlindMode")) { case 0 -> "up"; case 1 -> "down"; default -> "up / down"; };
		};
	}

	private static String edge(final int cmd) {
		return switch (cmd) { case 0 -> "nothing"; case 1 -> "on"; case 2 -> "off"; default -> "toggle"; };
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int k = 1; k <= N; k++) {
			m.put("e" + k, enabled(k));
			m.put("n" + k, name(k));
			m.put("d" + k, describe(k));
			m.put("c" + k, closed[k]);
			m.put("s" + k, on[k]);
			m.put("l" + k, last[k]);
		}
		return m;
	}
}
