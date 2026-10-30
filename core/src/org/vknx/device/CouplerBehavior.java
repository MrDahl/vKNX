package org.vknx.device;

import java.util.Map;

/**
 * Line coupler (x.y.0) or area coupler (x.0.0). Step 1: the settings downloaded by ETS are decoded and shown, but no
 * telegrams are routed between lines yet (the simulated network is still one flat line, see docs/limitations.md).
 */
public final class CouplerBehavior extends DeviceBehavior {
	private final boolean area;

	CouplerBehavior(final VirtualDevice dev, final boolean area) {
		super(dev);
		this.area = area;
	}

	static String mode(final int v) {
		return switch (v) {
			case 0 -> "block";
			case 1 -> "route all";
			default -> "filter";
		};
	}

	@Override
	void onApplicationLoaded() {
		dev.log("coupler", "application loaded: group down=" + mode(dev.param("GroupDown")) + ", group up="
				+ mode(dev.param("GroupUp")) + ", physical down=" + mode(dev.param("PhysDown")) + ", physical up="
				+ mode(dev.param("PhysUp")) + ", repeat=" + (dev.param("Repeat") == 1) + ", confirm="
				+ (dev.param("Confirm") == 1) + " (settings are stored; this version does not route telegrams)");
	}

	@Override void onObjectReceived(final int go, final int value) {}

	@Override Integer valueOf(final int go) { return null; }

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		m.put("kind", area ? "area" : "line");
		m.put("routing", false);
		if (dev.applicationLoaded()) {
			m.put("groupDown", mode(dev.param("GroupDown")));
			m.put("groupUp", mode(dev.param("GroupUp")));
			m.put("physDown", mode(dev.param("PhysDown")));
			m.put("physUp", mode(dev.param("PhysUp")));
			m.put("repeat", dev.param("Repeat") == 1);
			m.put("confirm", dev.param("Confirm") == 1);
		}
		return m;
	}
}
