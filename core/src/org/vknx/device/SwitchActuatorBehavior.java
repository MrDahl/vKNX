package org.vknx.device;

import java.util.Map;

/**
 * Switch actuator with 8 outputs; every output can be disabled and named in ETS. Objects: 1..8 switch, 9..16 status,
 * 17 scene (DPT 18.001: bit 7 = store, bits 0-5 = scene number - 1). ETS parameters: Out1Enable.., Out1Logic.. (relay
 * logic), StartupState, SendStatus, and per output four scene slots (Out1Scene1 = scene number, Out1Scene1Value).
 */
public final class SwitchActuatorBehavior extends DeviceBehavior {
	static final int N = 8;
	private final int[] obj = new int[N]; // logical value of each Switch object
	private volatile String lastScene = "";

	SwitchActuatorBehavior(final VirtualDevice dev) { super(dev); }

	private boolean enabled(final int ch) { return dev.param("Out" + (ch + 1) + "Enable") == 1; }

	private boolean closed(final int ch) { return enabled(ch) && (obj[ch] == 1) != (dev.param("Out" + (ch + 1) + "Logic") == 1); }

	@Override
	void onApplicationLoaded() {
		final int startup = dev.param("StartupState");
		if (startup < 2)
			java.util.Arrays.fill(obj, startup);
		dev.log("relay", "application started, StartupState=" + (startup == 0 ? "off" : startup == 1 ? "on" : "unchanged"));
		for (int c = 0; c < N; c++)
			status(c);
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go == 2 * N + 1) {
			scene(value);
			dev.changed();
			return;
		}
		if (go < 1 || go > N)
			return;
		final int c = go - 1;
		if (!enabled(c)) {
			dev.log("relay", "output " + go + ": telegram ignored, parameter Out" + go + "Enable=No");
			return;
		}
		final boolean before = closed(c);
		obj[c] = value & 1;
		dev.log("relay", "output " + go + " (" + dev.paramText("Out" + go + "Name") + "): Switch=" + (obj[c] == 1 ? "ON" : "OFF")
				+ ", parameter Out" + go + "Logic=" + (dev.param("Out" + go + "Logic") == 1 ? "inverted" : "normal")
				+ " -> relay " + (closed(c) ? "closed" : "open"));
		if (before != closed(c))
			status(c);
	}

	/** A scene telegram: call the scene (set every output that has it in a slot) or store the current state in those slots. */
	private void scene(final int value) {
		final boolean store = (value & 0x80) != 0;
		final int n = (value & 0x3f) + 1;
		int hit = 0;
		for (int c = 0; c < N; c++) {
			if (!enabled(c))
				continue;
			for (int s = 1; s <= org.vknx.product.Products.SCENE_SLOTS; s++) {
				final String pre = "Out" + (c + 1) + "Scene" + s;
				if (dev.param(pre) != n)
					continue;
				hit++;
				if (store) {
					dev.setParam(pre + "Value", obj[c]);
					dev.log("relay", "output " + (c + 1) + " (" + dev.paramText("Out" + (c + 1) + "Name") + "): scene " + n + " stored in slot " + s
							+ ": " + (obj[c] == 1 ? "ON" : "OFF") + " (replaces the ETS value until the next download)");
				}
				else {
					final boolean before = closed(c);
					final int v = dev.param(pre + "Value");
					if (v == 2) {
						dev.log("relay", "output " + (c + 1) + " (" + dev.paramText("Out" + (c + 1) + "Name") + "): scene " + n + " (slot " + s + ") keeps the current state ("
								+ (obj[c] == 1 ? "ON" : "OFF") + ")");
						continue;
					}
					obj[c] = v;
					dev.log("relay", "output " + (c + 1) + " (" + dev.paramText("Out" + (c + 1) + "Name") + "): scene " + n + " (slot " + s + ") -> "
							+ (obj[c] == 1 ? "ON" : "OFF") + ", relay " + (closed(c) ? "closed" : "open"));
					if (before != closed(c))
						status(c);
				}
			}
		}
		lastScene = (store ? "Scene " + n + " stored" : "Scene " + n + " called") + (hit == 0 ? " (no output uses it)" : "");
		if (hit == 0)
			dev.log("relay", "scene " + n + (store ? " store" : " call") + " received: no output has this scene number in a scene slot (parameters Out1Scene1 ...)");
	}

	private void status(final int c) {
		if (enabled(c) && dev.param("SendStatus") == 1)
			dev.transmit(N + 1 + c, obj[c]);
	}

	/** True while output {@code out} (1..8) has its relay closed (lamp lit). */
	public boolean lampLit(final int out) { return dev.applicationLoaded() && closed(out - 1); }

	/** Output 1 (tests). */
	public boolean lampLit() { return lampLit(1); }

	@Override Integer valueOf(final int go) { return go >= 1 && go <= 2 * N ? obj[(go - 1) % N] : null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		final var lamps = new StringBuilder();
		for (int c = 0; c < N; c++) {
			lamps.append(dev.applicationLoaded() && closed(c) ? '1' : '0');
			m.put("e" + (c + 1), enabled(c));
			m.put("n" + (c + 1), dev.paramText("Out" + (c + 1) + "Name"));
		}
		m.put("lamps", lamps.toString());
		m.put("scene", lastScene);
		return m;
	}
}
