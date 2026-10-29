package org.vknx.device;

import java.util.LinkedHashMap;
import java.util.Map;

import org.vknx.product.ProductDef;

/** Product-specific application logic. All inputs come from the ETS download (parameters, tables, flags). */
public abstract class DeviceBehavior {
	protected final VirtualDevice dev;

	protected DeviceBehavior(final VirtualDevice dev) { this.dev = dev; }

	static DeviceBehavior forProduct(final ProductDef p, final VirtualDevice dev) {
		return switch (p.key()) {
			case "push-button" -> new PushButtonBehavior(dev);
			case "switch-actuator" -> new SwitchActuatorBehavior(dev);
			default -> throw new IllegalArgumentException("no behaviour for product " + p.key());
		};
	}

	/** Called after ETS completed the application download (and at restart if the application is loaded). */
	abstract void onApplicationLoaded();

	/** A group telegram was accepted for object {@code go} (flags already checked). */
	abstract void onObjectReceived(int go, int value);

	/** Current value of object {@code go} for read requests, or null if the object has no value. */
	abstract Integer valueOf(int go);

	/** UI-facing state: name -> value. */
	public abstract Map<String, Object> view();

	protected Map<String, Object> newView() { return new LinkedHashMap<>(); }
}
