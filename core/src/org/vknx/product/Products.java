package org.vknx.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.vknx.product.ParamDef.Choice;

/**
 * The vKNX product catalogue. Manufacturer ID 0x00FA is the "KNX Association experimental" ID that the OpenKNX
 * projects use for DIY products. All texts are English.
 */
public final class Products {
	private Products() {}

	public static final int MANUFACTURER_ID = 0x00FA;

	/** Application version of every product in this release (bumped when texts or the parameter layout change). */
	static final int V = 2;

	private static final List<Choice> NO_YES = List.of(new Choice(0, "No"), new Choice(1, "Yes"));
	private static final List<Choice> STARTUP = List.of(new Choice(0, "Off"), new Choice(1, "On"), new Choice(2, "Unchanged"));
	private static final List<Choice> BUTTON_FUNCTION = List.of(new Choice(0, "Toggle (alternating on/off)"),
			new Choice(1, "On only"), new Choice(2, "Off only"));

	/** Push button: one key, switch + status. */
	public static final ProductDef PUSH_BUTTON = new ProductDef(
			"push-button", 0x0A01, V, "vKNX Push Button 1-fold", "VKNXPB1", 0x0A01, 4,
			List.of(
				new ParamDef(1, "ButtonFunction", "Button function", 0, 0, 2, 0, BUTTON_FUNCTION),
				new ParamDef(2, "FollowStatus", "Follow status object", 0, 2, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Status", "Status", "Input", 1, "DPST-1-1", false, true, false, true)));

	public static final ProductDef SWITCH_ACTUATOR = new ProductDef(
			"switch-actuator", 0x0A02, V, "vKNX Switch Actuator 1-fold", "VKNXSA1", 0x0A02, 4,
			List.of(
				new ParamDef(1, "RelayLogic", "Relay logic", 0, 0, 1, 0, List.of(
					new Choice(0, "Normal (on = relay closed)"), new Choice(1, "Inverted (on = relay open)"))),
				new ParamDef(2, "StartupState", "State after download/restart", 0, 1, 2, 0, STARTUP),
				new ParamDef(3, "SendStatusOnChange", "Send status on change", 0, 3, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Input", 1, "DPST-1-1", false, true, false, false),
				new ObjDef(2, "Status", "Status", "Output", 1, "DPST-1-1", true, false, true, false)));

	/** Double push button / rocker: short press switches, long press dims. */
	public static final ProductDef PUSH_ROCKER = new ProductDef(
			"push-rocker", 0x0A03, V, "vKNX Rocker (double push) 1-fold", "VKNXRK1", 0x0A03, 2,
			List.of(
				new ParamDef(1, "Orientation", "Upper key sends", 0, 0, 1, 0, List.of(
					new Choice(0, "On / brighter (lower: off / darker)"),
					new Choice(1, "Off / darker (lower: on / brighter)"))),
				new ParamDef(2, "LongPressTime", "Long-press time", 0, 1, 2, 1, List.of(
					new Choice(0, "0.4 s"), new Choice(1, "0.6 s"), new Choice(2, "1.0 s"))),
				new ParamDef(3, "DimStep", "Dimming step (relative dimming)", 1, 0, 3, 1,
					List.of(new Choice(1, "100 % (run to end)"), new Choice(2, "50 %"), new Choice(3, "25 %"),
						new Choice(4, "12.5 %"), new Choice(5, "6.25 %")))),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Dimming", "Relative dimming", "Output", 4, "DPST-3-7", false, false, true, false),
				new ObjDef(3, "Status", "Status", "Input", 1, "DPST-1-1", false, true, false, true)));

	/** Push button with a slider: switch + 1-byte brightness value. */
	public static final ProductDef PUSH_SLIDER = new ProductDef(
			"push-slider", 0x0A04, V, "vKNX Slider push 1-fold", "VKNXSL1", 0x0A04, 1,
			List.of(
				new ParamDef(1, "ButtonFunction", "Button function", 0, 0, 2, 0, BUTTON_FUNCTION),
				new ParamDef(2, "SliderSend", "Slider sends", 0, 2, 1, 0, List.of(
					new Choice(0, "Only when released"), new Choice(1, "Continuously while sliding")))),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Brightness", "Brightness (value)", "Output", 8, "DPST-5-1", false, false, true, false),
				new ObjDef(3, "BrightnessStatus", "Brightness status", "Input", 8, "DPST-5-1", false, true, false, true)));

	/** One-channel dimming actuator. */
	public static final ProductDef DIMMER = new ProductDef(
			"dimmer", 0x0A05, V, "vKNX Dimming Actuator 1-fold", "VKNXDM1", 0x0A05, 1,
			List.of(
				new ParamDef(1, "DimTime", "Time 0-100 % for relative dimming", 0, 0, 2, 1,
					List.of(new Choice(0, "2 s"), new Choice(1, "5 s"), new Choice(2, "10 s"), new Choice(3, "20 s"))),
				new ParamDef(2, "OnLevel", "Level when switched on", 0, 2, 1, 0, List.of(
					new Choice(0, "Last level"), new Choice(1, "100 %"))),
				new ParamDef(3, "StartupState", "State after download/restart", 0, 3, 2, 0, STARTUP),
				new ParamDef(4, "SendStatus", "Send status", 0, 5, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Input", 1, "DPST-1-1", false, true, false, false),
				new ObjDef(2, "RelativeDimming", "Relative dimming (4 bit)", "Input", 4, "DPST-3-7", false, true, false, false),
				new ObjDef(3, "BrightnessValue", "Brightness (value)", "Input", 8, "DPST-5-1", false, true, false, false),
				new ObjDef(4, "SwitchStatus", "Switch status", "Output", 1, "DPST-1-1", true, false, true, false),
				new ObjDef(5, "BrightnessStatus", "Brightness status", "Output", 8, "DPST-5-1", true, false, true, false)));

	private static List<Choice> runTimes() {
		return List.of(new Choice(0, "10 s"), new Choice(1, "20 s"), new Choice(2, "30 s"), new Choice(3, "60 s"));
	}

	/** Two-channel blind actuator. */
	public static final ProductDef BLIND_2 = new ProductDef(
			"blind-2", 0x0A06, V, "vKNX Blind Actuator 2-fold", "VKNXBL2", 0x0A06, 1,
			List.of(
				new ParamDef(1, "Ch1RunTime", "Blind 1: travel time", 0, 0, 2, 1, runTimes()),
				new ParamDef(2, "Ch2RunTime", "Blind 2: travel time", 0, 2, 2, 1, runTimes()),
				new ParamDef(3, "SendPosition", "Send position as status", 0, 4, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Ch1Move", "Blind 1: up/down", "Input", 1, "DPST-1-8", false, true, false, false),
				new ObjDef(2, "Ch1Stop", "Blind 1: stop", "Input", 1, "DPST-1-7", false, true, false, false),
				new ObjDef(3, "Ch1Position", "Blind 1: position status", "Output", 8, "DPST-5-1", true, false, true, false),
				new ObjDef(4, "Ch2Move", "Blind 2: up/down", "Input", 1, "DPST-1-8", false, true, false, false),
				new ObjDef(5, "Ch2Stop", "Blind 2: stop", "Input", 1, "DPST-1-7", false, true, false, false),
				new ObjDef(6, "Ch2Position", "Blind 2: position status", "Output", 8, "DPST-5-1", true, false, true, false)));

	private static ProductDef switchActuator(final int n, final int app, final String order) {
		final var params = new ArrayList<ParamDef>();
		for (int c = 1; c <= n; c++)
			params.add(new ParamDef(c, "Ch" + c + "Logic", "Output " + c + ": relay logic", 0, c - 1, 1, 0,
					List.of(new Choice(0, "Normal (on = relay closed)"), new Choice(1, "Inverted (on = relay open)"))));
		params.add(new ParamDef(n + 1, "StartupState", "State after download/restart", 1, 0, 2, 0, STARTUP));
		params.add(new ParamDef(n + 2, "SendStatus", "Send status on change", 1, 2, 1, 1, NO_YES));
		final var objs = new ArrayList<ObjDef>();
		for (int c = 1; c <= n; c++)
			objs.add(new ObjDef(c, "Ch" + c + "Switch", "Output " + c + ": switch", "Input", 1, "DPST-1-1", false, true, false, false));
		for (int c = 1; c <= n; c++)
			objs.add(new ObjDef(n + c, "Ch" + c + "Status", "Output " + c + ": status", "Output", 1, "DPST-1-1", true, false, true, false));
		return new ProductDef("switch-actuator-" + n, app, V, "vKNX Switch Actuator " + n + "-fold", order, app, 2, params, objs);
	}

	public static final ProductDef SWITCH_ACTUATOR_4 = switchActuator(4, 0x0A07, "VKNXSA4");
	public static final ProductDef SWITCH_ACTUATOR_8 = switchActuator(8, 0x0A08, "VKNXSA8");

	// ------------------------------------------------------------------ multisensor

	/** One multisensor measurement: object name, ETS page and text, DPT, unit shown in the UI, parameter scale and range. */
	public record Channel(String name, String page, String dpt, String unit, double unitsPerStep, double toDpt, int def,
			int min, int max, String valueText) {}

	/**
	 * The six measurements. A parameter value is converted to the engineering value with {@code unitsPerStep}
	 * (e.g. 0.1 for temperature in tenths of a degree) and to the DPT 9 value that is sent on the bus with {@code toDpt}
	 * (pressure is entered in hPa and sent in Pa).
	 */
	public static final List<Channel> CHANNELS = List.of(
		new Channel("CO2", "CO2", "DPST-9-8", "ppm", 1, 1, 600, 0, 5000, "Value (ppm)"),
		new Channel("Humidity", "Humidity", "DPST-9-7", "%", 1, 1, 45, 0, 100, "Value (%)"),
		new Channel("Illuminance", "Illuminance", "DPST-9-4", "lux", 1, 1, 300, 0, 65000, "Value (lux)"),
		new Channel("Temperature", "Temperature", "DPST-9-1", "°C", 0.1, 0.1, 215, -300, 600, "Value (0.1 °C, 215 = 21.5 °C)"),
		new Channel("Pressure", "Air pressure", "DPST-9-6", "hPa", 1, 100, 1013, 300, 1100, "Value (hPa)"),
		new Channel("WindSpeed", "Wind speed", "DPST-9-5", "m/s", 0.1, 0.1, 30, 0, 500, "Value (0.1 m/s, 30 = 3.0 m/s)"));

	/** Bytes per channel in the parameter area: mode(1) + pad(1) + interval(2) + value(2) + min(2) + max(2). */
	public static final int CHANNEL_BYTES = 10;

	private static ProductDef multisensor() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int i = 0; i < CHANNELS.size(); i++) {
			final Channel c = CHANNELS.get(i);
			final int b = i * CHANNEL_BYTES;
			final String pre = c.name();
			params.add(new ParamDef(id++, pre + "Mode", "Send", b, 0, 2, 0,
					List.of(new Choice(0, "Fixed value"), new Choice(1, "Random value between minimum and maximum"))).inGroup(c.page()));
			params.add(ParamDef.number(id++, pre + "Interval", "Send cyclically every (seconds, 0 = never)", b + 2, 16, 30, 0, 65535)
					.inGroup(c.page()));
			params.add(ParamDef.number(id++, pre + "Value", c.valueText(), b + 4, 16, c.def(), c.min(), c.max())
					.inGroup(c.page()).showWhen(pre + "Mode", 0));
			params.add(ParamDef.number(id++, pre + "Min", "Random minimum", b + 6, 16, c.min() < 0 ? c.min() : c.def() / 2, c.min(), c.max())
					.inGroup(c.page()).showWhen(pre + "Mode", 1));
			params.add(ParamDef.number(id++, pre + "Max", "Random maximum", b + 8, 16, c.def() + Math.max(1, Math.abs(c.def()) / 2),
					c.min(), c.max()).inGroup(c.page()).showWhen(pre + "Mode", 1));
			objs.add(new ObjDef(i + 1, pre, c.page() + " (" + c.unit() + ")", "Output", 16, c.dpt(), true, false, true, false)
					.inGroup(c.page()));
		}
		return new ProductDef("multisensor", 0x0A09, V, "vKNX Multisensor", "VKNXMS1", 0x0A09, CHANNELS.size() * CHANNEL_BYTES,
				params, objs);
	}

	/** Multisensor: six measurements, each with its own group object and its own ETS page (value or random range + interval). */
	public static final ProductDef MULTISENSOR = multisensor();

	// ------------------------------------------------------------------ couplers

	private static final List<Choice> ROUTING = List.of(new Choice(0, "Block"), new Choice(1, "Route all"),
			new Choice(2, "Filter (use filter table)"));

	/**
	 * Line coupler (x.y.0) or area coupler (x.0.0). Step 1: the coupler is a normal System B device for ETS (address,
	 * parameters, download) and shows its settings in the panel; it does not yet route telegrams between lines.
	 */
	private static ProductDef coupler(final boolean area) {
		final String up = area ? "backbone line" : "main line", down = area ? "main line" : "sub line";
		final var params = List.of(
			new ParamDef(1, "GroupDown", "Group telegrams: " + up + " > " + down, 0, 0, 2, 2, ROUTING),
			new ParamDef(2, "GroupUp", "Group telegrams: " + down + " > " + up, 0, 2, 2, 2, ROUTING),
			new ParamDef(3, "PhysDown", "Individually addressed telegrams: " + up + " > " + down, 0, 4, 2, 2, ROUTING),
			new ParamDef(4, "PhysUp", "Individually addressed telegrams: " + down + " > " + up, 0, 6, 2, 2, ROUTING),
			new ParamDef(5, "Repeat", "Repeat telegrams after transmission errors", 1, 0, 1, 1, NO_YES),
			new ParamDef(6, "Confirm", "Acknowledge routed group telegrams", 1, 1, 1, 1, NO_YES));
		return new ProductDef(area ? "coupler-area" : "coupler-line", area ? 0x0A0B : 0x0A0A, V,
				area ? "vKNX Area Coupler" : "vKNX Line Coupler", area ? "VKNXAC1" : "VKNXLC1", area ? 0x0A0B : 0x0A0A, 2,
				params, List.of(), true);
	}

	public static final ProductDef COUPLER_LINE = coupler(false);
	public static final ProductDef COUPLER_AREA = coupler(true);

	public static final Map<String, ProductDef> ALL;
	static {
		final var m = new java.util.LinkedHashMap<String, ProductDef>();
		for (final var p : List.of(PUSH_BUTTON, PUSH_ROCKER, PUSH_SLIDER, SWITCH_ACTUATOR, SWITCH_ACTUATOR_4, SWITCH_ACTUATOR_8,
				DIMMER, BLIND_2, MULTISENSOR, COUPLER_LINE, COUPLER_AREA))
			m.put(p.key(), p);
		ALL = java.util.Collections.unmodifiableMap(m);
	}
}
