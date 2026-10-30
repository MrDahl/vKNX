package org.vknx.product;

import java.util.Map;

/** Catalogue texts for the UI (readable title, short description, category, icon). Kept apart from the ETS product data. */
public final class ProductInfo {
	private ProductInfo() {}

	public record Info(String title, String category, String icon, String description) {}

	private static final Map<String, Info> INFO = Map.ofEntries(
			Map.entry("push-button", new Info("Push button", "Input", "button",
					"One button. A short press switches, a long press can dim. What it sends is set in ETS.")),
			Map.entry("push-rocker", new Info("Rocker (two keys)", "Input", "up",
					"Upper and lower key for switching and relative dimming (4 bit).")),
			Map.entry("push-slider", new Info("Slider push button", "Input", "slider",
					"A button plus a slider that sends a brightness value (1 byte).")),
			Map.entry("switch-actuator", new Info("Switch actuator, 1 output", "Actuator", "lamp",
					"Switches one output on and off and reports its status.")),
			Map.entry("switch-actuator-4", new Info("Switch actuator, 4 outputs", "Actuator", "lamp",
					"Four independent switch outputs with status objects.")),
			Map.entry("switch-actuator-8", new Info("Switch actuator, 8 outputs", "Actuator", "lamp",
					"Eight independent switch outputs with status objects.")),
			Map.entry("dimmer", new Info("Dimming actuator", "Actuator", "lamp",
					"Switches and dims one light: relative dimming (4 bit), brightness value (1 byte) and status.")),
			Map.entry("blind-2", new Info("Blind actuator, 2 channels", "Actuator", "blind",
					"Drives two blinds with move up/down, stop and position feedback.")),
			Map.entry("multisensor", new Info("Multisensor", "Sensor", "temp",
					"CO₂, humidity, brightness, temperature, pressure and wind. Each value has its own object and send settings in ETS.")),
			Map.entry("coupler-line", new Info("Line coupler", "Coupler", "router",
					"Connects a sub line to the main line (address area.line.0). Routing settings are set in ETS. This version does not route telegrams yet.")),
			Map.entry("coupler-area", new Info("Area coupler", "Coupler", "router",
					"Connects a main line to the backbone (address area.0.0). Routing settings are set in ETS. This version does not route telegrams yet.")));

	public static Info of(final ProductDef p) {
		return INFO.getOrDefault(p.key(), new Info(p.name(), "Other", "devices", ""));
	}
}
