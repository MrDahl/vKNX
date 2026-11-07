package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Blind actuator with 4 channels; every channel can be disabled and named in ETS. Per channel c (1..4): object
 * 3(c-1)+1 Move (0 = up, 1 = down), +2 Stop, +3 position status (1 byte, 0 % = fully open/up, 100 % = fully closed/down).
 * The travel time up and down is an ETS parameter per channel (Ch1UpTime, Ch1DownTime), SendPosition is global.
 * <p>Weather alarms: object 13 wind alarm and 14 rain alarm (1 = alarm). While an alarm is active a channel that reacts to it
 * (parameters ChNWindReaction, ChNRainReaction) drives up or down (wind has priority) and ignores move and stop telegrams;
 * when every alarm that concerns it is over it stays where it is and is operable again. WindMonitor and RainMonitor (minutes,
 * 0 = off) supervise the alarm objects: if no telegram arrives in time, the alarm is assumed to be active (fail-safe), as with
 * a real awning control when the weather station fails.
 */
public final class BlindBehavior extends DeviceBehavior {
	private static final int N = 4;
	private static final int TICK_MS = 100;
	private final double[] pos = new double[N]; // 0..100
	private final int[] dir = new int[N]; // -1 up, 0 stopped, +1 down
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[N];
	private final boolean[] alarm = new boolean[2]; // 0 wind, 1 rain (including an alarm assumed because the station is silent)
	private final boolean[] silent = new boolean[2]; // the alarm is only assumed (monitoring timeout)
	private final ScheduledFuture<?>[] monitor = new ScheduledFuture<?>[2];

	BlindBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int c) { return "Ch" + (c + 1); }

	private boolean enabled(final int c) { return dev.param(pre(c) + "Enable") == 1; }

	private String who(final int c) { return "blind " + (c + 1) + " (" + dev.paramText(pre(c) + "Name") + ")"; }

	@Override
	synchronized void onApplicationLoaded() {
		dev.log("blind", "application started: all blinds assumed fully open (0 %)");
		java.util.Arrays.fill(alarm, false);
		java.util.Arrays.fill(silent, false);
		for (int a = 0; a < 2; a++)
			watch(a);
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
		for (final var m : monitor)
			if (m != null)
				m.cancel(false);
	}

	private static String alarmName(final int a) { return a == 0 ? "wind" : "rain"; }

	/** (Re)starts the supervision of alarm object a (0 wind, 1 rain): no telegram within the timeout means alarm. */
	private void watch(final int a) {
		if (monitor[a] != null)
			monitor[a].cancel(false);
		final int minutes = dev.param(a == 0 ? "WindMonitor" : "RainMonitor");
		if (minutes > 0)
			monitor[a] = dev.later(() -> timeout(a, minutes), (long) (minutes * 60_000L / timeScale), TimeUnit.MILLISECONDS);
	}

	private synchronized void timeout(final int a, final int minutes) {
		dev.log("blind", alarmName(a) + " alarm: no telegram for " + minutes + " minutes (parameter " + (a == 0 ? "WindMonitor" : "RainMonitor")
				+ ") -> alarm assumed, the weather station may have failed");
		silent[a] = true;
		setAlarm(a, true);
		dev.changed();
	}

	/** A telegram of the wind (a = 0) or rain (a = 1) alarm object. */
	private void alarmTelegram(final int a, final boolean on) {
		silent[a] = false;
		watch(a);
		if (alarm[a] == on) {
			dev.log("blind", alarmName(a) + " alarm " + (on ? "ON" : "off") + " received (unchanged)");
			return;
		}
		setAlarm(a, on);
	}

	private void setAlarm(final int a, final boolean on) {
		alarm[a] = on;
		dev.log("blind", alarmName(a) + " alarm " + (on ? "ON" : "off") + (silent[a] ? " (assumed)" : " received"));
		for (int c = 0; c < N; c++) {
			if (!enabled(c) || reaction(c, a) == 0)
				continue;
			if (on)
				drive(c);
			else if (!locked(c))
				dev.log("blind", who(c) + ": alarm is over, the blind stays at " + Math.round(pos[c]) + " % and can be operated again");
		}
	}

	/** What blind c does for alarm a: 0 ignores, 1 up, 2 down. */
	private int reaction(final int c, final int a) { return dev.param(pre(c) + (a == 0 ? "WindReaction" : "RainReaction")); }

	private boolean locked(final int c) { return enabled(c) && ((alarm[0] && reaction(c, 0) != 0) || (alarm[1] && reaction(c, 1) != 0)); }

	/** Sends blind c to the position its active alarm asks for (wind before rain). */
	private void drive(final int c) {
		final int a = alarm[0] && reaction(c, 0) != 0 ? 0 : 1;
		final int d = reaction(c, a) == 1 ? -1 : 1;
		final double end = d < 0 ? 0 : 100;
		if (pos[c] == end) {
			dev.log("blind", who(c) + ": " + alarmName(a) + " alarm, already " + (d < 0 ? "up" : "down") + "; operation locked");
			return;
		}
		final int secs = Math.max(1, dev.param(pre(c) + (d > 0 ? "DownTime" : "UpTime")));
		dev.log("blind", who(c) + ": " + alarmName(a) + " alarm -> moving " + (d < 0 ? "UP" : "DOWN") + " (full travel " + secs + " s); operation locked");
		start(c, d, secs);
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		if (go == 13 || go == 14) {
			alarmTelegram(go - 13, (value & 1) != 0);
			dev.changed();
			return;
		}
		final int ch = (go - 1) / 3, kind = (go - 1) % 3; // 0 move, 1 stop, 2 status
		if (ch >= N || kind == 2)
			return;
		if (!enabled(ch)) {
			dev.log("blind", "blind " + (ch + 1) + ": telegram ignored, parameter " + pre(ch) + "Enable=No");
			return;
		}
		if (locked(ch)) {
			dev.log("blind", who(ch) + ": " + (kind == 0 ? "Move" : "Stop") + " ignored, locked by the " + (alarm[0] && reaction(ch, 0) != 0 ? "wind" : "rain") + " alarm");
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
	public synchronized Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < N; c++) {
			final int n = c + 1;
			m.put("e" + n, enabled(c));
			m.put("n" + n, dev.paramText(pre(c) + "Name"));
			m.put("pos" + n, (int) Math.round(pos[c]));
			m.put("dir" + n, dir[c]);
			m.put("lock" + n, locked(c));
			m.put("wr" + n, reaction(c, 0));
			m.put("rr" + n, reaction(c, 1));
		}
		m.put("windAlarm", alarm[0]);
		m.put("rainAlarm", alarm[1]);
		m.put("windSilent", silent[0]);
		m.put("rainSilent", silent[1]);
		return m;
	}
}
