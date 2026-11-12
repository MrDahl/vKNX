package org.vknx.device;

import java.util.Locale;
import java.util.Map;

import org.vknx.product.Products;
import org.vknx.product.Products.RowType;

/**
 * Info display: up to five screens with six rows each. Every row shows one value it receives from the bus (on/off status,
 * percent, the active scene or one of the multisensor values); the display itself never sends anything except the
 * "read on init" requests that ask the status objects for their value when it starts. Objects 1 and 2 (and the buttons
 * on the control panel card) page through the enabled screens.
 * <p>
 * A screen can instead be a light control (parameter S1Kind): the card then shows an on/off button, a brightness slider
 * and a colour wheel (RGB) or a colour temperature slider (tunable white). Operating them sends on the screen's output
 * objects (switch, brightness 0-255, RGB as 3 bytes, colour temperature in Kelvin); the four status inputs keep the
 * screen in step with the actuator. What is sent is also shown at once, so the screen works without status objects.
 */
public final class InfoDisplayBehavior extends DeviceBehavior {
	private static final int S = Products.DISPLAY_SCREENS, R = Products.DISPLAY_ROWS;
	private final int[][] raw = new int[S + 1][R + 1];
	private final boolean[][] has = new boolean[S + 1][R + 1];
	private volatile int cur;
	// light control screens: what the screen shows (from what it sent last or from the status objects)
	private final boolean[] lOn = new boolean[S + 1];
	private final int[] lLevel = new int[S + 1]; // 0..255
	private final int[] lRgb = new int[S + 1];
	private final int[] lKelvin = new int[S + 1];
	private long lastLive;

	InfoDisplayBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int s, final int r) { return "S" + s + "R" + r; }

	private boolean enabled(final int s) { return dev.param("S" + s + "Enable") == 1; }

	private int type(final int s, final int r) { return dev.param(pre(s, r) + "Type"); }

	private static RowType rowType(final int value) {
		return Products.ROW_TYPES.stream().filter(t -> t.value() == value).findFirst().orElse(null);
	}

	/** The object kind a row type listens on: 0 = 1 bit, 1 = percent, 2 = scene, 3 = 2-byte value. */
	private static int kindOf(final int type) {
		return type == 1 ? 0 : type == 2 ? 1 : type == 3 ? 2 : 3;
	}

	private boolean light(final int s) { return dev.param("S" + s + "Kind") == 1; }

	/** 0 = no colour, 1 = RGB, 2 = tunable white. */
	private int colour(final int s) { return dev.param("S" + s + "Colour"); }

	private int warm(final int s) { return dev.param("S" + s + "WarmK") * 100; }

	private int cold(final int s) { return Math.max(warm(s), dev.param("S" + s + "ColdK") * 100); }

	private static int lo(final int s, final int kind) { return Products.displayLightObject(s, kind); }

	private int firstEnabled() {
		for (int s = 1; s <= S; s++)
			if (enabled(s))
				return s;
		return 0;
	}

	@Override
	synchronized void onApplicationLoaded() {
		for (final var a : has)
			java.util.Arrays.fill(a, false);
		cur = firstEnabled();
		int rows = 0;
		for (int s = 1; s <= S; s++) {
			if (lRgb[s] == 0)
				lRgb[s] = 0xFFFFFF;
			lKelvin[s] = Math.max(warm(s), Math.min(cold(s), lKelvin[s] == 0 ? 3000 : lKelvin[s]));
		}
		for (int s = 1; s <= S; s++)
			if (enabled(s) && !light(s))
				for (int r = 1; r <= R; r++)
					if (type(s, r) != 0)
						rows++;
		dev.log("display", cur == 0 ? "no screen is enabled (parameters S1Enable ... S5Enable)"
				: "showing screen " + cur + " (" + dev.paramText("S" + cur + "Name") + "), " + rows + " row(s) in the enabled screens");
		dev.changed();
	}

	private synchronized void step(final int dir) {
		if (cur == 0)
			return;
		int s = cur;
		for (int i = 0; i < S; i++) {
			s = (s - 1 + dir + S) % S + 1;
			if (enabled(s)) {
				cur = s;
				dev.log("display", "showing screen " + s + " (" + dev.paramText("S" + s + "Name") + ")");
				return;
			}
		}
	}

	@Override
	public void act(final String action, final int value) {
		if (!dev.applicationLoaded()) {
			dev.log("display", "operated, but no application is loaded: nothing happens");
			return;
		}
		switch (action) {
			case "next" -> step(1);
			case "prev" -> step(-1);
			case "goto" -> {
				if (value >= 1 && value <= S && enabled(value))
					cur = value;
			}
			case "lswitch", "llevel", "llevelLive", "lstep", "lrgb", "lrgbLive", "lkelvin", "lkelvinLive" -> lightAct(action, value);
			default -> super.act(action, value);
		}
		dev.changed();
	}

	/** The light control of the screen that is shown was operated on the card. */
	private synchronized void lightAct(final String action, final int value) {
		final int s = cur;
		if (s == 0 || !light(s)) {
			dev.log("display", "light control operated, but the screen shown is not a light control");
			return;
		}
		final boolean live = action.endsWith("Live");
		if (live) { // while a slider or the colour wheel is dragged: at most four telegrams a second
			final long now = System.currentTimeMillis();
			if (now - lastLive < 250)
				return;
			lastLive = now;
		}
		final String who = "screen " + s + " (" + dev.paramText("S" + s + "Name") + ")";
		switch (action.replace("Live", "")) {
			case "lswitch" -> {
				lOn[s] = value == 2 ? !lOn[s] : value == 1;
				if (lOn[s] && lLevel[s] == 0)
					lLevel[s] = 255;
				dev.log("display", who + ": on/off pressed -> sending " + (lOn[s] ? "ON" : "OFF"));
				dev.transmit(lo(s, 0), lOn[s] ? 1 : 0);
			}
			case "llevel", "lstep" -> {
				final int pct = action.equals("lstep") ? Math.round(lLevel[s] * 100f / 255) + value : value;
				lLevel[s] = (int) Math.round(Math.max(0, Math.min(100, pct)) * 255.0 / 100.0);
				lOn[s] = lLevel[s] > 0;
				dev.log("display", who + ": brightness " + Math.round(lLevel[s] * 100.0 / 255) + " % -> sending value " + lLevel[s] + " (0..255)");
				dev.transmit(lo(s, 1), lLevel[s]);
			}
			case "lrgb" -> {
				if (colour(s) != 1) {
					dev.log("display", who + ": colour chosen, but the screen is not set to RGB in ETS");
					return;
				}
				lRgb[s] = value & 0xFFFFFF;
				if (lRgb[s] != 0) {
					lOn[s] = true;
					if (lLevel[s] == 0)
						lLevel[s] = 255;
				}
				dev.log("display", who + ": colour " + String.format("#%06X", lRgb[s]) + " -> sending red " + (lRgb[s] >> 16 & 0xff) + ", green "
						+ (lRgb[s] >> 8 & 0xff) + ", blue " + (lRgb[s] & 0xff));
				dev.transmit(lo(s, 2), lRgb[s]);
			}
			default -> {
				if (colour(s) != 2) {
					dev.log("display", who + ": colour temperature chosen, but the screen is not set to tunable white in ETS");
					return;
				}
				lKelvin[s] = Math.max(warm(s), Math.min(cold(s), value));
				dev.log("display", who + ": colour temperature -> sending " + lKelvin[s] + " K");
				dev.transmit(lo(s, 3), lKelvin[s]);
			}
		}
	}

	/** A status telegram for a light control screen. */
	private void lightStatus(final int s, final int kind, final int value) {
		if (!enabled(s) || !light(s)) {
			dev.log("display", "screen " + s + ": status received for a light control that is not in use");
			return;
		}
		switch (kind) {
			case 4 -> lOn[s] = (value & 1) != 0;
			case 5 -> {
				lLevel[s] = value & 0xff;
				lOn[s] = lLevel[s] > 0;
			}
			case 6 -> {
				if ((value & 0xFFFFFF) != 0)
					lRgb[s] = value & 0xFFFFFF; // black is what an RGB actuator reports while it is off: keep the colour
			}
			default -> lKelvin[s] = value & 0xFFFF;
		}
		dev.log("display", "screen " + s + " light: status received: " + (kind == 4 ? (lOn[s] ? "on" : "off") : kind == 5 ? Math.round(lLevel[s] * 100.0 / 255) + " %"
				: kind == 6 ? String.format("#%06X", value & 0xFFFFFF) : lKelvin[s] + " K"));
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		final int li = go - Products.displayLightObject(1, 0);
		if (li >= 0 && li < S * Products.DISPLAY_LIGHT_OBJS) {
			if (li % Products.DISPLAY_LIGHT_OBJS >= 4)
				lightStatus(li / Products.DISPLAY_LIGHT_OBJS + 1, li % Products.DISPLAY_LIGHT_OBJS, value);
			return;
		}
		if (go == 1 || go == 2) {
			if ((value & 1) != 0) {
				dev.log("display", (go == 1 ? "next" : "previous") + " screen requested over the bus");
				step(go == 1 ? 1 : -1);
			}
			return;
		}
		final int idx = go - 3;
		if (idx < 0 || idx >= S * R * 4)
			return;
		final int kind = idx % 4, s = idx / 4 / R + 1, r = idx / 4 % R + 1;
		final int t = type(s, r);
		if (!enabled(s) || light(s) || t == 0 || kindOf(t) != kind) {
			dev.log("display", "screen " + s + " row " + r + ": value received for an object that is not in use (row type does not match)");
			return;
		}
		if (kind == 2 && (value & 0x80) != 0) {
			dev.log("display", "screen " + s + " row " + r + ": scene store telegram ignored (the display shows called scenes)");
			return;
		}
		raw[s][r] = value;
		has[s][r] = true;
		dev.log("display", "screen " + s + " row " + r + " (" + dev.paramText(pre(s, r) + "Label") + "): " + text(s, r));
	}

	private String text(final int s, final int r) {
		if (!has[s][r])
			return "–";
		final int t = type(s, r);
		final int v = raw[s][r];
		final RowType rt = rowType(t);
		if (rt == null)
			return "–";
		return switch (t) {
			case 1 -> (v & 1) != 0 ? "On" : "Off";
			case 2 -> Math.round((v & 0xff) * 100.0 / 255.0) + " %";
			case 3 -> "Scene " + ((v & 0x3f) + 1);
			case 4, 9 -> String.format(Locale.ROOT, "%.1f %s", Dpt9.decode(v) * rt.scale(), rt.unit());
			default -> String.format(Locale.ROOT, "%.0f %s", Dpt9.decode(v) * rt.scale(), rt.unit());
		};
	}

	@Override
	Integer valueOf(final int go) { return null; }

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		int count = 0, pos = 0;
		for (int s = 1; s <= S; s++)
			if (enabled(s)) {
				count++;
				if (s == cur)
					pos = count;
			}
		m.put("cur", cur);
		m.put("count", count);
		m.put("pos", pos);
		m.put("name", cur == 0 ? "" : dev.paramText("S" + cur + "Name"));
		final boolean lightScreen = cur != 0 && light(cur);
		m.put("light", lightScreen);
		if (lightScreen) {
			m.put("lcol", colour(cur));
			m.put("lon", lOn[cur]);
			m.put("llevel", lOn[cur] ? (int) Math.round(lLevel[cur] * 100.0 / 255) : 0);
			m.put("lrgb", String.format("#%06X", lRgb[cur]));
			m.put("lk", lKelvin[cur]);
			m.put("lwarm", warm(cur));
			m.put("lcold", cold(cur));
		}
		for (int r = 1; r <= R; r++) {
			final boolean used = cur != 0 && !lightScreen && type(cur, r) != 0;
			m.put("r" + r + "l", used ? dev.paramText(pre(cur, r) + "Label") : "");
			m.put("r" + r + "v", used ? text(cur, r) : "");
			m.put("r" + r + "t", used ? type(cur, r) : 0);
		}
		return m;
	}
}
