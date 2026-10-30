package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Blind actuator with 4 channels; every channel can be disabled and named in ETS. Per channel c (1..4): object
 * 3(c-1)+1 Move (0 = up, 1 = down), +2 Stop, +3 position status (1 byte, 0 % = fully open/up, 100 % = fully closed/down).
 * The travel time up and down is an ETS parameter per channel (Ch1UpTime, Ch1DownTime), SendPosition is global.
 */
public final class BlindBehavior extends DeviceBehavior {
	private static final int N = 4;
	private static final int TICK_MS = 100;
	private final double[] pos = new double[N]; // 0..100
	private final int[] dir = new int[N]; // -1 up, 0 stopped, +1 down
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[N];

	BlindBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int c) { return "Ch" + (c + 1); }

	private boolean enabled(final int c) { return dev.param(pre(c) + "Enable") == 1; }

	private String who(final int c) { return "blind " + (c + 1) + " (" + dev.paramText(pre(c) + "Name") + ")"; }

	@Override
	synchronized void onApplicationLoaded() {
		dev.log("blind", "application started: all blinds assumed fully open (0 %)");
		for (int c = 0; c < N; c++) {
			halt(c);
			pos[c] = 0;
			if (enabled(c))
				report(c);
		}
	}

	@Override
	synchronized void onStop() {
		for (int c = 0; c < N; c++)
			halt(c);
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		final int ch = (go - 1) / 3, kind = (go - 1) % 3; // 0 move, 1 stop, 2 status
		if (ch >= N || kind == 2)
			return;
		if (!enabled(ch)) {
			dev.log("blind", "blind " + (ch + 1) + ": telegram ignored, parameter " + pre(ch) + "Enable=No");
			return;
		}
		if (kind == 0) {
			final int d = (value & 1) != 0 ? 1 : -1;
			final int secs = Math.max(1, dev.param(pre(ch) + (d > 0 ? "DownTime" : "UpTime")));
			dev.log("blind", who(ch) + " Move=" + (d > 0 ? "DOWN" : "UP") + ", full travel " + secs + " s (parameter " + pre(ch)
					+ (d > 0 ? "DownTime" : "UpTime") + ")");
			start(ch, d, secs);
		}
		else {
			if (dir[ch] != 0)
				dev.log("blind", who(ch) + " Stop received at " + Math.round(pos[ch]) + " %");
			else
				dev.log("blind", who(ch) + " Stop received, blind was not moving");
			halt(ch);
			report(ch);
		}
		dev.changed();
	}

	private void start(final int ch, final int d, final int secs) {
		halt(ch);
		dir[ch] = d;
		final double delta = 100.0 * TICK_MS / (secs * 1000.0);
		task[ch] = dev.every(() -> tick(ch, delta), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
	}

	private synchronized void tick(final int ch, final double delta) {
		if (dir[ch] == 0)
			return;
		pos[ch] = Math.max(0, Math.min(100, pos[ch] + dir[ch] * delta));
		if (pos[ch] == 0 || pos[ch] == 100) {
			dev.log("blind", who(ch) + " reached the end position (" + Math.round(pos[ch]) + " %)");
			halt(ch);
			report(ch);
		}
		dev.changed();
	}

	private void halt(final int ch) {
		dir[ch] = 0;
		if (task[ch] != null)
			task[ch].cancel(false);
	}

	private void report(final int ch) {
		if (dev.param("SendPosition") == 1)
			dev.transmit(3 * ch + 3, (int) Math.round(pos[ch] * 255.0 / 100.0));
		else
			dev.log("blind", who(ch) + ": position not sent: parameter SendPosition=No");
	}

	@Override
	Integer valueOf(final int go) {
		return go >= 1 && go <= 3 * N && (go - 1) % 3 == 2 ? (int) Math.round(pos[(go - 1) / 3] * 255.0 / 100.0) : null;
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < N; c++) {
			final int n = c + 1;
			m.put("e" + n, enabled(c));
			m.put("n" + n, dev.paramText(pre(c) + "Name"));
			m.put("pos" + n, (int) Math.round(pos[c]));
			m.put("dir" + n, dir[c]);
		}
		return m;
	}
}
