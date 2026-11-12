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
	static final int V = 5;

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

	private static int indexOfParam(final List<ParamDef> params, final String name) {
		for (int i = 0; i < params.size(); i++)
			if (params.get(i).name().equals(name))
				return i;
		throw new IllegalArgumentException(name);
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
		// blind key: press moves at once (added in application version 5; after the older parameters, but listed next to BlindDir)
		for (int k = 1; k <= 4; k++) {
			final String pre = "Key" + k, page = "Key " + k, en = pre + "Enable", fn = pre + "Function";
			final int b = (k - 1) * STRIDE, at = indexOfParam(params, pre + "BlindDir") + 1;
			final var shortPress = new ParamDef(id++, pre + "BlindShort", "Short press", b + 19, 1, 1, 0, List.of(
					new Choice(0, "Step / stop (a long press moves)"), new Choice(1, "Move at once (press again to stop)")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2).showWhen(pre + "BlindMode", 0, 1, 2);
			final var runTime = new ParamDef(id++, pre + "BlindRun", "Press again to stop within", b + 19, 2, 3, 2, List.of(
					new Choice(0, "10 s"), new Choice(1, "20 s"), new Choice(2, "30 s"), new Choice(3, "60 s"), new Choice(4, "120 s")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2).showWhen(pre + "BlindMode", 0, 1, 2).showWhen(pre + "BlindShort", 1);
			params.add(at, shortPress);
			params.add(at + 1, runTime);
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
			objs.add(new ObjDef(o + 2, pre + "RelativeDimming", page + ": relative dimming", "Input", 4, "DPST-3-7", false, true, false, false)
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
		// weather alarms (added in this release, so they come last): one wind and one rain alarm object for the whole device,
		// a monitoring time for each, and per channel what the blind does when an alarm is active
		final String wa = "Weather alarms";
		params.add(new ParamDef(id++, "WindMonitor", "Monitor the wind alarm, timeout", g + 1, 0, 8, 0, MONITOR_CHOICES).inGroup(wa));
		params.add(new ParamDef(id++, "RainMonitor", "Monitor the rain alarm, timeout", g + 2, 0, 8, 0, MONITOR_CHOICES).inGroup(wa));
		for (int c = 1; c <= 4; c++) {
			final String pre = "Ch" + c, page = "Blind " + c;
			params.add(new ParamDef(id++, pre + "WindReaction", "Wind alarm: blind", g + 2 + c, 0, 2, 1, ALARM_REACTION)
					.inGroup(page).showWhen(pre + "Enable", 1));
			params.add(new ParamDef(id++, pre + "RainReaction", "Rain alarm: blind", g + 2 + c, 2, 2, 0, ALARM_REACTION)
					.inGroup(page).showWhen(pre + "Enable", 1));
		}
		objs.add(new ObjDef(13, "WindAlarm", "Wind alarm (1 = alarm)", "Input", 1, "DPST-1-5", false, true, false, false).inGroup(wa));
		objs.add(new ObjDef(14, "RainAlarm", "Rain alarm (1 = alarm)", "Input", 1, "DPST-1-5", false, true, false, false).inGroup(wa));
		return new ProductDef("blind-actuator", 0x0A06, V, "vKNX Blind Actuator 4-fold", "VKNXBL4", 0x0A06, g + 7, params, objs);
	}

	private static final List<Choice> MONITOR_CHOICES = List.of(new Choice(0, "Off"), new Choice(5, "5 minutes"), new Choice(10, "10 minutes"),
			new Choice(15, "15 minutes"), new Choice(30, "30 minutes"), new Choice(60, "60 minutes"));
	private static final List<Choice> ALARM_REACTION = List.of(new Choice(0, "Ignores the alarm"), new Choice(1, "Moves up (retracts)"),
			new Choice(2, "Moves down"));

	public static final ProductDef BLIND_ACTUATOR = blindActuator();

	// ------------------------------------------------------------------ multisensor

	/** One multisensor measurement: object name, ETS page and text, DPT, unit shown in the UI, parameter scale and range. */
	public record Channel(String name, String page, String dpt, String unit, double unitsPerStep, double toDpt, int def,
			int min, int max, String valueText) {}

	/**
	 * The six measurements. A parameter value is converted to the engineering value with {@code unitsPerStep} and to the
	 * DPT 9 value that is sent on the bus with {@code toDpt} (pressure is entered in hPa and sent in Pa). Temperature and
	 * wind speed are entered in whole units plus a separate "tenths" parameter (see {@link #TENTHS_OFFSET}), so that
	 * 21 and 5 mean 21.5 °C (before 0.10.3 the parameter was in tenths, which made 20 look like 2.0 °C).
	 */
	public static final List<Channel> CHANNELS = List.of(
		new Channel("CO2", "CO2", "DPST-9-8", "ppm", 1, 1, 600, 0, 5000, "Value (ppm)"),
		new Channel("Humidity", "Humidity", "DPST-9-7", "%", 1, 1, 45, 0, 100, "Value (%)"),
		new Channel("Illuminance", "Illuminance", "DPST-9-4", "lux", 1, 1, 300, 0, 65000, "Value (lux)"),
		new Channel("Temperature", "Temperature", "DPST-9-1", "°C", 1, 1, 21, -30, 60, "Value (whole °C, tenths below)"),
		new Channel("Pressure", "Air pressure", "DPST-9-6", "hPa", 1, 100, 1013, 300, 1100, "Value (hPa)"),
		new Channel("WindSpeed", "Wind speed", "DPST-9-5", "m/s", 1, 1, 3, 0, 50, "Value (whole m/s, tenths below)"));

	/** Bytes per channel in the parameter area: mode(1) + pad(1) + interval(2) + value(2) + min(2) + max(2). */
	public static final int CHANNEL_BYTES = 10;

	/** Temperature and wind speed have a "tenths" parameter (0-9, one byte each) after the channel blocks. Value: whole units. */
	public static final Map<String, Integer> TENTHS_OFFSET = Map.of("Temperature", CHANNELS.size() * CHANNEL_BYTES,
			"WindSpeed", CHANNELS.size() * CHANNEL_BYTES + 1);
	public static final Map<String, Integer> TENTHS_DEFAULT = Map.of("Temperature", 5, "WindSpeed", 0);

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
			if (TENTHS_OFFSET.containsKey(pre))
				params.add(ParamDef.number(id++, pre + "Tenths", "Tenths (0-9, added to the value)", TENTHS_OFFSET.get(pre), 8,
						TENTHS_DEFAULT.get(pre), 0, 9).inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 0));
			params.add(ParamDef.number(id++, pre + "Min", "Random minimum", b + 6, 16, c.min() < 0 ? c.min() : c.def() / 2, c.min(), c.max())
					.inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			params.add(ParamDef.number(id++, pre + "Max", "Random maximum", b + 8, 16, c.def() + Math.max(1, Math.abs(c.def()) / 2),
					c.min(), c.max()).inGroup(c.page()).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			objs.add(new ObjDef(i + 1, pre, c.page() + " (" + c.unit() + ")", "Output", 16, c.dpt(), true, false, true, false)
					.inGroup(c.page()).showWhen(pre + "Enable", 1));
		}
		return new ProductDef("multisensor", 0x0A09, V, "vKNX Multisensor", "VKNXMS1", 0x0A09, CHANNELS.size() * CHANNEL_BYTES + 2,
				params, objs);
	}

	/** Multisensor: six measurements, each with its own group object and its own ETS page (value or random range + interval). */
	public static final ProductDef MULTISENSOR = multisensor();


	// ------------------------------------------------------------------ info display

	public static final int DISPLAY_SCREENS = 5, DISPLAY_ROWS = 6;
	/** Application version of the info display: one ahead of the other products since the light control screen was added. */
	public static final int DISPLAY_VERSION = V + 1;
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

	/** Where the light control settings start (after the screens) and how many bytes each screen has there: kind and colour, warmest, coldest white. */
	public static final int DISPLAY_LIGHT_AT = DISPLAY_SCREENS * DISPLAY_SCREEN_BYTES, DISPLAY_LIGHT_BYTES = 3;
	/** Group objects of a light control screen: switch, brightness, RGB, colour temperature (outputs), then the same four as status inputs. */
	public static final int DISPLAY_LIGHT_OBJS = 8;

	/** Object number of a light control screen: {@code kind} 0 switch, 1 brightness, 2 RGB, 3 colour temperature; 4 to 7 the status inputs in the same order. */
	public static int displayLightObject(final int screen, final int kind) {
		return 3 + DISPLAY_SCREENS * DISPLAY_ROWS * 4 + (screen - 1) * DISPLAY_LIGHT_OBJS + kind;
	}

	/**
	 * Info display: up to five screens (named, each can be left out). A screen either shows six rows of values or (since
	 * application version 6) is a light control: on/off, a brightness slider and a colour wheel (RGB) or a colour
	 * temperature slider (tunable white), which sends to a dimming actuator and follows its status objects.
	 * A values screen has six rows. A row has a label and a type
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
			final String kd = "S" + s + "Kind";
			for (int r = 1; r <= DISPLAY_ROWS; r++) {
				final String pre = "S" + s + "R" + r, ty = pre + "Type";
				final int rb = b + 17 + (r - 1) * DISPLAY_ROW_BYTES;
				params.add(new ParamDef(id++, ty, "Row " + r + " shows", rb, 0, 4, 0, choices).inGroup(page).showWhen(en, 1).showWhen(kd, 0));
				params.add(ParamDef.text(id++, pre + "Label", "Row " + r + " text", rb + 1, DISPLAY_LABEL_CHARS, "Value " + r)
						.inGroup(page).showWhen(en, 1).showWhen(kd, 0).showWhen(ty, 1, 2, 3, 4, 5, 6, 7, 8, 9));
				final String t = page + " row " + r + ": ";
				objs.add(new ObjDef(displayObject(s, r, 0), pre + "Status", t + "on/off status", "Input", 1, "DPST-1-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(kd, 0).showWhen(ty, 1).withReadOnInit());
				objs.add(new ObjDef(displayObject(s, r, 1), pre + "Percent", t + "percent value", "Input", 8, "DPST-5-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(kd, 0).showWhen(ty, 2).withReadOnInit());
				objs.add(new ObjDef(displayObject(s, r, 2), pre + "Scene", t + "active scene", "Input", 8, "DPST-18-1", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(kd, 0).showWhen(ty, 3));
				objs.add(new ObjDef(displayObject(s, r, 3), pre + "Value", t + "measured value", "Input", 16, "DPT-9", false, true, false, true)
						.inGroup(page).showWhen(en, 1).showWhen(kd, 0).showWhen(ty, 4, 5, 6, 7, 8, 9).withReadOnInit());
			}
		}
		// light control screens (application version 6): the settings come after all screens, so a display that still has
		// the older download reads the defaults (every screen shows values) until it is downloaded again
		for (int s = 1; s <= DISPLAY_SCREENS; s++) {
			final String page = "Screen " + s, en = "S" + s + "Enable", kd = "S" + s + "Kind", col = "S" + s + "Colour";
			final int b = DISPLAY_LIGHT_AT + (s - 1) * DISPLAY_LIGHT_BYTES;
			final int at = indexOfParam(params, "S" + s + "Name") + 1;
			params.add(at, new ParamDef(id++, kd, "The screen is", b, 0, 1, 0, List.of(
					new Choice(0, "Values (six rows)"), new Choice(1, "Light control (on/off, brightness, colour)"))).inGroup(page).showWhen(en, 1));
			params.add(at + 1, new ParamDef(id++, col, "Colour", b, 1, 2, 1, List.of(
					new Choice(0, "None (on/off and brightness only)"), new Choice(1, "RGB (colour wheel)"),
					new Choice(2, "Tunable white (warm to cold)"))).inGroup(page).showWhen(en, 1).showWhen(kd, 1));
			params.add(at + 2, ParamDef.number(id++, "S" + s + "WarmK", "Warmest white (x 100 K)", b + 1, 8, 27, 15, 40)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 2));
			params.add(at + 3, ParamDef.number(id++, "S" + s + "ColdK", "Coldest white (x 100 K)", b + 2, 8, 65, 40, 100)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 2));
			final String t = page + " light: ", pre = "S" + s + "Light";
			objs.add(new ObjDef(displayLightObject(s, 0), pre + "Switch", t + "switch", "Output", 1, "DPST-1-1", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1));
			objs.add(new ObjDef(displayLightObject(s, 1), pre + "Brightness", t + "brightness (value)", "Output", 8, "DPST-5-1", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1));
			objs.add(new ObjDef(displayLightObject(s, 2), pre + "Rgb", t + "colour RGB", "Output", 24, "DPST-232-600", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 1));
			objs.add(new ObjDef(displayLightObject(s, 3), pre + "ColourTemp", t + "colour temperature (K)", "Output", 16, "DPST-7-600", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 2));
			objs.add(new ObjDef(displayLightObject(s, 4), pre + "SwitchStatus", t + "switch status", "Input", 1, "DPST-1-1", false, true, false, true)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).withReadOnInit());
			objs.add(new ObjDef(displayLightObject(s, 5), pre + "BrightnessStatus", t + "brightness status", "Input", 8, "DPST-5-1", false, true, false, true)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).withReadOnInit());
			objs.add(new ObjDef(displayLightObject(s, 6), pre + "RgbStatus", t + "colour RGB status", "Input", 24, "DPST-232-600", false, true, false, true)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 1).withReadOnInit());
			objs.add(new ObjDef(displayLightObject(s, 7), pre + "ColourTempStatus", t + "colour temp. status (K)", "Input", 16, "DPST-7-600", false, true, false, true)
					.inGroup(page).showWhen(en, 1).showWhen(kd, 1).showWhen(col, 2).withReadOnInit());
		}
		return new ProductDef("info-display", 0x0A0A, DISPLAY_VERSION, "vKNX Info Display", "VKNXID1", 0x0A0A,
				DISPLAY_LIGHT_AT + DISPLAY_SCREENS * DISPLAY_LIGHT_BYTES, params, objs);
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

	// ------------------------------------------------------------------ binary input (4 inputs)

	public static final int BI_INPUTS = 4;
	private static final List<Choice> EDGE_COMMAND = List.of(new Choice(0, "Nothing"), new Choice(1, "On"), new Choice(2, "Off"),
			new Choice(3, "Toggle (alternating on/off)"));

	/**
	 * Binary input with four inputs for potential-free contacts (push buttons, switches, window contacts). Per input (block
	 * of {@link #STRIDE} bytes): byte 0 = Enable, Function, command on closing, command on opening, DimDir; byte 1 = DimStep,
	 * BlindMode, LongPress; bytes 2-17 = name; bytes 18-19 = cyclic sending (seconds). Objects per input: 4 (switch, relative
	 * dimming, blind move, blind step/stop), shown by function.
	 */
	private static ProductDef binaryInput() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int k = 1; k <= BI_INPUTS; k++) {
			final String pre = "In" + k, page = "Input " + k, en = pre + "Enable", fn = pre + "Function";
			final int b = (k - 1) * STRIDE, o = (k - 1) * 4;
			params.add(enable(id++, pre, b, "Enable input", page));
			params.add(nameParam(id++, pre, b, "Input " + k, page));
			params.add(new ParamDef(id++, fn, "Function", b, 1, 2, 0, List.of(new Choice(0, "Switching (reacts to closing and opening)"),
					new Choice(1, "Relative dimming (short and long press)"), new Choice(2, "Blinds (short and long press)")))
					.inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "Closing", "When the contact closes, send", b, 3, 2, 1, EDGE_COMMAND)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0));
			params.add(new ParamDef(id++, pre + "Opening", "When the contact opens, send", b, 5, 2, 2, EDGE_COMMAND)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0));
			params.add(ParamDef.number(id++, pre + "Cyclic", "Send cyclically every (seconds, 0 = never)", b + 18, 16, 0, 0, 65535)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0));
			params.add(new ParamDef(id++, pre + "DimDir", "Direction", b, 7, 1, 0, List.of(
					new Choice(0, "Brighter (short press: on)"), new Choice(1, "Darker (short press: off)")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			params.add(new ParamDef(id++, pre + "DimStep", "Dimming step", b + 1, 0, 3, 3, List.of(
					new Choice(1, "100 % (run to end)"), new Choice(2, "50 %"), new Choice(3, "25 %"), new Choice(4, "12.5 %"),
					new Choice(5, "6.25 %"))).inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			params.add(new ParamDef(id++, pre + "BlindMode", "Contact sends", b + 1, 3, 2, 2, List.of(new Choice(0, "Up"),
					new Choice(1, "Down"), new Choice(2, "Up / Down (alternating)")))
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
			params.add(new ParamDef(id++, pre + "LongPress", "Long-press time", b + 1, 5, 2, 1, List.of(
					new Choice(0, "0.4 s"), new Choice(1, "0.6 s"), new Choice(2, "1.0 s"))).inGroup(page).showWhen(en, 1).showWhen(fn, 1, 2));
			final String t = page + ": ";
			objs.add(new ObjDef(o + 1, pre + "Switch", t + "switch", "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 0, 1));
			objs.add(new ObjDef(o + 2, pre + "Dimming", t + "relative dimming", "Output", 4, "DPST-3-7", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 1));
			objs.add(new ObjDef(o + 3, pre + "Move", t + "blind move up/down", "Output", 1, "DPST-1-8", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
			objs.add(new ObjDef(o + 4, pre + "StepStop", t + "blind step / stop", "Output", 1, "DPST-1-7", false, false, true, false)
					.inGroup(page).showWhen(en, 1).showWhen(fn, 2));
		}
		return new ProductDef("binary-input", 0x0A0E, V, "vKNX Binary Input 4-fold", "VKNXBI4", 0x0A0E, BI_INPUTS * STRIDE, params, objs);
	}

	public static final ProductDef BINARY_INPUT = binaryInput();

	// ------------------------------------------------------------------ room thermostat (heating)

	/** Thermostat parameter bytes (see {@link #thermostat()}). */
	public static final int TH_SIZE = 19;
	/** Room speed choices (simulated time per real time) and the hysteresis / proportional band / integral time choices. */
	public static final List<Integer> ROOM_SPEEDS = List.of(1, 10, 60, 240);

	/**
	 * Room thermostat for heating. The room temperature comes from a simulated room (it warms up while the control value is
	 * above 0 and cools towards the outside temperature), a fixed value or an external sensor object. Four operating modes
	 * (comfort, standby, economy, building protection) with a setpoint each; a window contact switches to building
	 * protection. The control value is two-point (1 bit) or continuous PI (1 byte). Objects: 1 room temperature, 2 external
	 * temperature, 3 operating mode (DPT 20.102), 4 operating mode status, 5 window contact, 6 setpoint status, 7 control value
	 * (1 bit), 8 control value (1 byte).
	 */
	private static ProductDef thermostat() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		final String room = "Room temperature", sp = "Setpoints", ctl = "Controller", op = "Operation";
		params.add(new ParamDef(id++, "TempSource", "Room temperature comes from", 0, 0, 2, 0, List.of(
				new Choice(0, "Simulated room (heats up and cools down)"), new Choice(1, "Fixed value"),
				new Choice(2, "External sensor (group object)"))).inGroup(room));
		params.add(ParamDef.number(id++, "FixedTemp", "Room temperature (whole °C)", 1, 8, 20, 0, 40).inGroup(room).showWhen("TempSource", 1));
		params.add(ParamDef.number(id++, "FixedTenths", "Tenths (0-9, added to the value)", 2, 8, 0, 0, 9).inGroup(room).showWhen("TempSource", 1));
		params.add(ParamDef.number(id++, "StartTemp", "Start temperature (whole °C)", 3, 8, 19, 5, 30).inGroup(room).showWhen("TempSource", 0));
		params.add(ParamDef.number(id++, "Ambient", "Outside temperature (whole °C)", 4, 16, 15, -20, 35).inGroup(room).showWhen("TempSource", 0));
		params.add(new ParamDef(id++, "RoomSpeed", "Simulation speed", 6, 0, 8, 60, List.of(new Choice(1, "Real time"),
				new Choice(10, "10 times faster"), new Choice(60, "60 times faster (1 minute = 1 hour)"),
				new Choice(240, "240 times faster"))).inGroup(room).showWhen("TempSource", 0));
		params.add(ParamDef.number(id++, "TempCycle", "Send temperature every (seconds, 0 = never)", 17, 16, 60, 0, 65535).inGroup(room));
		params.add(new ParamDef(id++, "StartMode", "Operating mode after download", 0, 4, 3, 1, List.of(new Choice(1, "Comfort"),
				new Choice(2, "Standby"), new Choice(3, "Economy"), new Choice(4, "Building protection"))).inGroup(op));
		params.add(new ParamDef(id++, "WindowUsed", "Window contact", 0, 3, 1, 0, List.of(new Choice(0, "Not used"),
				new Choice(1, "Used (open window = building protection)"))).inGroup(op));
		final String[] modes = { "Comfort", "Standby", "Economy" };
		final int[] defs = { 21, 19, 17 };
		for (int m = 0; m < 3; m++) {
			params.add(ParamDef.number(id++, modes[m] + "Setpoint", modes[m] + " setpoint (whole °C)", 7 + m, 8, defs[m], 5, 35).inGroup(sp));
			params.add(new ParamDef(id++, modes[m] + "Half", modes[m] + ": add 0.5 °C", 11, m, 1, 0, NO_YES).inGroup(sp));
		}
		params.add(ParamDef.number(id++, "ProtectionSetpoint", "Building protection setpoint (whole °C)", 10, 8, 7, 5, 15).inGroup(sp));
		params.add(new ParamDef(id++, "ControlType", "Control value", 0, 2, 1, 0, List.of(new Choice(0, "Two-point (on / off, 1 bit)"),
				new Choice(1, "Continuous PI (0-100 %, 1 byte)"))).inGroup(ctl));
		params.add(new ParamDef(id++, "Hysteresis", "Hysteresis (total band)", 12, 0, 8, 5, List.of(new Choice(2, "0.2 K"),
				new Choice(3, "0.3 K"), new Choice(5, "0.5 K"), new Choice(10, "1.0 K"))).inGroup(ctl).showWhen("ControlType", 0));
		params.add(new ParamDef(id++, "PBand", "Proportional band", 13, 0, 8, 4, List.of(new Choice(2, "2 K"), new Choice(3, "3 K"),
				new Choice(4, "4 K"), new Choice(5, "5 K"), new Choice(8, "8 K"))).inGroup(ctl).showWhen("ControlType", 1));
		params.add(new ParamDef(id++, "IntTime", "Integral time", 14, 0, 8, 30, List.of(new Choice(0, "None (P controller)"),
				new Choice(15, "15 minutes"), new Choice(30, "30 minutes"), new Choice(60, "60 minutes"), new Choice(120, "120 minutes")))
				.inGroup(ctl).showWhen("ControlType", 1));
		params.add(ParamDef.number(id++, "ControlCycle", "Send control value every (seconds, 0 = on change)", 15, 16, 60, 0, 65535).inGroup(ctl));

		objs.add(new ObjDef(1, "RoomTemp", "Room temperature", "Output", 16, "DPST-9-1", true, false, true, false).inGroup(room));
		objs.add(new ObjDef(2, "ExtTemp", "External temperature sensor", "Input", 16, "DPST-9-1", false, true, false, true)
				.inGroup(room).showWhen("TempSource", 2).withReadOnInit());
		objs.add(new ObjDef(3, "Mode", "Operating mode (DPT 20.102)", "Input", 8, "DPST-20-102", false, true, false, false).inGroup(op));
		objs.add(new ObjDef(4, "ModeStatus", "Operating mode status", "Output", 8, "DPST-20-102", true, false, true, false).inGroup(op));
		objs.add(new ObjDef(5, "Window", "Window contact (1 = open)", "Input", 1, "DPST-1-19", false, true, false, false)
				.inGroup(op).showWhen("WindowUsed", 1));
		objs.add(new ObjDef(6, "SetpointStatus", "Current setpoint", "Output", 16, "DPST-9-1", true, false, true, false).inGroup(sp));
		objs.add(new ObjDef(7, "ControlOnOff", "Heating control value (on / off)", "Output", 1, "DPST-1-1", true, false, true, false)
				.inGroup(ctl).showWhen("ControlType", 0));
		objs.add(new ObjDef(8, "ControlPercent", "Heating control value (%)", "Output", 8, "DPST-5-1", true, false, true, false)
				.inGroup(ctl).showWhen("ControlType", 1));
		return new ProductDef("thermostat", 0x0A0F, V, "vKNX Room Thermostat", "VKNXRT1", 0x0A0F, TH_SIZE, params, objs);
	}

	public static final ProductDef THERMOSTAT = thermostat();

	// ------------------------------------------------------------------ heating actuator (6 channels)

	public static final int HA_CHANNELS = 6;
	public static final List<Integer> PWM_CYCLES = List.of(1, 2, 5, 10, 15, 20);
	public static final List<Integer> MONITOR_TIMES = List.of(0, 5, 10, 15, 30, 60);
	/** Object number of the "control value missing" alarm of the heating actuator. */
	public static final int HA_FAULT_OBJECT = 3 * HA_CHANNELS + 1;

	/**
	 * Heating actuator with six channels for thermal valve drives. Per channel (block of {@link #STRIDE} bytes): byte 0 =
	 * Enable, control value type, valve type; byte 1 = PWM cycle (minutes); byte 2 = monitoring time (minutes); byte 3 =
	 * emergency position (%); bytes 4-19 = name. Objects per channel: 3 (control value 1 bit, control value 1 byte, valve
	 * status), then one alarm object for the whole device.
	 */
	private static ProductDef heatingActuator() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		final var cycles = new ArrayList<Choice>();
		for (final int c : PWM_CYCLES)
			cycles.add(new Choice(c, c == 1 ? "1 minute" : c + " minutes"));
		final var monitors = new ArrayList<Choice>();
		for (final int m : MONITOR_TIMES)
			monitors.add(new Choice(m, m == 0 ? "Off" : m + " minutes"));
		for (int c = 1; c <= HA_CHANNELS; c++) {
			final String pre = "Ch" + c, page = "Valve " + c, en = pre + "Enable", ty = pre + "Type", mon = pre + "Monitor";
			final int b = (c - 1) * STRIDE, o = (c - 1) * 3;
			params.add(enable(id++, pre, b, "Enable valve", page));
			params.add(ParamDef.text(id++, pre + "Name", "Name", b + 4, NAME_CHARS, "Valve " + c).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, ty, "Control value", b, 1, 1, 0, List.of(new Choice(0, "On / off (1 bit)"),
					new Choice(1, "Continuous 0-100 % (1 byte, PWM)"))).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "ValveType", "Valve without current is", b, 2, 1, 0, List.of(
					new Choice(0, "Closed (normally closed)"), new Choice(1, "Open (normally open)"))).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "Cycle", "PWM cycle time", b + 1, 0, 8, 2, cycles).inGroup(page).showWhen(en, 1).showWhen(ty, 1));
			params.add(new ParamDef(id++, mon, "Monitor the control value, timeout", b + 2, 0, 8, 0, monitors).inGroup(page).showWhen(en, 1));
			params.add(ParamDef.number(id++, pre + "Emergency", "Valve position on timeout (%)", b + 3, 8, 30, 0, 100)
					.inGroup(page).showWhen(en, 1).showWhen(mon, 5, 10, 15, 30, 60));
			final String t = page + ": ";
			objs.add(new ObjDef(o + 1, pre + "ControlBit", t + "control value (on / off)", "Input", 1, "DPST-1-1", false, true, false, false)
					.inGroup(page).showWhen(en, 1).showWhen(ty, 0));
			objs.add(new ObjDef(o + 2, pre + "ControlByte", t + "control value (%)", "Input", 8, "DPST-5-1", false, true, false, false)
					.inGroup(page).showWhen(en, 1).showWhen(ty, 1));
			objs.add(new ObjDef(o + 3, pre + "Status", t + "valve status (1 = open)", "Output", 1, "DPST-1-1", true, false, true, false)
					.inGroup(page).showWhen(en, 1));
		}
		objs.add(new ObjDef(HA_FAULT_OBJECT, "Fault", "Control value missing (1 = alarm)", "Output", 1, "DPST-1-5", true, false, true, false));
		return new ProductDef("heating-actuator", 0x0A12, V, "vKNX Heating Actuator 6-fold", "VKNXHA6", 0x0A12, HA_CHANNELS * STRIDE, params, objs);
	}

	public static final ProductDef HEATING_ACTUATOR = heatingActuator();

	// ------------------------------------------------------------------ weather station

	/** The three measured values of the weather station (same scheme as the multisensor: whole units plus a tenths parameter). */
	public static final List<Channel> WEATHER_CHANNELS = List.of(
		new Channel("Temperature", "Outdoor temperature", "DPST-9-1", "°C", 1, 1, 8, -30, 60, "Value (whole °C, tenths below)"),
		new Channel("WindSpeed", "Wind speed", "DPST-9-5", "m/s", 1, 1, 4, 0, 50, "Value (whole m/s, tenths below)"),
		new Channel("Illuminance", "Brightness", "DPST-9-4", "lux", 1, 1, 20000, 0, 65000, "Value (lux)"));
	public static final Map<String, Integer> WEATHER_TENTHS_OFFSET = Map.of("Temperature", WEATHER_CHANNELS.size() * CHANNEL_BYTES,
			"WindSpeed", WEATHER_CHANNELS.size() * CHANNEL_BYTES + 1);
	public static final Map<String, Integer> WEATHER_TENTHS_DEFAULT = Map.of("Temperature", 5, "WindSpeed", 0);
	/** Byte offsets of the rain block (enable, mode, interval(2), chance) and the wind alarm block (enable, threshold, hysteresis, interval(2)). */
	public static final int RAIN_BASE = WEATHER_CHANNELS.size() * CHANNEL_BYTES + 2, ALARM_BASE = RAIN_BASE + 5, WEATHER_SIZE = ALARM_BASE + 5;
	/** Object numbers: the three measurements 1-3, then rain and wind alarm. */
	public static final int OBJ_RAIN = 4, OBJ_WIND_ALARM = 5;

	/**
	 * Weather station: outdoor temperature, wind speed and brightness (fixed value or random range, like the multisensor),
	 * a rain sensor and a wind alarm. Rain is a fixed state (dry or rain) or random with a chance per send interval. The wind
	 * alarm switches on when the wind speed reaches the threshold and off again when it is below the threshold minus the
	 * hysteresis; it is sent when it changes and cyclically. Blind and awning actuators react to the alarm objects.
	 */
	private static ProductDef weatherStation() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int i = 0; i < WEATHER_CHANNELS.size(); i++) {
			final Channel c = WEATHER_CHANNELS.get(i);
			final int b = i * CHANNEL_BYTES;
			final String pre = c.name(), page = c.page();
			params.add(new ParamDef(id++, pre + "Enable", "Enable " + page.toLowerCase(), b + 1, 0, 1, 1, NO_YES).inGroup(page));
			params.add(new ParamDef(id++, pre + "Mode", "Send", b, 0, 2, 0,
					List.of(new Choice(0, "Fixed value"), new Choice(1, "Random value between minimum and maximum")))
					.inGroup(page).showWhen(pre + "Enable", 1));
			params.add(ParamDef.number(id++, pre + "Interval", "Send cyclically every (seconds, 0 = never)", b + 2, 16, 30, 0, 65535)
					.inGroup(page).showWhen(pre + "Enable", 1));
			params.add(ParamDef.number(id++, pre + "Value", c.valueText(), b + 4, 16, c.def(), c.min(), c.max())
					.inGroup(page).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 0));
			if (WEATHER_TENTHS_OFFSET.containsKey(pre))
				params.add(ParamDef.number(id++, pre + "Tenths", "Tenths (0-9, added to the value)", WEATHER_TENTHS_OFFSET.get(pre), 8,
						WEATHER_TENTHS_DEFAULT.get(pre), 0, 9).inGroup(page).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 0));
			params.add(ParamDef.number(id++, pre + "Min", "Random minimum", b + 6, 16, c.min() < 0 ? c.min() : c.def() / 2, c.min(), c.max())
					.inGroup(page).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			params.add(ParamDef.number(id++, pre + "Max", "Random maximum", b + 8, 16, c.def() + Math.max(1, Math.abs(c.def()) / 2),
					c.min(), c.max()).inGroup(page).showWhen(pre + "Enable", 1).showWhen(pre + "Mode", 1));
			objs.add(new ObjDef(i + 1, pre, page + " (" + c.unit() + ")", "Output", 16, c.dpt(), true, false, true, false)
					.inGroup(page).showWhen(pre + "Enable", 1));
		}
		final String rain = "Rain";
		params.add(new ParamDef(id++, "RainEnable", "Enable rain sensor", RAIN_BASE, 0, 1, 1, NO_YES).inGroup(rain));
		params.add(new ParamDef(id++, "RainMode", "Rain sensor", RAIN_BASE + 1, 0, 2, 2, List.of(new Choice(0, "Always dry"),
				new Choice(1, "Always raining"), new Choice(2, "Random (chance of rain at every send)"))).inGroup(rain).showWhen("RainEnable", 1));
		params.add(ParamDef.number(id++, "RainChance", "Chance of rain at every send (%)", RAIN_BASE + 4, 8, 30, 0, 100)
				.inGroup(rain).showWhen("RainEnable", 1).showWhen("RainMode", 2));
		params.add(ParamDef.number(id++, "RainInterval", "Send cyclically every (seconds, 0 = never)", RAIN_BASE + 2, 16, 30, 0, 65535)
				.inGroup(rain).showWhen("RainEnable", 1));
		objs.add(new ObjDef(OBJ_RAIN, "Rain", "Rain alarm (1 = rain)", "Output", 1, "DPST-1-5", true, false, true, false)
				.inGroup(rain).showWhen("RainEnable", 1));
		final String wind = "Wind alarm";
		params.add(new ParamDef(id++, "AlarmEnable", "Enable wind alarm", ALARM_BASE, 0, 1, 1, NO_YES).inGroup(wind).showWhen("WindSpeedEnable", 1));
		params.add(ParamDef.number(id++, "AlarmThreshold", "Alarm when the wind speed reaches (m/s)", ALARM_BASE + 1, 8, 12, 0, 50)
				.inGroup(wind).showWhen("WindSpeedEnable", 1).showWhen("AlarmEnable", 1));
		params.add(ParamDef.number(id++, "AlarmHysteresis", "Alarm ends below threshold minus (m/s)", ALARM_BASE + 2, 8, 2, 0, 20)
				.inGroup(wind).showWhen("WindSpeedEnable", 1).showWhen("AlarmEnable", 1));
		params.add(ParamDef.number(id++, "AlarmInterval", "Send cyclically every (seconds, 0 = on change)", ALARM_BASE + 3, 16, 30, 0, 65535)
				.inGroup(wind).showWhen("WindSpeedEnable", 1).showWhen("AlarmEnable", 1));
		objs.add(new ObjDef(OBJ_WIND_ALARM, "WindAlarm", "Wind alarm (1 = alarm)", "Output", 1, "DPST-1-5", true, false, true, false)
				.inGroup(wind).showWhen("WindSpeedEnable", 1).showWhen("AlarmEnable", 1));
		return new ProductDef("weather-station", 0x0A13, V, "vKNX Weather Station", "VKNXWS1", 0x0A13, WEATHER_SIZE, params, objs);
	}

	public static final ProductDef WEATHER_STATION = weatherStation();

	// ------------------------------------------------------------------ couplers

	// ------------------------------------------------------------------ presence detector

	/** Follow-up times of the presence detector in seconds (index = parameter value). */
	public static final int[] PRESENCE_FOLLOW_S = { 10, 30, 60, 120, 300, 600, 900, 1800 };
	/** Follow-up times of the HVAC presence object in seconds (index = parameter value). */
	public static final int[] PRESENCE_HVAC_S = { 60, 300, 600, 900, 1800, 3600 };

	/**
	 * Presence detector for a ceiling: a light channel (switches on at movement when the room is darker than a threshold,
	 * off after the follow-up time), a presence object for heating and ventilation with its own, longer follow-up time
	 * (independent of the brightness), the measured brightness and a lock object. Movement and the brightness of the room
	 * are simulated from the card. Byte 0: follow-up time, what happens at its end, HVAC on/off, lock reaction; byte 1:
	 * HVAC follow-up time; bytes 2-3 threshold (lux); 4-5 brightness send interval; 6-7 brightness at start.
	 */
	private static ProductDef presenceDetector() {
		final String light = "Light", hvac = "Presence (HVAC)", lux = "Brightness";
		final var params = List.of(
			new ParamDef(1, "FollowTime", "Follow-up time after the last movement", 0, 0, 3, 3, List.of(
					new Choice(0, "10 s"), new Choice(1, "30 s"), new Choice(2, "1 min"), new Choice(3, "2 min"), new Choice(4, "5 min"),
					new Choice(5, "10 min"), new Choice(6, "15 min"), new Choice(7, "30 min"))).inGroup(light),
			new ParamDef(2, "SendOff", "At the end of the follow-up time", 0, 3, 1, 1, List.of(
					new Choice(0, "Sends nothing"), new Choice(1, "Sends OFF"))).inGroup(light),
			ParamDef.number(3, "Threshold", "Switch on only below (lux, 0 = always)", 2, 16, 300, 0, 2000).inGroup(light),
			new ParamDef(4, "LockReaction", "When locked", 0, 5, 1, 0, List.of(
					new Choice(0, "Sends nothing (the light stays as it is)"), new Choice(1, "Sends OFF"))).inGroup(light),
			new ParamDef(5, "HvacEnable", "Presence object for heating / ventilation", 0, 4, 1, 1, NO_YES).inGroup(hvac),
			new ParamDef(6, "HvacFollow", "Follow-up time after the last movement", 1, 0, 3, 2, List.of(
					new Choice(0, "1 min"), new Choice(1, "5 min"), new Choice(2, "10 min"), new Choice(3, "15 min"),
					new Choice(4, "30 min"), new Choice(5, "60 min"))).inGroup(hvac).showWhen("HvacEnable", 1),
			ParamDef.number(7, "LuxInterval", "Send the brightness every (seconds, 0 = never)", 4, 16, 60, 0, 65535).inGroup(lux),
			ParamDef.number(8, "LuxStart", "Brightness of the room when vKNX starts (lux)", 6, 16, 150, 0, 2000).inGroup(lux));
		final var objs = List.of(
			new ObjDef(1, "LightSwitch", "Light: switch", "Output", 1, "DPST-1-1", true, false, true, false).inGroup(light),
			new ObjDef(2, "Presence", "Presence (occupied)", "Output", 1, "DPST-1-18", true, false, true, false).inGroup(hvac).showWhen("HvacEnable", 1),
			new ObjDef(3, "Brightness", "Brightness (lux)", "Output", 16, "DPST-9-4", true, false, true, false).inGroup(lux),
			new ObjDef(4, "Lock", "Lock (1 = locked)", "Input", 1, "DPST-1-3", false, true, false, false).inGroup(light));
		return new ProductDef("presence-detector", 0x0A14, V, "vKNX Presence Detector", "VKNXPD1", 0x0A14, 8, params, objs);
	}

	public static final ProductDef PRESENCE_DETECTOR = presenceDetector();

	// ------------------------------------------------------------------ colour dimming actuator (RGB / tunable white)

	/** Channels of the colour dimming actuator and group objects per channel. */
	public static final int COLOUR_CHANNELS = 2, COLOUR_OBJS = 9;

	/**
	 * Dimming actuator for colour: two channels, each either RGB (DPT 232.600, 3 bytes) or tunable white (colour
	 * temperature in Kelvin, DPT 7.600). Per channel (block of {@link #STRIDE} bytes): byte 0 = Enable, Type, DimTime,
	 * SendStatus, StartupState; bytes 2-17 name; byte 18 warmest, byte 19 coldest colour temperature in 100 K.
	 * Objects of channel c: 9(c-1)+1 switch, +2 relative dimming, +3 brightness, +4 RGB colour, +5 colour temperature,
	 * +6 switch status, +7 brightness status, +8 RGB status, +9 colour temperature status.
	 */
	private static ProductDef colourDimmer() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		int id = 1;
		for (int c = 1; c <= COLOUR_CHANNELS; c++) {
			final String pre = "Ch" + c, page = "Channel " + c, en = pre + "Enable", ty = pre + "Type";
			final int b = (c - 1) * STRIDE, o = (c - 1) * COLOUR_OBJS;
			params.add(enable(id++, pre, b, "Enable channel", page));
			params.add(nameParam(id++, pre, b, "Channel " + c, page));
			params.add(new ParamDef(id++, ty, "Light source", b, 1, 1, c == 1 ? 0 : 1, List.of(
					new Choice(0, "RGB (colour)"), new Choice(1, "Tunable white (warm to cold)"))).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "DimTime", "Time 0-100 % for relative dimming", b, 2, 2, 1,
					List.of(new Choice(0, "2 s"), new Choice(1, "5 s"), new Choice(2, "10 s"), new Choice(3, "20 s")))
					.inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "SendStatus", "Send status", b, 4, 1, 1, NO_YES).inGroup(page).showWhen(en, 1));
			params.add(new ParamDef(id++, pre + "StartupState", "State after download/restart", b, 5, 2, 0, STARTUP)
					.inGroup(page).showWhen(en, 1));
			params.add(ParamDef.number(id++, pre + "WarmK", "Warmest white (x 100 K)", b + 18, 8, 27, 15, 40)
					.inGroup(page).showWhen(en, 1).showWhen(ty, 1));
			params.add(ParamDef.number(id++, pre + "ColdK", "Coldest white (x 100 K)", b + 19, 8, 65, 40, 100)
					.inGroup(page).showWhen(en, 1).showWhen(ty, 1));
			final String t = page + ": ";
			objs.add(new ObjDef(o + 1, pre + "Switch", t + "switch", "Input", 1, "DPST-1-1", false, true, false, false).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 2, pre + "RelativeDimming", t + "relative dimming", "Input", 4, "DPST-3-7", false, true, false, false).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 3, pre + "BrightnessValue", t + "brightness (value)", "Input", 8, "DPST-5-1", false, true, false, false).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 4, pre + "Rgb", t + "colour RGB", "Input", 24, "DPST-232-600", false, true, false, false).inGroup(page).showWhen(en, 1).showWhen(ty, 0));
			objs.add(new ObjDef(o + 5, pre + "ColourTemp", t + "colour temperature (K)", "Input", 16, "DPST-7-600", false, true, false, false).inGroup(page).showWhen(en, 1).showWhen(ty, 1));
			objs.add(new ObjDef(o + 6, pre + "SwitchStatus", t + "switch status", "Output", 1, "DPST-1-1", true, false, true, false).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 7, pre + "BrightnessStatus", t + "brightness status", "Output", 8, "DPST-5-1", true, false, true, false).inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(o + 8, pre + "RgbStatus", t + "colour RGB status", "Output", 24, "DPST-232-600", true, false, true, false).inGroup(page).showWhen(en, 1).showWhen(ty, 0));
			objs.add(new ObjDef(o + 9, pre + "ColourTempStatus", t + "colour temp. status (K)", "Output", 16, "DPST-7-600", true, false, true, false).inGroup(page).showWhen(en, 1).showWhen(ty, 1));
		}
		return new ProductDef("colour-dimmer", 0x0A15, V, "vKNX Colour Dimming Actuator 2-fold", "VKNXCD2", 0x0A15, COLOUR_CHANNELS * STRIDE, params, objs);
	}

	public static final ProductDef COLOUR_DIMMER = colourDimmer();

	// ------------------------------------------------------------------ energy meter

	/** Monitored loads of the energy meter, the byte where their blocks start, and the first load object. */
	public static final int METER_LOADS = 4, METER_LOAD_AT = 10, METER_LOAD_OBJ = 7;

	/**
	 * Energy meter (one phase, 230 V) with a simulated consumption: a base load plus up to four monitored loads. Each load
	 * has a 1 bit input (connect it to the status of a switch actuator output) and a power in watt; when the input is on
	 * the load is added. Sends power (W, DPT 14.056), energy (Wh, DPT 13.010 and kWh, DPT 13.013), current (mA, DPT 9.021)
	 * and an alarm when a limit is exceeded; the energy counter can be reset. Bytes 0-1 base load, 2 fluctuation, 3-4 power
	 * interval, 5 send on change, 6-7 energy interval, 8-9 limit; then one block of {@link #STRIDE} bytes per load
	 * (byte 0 enable, 2-17 name, 18-19 power).
	 */
	private static ProductDef energyMeter() {
		final var params = new ArrayList<ParamDef>();
		final var objs = new ArrayList<ObjDef>();
		final String gen = "Measurement", lim = "Limit";
		int id = 1;
		params.add(ParamDef.number(id++, "BaseLoad", "Base load, always on (W)", 0, 16, 120, 0, 10000).inGroup(gen));
		params.add(new ParamDef(id++, "Fluctuation", "Random fluctuation of the power", 2, 0, 8, 2, List.of(
				new Choice(0, "None"), new Choice(2, "2 %"), new Choice(5, "5 %"), new Choice(10, "10 %"))).inGroup(gen));
		params.add(ParamDef.number(id++, "PowerInterval", "Send power and current every (seconds, 0 = never)", 3, 16, 30, 0, 65535).inGroup(gen));
		params.add(new ParamDef(id++, "SendOnChange", "Send power and current when the power changes by", 5, 0, 8, 10, List.of(
				new Choice(0, "Do not send on change"), new Choice(5, "5 %"), new Choice(10, "10 %"), new Choice(25, "25 %"))).inGroup(gen));
		params.add(ParamDef.number(id++, "EnergyInterval", "Send the energy every (seconds, 0 = never)", 6, 16, 60, 0, 65535).inGroup(gen));
		params.add(ParamDef.number(id++, "Limit", "Alarm when the power is above (W, 0 = no alarm)", 8, 16, 0, 0, 20000).inGroup(lim));
		objs.add(new ObjDef(1, "Power", "Active power (W)", "Output", 32, "DPST-14-56", true, false, true, false).inGroup(gen));
		objs.add(new ObjDef(2, "EnergyWh", "Active energy (Wh)", "Output", 32, "DPST-13-10", true, false, true, false).inGroup(gen));
		objs.add(new ObjDef(3, "EnergyKwh", "Active energy (kWh)", "Output", 32, "DPST-13-13", true, false, true, false).inGroup(gen));
		objs.add(new ObjDef(4, "Current", "Current (mA)", "Output", 16, "DPST-9-21", true, false, true, false).inGroup(gen));
		objs.add(new ObjDef(5, "Reset", "Reset the energy counter (1 = reset)", "Input", 1, "DPST-1-15", false, true, false, false).inGroup(gen));
		objs.add(new ObjDef(6, "LimitAlarm", "Power limit exceeded (1 = alarm)", "Output", 1, "DPST-1-5", true, false, true, false).inGroup(lim));
		for (int l = 1; l <= METER_LOADS; l++) {
			final String pre = "Load" + l, page = "Load " + l, en = pre + "Enable";
			final int b = METER_LOAD_AT + (l - 1) * STRIDE;
			params.add(new ParamDef(id++, en, "Monitor this load", b, 0, 1, l == 1 ? 1 : 0, NO_YES).inGroup(page));
			params.add(nameParam(id++, pre, b, "Load " + l, page));
			params.add(ParamDef.number(id++, pre + "Power", "Power of the load when it is on (W)", b + 18, 16, l == 1 ? 60 : 100, 0, 10000)
					.inGroup(page).showWhen(en, 1));
			objs.add(new ObjDef(METER_LOAD_OBJ + l - 1, pre + "State", page + ": is on (status)", "Input", 1, "DPST-1-1", false, true, false, true)
					.inGroup(page).showWhen(en, 1));
		}
		return new ProductDef("energy-meter", 0x0A16, V, "vKNX Energy Meter", "VKNXEM1", 0x0A16, METER_LOAD_AT + METER_LOADS * STRIDE, params, objs);
	}

	public static final ProductDef ENERGY_METER = energyMeter();

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
		for (final var p : List.of(PUSH_BUTTON, PUSH_SLIDER, SWITCH_ACTUATOR, SECURE_PUSH_BUTTON, SECURE_SWITCH_ACTUATOR, DIMMER, BLIND_ACTUATOR, MULTISENSOR, INFO_DISPLAY, LOGIC, COUPLER,
				BINARY_INPUT, THERMOSTAT, HEATING_ACTUATOR, WEATHER_STATION, PRESENCE_DETECTOR, COLOUR_DIMMER, ENERGY_METER))
			m.put(p.key(), p);
		ALL = java.util.Collections.unmodifiableMap(m);
	}
}
