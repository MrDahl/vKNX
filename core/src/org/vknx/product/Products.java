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
			"push-button", 0x0A01, 1, "vKNX Tryk 1-fold", "vKNX Push Button 1-fold", "VKNX-PB1", 0x0A01, 4,
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
			"switch-actuator", 0x0A02, 1, "vKNX Koblingsaktuator 1-fold", "vKNX Switch Actuator 1-fold", "VKNX-SA1",
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

	public static final Map<String, ProductDef> ALL = Map.of(
			PUSH_BUTTON.key(), PUSH_BUTTON,
			SWITCH_ACTUATOR.key(), SWITCH_ACTUATOR);
}
