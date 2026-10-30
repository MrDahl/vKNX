package org.vknx.product;

import java.util.Map;

/** Catalogue texts for the UI (readable title, short description, category, icon). Kept apart from the ETS product data. */
public final class ProductInfo {
	private ProductInfo() {}

	public record Info(String title, String category, String icon, String description) {}

	private static final Map<String, Info> INFO = Map.ofEntries(
			Map.entry("push-button", new Info("Push button, 4 keys", "Input", "button",
					"Four keys. Each key can be disabled, named and given a function in ETS: switch, relative dimming or blinds.")),
			Map.entry("push-slider", new Info("Slider push button", "Input", "slider",
					"A button plus a slider that sends a brightness value (1 byte).")),
			Map.entry("switch-actuator", new Info("Switch actuator, 8 outputs", "Actuator", "lamp",
					"Eight switch outputs with status objects. Each output can be disabled and named in ETS.")),
			Map.entry("dimmer", new Info("Dimming actuator, 4 channels", "Actuator", "lamp",
					"Four channels with switch, relative dimming (4 bit), brightness value (1 byte) and status. Each channel can be disabled and named.")),
			Map.entry("blind-actuator", new Info("Blind actuator, 4 channels", "Actuator", "blind",
					"Four blinds with move up/down, stop and position status, separate travel times up and down. Each blind can be disabled and named.")),
			Map.entry("multisensor", new Info("Multisensor", "Sensor", "temp",
					"CO₂, humidity, brightness, temperature, pressure and wind. Each value has its own object and send settings in ETS and can be disabled.")),
			Map.entry("coupler", new Info("Coupler (line / area)", "Coupler", "router",
					"One coupler for both roles: x.y.0 connects a line to its main line, x.0.0 connects a main line to the backbone. Routing and the filter table come from the ETS download; a missing download shows up as blocked telegrams.")));

	public static Info of(final ProductDef p) {
		return INFO.getOrDefault(p.key(), new Info(p.name(), "Other", "devices", ""));
	}
}
