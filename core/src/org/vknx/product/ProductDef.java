package org.vknx.product;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for one vKNX product: the simulator backend reads parameters and
 * communication objects from here, and {@code ProductXmlGenerator} writes the ETS product-database XML
 * from exactly the same data, so ETS and simulator cannot drift apart.
 */
public record ProductDef(String key, int appNumber, int appVersion, String name, String orderNumber,
		int hardwareSerial, int paramAreaSize, List<ParamDef> params, List<ObjDef> objects, boolean coupler,
		boolean secure) {

	/** Coupler flag given, not secure. */
	public ProductDef(final String key, final int appNumber, final int appVersion, final String name,
			final String orderNumber, final int hardwareSerial, final int paramAreaSize, final List<ParamDef> params,
			final List<ObjDef> objects, final boolean coupler) {
		this(key, appNumber, appVersion, name, orderNumber, hardwareSerial, paramAreaSize, params, objects, coupler, false);
	}

	/** A normal (non-coupler) product. */
	public ProductDef(final String key, final int appNumber, final int appVersion, final String name,
			final String orderNumber, final int hardwareSerial, final int paramAreaSize, final List<ParamDef> params,
			final List<ObjDef> objects) {
		this(key, appNumber, appVersion, name, orderNumber, hardwareSerial, paramAreaSize, params, objects, false);
	}

	public ProductDef {
		params = List.copyOf(params);
		objects = List.copyOf(objects);
	}

	/** The key of the non-secure product this one is built on (behaviour and parameters are the same). */
	public String baseKey() { return key.startsWith("secure-") ? key.substring(7) : key; }

	/** A KNX Data Secure variant of this product: same parameters and objects, own application, ETS flags it as secure. */
	public ProductDef secured(final String newKey, final int newApp, final String newName, final String newOrder) {
		return new ProductDef(newKey, newApp, appVersion, newName, newOrder, newApp, paramAreaSize, params, objects, false, true);
	}

	public Optional<ObjDef> object(final int number) {
		return objects.stream().filter(o -> o.number() == number).findFirst();
	}

	/** Text shown by ETS for the name of a channel the user has not named. */
	public static final String NO_NAME = "unnamed";

	/**
	 * The name parameter of the channel an object belongs to ("Out1Name" for "Out1Switch"), or null. The object name
	 * starts with the parameter name without "Name" and the next character is a capital letter, so "In1" does not
	 * match "In10Switch". The info display has screen names, not channel names, so its objects never match.
	 */
	public ParamDef nameParamOf(final ObjDef o) {
		if (key.equals("info-display"))
			return null;
		for (final ParamDef p : params) {
			if (!p.isText() || !p.name().endsWith("Name"))
				continue;
			final String pre = p.name().substring(0, p.name().length() - 4);
			if (o.name().length() > pre.length() && o.name().startsWith(pre) && Character.isUpperCase(o.name().charAt(pre.length())))
				return p;
			if (o.name().equals(pre))
				return p;
		}
		return null;
	}

	/**
	 * The object text ETS shows when the object follows the name of its channel: "Output 1: switch" becomes
	 * "Output 1 - {{0:unnamed}}: switch" ({{0:...}} is replaced by the text parameter, the default is shown while the
	 * name is empty). Objects without a name parameter keep their text.
	 */
	public String dynamicText(final ObjDef o) {
		if (nameParamOf(o) == null)
			return o.text();
		final int c = o.text().indexOf(": ");
		final String slot = " - {{0:" + NO_NAME + "}}";
		return c < 0 ? o.text() + slot : o.text().substring(0, c) + slot + o.text().substring(c);
	}

	/** The same text with the current name filled in (what ETS shows), for the simulator's own object lists. */
	public String displayText(final ObjDef o, final String currentName) {
		final String t = dynamicText(o);
		if (t.equals(o.text()))
			return t;
		final String n = currentName == null || currentName.isBlank() ? NO_NAME : currentName.strip();
		return t.replace("{{0:" + NO_NAME + "}}", n);
	}

	public ParamDef param(final String name) {
		return params.stream().filter(p -> p.name().equals(name)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("no parameter " + name + " in " + key));
	}

	/** A parameter area with every parameter at its default value. */
	public byte[] defaultParams() {
		final byte[] area = new byte[paramAreaSize];
		for (final ParamDef p : params)
			if (p.isText())
				p.writeText(area, p.defaultText());
			else
				p.write(area, p.defaultValue());
		return area;
	}

	/**
	 * A parameter area as ETS would write it: defaults, then the given overrides as name, value pairs
	 * (value an Integer, or a String for text parameters). Used by the tests.
	 */
	public byte[] params(final Object... nameValue) {
		final byte[] area = defaultParams();
		for (int i = 0; i + 1 < nameValue.length; i += 2) {
			final ParamDef p = param((String) nameValue[i]);
			if (nameValue[i + 1] instanceof String s)
				p.writeText(area, s);
			else
				p.write(area, (Integer) nameValue[i + 1]);
		}
		return area;
	}

	/** 5-byte program version as read by ETS: manufacturer(2) + application number(2) + version(1). */
	public byte[] programVersion(final int manufacturerId) {
		return new byte[] { (byte) (manufacturerId >> 8), (byte) manufacturerId, (byte) (appNumber >> 8),
			(byte) appNumber, (byte) appVersion };
	}

	/** 6-byte hardware type as read by ETS: manufacturer(2) + hardware serial(2) + version(2)... see Products. */
	public byte[] hardwareType(final int manufacturerId) {
		return new byte[] { (byte) (manufacturerId >> 8), (byte) manufacturerId, 0, (byte) (hardwareSerial >> 8),
			(byte) hardwareSerial, 1 };
	}
}
