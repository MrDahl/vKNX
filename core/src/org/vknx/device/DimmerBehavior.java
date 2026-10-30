package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dimming actuator with 4 channels; every channel can be disabled and named in ETS. Objects of channel c (1..4):
 * 5(c-1)+1 switch, +2 relative dimming (4 bit), +3 brightness value (1 byte), +4 switch status, +5 brightness status.
 * Ramp speed, on level, startup state and status sending are ETS parameters per channel (Ch1DimTime ...).
 */
public final class DimmerBehavior extends DeviceBehavior {
	private static final int N = 4;
	private static final int[] DIM_SECONDS = { 2, 5, 10, 20 };
	private static final int TICK_MS = 50;
	private final double[] level = new double[N]; // 0..255
	private final double[] lastLevel = { 255, 255, 255, 255 };
	private final double[] target = new double[N];
	private final boolean[] ramping = new boolean[N];
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[N];

	DimmerBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int c) { return "Ch" + (c + 1); }

	private boolean enabled(final int c) { return dev.param(pre(c) + "Enable") == 1; }

	/** Brightness of channel 1 in percent (tests). */
	public int levelPct() { return levelPct(0); }

	/** Brightness of channel {@code c} (0-based) in percent. */
	public int levelPct(final int c) { return (int) Math.round(level[c] * 100.0 / 255.0); }

	private String who(final int c) { return "channel " + (c + 1) + " (" + dev.paramText(pre(c) + "Name") + ")"; }

	@Override
	synchronized void onApplicationLoaded() {
		for (int c = 0; c < N; c++) {
			stopRamp(c);
			if (!enabled(c)) {
				level[c] = 0;
				continue;
			}
			final int startup = dev.param(pre(c) + "StartupState");
			if (startup == 0)
				level[c] = 0;
			else if (startup == 1)
				level[c] = 255;
			dev.log("dimmer", who(c) + ": application started, StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged")
					+ " -> brightness " + levelPct(c) + " %");
			reportStatus(c);
		}
	}

	@Override
	synchronized void onStop() {
		for (int c = 0; c < N; c++)
			stopRamp(c);
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		final int c = (go - 1) / 5, kind = (go - 1) % 5 + 1;
		if (c >= N || kind > 3)
			return; // status objects are outputs
		if (!enabled(c)) {
			dev.log("dimmer", "channel " + (c + 1) + ": telegram ignored, parameter " + pre(c) + "Enable=No");
			return;
		}
		switch (kind) {
			case 1 -> {
				stopRamp(c);
				if ((value & 1) != 0) {
					final boolean full = dev.param(pre(c) + "OnLevel") == 1;
					level[c] = full || lastLevel[c] <= 0 ? 255 : lastLevel[c];
					dev.log("dimmer", who(c) + ": Switch=ON, parameter OnLevel=" + (full ? "100 %" : "last level") + " -> brightness "
							+ levelPct(c) + " %");
				}
				else {
					if (level[c] > 0)
						lastLevel[c] = level[c];
					level[c] = 0;
					dev.log("dimmer", who(c) + ": Switch=OFF -> brightness 0 % (last level " + Math.round(lastLevel[c] * 100 / 255) + " % remembered)");
				}
				reportStatus(c);
			}
			case 3 -> {
				stopRamp(c);
				level[c] = value & 0xff;
				if (level[c] > 0)
					lastLevel[c] = level[c];
				dev.log("dimmer", who(c) + ": brightness value received: " + levelPct(c) + " % (" + (value & 0xff) + "/255)");
				reportStatus(c);
			}
			default -> relative(c, value & 0x0f);
		}
		dev.changed();
	}

	private void relative(final int c, final int code) {
		final int step = code & 7;
		final boolean up = (code & 8) != 0;
		if (step == 0) {
			if (ramping[c]) {
				dev.log("dimmer", who(c) + ": relative dimming STOP at " + levelPct(c) + " %");
				stopRamp(c);
				reportStatus(c);
			}
			else
				dev.log("dimmer", who(c) + ": relative dimming STOP received, nothing was dimming");
			return;
		}
		final double span = step == 1 ? 255 : 255.0 / (1 << (step - 1));
		target[c] = up ? Math.min(255, level[c] + span) : Math.max(0, level[c] - span);
		if (step == 1)
			target[c] = up ? 255 : 0;
		final int secs = DIM_SECONDS[Math.min(3, dev.param(pre(c) + "DimTime"))];
		dev.log("dimmer", who(c) + ": relative dimming " + (up ? "UP" : "DOWN") + " step code " + step + " -> ramp to "
				+ Math.round(target[c] * 100 / 255) + " % at " + secs + " s for 0-100 % (parameter " + pre(c) + "DimTime)");
		if (up && level[c] == 0 && dev.param(pre(c) + "SendStatus") == 1)
			dev.transmit(5 * c + 4, 1);
		startRamp(c, secs);
	}

	private void startRamp(final int c, final int secondsFull) {
		stopRamp(c);
		ramping[c] = true;
		final double delta = 255.0 * TICK_MS / (secondsFull * 1000.0);
		task[c] = dev.every(() -> tick(c, delta), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
	}

	private synchronized void tick(final int c, final double delta) {
		if (!ramping[c])
			return;
		if (level[c] < target[c])
			level[c] = Math.min(target[c], level[c] + delta);
		else
			level[c] = Math.max(target[c], level[c] - delta);
		if (level[c] == target[c]) {
			stopRamp(c);
			if (level[c] > 0)
				lastLevel[c] = level[c];
			dev.log("dimmer", who(c) + ": ramp finished at " + levelPct(c) + " %");
			reportStatus(c);
		}
		dev.changed();
	}

	private void stopRamp(final int c) {
		ramping[c] = false;
		if (task[c] != null)
			task[c].cancel(false);
	}

	private void reportStatus(final int c) {
		if (dev.param(pre(c) + "SendStatus") == 1) {
			dev.transmit(5 * c + 4, level[c] > 0 ? 1 : 0);
			dev.transmit(5 * c + 5, (int) Math.round(level[c]));
		}
		else
			dev.log("dimmer", who(c) + ": status not sent: parameter " + pre(c) + "SendStatus=No");
	}

	@Override
	Integer valueOf(final int go) {
		final int c = (go - 1) / 5, kind = (go - 1) % 5 + 1;
		if (c >= N || go < 1)
			return null;
		return switch (kind) {
			case 4 -> level[c] > 0 ? 1 : 0;
			case 5 -> (int) Math.round(level[c]);
			default -> null;
		};
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < N; c++) {
			final int n = c + 1;
			m.put("e" + n, enabled(c));
			m.put("n" + n, dev.paramText(pre(c) + "Name"));
			m.put("level" + n, levelPct(c));
			m.put("ramping" + n, ramping[c]);
		}
		return m;
	}
}
