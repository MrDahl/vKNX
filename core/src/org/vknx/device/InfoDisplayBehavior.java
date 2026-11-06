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
 */
public final class InfoDisplayBehavior extends DeviceBehavior {
	private static final int S = Products.DISPLAY_SCREENS, R = Products.DISPLAY_ROWS;
	private final int[][] raw = new int[S + 1][R + 1];
	private final boolean[][] has = new boolean[S + 1][R + 1];
	private volatile int cur;

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
		for (int s = 1; s <= S; s++)
			if (enabled(s))
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
			default -> super.act(action, value);
		}
		dev.changed();
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
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
		if (!enabled(s) || t == 0 || kindOf(t) != kind) {
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
		for (int r = 1; r <= R; r++) {
			final boolean used = cur != 0 && type(cur, r) != 0;
			m.put("r" + r + "l", used ? dev.paramText(pre(cur, r) + "Label") : "");
			m.put("r" + r + "v", used ? text(cur, r) : "");
			m.put("r" + r + "t", used ? type(cur, r) : 0);
		}
		return m;
	}
}
