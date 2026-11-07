package org.vknx.product;

import java.util.Map;

/** Catalogue texts for the UI (readable title, short description, category, icon). Kept apart from the ETS product data. */
public final class ProductInfo {
	private ProductInfo() {}

	public record Info(String title, String category, String icon, String description) {}

	private static final Map<String, Info> INFO = Map.ofEntries(
			Map.entry("push-button", new Info("Push button, 4 keys", "Input", "button",
					"Four keys. Each key can be disabled, named and given a function in ETS: switch, relative dimming or blinds.")),
			Map.entry("secure-push-button", new Info("Secure push button, 4 keys", "Input", "button",
					"Like the push button, but a KNX Data Secure device: ETS needs its serial number and FDSK (shown in the device details) to commission it, and group telegrams are then authenticated and encrypted.")),
			Map.entry("secure-switch-actuator", new Info("Secure switch actuator, 8 outputs", "Actuator", "lamp",
					"Like the switch actuator, but a KNX Data Secure device: ETS needs its serial number and FDSK (shown in the device details) to commission it. It only accepts group telegrams that are authenticated and encrypted as ETS configured.")),
			Map.entry("push-slider", new Info("Slider push button", "Input", "slider",
					"A button plus a slider that sends a brightness value (1 byte).")),
			Map.entry("switch-actuator", new Info("Switch actuator, 8 outputs", "Actuator", "lamp",
					"Eight switch outputs with status objects. Each output can be disabled and named in ETS.")),
			Map.entry("dimmer", new Info("Dimming actuator, 4 channels", "Actuator", "lamp",
					"Four channels with switch, relative dimming (4 bit), brightness value (1 byte) and status. Each channel can be disabled and named.")),
			Map.entry("blind-actuator", new Info("Blind actuator, 4 channels", "Actuator", "blind",
					"Four blinds with move up/down, stop and position status, separate travel times up and down. Wind and rain alarm objects retract awnings and lock the blinds. Each blind can be disabled and named.")),
			Map.entry("multisensor", new Info("Multisensor", "Sensor", "temp",
					"CO₂, humidity, brightness, temperature, pressure and wind. Each value has its own object and send settings in ETS and can be disabled.")),
			Map.entry("info-display", new Info("Info display, 5 screens", "Display", "display",
					"Shows values it receives from the bus on up to five named screens with six rows each: on/off status, percent (dimming value, blind position), the active scene and the multisensor values. Which rows a screen shows is set in the ETS parameters; the screens can be paged on the card or with two group objects.")),
			Map.entry("logic", new Info("Logic module and time master", "Logic", "logic",
					"Sixteen 1 bit inputs and sixteen 1 bit outputs that you enable and name in ETS, wired together with gates, delays, a stairway timer and an impulse relay in the logic editor of the control panel. Also sends time and date and has eight weekday timers.")),
			Map.entry("binary-input", new Info("Binary input, 4 inputs", "Input", "contact",
					"Four inputs for potential-free contacts (push buttons, switches, window contacts). Each input switches, dims or controls blinds when its contact closes and opens, as set in ETS. Close the contacts on the card.")),
			Map.entry("thermostat", new Info("Room thermostat", "Controller", "thermostat",
					"Heating thermostat with a simulated room, four operating modes and setpoints, an optional window contact and a two-point or PI control value. Pair it with the heating actuator.")),
			Map.entry("heating-actuator", new Info("Heating actuator, 6 valves", "Actuator", "radiator",
					"Six outputs for thermal valve drives with on/off or continuous (PWM) control value, normally closed or open valves, valve status and monitoring of the control value with an emergency position.")),
			Map.entry("weather-station", new Info("Weather station", "Sensor", "weather",
					"Outdoor temperature, wind speed and brightness (fixed or random like the multisensor), a rain sensor and a wind alarm. Blind actuators retract awnings on the alarm objects.")),
			Map.entry("coupler", new Info("Coupler (line / area)", "Coupler", "router",
					"One coupler for both roles: x.y.0 connects a line to its main line, x.0.0 connects a main line to the backbone. Routing and the filter table come from the ETS download; a missing download shows up as blocked telegrams.")));

	public static Info of(final ProductDef p) {
		return INFO.getOrDefault(p.key(), new Info(p.name(), "Other", "devices", ""));
	}
}
