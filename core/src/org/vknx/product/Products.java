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
	static final int V = 4;

	private static final List<Choice> NO_YES = List.of(new Choice(0, "No"), new Choice(1, "Yes"));
	private static final List<Choice> STARTUP = List.of(new Choice(0, "Off"), new Choice(1, "On"), new Choice(2, "Unchanged"));
	private static final List<Choice> BUTTON_FUNCTION = List.of(new Choice(0, "Toggle (alternating on/off)"),
			new Choice(1, "On only"), new Choice(2, "Off only"));

	/** Bytes per channel block (key, output, dimmer or blind channel) in the parameter area. */
	static final int STRIDE = 20;
	/** Characters of a channel name text parameter. */
	public static final int NAME_CHARS = 16;

	/** Scene slots per output (switch actuator) or channel (dimming actuator). */
	public static final int SCENE_SLOTS = 4;

	private static ParamDef sceneNumber(final int id, final String name, final int slot, final int byteOffset, final String page) {
		return ParamDef.number(id, name, "Scene " + slot + ": scene number (0 = not used, 1-64)", byteOffset, 8, 0, 0, 64).inGroup(page);
	}

	private static ParamDef enable(final int id, final String pre, final int b, final String label, final String page) {
		return new ParamDef(id, pre + "Enable", label, b, 0, 1, 1, NO_YES).inGroup(page);
	}

	private static ParamDef nameParam(final int id, final String pre, final int b, final String defaultName, final String page) {
		return ParamDef.text(id, pre + "Name", "Name", b + 2, NAME_CHARS, defaultName).inGroup(page).showWhen(pre + "Enable", 1);
	}

	// ------------------------------------------------------------------ push button (4 keys)

	/**
	 * Push button with four keys. Every key can be disabled and named and has one function: Switch, Relative dimming or
	 * Blinds or Scene. Per key (block of {@link #STRIDE} bytes): byte 0 = Enable, Function, SwitchMode, FollowStatus,
	 * DimDir; byte 1 = BlindMode, BlindDir, LongPress, DimStep; bytes 2-17 = name; byte 18 = scene number; byte 19 = scene
	 * store. Objects per key: 5 (switch, status, dimming, move, step/stop), shown by function; the scene objects 21..24
	 * (one per key, DPT 18.001) come after them so the numbers of the older objects did not change.
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
					new Choice(1, "Relative dimming"), new Choice(2, "Blinds"), new Choice(3, "Scene"))).inGroup(page).showWhen(en, 1));
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
					new Choice(0, "0.4 s"), new Choice(1, "0.6 s"), new Choice(2, "1.0 s"))).inGroup(page).showWhen(en, 1).showWhen(fn, 1, 2, 3));
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
		// scene function (added in application version 4, so its parameters and objects come last)
		for (int k = 1; k <= 4; k++) {
			final String pre = "Key" + k, page = "Key " + k, en = pre + "Enable", fn = pre + "Function", t = page + ": ";
			final int b = (k - 1) * STRIDE;
			params.add(ParamDef.number(id++, pre + "Scene", "Scene number (1-64)", b + 18, 8, 1, 1, 64)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 3));
			params.add(new ParamDef(id++, pre + "SceneStore", "Long press", b + 19, 0, 1, 1, List.of(
					new Choice(0, "Nothing (scene called when pressed)"),
					new Choice(1, "Stores the scene (short press calls it)")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 3));
			objs.add(new ObjDef(20 + k, pre + "Scene", t + "scene (call / store)", "Output", 8, "DPST-18-1", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 3));
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
		// scenes (added in application version 4): one scene object for the whole device, four scene slots per output
		for (int c = 1; c <= n; c++) {
			final String pre = "Out" + c, page = "Output " + c;
			for (int s = 1; s <= SCENE_SLOTS; s++) {
				final int at = g + 1 + (c - 1) * SCENE_SLOTS * 2 + (s - 1) * 2;
				params.add(sceneNumber(id++, pre + "Scene" + s, s, at, page).showWhen(pre + "Enable", 1));
				params.add(new ParamDef(id++, pre + "Scene" + s + "Value", "Scene " + s + ": output is", at + 1, 0, 2, 1, List.of(
						new Choice(0, "Off"), new Choice(1, "On"), new Choice(2, "Keep the current state"))).inGroup(page).showWhen(pre + "Enable", 1));
			}
		}
		objs.add(new ObjDef(2 * n + 1, "Scene", "Scene (call / store)", "Input", 8, "DPST-18-1", false, true, false, false));
		return new ProductDef("switch-actuator", 0x0A02, V, "vKNX Switch Actuator 8-fold", "VKNXSA8", 0x0A02, g + 1 + n * SCENE_SLOTS * 2, params, objs);
	}

	public static final ProductDef SWITCH_ACTUATOR = switchActuator();

	// ------------------------------------------------------------------ KNX Data Secure variants

	public static final ProductDef SECURE_PUSH_BUTTON = PUSH_BUTTON.secured("secure-push-button", 0x0A10,
			"vKNX Secure Push Button 4-fold", "VKNXSPB4");
	public static final ProductDef SECURE_SWITCH_ACTUATOR = SWITCH_ACTUATOR.secured("secure-switch-actuator", 0x0A11,
			"vKNX Secure Switch Actuator 8-fold", "VKNXSSA8");

	/** Push button with a slider: switch + 1-byte brightness value, with a switch status and a brightness status input. */
	public static final ProductDef PUSH_SLIDER = new ProductDef(
			"push-slider", 0x0A04, V, "vKNX Slider push 1-fold", "VKNXSL1", 0x0A04, 3,
			List.of(
				new ParamDef(1, "ButtonFunction", "Button function", 0, 0, 2, 0, BUTTON_FUNCTION),
				new ParamDef(2, "SliderSend", "Slider sends", 0, 2, 1, 0, List.of(
					new Choice(0, "Only when released"), new Choice(1, "Continuously while sliding"))),
				new ParamDef(3, "FollowStatus", "Follow switch status object", 0, 3, 1, 1, NO_YES),
				// scene button (added in application version 4): a second key on the device that sends a scene number
				new ParamDef(4, "SceneEnable", "Scene key", 1, 0, 1, 0, NO_YES),
				ParamDef.number(5, "Scene", "Scene number (1-64)", 2, 8, 1, 1, 64).showWhen("SceneEnable", 1),
				new ParamDef(6, "SceneStore", "Long press", 1, 1, 1, 0, List.of(
						new Choice(0, "Nothing (scene called when pressed)"),
						new Choice(1, "Stores the scene (short press calls it)"))).showWhen("SceneEnable", 1),
				new ParamDef(7, "SceneLongPress", "Long-press time", 1, 2, 2, 1, List.of(
						new Choice(0, "0.4 s"), new Choice(1, "0.6 s"), new Choice(2, "1.0 s"))).showWhen("SceneEnable", 1).showWhen("SceneStore", 1)),
			List.of(
				new ObjDef(1, "Switch", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Brightness", "Brightness (value)", "Output", 8, "DPST-5-1", false, false, true, false),
				new ObjDef(3, "BrightnessStatus", "Brightness status", "Input", 8, "DPST-5-1", false, true, false, true),
				new ObjDef(4, "SwitchStatus", "Switch status", "Input", 1, "DPST-1-1", false, true, false, true),
				new ObjDef(5, "SceneKey", "Scene (call / store)", "Output", 8, "DPST-18-1", false, false, true, false).showWhen("SceneEnable", 1)));

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
		// scenes (added in application version 4): one scene object for the whole device, four scene slots per channel
		final int g = 4 * STRIDE;
		for (int c = 1; c <= 4; c++) {
			final String pre = "Ch" + c, page = "Channel " + c;
			for (int s = 1; s <= SCENE_SLOTS; s++) {
				final int at = g + (c - 1) * SCENE_SLOTS * 3 + (s - 1) * 3;
				params.add(sceneNumber(id++, pre + "Scene" + s, s, at, page).showWhen(pre + "Enable", 1));
				params.add(new ParamDef(id++, pre + "Scene" + s + "Mode", "Scene " + s + ": brightness is", at + 1, 0, 1, 0, List.of(
						new Choice(0, "Set to a value"), new Choice(1, "Keep the current brightness"))).inGroup(page).showWhen(pre + "Enable", 1));
				params.add(ParamDef.number(id++, pre + "Scene" + s + "Value", "Scene " + s + ": brightness (%)", at + 2, 8, 100, 0, 100)
						.inGroup(page).showWhen(pre + "Enable", 1).showWhen(pre + "Scene" + s + "Mode", 0));
			}
		}
		objs.add(new ObjDef(21, "Scene", "Scene (call / store)", "Input", 8, "DPST-18-1", false, true, false, false));
		return new ProductDef("dimmer", 0x0A05, V, "vKNX Dimming Actuator 4-fold", "VKNXDM4", 0x0A05, g + 4 * SCENE_SLOTS * 3, params, objs);
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


	// ------------------------------------------------------------------ info display

	public static final int DISPLAY_SCREENS = 5, DISPLAY_ROWS = 6;
	/** Parameter bytes per screen: enable(1) + name(16) + six rows of type(1) + label(12). */
	public static final int DISPLAY_SCREEN_BYTES = 96, DISPLAY_ROW_BYTES = 13, DISPLAY_LABEL_CHARS = 12;
	/** What a row shows. The value is the ETS parameter value; the unit is only for the text on the display. */
	public record RowType(int value, String text, String unit, String dpt, int sizeBits, double scale) {}
	public static final List<RowType> ROW_TYPES = List.of(
		new RowType(1, "On / off status", "", "DPST-1-1", 1, 1),
		new RowType(2, "Percent (brightness, blind position)", "%", "DPST-5-1", 8, 1),
		new RowType(3, "Active scene (DPT 18.001)", "", "DPST-18-1", 8, 1),
		new RowType(4, "Temperature", "°C", "DPT-9", 16, 1),
		new RowType(5, "Humidity", "%", "DPT-9", 16, 1),
		new RowType(6, "CO2", "ppm", "DPT-9", 16, 1),
		new RowType(7, "Illuminance", "lux", "DPT-9", 16, 1),
		new RowType(8, "Air pressure (value in Pa)", "hPa", "DPT-9", 16, 0.01),
		new RowType(9, "Wind speed", "m/s", "DPT-9", 16, 1));

	/** Object number of one row's object: {@code kind} 0 = 1 bit, 1 = percent, 2 = scene, 3 = 2-byte value. Objects 1 and 2 page through the screens. */
	public static int displayObject(final int screen, final int row, final int kind) {
		return 3 + ((screen - 1) * DISPLAY_ROWS + (row - 1)) * 4 + kind;
	}

	/**
	 * Info display: up to five screens (named, each can be left out) with six rows each. A row has a label and a type
	 * (on/off status, percent, scene or one of the multisensor values) and its own group object of the matching size.
	 * It only listens; objects 1 and 2 (1 bit) switch to the next and previous screen.
	 */
	private static ProductDef infoDisplay() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		objs.add(new ObjDef(1, "NextScreen", "Next screen (1 = show)", "Input", 1, "DPST-1-1", false, true, false, false));
		objs.add(new ObjDef(2, "PreviousScreen", "Previous screen (1 = show)", "Input", 1, "DPST-1-1", false, true, false, false));
		final var choices = new ArrayList<Choice>();
		choices.add(new Choice(0, "Not used"));
		for (final var t : ROW_TYPES)
			choices.add(new Choice(t.value(), t.text()));
		for (int s = 1; s <= DISPLAY_SCREENS; s++) {
			final String page = "Screen " + s, en = "S" + s + "Enable";
			final int b = (s - 1) * DISPLAY_SCREEN_BYTES;
			params.add(new ParamDef(id++, en, "Show this screen", b, 0, 1, s == 1 ? 1 : 0, NO_YES).inGroup(page));
			params.add(ParamDef.text(id++, "S" + s + "Name", "Name of the screen", b + 1, 16, "Screen " + s).inGroup(page).showWhen(en, 1));
			for (int r = 1; r <= DISPLAY_ROWS; r++) {
				final String pre = "S" + s + "R" + r, ty = pre + "Type";
				final int rb = b + 17 + (r - 1) * DISPLAY_ROW_BYTES;
				params.add(new ParamDef(id++, ty, "Row " + r + " shows", rb, 0, 4, 0, choices).inGroup(page).showWhen(en, 1));
				params.add(ParamDef.text(id++, pre + "Label", "Row " + r + " text", rb + 1, DISPLAY_LABEL_CHARS, "Value " + r)
						.inGroup(page).showWhen(en, 1).showWhen(ty, 1, 2, 3, 4, 5, 6, 7, 8, 9));
				final String t = page + " row " + r + ": ";
				objs.add(new ObjDef(displayObject(s, r, 0), pre + "Status", t + "on/off status", "Input", 1, "DPST-1-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(ty, 1).withReadOnInit());
				objs.add(new ObjDef(displayObject(s, r, 1), pre + "Percent", t + "percent value", "Input", 8, "DPST-5-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(ty, 2).withReadOnInit());
				objs.add(new ObjDef(displayObject(s, r, 2), pre + "Scene", t + "active scene", "Input", 8, "DPST-18-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(ty, 3));
				objs.add(new ObjDef(displayObject(s, r, 3), pre + "Value", t + "measured value", "Input", 16, "DPT-9", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(ty, 4, 5, 6, 7, 8, 9).withReadOnInit());
			}
		}
		return new ProductDef("info-display", 0x0A0A, V, "vKNX Info Display", "VKNXID1", 0x0A0A,
				DISPLAY_SCREENS * DISPLAY_SCREEN_BYTES, params, objs);
	}

	public static final ProductDef INFO_DISPLAY = infoDisplay();

	// ------------------------------------------------------------------ logic module + time master

	public static final int LOGIC_INPUTS = 16, LOGIC_OUTPUTS = 16, TIMERS = 8;
	/** Parameter bytes: per input/output enable(1) + name(16); the time block; per timer 18 bytes. */
	public static final int LOGIC_IO_BYTES = 17, TIME_BASE = 544, TIMER_BASE = 552, TIMER_BYTES = 18, TIMER_NAME_CHARS = 12;
	/** Object numbers: inputs 1-16, outputs 17-32, then date, time, date and time and one output per timer. */
	public static final int OBJ_DATE = 33, OBJ_TIME = 34, OBJ_DATETIME = 35;
	public static int logicInputObject(final int n) { return n; }
	public static int logicOutputObject(final int n) { return LOGIC_INPUTS + n; }
	public static int timerObject(final int n) { return 35 + n; }
	public static final List<String> DAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

	/**
	 * Logic module and time master in one device. Sixteen 1 bit inputs and sixteen 1 bit outputs, all hidden until they are
	 * enabled (and named) in the ETS parameters. What happens between them is drawn in the logic editor of the vKNX control
	 * panel (not in ETS) and kept inside the simulated device. The time master can send the time, the date or both and has
	 * eight weekday timers, each with a 1 bit output that switches on and/or off at a set time on the chosen weekdays.
	 */
	private static ProductDef logicModule() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int kind = 0; kind < 2; kind++) {
			final boolean in = kind == 0;
			final int count = in ? LOGIC_INPUTS : LOGIC_OUTPUTS;
			for (int n = 1; n <= count; n++) {
				final String pre = (in ? "In" : "Out") + n, en = pre + "Enable";
				final int b = (in ? 0 : LOGIC_INPUTS * LOGIC_IO_BYTES) + (n - 1) * LOGIC_IO_BYTES;
				final String page = (in ? "Inputs " : "Outputs ") + (n <= 8 ? "1-8" : "9-16");
				params.add(new ParamDef(id++, en, (in ? "Input " : "Output ") + n + " in use", b, 0, 1, 0, NO_YES).inGroup(page));
				params.add(ParamDef.text(id++, pre + "Name", (in ? "Input " : "Output ") + n + " name", b + 1, NAME_CHARS, (in ? "Input " : "Output ") + n)
						.inGroup(page).showWhen(en, 1));
				if (in)
					objs.add(new ObjDef(logicInputObject(n), pre, "Input " + n, "Input", 1, "DPST-1-1", false, true, false, true)
							.inGroup(page).showWhen(en, 1).withReadOnInit());
			}
		}
		for (int n = 1; n <= LOGIC_OUTPUTS; n++) {
			final String pre = "Out" + n, page = "Outputs " + (n <= 8 ? "1-8" : "9-16");
			objs.add(new ObjDef(logicOutputObject(n), pre, "Output " + n, "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(pre + "Enable", 1));
		}
		final String tp = "Date and time";
		params.add(new ParamDef(id++, "TimeObjects", "Send", TIME_BASE, 0, 4, 0, List.of(new Choice(0, "Nothing"),
				new Choice(1, "Time (DPT 10.001)"), new Choice(2, "Date (DPT 11.001)"), new Choice(3, "Time and date, two objects"),
				new Choice(4, "Date and time in one object (DPT 19.001)"))).inGroup(tp));
		params.add(new ParamDef(id++, "TimeAtStart", "Send after a download and at start", TIME_BASE + 1, 0, 1, 1, NO_YES)
				.inGroup(tp).showWhen("TimeObjects", 1, 2, 3, 4));
		params.add(ParamDef.number(id++, "TimeInterval", "Send every (minutes, 0 = not cyclic)", TIME_BASE + 2, 16, 60, 0, 1440)
				.inGroup(tp).showWhen("TimeObjects", 1, 2, 3, 4));
		objs.add(new ObjDef(OBJ_DATE, "Date", "Date", "Output", 24, "DPST-11-1", false, false, true, false)
				.inGroup(tp).showWhen("TimeObjects", 2, 3));
		objs.add(new ObjDef(OBJ_TIME, "Time", "Time of day", "Output", 24, "DPST-10-1", false, false, true, false)
				.inGroup(tp).showWhen("TimeObjects", 1, 3));
		objs.add(new ObjDef(OBJ_DATETIME, "DateTime", "Date and time", "Output", 64, "DPST-19-1", false, false, true, false)
				.inGroup(tp).showWhen("TimeObjects", 4));
		for (int t = 1; t <= TIMERS; t++) {
			final String pre = "Tm" + t, page = "Timer " + t, en = pre + "Enable";
			final int b = TIMER_BASE + (t - 1) * TIMER_BYTES;
			params.add(new ParamDef(id++, en, "Timer " + t + " in use", b, 0, 1, 0, NO_YES).inGroup(page));
			params.add(ParamDef.text(id++, pre + "Name", "Name", b + 6, TIMER_NAME_CHARS, "Timer " + t).inGroup(page).showWhen(en, 1));
			for (int d = 0; d < 7; d++)
				params.add(new ParamDef(id++, pre + "Day" + d, DAYS.get(d), b, d + 1, 1, 1, NO_YES).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "OnEnable", "Switch on", b + 1, 0, 1, 1, NO_YES).inGroup(page).showWhen(en, 1));
			params.add(ParamDef.number(id++, pre + "OnHour", "Switch on at: hour", b + 2, 8, 7, 0, 23).inGroup(page)
					.showWhen(en, 1).showWhen(pre + "OnEnable", 1));
			params.add(ParamDef.number(id++, pre + "OnMinute", "Switch on at: minute", b + 3, 8, 0, 0, 59).inGroup(page)
					.showWhen(en, 1).showWhen(pre + "OnEnable", 1));
			params.add(new ParamDef(id++, pre + "OffEnable", "Switch off", b + 1, 1, 1, 1, NO_YES).inGroup(page).showWhen(en, 1));
			params.add(ParamDef.number(id++, pre + "OffHour", "Switch off at: hour", b + 4, 8, 22, 0, 23).inGroup(page)
					.showWhen(en, 1).showWhen(pre + "OffEnable", 1));
			params.add(ParamDef.number(id++, pre + "OffMinute", "Switch off at: minute", b + 5, 8, 0, 0, 59).inGroup(page)
					.showWhen(en, 1).showWhen(pre + "OffEnable", 1));
			objs.add(new ObjDef(timerObject(t), pre, "Timer " + t + " output", "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1));
		}
		return new ProductDef("logic", 0x0A0D, V, "vKNX Logic Module and Time Master", "VKNXLG1", 0x0A0D,
				TIMER_BASE + TIMERS * TIMER_BYTES, params, objs);
	}

	public static final ProductDef LOGIC = logicModule();

	// ------------------------------------------------------------------ couplers

	/**
	 * The coupler: ONE product for both line couplers (x.y.0) and area couplers (x.0.0); the role follows from the
	 * address ETS gives it. For ETS it is a TP/TP coupler (mask 0x0912) with a router object and a group filter
	 * table, not a System B device, so it has no parameter memory, no group objects and no tables. The routing
	 * settings are router object properties (see {@code ProductXmlGenerator.generateCoupler}).
	 */
	public static final ProductDef COUPLER = new ProductDef("coupler", 0x0A0C, 1, "vKNX Coupler", "VKNXC1", 0x0A0C, 0,
			List.of(), List.of(), true);

	public static final Map<String, ProductDef> ALL;
	static {
		final var m = new java.util.LinkedHashMap<String, ProductDef>();
		for (final var p : List.of(PUSH_BUTTON, PUSH_SLIDER, SWITCH_ACTUATOR, SECURE_PUSH_BUTTON, SECURE_SWITCH_ACTUATOR, DIMMER, BLIND_ACTUATOR, MULTISENSOR, INFO_DISPLAY, LOGIC, COUPLER))
			m.put(p.key(), p);
		ALL = java.util.Collections.unmodifiableMap(m);
	}
}
