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
	static final int V = 3;

	private static final List<Choice> NO_YES = List.of(new Choice(0, "No"), new Choice(1, "Yes"));
	private static final List<Choice> STARTUP = List.of(new Choice(0, "Off"), new Choice(1, "On"), new Choice(2, "Unchanged"));
	private static final List<Choice> BUTTON_FUNCTION = List.of(new Choice(0, "Toggle (alternating on/off)"),
			new Choice(1, "On only"), new Choice(2, "Off only"));

	/** Bytes per channel block (key, output, dimmer or blind channel) in the parameter area. */
	static final int STRIDE = 20;
	/** Characters of a channel name text parameter. */
	public static final int NAME_CHARS = 16;

	private static ParamDef enable(final int id, final String pre, final int b, final String label, final String page) {
		return new ParamDef(id, pre + "Enable", label, b, 0, 1, 1, NO_YES).inGroup(page);
	}

	private static ParamDef nameParam(final int id, final String pre, final int b, final String defaultName, final String page) {
		return ParamDef.text(id, pre + "Name", "Name", b + 2, NAME_CHARS, defaultName).inGroup(page).showWhen(pre + "Enable", 1);
	}

	// ------------------------------------------------------------------ push button (4 keys)

	/**
	 * Push button with four keys. Every key can be disabled and named and has one function: Switch, Relative dimming or
	 * Blinds. Per key (block of {@link #STRIDE} bytes): byte 0 = Enable, Function, SwitchMode, FollowStatus, DimDir;
	 * byte 1 = BlindMode, BlindDir, LongPress, DimStep; bytes 2-17 = name. Objects per key: 5 (switch, status, dimming,
	 * move, step/stop), shown by function.
	 */
	private static ProductDef pushButton() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int k = 1; k <= 4; k++) {
			final String pre = "Key" + k, page = "Key " + k, en = pre + "Enable", fn = pre + "Function";
			final int b = (k - 1) * STRIDE, o = (k - 1) * 5;
			params.add(enable(id++, pre, b, "Enable key", page));
			params.add(nameParam(id++, pre, b, "Key " + k, page));
			params.add(new ParamDef(id++, fn, "Function", b, 1, 2, 0, List.of(new Choice(0, "Switch"),
					new Choice(1, "Relative dimming"), new Choice(2, "Blinds"))).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "SwitchMode", "Key sends", b, 3, 2, 2, List.of(new Choice(0, "On"),
					new Choice(1, "Off"), new Choice(2, "Alternating on / off"))).inGroup(page).showWhen(en, 1).showWhen(fn, 0));
			params.add(new ParamDef(id++, pre + "FollowStatus", "Follow status object", b, 5, 1, 1, NO_YES)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0));
			params.add(new ParamDef(id++, pre + "DimDir", "Direction", b, 6, 1, 0, List.of(
					new Choice(0, "Brighter (short press: on)"), new Choice(1, "Darker (short press: off)")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			params.add(new ParamDef(id++, pre + "DimStep", "Dimming step", b + 1, 5, 3, 1, List.of(
					new Choice(1, "100 % (run to end)"), new Choice(2, "50 %"), new Choice(3, "25 %"), new Choice(4, "12.5 %"),
					new Choice(5, "6.25 %"))).inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			params.add(new ParamDef(id++, pre + "BlindMode", "Key sends", b + 1, 0, 2, 2, List.of(new Choice(0, "Up"),
					new Choice(1, "Down"), new Choice(2, "Up / Down (alternating)"), new Choice(3, "Steps")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
			params.add(new ParamDef(id++, pre + "BlindDir", "Step direction", b + 1, 2, 1, 0, List.of(new Choice(0, "Up"),
					new Choice(1, "Down"))).inGroup(page).showWhen(en, 1).showWhen(fn, 2).showWhen(pre + "BlindMode", 3));
			params.add(new ParamDef(id++, pre + "LongPress", "Long-press time", b + 1, 3, 2, 1, List.of(
					new Choice(0, "0.4 s"), new Choice(1, "0.6 s"), new Choice(2, "1.0 s"))).inGroup(page).showWhen(en, 1).showWhen(fn, 1, 2));
			final String t = page + ": ";
			objs.add(new ObjDef(o + 1, pre + "Switch", t + "switch", "Output", 1, "DPST-1-1", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0, 1));
			objs.add(new ObjDef(o + 2, pre + "Status", t + "status", "Input", 1, "DPST-1-1", false, true, false, true)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0, 1));
			objs.add(new ObjDef(o + 3, pre + "Dimming", t + "relative dimming", "Output", 4, "DPST-3-7", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			objs.add(new ObjDef(o + 4, pre + "Move", t + "blind move up/down", "Output", 1, "DPST-1-8", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
			objs.add(new ObjDef(o + 5, pre + "StepStop", t + "blind step / stop", "Output", 1, "DPST-1-7", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
		}
		return new ProductDef("push-button", 0x0A01, V, "vKNX Push Button 4-fold", "VKNXPB4", 0x0A01, 4 * STRIDE, params, objs);
	}

	public static final ProductDef PUSH_BUTTON = pushButton();

	// ------------------------------------------------------------------ switch actuator (8 outputs)

	/** Switch actuator with 8 outputs; each can be disabled and named. Globals after the blocks: startup state, send status. */
	private static ProductDef switchActuator() {
		final int n = 8, g = n * STRIDE;
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int c = 1; c <= n; c++) {
			final String pre = "Out" + c, page = "Output " + c;
			final int b = (c - 1) * STRIDE;
			params.add(enable(id++, pre, b, "Enable output", page));
			params.add(nameParam(id++, pre, b, "Output " + c, page));
			params.add(new ParamDef(id++, pre + "Logic", "Relay logic", b, 1, 1, 0, List.of(
					new Choice(0, "Normal (on = relay closed)"), new Choice(1, "Inverted (on = relay open)")))
					.inGroup(page).showWhen(pre + "Enable", 1));
			objs.add(new ObjDef(c, pre + "Switch", page + ": switch", "Input", 1, "DPST-1-1", false, true, false, false)
					.inGroup(page).showWhen(pre + "Enable", 1));
			objs.add(new ObjDef(n + c, pre + "Status", page + ": status", "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(pre + "Enable", 1));
		}
		params.add(new ParamDef(id++, "StartupState", "State after download/restart", g, 0, 2, 0, STARTUP));
		params.add(new ParamDef(id++, "SendStatus", "Send status on change", g, 2, 1, 1, NO_YES));
		return new ProductDef("switch-actuator", 0x0A02, V, "vKNX Switch Actuator 8-fold", "VKNXSA8", 0x0A02, g + 1, params, objs);
	}

	public static final ProductDef SWITCH_ACTUATOR = switchActuator();

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

	// ------------------------------------------------------------------ dimming actuator (4 channels)

	/** Dimming actuator with 4 channels; each can be disabled and named and has its own dimming time and startup state. */
	private static ProductDef dimmer() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int c = 1; c <= 4; c++) {
			final String pre = "Ch" + c, page = "Channel " + c, en = pre + "Enable";
			final int b = (c - 1) * STRIDE, o = (c - 1) * 5;
			params.add(enable(id++, pre, b, "Enable channel", page));
			params.add(nameParam(id++, pre, b, "Channel " + c, page));
			params.add(new ParamDef(id++, pre + "DimTime", "Time 0-100 % for relative dimming", b, 1, 2, 1,
					List.of(new Choice(0, "2 s"), new Choice(1, "5 s"), new Choice(2, "10 s"), new Choice(3, "20 s")))
					.inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "OnLevel", "Level when switched on", b, 3, 1, 0, List.of(
					new Choice(0, "Last level"), new Choice(1, "100 %"))).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "StartupState", "State after download/restart", b, 4, 2, 0, STARTUP)
					.inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "SendStatus", "Send status", b, 6, 1, 1, NO_YES).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 1, pre + "Switch", page + ": switch", "Input", 1, "DPST-1-1", false, true, false, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 2, pre + "RelativeDimming", page + ": relative dimming (4 bit)", "Input", 4, "DPST-3-7", false, true, false, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 3, pre + "BrightnessValue", page + ": brightness (value)", "Input", 8, "DPST-5-1", false, true, false, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 4, pre + "SwitchStatus", page + ": switch status", "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 5, pre + "BrightnessStatus", page + ": brightness status", "Output", 8, "DPST-5-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1));
		}
		return new ProductDef("dimmer", 0x0A05, V, "vKNX Dimming Actuator 4-fold", "VKNXDM4", 0x0A05, 4 * STRIDE, params, objs);
	}

	public static final ProductDef DIMMER = dimmer();

	// ------------------------------------------------------------------ blind actuator (4 channels)

	/** Blind actuator with 4 channels; per channel its own travel time up and down. Global after the blocks: send position. */
	private static ProductDef blindActuator() {
		final int g = 4 * STRIDE;
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int c = 1; c <= 4; c++) {
			final String pre = "Ch" + c, page = "Blind " + c, en = pre + "Enable";
			final int b = (c - 1) * STRIDE, o = (c - 1) * 3;
			params.add(enable(id++, pre, b, "Enable blind", page));
			params.add(ParamDef.number(id++, pre + "UpTime", "Travel time up (seconds)", b + 1, 8, 30, 1, 255)
					.inGroup(page).showWhen(en, 1));
			params.add(ParamDef.number(id++, pre + "DownTime", "Travel time down (seconds)", b + 2, 8, 30, 1, 255)
					.inGroup(page).showWhen(en, 1));
			params.add(ParamDef.text(id++, pre + "Name", "Name", b + 3, NAME_CHARS, "Blind " + c).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 1, pre + "Move", page + ": up/down", "Input", 1, "DPST-1-8", false, true, false, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 2, pre + "Stop", page + ": stop", "Input", 1, "DPST-1-7", false, true, false, false)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 3, pre + "Position", page + ": position status", "Output", 8, "DPST-5-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1));
		}
		params.add(new ParamDef(id++, "SendPosition", "Send position as status", g, 0, 1, 1, NO_YES));
		return new ProductDef("blind-actuator", 0x0A06, V, "vKNX Blind Actuator 4-fold", "VKNXBL4", 0x0A06, g + 1, params, objs);
	}

	public static final ProductDef BLIND_ACTUATOR = blindActuator();

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
			params.add(new ParamDef(id++, pre + "Enable", "Enable " + c.page().toLowerCase(), b + 1, 0, 1, 1, NO_YES).inGroup(c.page()));
			params.add(new ParamDef(id++, pre + "Mode", "Send", b, 0, 2, 0,
					List.of(new Choice(0, "Fixed value"), new Choice(1, "Random value between minimum and maximum")))
					.inGroup(c.page()).showWhen(pre + "Enable", 1));
			params.add(ParamDef.number(id++, pre + "Interval", "Send cyclically every (seconds, 0 = never)", b + 2, 16, 30, 0, 65535)
					.inGroup(c.page()).showWhen(pre + "Enable", 1));
			params.add(ParamDef.number(id++, pre + "Value", c.valueText(), b + 4, 16, c.def(), c.min(), c.max())
					.inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 0));
			params.add(ParamDef.number(id++, pre + "Min", "Random minimum", b + 6, 16, c.min() < 0 ? c.min() : c.def() / 2, c.min(), c.max())
					.inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			params.add(ParamDef.number(id++, pre + "Max", "Random maximum", b + 8, 16, c.def() + Math.max(1, Math.abs(c.def()) / 2),
					c.min(), c.max()).inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			objs.add(new ObjDef(i + 1, pre, c.page() + " (" + c.unit() + ")", "Output", 16, c.dpt(), true, false, true, false)
					.inGroup(c.page()).showWhen(pre + "Enable", 1));
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
		for (final var p : List.of(PUSH_BUTTON, PUSH_SLIDER, SWITCH_ACTUATOR, DIMMER, BLIND_ACTUATOR, MULTISENSOR, COUPLER_LINE, COUPLER_AREA))
			m.put(p.key(), p);
		ALL = java.util.Collections.unmodifiableMap(m);
	}
}
