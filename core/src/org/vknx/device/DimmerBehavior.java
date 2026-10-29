package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One-channel dimming actuator. Objects: 1 switch, 2 relative dimming (4 bit), 3 brightness value (1 byte),
 * 4 switch status, 5 brightness status. Ramp speed comes from the ETS parameter DimTime.
 */
public final class DimmerBehavior extends DeviceBehavior {
	private static final int[] DIM_SECONDS = { 2, 5, 10, 20 };
	private static final int TICK_MS = 50;
	private volatile double level; // 0..255
	private volatile double lastLevel = 255;
	private volatile double target;
	private volatile boolean ramping;
	private ScheduledFuture<?> task;

	DimmerBehavior(final VirtualDevice dev) { super(dev); }

	public int levelPct() { return (int) Math.round(level * 100.0 / 255.0); }

	@Override
	void onApplicationLoaded() {
		final int startup = dev.param("StartupState");
		if (startup == 0)
			level = 0;
		else if (startup == 1)
			level = 255;
		dev.log("dimmer", "application started, StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged")
				+ " -> brightness " + levelPct() + " %");
		reportStatus();
	}

	@Override
	synchronized void onStop() {
		if (task != null)
			task.cancel(false);
		ramping = false;
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		switch (go) {
			case 1 -> {
				stopRamp();
				if ((value & 1) != 0) {
					final boolean full = dev.param("OnLevel") == 1;
					level = full || lastLevel <= 0 ? 255 : lastLevel;
					dev.log("dimmer", "Switch=ON, parameter OnLevel=" + (full ? "100 %" : "last level") + " -> brightness "
							+ levelPct() + " %");
				}
				else {
					if (level > 0)
						lastLevel = level;
					level = 0;
					dev.log("dimmer", "Switch=OFF -> brightness 0 % (last level " + Math.round(lastLevel * 100 / 255) + " % remembered)");
				}
				reportStatus();
			}
			case 3 -> {
				stopRamp();
				level = value & 0xff;
				if (level > 0)
					lastLevel = level;
				dev.log("dimmer", "brightness value received: " + levelPct() + " % (" + (value & 0xff) + "/255)");
				reportStatus();
			}
			case 2 -> relative(value & 0x0f);
			default -> { /* status objects are outputs */ }
		}
	}

	private void relative(final int code) {
		final int step = code & 7;
		final boolean up = (code & 8) != 0;
		if (step == 0) {
			if (ramping) {
				dev.log("dimmer", "relative dimming STOP at " + levelPct() + " %");
				stopRamp();
				reportStatus();
			}
			else
				dev.log("dimmer", "relative dimming STOP received, nothing was dimming");
			return;
		}
		final double span = step == 1 ? 255 : 255.0 / (1 << (step - 1));
		target = up ? Math.min(255, level + span) : Math.max(0, level - span);
		if (step == 1)
			target = up ? 255 : 0;
		final int secs = DIM_SECONDS[Math.min(3, dev.param("DimTime"))];
		dev.log("dimmer", "relative dimming " + (up ? "UP" : "DOWN") + " step code " + step + " -> ramp to " + Math.round(target * 100 / 255)
				+ " % at " + secs + " s for 0-100 % (parameter DimTime)");
		if (up && level == 0 && dev.param("SendStatus") == 1)
			dev.transmit(4, 1);
		startRamp(secs);
	}

	private void startRamp(final int secondsFull) {
		stopRamp();
		ramping = true;
		final double delta = 255.0 * TICK_MS / (secondsFull * 1000.0);
		task = dev.scheduler().scheduleAtFixedRate(() -> tick(delta), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
	}

	private synchronized void tick(final double delta) {
		if (!ramping)
			return;
		if (level < target)
			level = Math.min(target, level + delta);
		else
			level = Math.max(target, level - delta);
		if (level == target) {
			stopRamp();
			if (level > 0)
				lastLevel = level;
			dev.log("dimmer", "ramp finished at " + levelPct() + " %");
			reportStatus();
		}
		dev.changed();
	}

	private void stopRamp() {
		ramping = false;
		if (task != null)
			task.cancel(false);
	}

	private void reportStatus() {
		if (dev.param("SendStatus") == 1) {
			dev.transmit(4, level > 0 ? 1 : 0);
			dev.transmit(5, (int) Math.round(level));
		}
		else
			dev.log("dimmer", "status not sent: parameter SendStatus=No");
	}

	@Override
	Integer valueOf(final int go) {
		return switch (go) {
			case 4 -> level > 0 ? 1 : 0;
			case 5 -> (int) Math.round(level);
			default -> null;
		};
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("level", levelPct());
		m.put("on", level > 0);
		m.put("ramping", ramping);
		return m;
	}
}
