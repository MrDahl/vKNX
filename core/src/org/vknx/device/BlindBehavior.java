package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-channel blind actuator. Per channel c (0/1): object 3c+1 Move (0 = up, 1 = down), 3c+2 Stop,
 * 3c+3 position status (1 byte, 0 % = fully open/up, 100 % = fully closed/down). Travel time is an ETS parameter.
 */
public final class BlindBehavior extends DeviceBehavior {
	private static final int[] RUN_SECONDS = { 10, 20, 30, 60 };
	private static final int TICK_MS = 100;
	private final double[] pos = new double[2]; // 0..100
	private final int[] dir = new int[2]; // -1 up, 0 stopped, +1 down
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[2];

	BlindBehavior(final VirtualDevice dev) { super(dev); }

	private int runSeconds(final int ch) { return RUN_SECONDS[Math.min(3, dev.param(ch == 0 ? "Ch1RunTime" : "Ch2RunTime"))]; }

	@Override
	void onApplicationLoaded() {
		dev.log("blind", "application started: both blinds assumed fully open (0 %)");
		pos[0] = pos[1] = 0;
		dir[0] = dir[1] = 0;
		report(0);
		report(1);
	}

	@Override
	synchronized void onStop() {
		for (int c = 0; c < 2; c++) {
			dir[c] = 0;
			if (task[c] != null)
				task[c].cancel(false);
		}
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		final int ch = (go - 1) / 3;
		final int kind = (go - 1) % 3; // 0 move, 1 stop, 2 status
		if (kind == 0) {
			final int d = (value & 1) != 0 ? 1 : -1;
			final int secs = runSeconds(ch);
			dev.log("blind", "blind " + (ch + 1) + " Move=" + (d > 0 ? "DOWN" : "UP") + ", full travel " + secs
					+ " s (parameter Ch" + (ch + 1) + "RunTime)");
			start(ch, d, secs);
		}
		else if (kind == 1) {
			if (dir[ch] != 0)
				dev.log("blind", "blind " + (ch + 1) + " Stop received at " + Math.round(pos[ch]) + " %");
			else
				dev.log("blind", "blind " + (ch + 1) + " Stop received, blind was not moving");
			halt(ch);
			report(ch);
		}
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
			dev.log("blind", "blind " + (ch + 1) + " reached the end position (" + Math.round(pos[ch]) + " %)");
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
			dev.log("blind", "position not sent: parameter SendPosition=No");
	}

	@Override
	Integer valueOf(final int go) {
		return (go - 1) % 3 == 2 ? (int) Math.round(pos[(go - 1) / 3] * 255.0 / 100.0) : null;
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < 2; c++) {
			m.put("pos" + (c + 1), (int) Math.round(pos[c]));
			m.put("dir" + (c + 1), dir[c]);
		}
		return m;
	}
}
