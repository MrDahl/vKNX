package org.vknx.product;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for one vKNX product: the simulator backend reads parameters and
 * communication objects from here, and {@code ProductXmlGenerator} writes the ETS product-database XML
 * from exactly the same data, so ETS and simulator cannot drift apart.
 */
public record ProductDef(String key, int appNumber, int appVersion, String name, String orderNumber,
		int hardwareSerial, int paramAreaSize, List<ParamDef> params, List<ObjDef> objects, boolean coupler) {

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

	public Optional<ObjDef> object(final int number) {
		return objects.stream().filter(o -> o.number() == number).findFirst();
	}

	public ParamDef param(final String name) {
		return params.stream().filter(p -> p.name().equals(name)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("no parameter " + name + " in " + key));
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
