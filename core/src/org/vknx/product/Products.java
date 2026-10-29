package org.vknx.product;

import java.util.List;
import java.util.Map;

import org.vknx.product.ParamDef.Choice;

/**
 * The vKNX product catalogue (first slice: push button + switch actuator).
 * Manufacturer ID 0x00FA is the "KNX Association experimental" ID that the OpenKNX projects use for
 * DIY products; whether ETS 6.4 accepts it for our .knxprod is part of the open ETS test (see docs/).
 */
public final class Products {
	private Products() {}

	public static final int MANUFACTURER_ID = 0x00FA;

	public static final ProductDef PUSH_BUTTON = new ProductDef(
			"push-button", 0x0A01, 1, "vKNX Tryk 1-fold", "vKNX Push Button 1-fold", "VKNXPB1", 0x0A01, 4,
			List.of(
				new ParamDef(1, "ButtonFunction", "Tastefunktion", "Button function", 0, 0, 2, 0, List.of(
					new Choice(0, "Skift (vekselvis tænd/sluk)", "Toggle (alternating on/off)"),
					new Choice(1, "Kun tænd", "On only"),
					new Choice(2, "Kun sluk", "Off only"))),
				new ParamDef(2, "FollowStatus", "Følg statusobjekt", "Follow status object", 0, 2, 1, 1, List.of(
					new Choice(0, "Nej", "No"),
					new Choice(1, "Ja", "Yes")))),
			List.of(
				new ObjDef(1, "Switch", "Kontakt", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Status", "Status", "Status", "Input", 1, "DPST-1-1", false, true, false, true)));

	public static final ProductDef SWITCH_ACTUATOR = new ProductDef(
			"switch-actuator", 0x0A02, 1, "vKNX Koblingsaktuator 1-fold", "vKNX Switch Actuator 1-fold", "VKNXSA1",
			0x0A02, 4,
			List.of(
				new ParamDef(1, "RelayLogic", "Relæ-logik", "Relay logic", 0, 0, 1, 0, List.of(
					new Choice(0, "Normal (tænd = relæ sluttet)", "Normal (on = relay closed)"),
					new Choice(1, "Inverteret (tænd = relæ åbent)", "Inverted (on = relay open)"))),
				new ParamDef(2, "StartupState", "Tilstand efter download/genstart", "State after download/restart", 0, 1, 2,
					0, List.of(
					new Choice(0, "Slukket", "Off"),
					new Choice(1, "Tændt", "On"),
					new Choice(2, "Uændret", "Unchanged"))),
				new ParamDef(3, "SendStatusOnChange", "Send status ved ændring", "Send status on change", 0, 3, 1, 1,
					List.of(new Choice(0, "Nej", "No"), new Choice(1, "Ja", "Yes")))),
			List.of(
				new ObjDef(1, "Switch", "Kontakt", "Switch", "Input", 1, "DPST-1-1", false, true, false, false),
				new ObjDef(2, "Status", "Status", "Status", "Output", 1, "DPST-1-1", true, false, true, false)));

	private static final List<Choice> NO_YES = List.of(new Choice(0, "Nej", "No"), new Choice(1, "Ja", "Yes"));
	private static final List<Choice> STARTUP = List.of(new Choice(0, "Slukket", "Off"), new Choice(1, "Tændt", "On"),
			new Choice(2, "Uændret", "Unchanged"));

	/** Double push button / rocker: short press switches, long press dims. */
	public static final ProductDef PUSH_ROCKER = new ProductDef(
			"push-rocker", 0x0A03, 1, "vKNX Vippe (dobbelt tryk) 1-fold", "vKNX Rocker (double push) 1-fold", "VKNXRK1",
			0x0A03, 2,
			List.of(
				new ParamDef(1, "Orientation", "Øverste tast sender", "Upper key sends", 0, 0, 1, 0, List.of(
					new Choice(0, "Tænd / lysere (nederste: sluk / mørkere)", "On / brighter (lower: off / darker)"),
					new Choice(1, "Sluk / mørkere (nederste: tænd / lysere)", "Off / darker (lower: on / brighter)"))),
				new ParamDef(2, "LongPressTime", "Tid for langt tryk", "Long-press time", 0, 1, 2, 1, List.of(
					new Choice(0, "0,4 s", "0.4 s"), new Choice(1, "0,6 s", "0.6 s"), new Choice(2, "1,0 s", "1.0 s"))),
				new ParamDef(3, "DimStep", "Dæmpetrin (relativ dæmpning)", "Dimming step (relative dimming)", 1, 0, 3, 1,
					List.of(new Choice(1, "100 % (kør til endepunkt)", "100 % (run to end)"), new Choice(2, "50 %", "50 %"),
						new Choice(3, "25 %", "25 %"), new Choice(4, "12,5 %", "12.5 %"), new Choice(5, "6,25 %", "6.25 %")))),
			List.of(
				new ObjDef(1, "Switch", "Kontakt", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Dimming", "Relativ dæmpning", "Relative dimming", "Output", 4, "DPST-3-7", false, false, true, false),
				new ObjDef(3, "Status", "Status", "Status", "Input", 1, "DPST-1-1", false, true, false, true)));

	/** Push button with a slider: switch + 1-byte brightness value. */
	public static final ProductDef PUSH_SLIDER = new ProductDef(
			"push-slider", 0x0A04, 1, "vKNX Skydetryk (dæmper) 1-fold", "vKNX Slider push 1-fold", "VKNXSL1", 0x0A04, 1,
			List.of(
				new ParamDef(1, "ButtonFunction", "Tastefunktion", "Button function", 0, 0, 2, 0, List.of(
					new Choice(0, "Skift (vekselvis tænd/sluk)", "Toggle (alternating on/off)"),
					new Choice(1, "Kun tænd", "On only"), new Choice(2, "Kun sluk", "Off only"))),
				new ParamDef(2, "SliderSend", "Skyder sender", "Slider sends", 0, 2, 1, 0, List.of(
					new Choice(0, "Kun når skyderen slippes", "Only when released"),
					new Choice(1, "Løbende mens der skydes", "Continuously while sliding")))),
			List.of(
				new ObjDef(1, "Switch", "Kontakt", "Switch", "Output", 1, "DPST-1-1", false, false, true, false),
				new ObjDef(2, "Brightness", "Lysstyrke (værdi)", "Brightness (value)", "Output", 8, "DPST-5-1", false, false, true, false),
				new ObjDef(3, "BrightnessStatus", "Lysstyrke status", "Brightness status", "Input", 8, "DPST-5-1", false, true, false, true)));

	/** One-channel dimming actuator. */
	public static final ProductDef DIMMER = new ProductDef(
			"dimmer", 0x0A05, 1, "vKNX Dæmperaktuator 1-fold", "vKNX Dimming Actuator 1-fold", "VKNXDM1", 0x0A05, 1,
			List.of(
				new ParamDef(1, "DimTime", "Tid for 0-100 % ved relativ dæmpning", "Time 0-100 % for relative dimming", 0, 0, 2, 1,
					List.of(new Choice(0, "2 s", "2 s"), new Choice(1, "5 s", "5 s"), new Choice(2, "10 s", "10 s"),
						new Choice(3, "20 s", "20 s"))),
				new ParamDef(2, "OnLevel", "Niveau ved tænd", "Level when switched on", 0, 2, 1, 0, List.of(
					new Choice(0, "Sidste niveau", "Last level"), new Choice(1, "100 %", "100 %"))),
				new ParamDef(3, "StartupState", "Tilstand efter download/genstart", "State after download/restart", 0, 3, 2, 0,
					STARTUP),
				new ParamDef(4, "SendStatus", "Send status", "Send status", 0, 5, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Switch", "Tænd/sluk", "Switch", "Input", 1, "DPST-1-1", false, true, false, false),
				new ObjDef(2, "RelativeDimming", "Relativ dæmpning (4 bit)", "Relative dimming (4 bit)", "Input", 4, "DPST-3-7", false, true, false, false),
				new ObjDef(3, "BrightnessValue", "Lysstyrke (værdi)", "Brightness (value)", "Input", 8, "DPST-5-1", false, true, false, false),
				new ObjDef(4, "SwitchStatus", "Tænd/sluk status", "Switch status", "Output", 1, "DPST-1-1", true, false, true, false),
				new ObjDef(5, "BrightnessStatus", "Lysstyrke status", "Brightness status", "Output", 8, "DPST-5-1", true, false, true, false)));

	/** Two-channel blind actuator. */
	public static final ProductDef BLIND_2 = new ProductDef(
			"blind-2", 0x0A06, 1, "vKNX Persienneaktuator 2-fold", "vKNX Blind Actuator 2-fold", "VKNXBL2", 0x0A06, 1,
			List.of(
				new ParamDef(1, "Ch1RunTime", "Persienne 1: køretid op/ned", "Blind 1: travel time", 0, 0, 2, 1, RUN_TIMES()),
				new ParamDef(2, "Ch2RunTime", "Persienne 2: køretid op/ned", "Blind 2: travel time", 0, 2, 2, 1, RUN_TIMES()),
				new ParamDef(3, "SendPosition", "Send position som status", "Send position as status", 0, 4, 1, 1, NO_YES)),
			List.of(
				new ObjDef(1, "Ch1Move", "Persienne 1: op/ned", "Blind 1: up/down", "Input", 1, "DPST-1-8", false, true, false, false),
				new ObjDef(2, "Ch1Stop", "Persienne 1: stop", "Blind 1: stop", "Input", 1, "DPST-1-7", false, true, false, false),
				new ObjDef(3, "Ch1Position", "Persienne 1: position status", "Blind 1: position status", "Output", 8, "DPST-5-1", true, false, true, false),
				new ObjDef(4, "Ch2Move", "Persienne 2: op/ned", "Blind 2: up/down", "Input", 1, "DPST-1-8", false, true, false, false),
				new ObjDef(5, "Ch2Stop", "Persienne 2: stop", "Blind 2: stop", "Input", 1, "DPST-1-7", false, true, false, false),
				new ObjDef(6, "Ch2Position", "Persienne 2: position status", "Blind 2: position status", "Output", 8, "DPST-5-1", true, false, true, false)));

	private static List<Choice> RUN_TIMES() {
		return List.of(new Choice(0, "10 s", "10 s"), new Choice(1, "20 s", "20 s"), new Choice(2, "30 s", "30 s"),
				new Choice(3, "60 s", "60 s"));
	}

	private static ProductDef switchActuator(final int n, final int app, final String order) {
		final var params = new java.util.ArrayList<ParamDef>();
		for (int c = 1; c <= n; c++)
			params.add(new ParamDef(c, "Ch" + c + "Logic", "Udgang " + c + ": relælogik", "Output " + c + ": relay logic", 0, c - 1, 1, 0,
					List.of(new Choice(0, "Normal (tænd = relæ sluttet)", "Normal (on = relay closed)"),
						new Choice(1, "Inverteret (tænd = relæ åbent)", "Inverted (on = relay open)"))));
		params.add(new ParamDef(n + 1, "StartupState", "Tilstand efter download/genstart", "State after download/restart", 1, 0, 2, 0, STARTUP));
		params.add(new ParamDef(n + 2, "SendStatus", "Send status ved ændring", "Send status on change", 1, 2, 1, 1, NO_YES));
		final var objs = new java.util.ArrayList<ObjDef>();
		for (int c = 1; c <= n; c++)
			objs.add(new ObjDef(c, "Ch" + c + "Switch", "Udgang " + c + ": kontakt", "Output " + c + ": switch", "Input", 1, "DPST-1-1", false, true, false, false));
		for (int c = 1; c <= n; c++)
			objs.add(new ObjDef(n + c, "Ch" + c + "Status", "Udgang " + c + ": status", "Output " + c + ": status", "Output", 1, "DPST-1-1", true, false, true, false));
		return new ProductDef("switch-actuator-" + n, app, 1, "vKNX Koblingsaktuator " + n + "-fold", "vKNX Switch Actuator " + n + "-fold",
				order, app, 2, params, objs);
	}

	public static final ProductDef SWITCH_ACTUATOR_4 = switchActuator(4, 0x0A07, "VKNXSA4");
	public static final ProductDef SWITCH_ACTUATOR_8 = switchActuator(8, 0x0A08, "VKNXSA8");

	/** Multisensor: six cyclic measurement values + one random value. */
	public static final ProductDef MULTISENSOR = new ProductDef(
			"multisensor", 0x0A09, 1, "vKNX Multisensor", "vKNX Multisensor", "VKNXMS1", 0x0A09, 8,
			List.of(
				ParamDef.number(1, "CycleTime", "Cyklisk sending hver (sekunder, 0 = fra)", "Cyclic sending every (seconds, 0 = off)", 0, 16, 30, 0, 65535),
				ParamDef.number(2, "RandomInterval", "Tilfældig værdi hver (sekunder, 0 = fra)", "Random value every (seconds, 0 = off)", 2, 16, 10, 0, 65535),
				ParamDef.number(3, "RandomMin", "Tilfældig værdi: mindste tal", "Random value: lowest number", 4, 16, 0, -1000, 1000),
				ParamDef.number(4, "RandomMax", "Tilfældig værdi: største tal", "Random value: highest number", 6, 16, 100, -1000, 1000)),
			List.of(
				new ObjDef(1, "CO2", "CO2 (ppm)", "CO2 (ppm)", "Output", 16, "DPST-9-8", true, false, true, false),
				new ObjDef(2, "Humidity", "Luftfugtighed (%)", "Humidity (%)", "Output", 16, "DPST-9-7", true, false, true, false),
				new ObjDef(3, "Illuminance", "Lysstyrke (lux)", "Illuminance (lux)", "Output", 16, "DPST-9-4", true, false, true, false),
				new ObjDef(4, "Temperature", "Temperatur (°C)", "Temperature (°C)", "Output", 16, "DPST-9-1", true, false, true, false),
				new ObjDef(5, "Pressure", "Lufttryk (Pa)", "Air pressure (Pa)", "Output", 16, "DPST-9-6", true, false, true, false),
				new ObjDef(6, "WindSpeed", "Vindhastighed (m/s)", "Wind speed (m/s)", "Output", 16, "DPST-9-5", true, false, true, false),
				new ObjDef(7, "Random", "Tilfældig værdi", "Random value", "Output", 16, "DPT-9", true, false, true, false)));

	public static final Map<String, ProductDef> ALL;
	static {
		final var m = new java.util.LinkedHashMap<String, ProductDef>();
		for (final var p : List.of(PUSH_BUTTON, PUSH_ROCKER, PUSH_SLIDER, SWITCH_ACTUATOR, SWITCH_ACTUATOR_4, SWITCH_ACTUATOR_8,
				DIMMER, BLIND_2, MULTISENSOR))
			m.put(p.key(), p);
		ALL = java.util.Collections.unmodifiableMap(m);
	}
}
