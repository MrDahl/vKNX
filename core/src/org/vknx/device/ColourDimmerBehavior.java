package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Colour dimming actuator with two channels; each channel is RGB or tunable white (parameter Type). Objects of channel c
 * (1..2): 9(c-1)+1 switch, +2 relative dimming (4 bit), +3 brightness (1 byte), +4 RGB colour (3 bytes), +5 colour
 * temperature (2 bytes, Kelvin), +6 switch status, +7 brightness status, +8 RGB status, +9 colour temperature status.
 * <p>
 * Model: a channel has a colour (RGB, or a colour temperature) and a brightness. The brightness objects dim the light
 * without changing the colour. An RGB telegram sets the colour and switches the channel on with its last brightness;
 * black (0,0,0) switches it off and keeps the colour. The RGB status is the colour while the channel is on and black
 * while it is off. A colour temperature is limited to the range set in ETS (warmest to coldest white).
 */
public final class ColourDimmerBehavior extends DeviceBehavior {
	private static final int N = Products.COLOUR_CHANNELS, K = Products.COLOUR_OBJS;
	private static final int[] DIM_SECONDS = { 2, 5, 10, 20 };
	private static final int TICK_MS = 50;
	private final double[] level = new double[N]; // 0..255
	private final double[] lastLevel = new double[N];
	private final double[] target = new double[N];
	private final boolean[] ramping = new boolean[N];
	private final int[] rgb = new int[N];
	private final int[] kelvin = new int[N];
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[N];

	ColourDimmerBehavior(final VirtualDevice dev) {
		super(dev);
		for (int c = 0; c < N; c++) {
			lastLevel[c] = 255;
			rgb[c] = 0xFFFFFF;
			kelvin[c] = 3000;
		}
	}

	private static String pre(final int c) { return "Ch" + (c + 1); }

	private boolean enabled(final int c) { return dev.param(pre(c) + "Enable") == 1; }

	private boolean white(final int c) { return dev.param(pre(c) + "Type") == 1; }

	private int warm(final int c) { return dev.param(pre(c) + "WarmK") * 100; }

	private int cold(final int c) { return Math.max(warm(c), dev.param(pre(c) + "ColdK") * 100); }

	private String who(final int c) { return "channel " + (c + 1) + " (" + dev.paramText(pre(c) + "Name") + ")"; }

	/** Brightness of channel {@code c} (0-based) in percent. */
	public int levelPct(final int c) { return (int) Math.round(level[c] * 100.0 / 255.0); }

	/** Colour of channel {@code c} (0-based) as 0xRRGGBB. */
	public int rgb(final int c) { return rgb[c]; }

	/** Colour temperature of channel {@code c} (0-based) in Kelvin. */
	public int kelvin(final int c) { return kelvin[c]; }

	private static String hex(final int v) { return String.format("#%06X", v & 0xFFFFFF); }

	@Override
	synchronized void onApplicationLoaded() {
		for (int c = 0; c < N; c++) {
			stopRamp(c);
			if (!enabled(c)) {
				level[c] = 0;
				continue;
			}
			kelvin[c] = Math.max(warm(c), Math.min(cold(c), kelvin[c]));
			final int startup = dev.param(pre(c) + "StartupState");
			if (startup == 0)
				level[c] = 0;
			else if (startup == 1)
				level[c] = 255;
			dev.log("dimmer", who(c) + ": application started as " + (white(c) ? "tunable white " + warm(c) + "-" + cold(c) + " K" : "RGB")
					+ ", StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged") + " -> brightness " + levelPct(c) + " %");
			reportStatus(c, true);
		}
	}

	@Override
	synchronized void onStop() {
		for (int c = 0; c < N; c++)
			stopRamp(c);
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		final int c = (go - 1) / K, kind = (go - 1) % K + 1;
		if (go < 1 || c >= N || kind > 5)
			return; // status objects are outputs
		if (!enabled(c)) {
			dev.log("dimmer", "channel " + (c + 1) + ": telegram ignored, parameter " + pre(c) + "Enable=No");
			return;
		}
		switch (kind) {
			case 1 -> {
				stopRamp(c);
				if ((value & 1) != 0) {
					level[c] = lastLevel[c] <= 0 ? 255 : lastLevel[c];
					dev.log("dimmer", who(c) + ": Switch=ON -> brightness " + levelPct(c) + " % (last level)");
				}
				else {
					remember(c);
					level[c] = 0;
					dev.log("dimmer", who(c) + ": Switch=OFF -> brightness 0 %");
				}
				reportStatus(c, true);
			}
			case 2 -> relative(c, value & 0x0f);
			case 3 -> {
				stopRamp(c);
				level[c] = value & 0xff;
				remember(c);
				dev.log("dimmer", who(c) + ": brightness value received: " + levelPct(c) + " % (" + (value & 0xff) + "/255)");
				reportStatus(c, true);
			}
			case 4 -> {
				if (white(c)) {
					dev.log("dimmer", who(c) + ": RGB telegram ignored, the channel is set to tunable white in ETS");
					return;
				}
				stopRamp(c);
				final int v = value & 0xFFFFFF;
				if (v == 0) {
					remember(c);
					level[c] = 0;
					dev.log("dimmer", who(c) + ": colour black (0,0,0) received -> switched off, the colour " + hex(rgb[c]) + " is kept");
				}
				else {
					rgb[c] = v;
					if (level[c] <= 0)
						level[c] = lastLevel[c] <= 0 ? 255 : lastLevel[c];
					dev.log("dimmer", who(c) + ": colour received: red " + (v >> 16 & 0xff) + ", green " + (v >> 8 & 0xff) + ", blue " + (v & 0xff)
							+ " (" + hex(v) + ") -> on at " + levelPct(c) + " %");
				}
				reportStatus(c, true);
			}
			default -> {
				if (!white(c)) {
					dev.log("dimmer", who(c) + ": colour temperature telegram ignored, the channel is set to RGB in ETS");
					return;
				}
				final int want = value & 0xFFFF;
				kelvin[c] = Math.max(warm(c), Math.min(cold(c), want));
				dev.log("dimmer", who(c) + ": colour temperature received: " + want + " K"
						+ (kelvin[c] != want ? " -> limited to " + kelvin[c] + " K (range " + warm(c) + "-" + cold(c) + " K in ETS)" : ""));
				reportStatus(c, false);
			}
		}
		dev.changed();
	}

	private void remember(final int c) {
		if (level[c] > 0)
			lastLevel[c] = level[c];
	}

	private void relative(final int c, final int code) {
		final int step = code & 7;
		final boolean up = (code & 8) != 0;
		if (step == 0) {
			if (ramping[c]) {
				dev.log("dimmer", who(c) + ": relative dimming STOP at " + levelPct(c) + " %");
				stopRamp(c);
				remember(c);
				reportStatus(c, true);
			}
			else
				dev.log("dimmer", who(c) + ": relative dimming STOP received, nothing was dimming");
			return;
		}
		final double span = step == 1 ? 255 : 255.0 / (1 << (step - 1));
		target[c] = up ? Math.min(255, level[c] + span) : Math.max(0, level[c] - span);
		final int secs = DIM_SECONDS[Math.min(3, dev.param(pre(c) + "DimTime"))];
		dev.log("dimmer", who(c) + ": relative dimming " + (up ? "UP" : "DOWN") + " step code " + step + " -> ramp to "
				+ Math.round(target[c] * 100 / 255) + " % at " + secs + " s for 0-100 %");
		if (up && level[c] == 0 && dev.param(pre(c) + "SendStatus") == 1)
			dev.transmit(K * c + 6, 1);
		stopRamp(c);
		ramping[c] = true;
		final double delta = 255.0 * TICK_MS / (secs * 1000.0);
		task[c] = dev.every(() -> tick(c, delta), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
	}

	private synchronized void tick(final int c, final double delta) {
		if (!ramping[c])
			return;
		level[c] = level[c] < target[c] ? Math.min(target[c], level[c] + delta) : Math.max(target[c], level[c] - delta);
		if (level[c] == target[c]) {
			stopRamp(c);
			remember(c);
			dev.log("dimmer", who(c) + ": ramp finished at " + levelPct(c) + " %");
			reportStatus(c, true);
		}
		dev.changed();
	}

	private void stopRamp(final int c) {
		ramping[c] = false;
		if (task[c] != null)
			task[c].cancel(false);
	}

	private int rgbStatus(final int c) { return level[c] > 0 ? rgb[c] : 0; }

	/** @param brightness false when only the colour temperature changed */
	private void reportStatus(final int c, final boolean brightness) {
		if (dev.param(pre(c) + "SendStatus") != 1) {
			dev.log("dimmer", who(c) + ": status not sent: parameter " + pre(c) + "SendStatus=No");
			return;
		}
		if (brightness) {
			dev.transmit(K * c + 6, level[c] > 0 ? 1 : 0);
			dev.transmit(K * c + 7, (int) Math.round(level[c]));
		}
		if (white(c))
			dev.transmit(K * c + 9, kelvin[c]);
		else if (brightness)
			dev.transmit(K * c + 8, rgbStatus(c));
	}

	@Override
	synchronized Integer valueOf(final int go) {
		final int c = (go - 1) / K, kind = (go - 1) % K + 1;
		if (c >= N || go < 1 || !enabled(c))
			return null;
		return switch (kind) {
			case 6 -> level[c] > 0 ? 1 : 0;
			case 7 -> (int) Math.round(level[c]);
			case 8 -> white(c) ? null : rgbStatus(c);
			case 9 -> white(c) ? kelvin[c] : null;
			default -> null;
		};
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < N; c++) {
			final int n = c + 1;
			m.put("e" + n, enabled(c));
			m.put("n" + n, dev.paramText(pre(c) + "Name"));
			m.put("w" + n, white(c));
			m.put("level" + n, levelPct(c));
			m.put("rgb" + n, hex(rgb[c]));
			m.put("k" + n, kelvin[c]);
			m.put("ramping" + n, ramping[c]);
		}
		return m;
	}
}
