package org.vknx.product;

import java.util.ArrayList;
import java.util.List;

/**
 * One ETS communication object (group object) of a product. {@code number} is the ETS object number (1-based).
 * {@code group} is the ETS parameter page the object is listed on ("" = "General"). {@code when} (empty = always shown)
 * lists the parameter conditions under which ETS shows the object.
 */
public record ObjDef(int number, String name, String text, String functionText, int sizeBits, String dpt,
		boolean read, boolean write, boolean transmit, boolean update, String group, List<Cond> when) {

	public ObjDef(final int number, final String name, final String text, final String functionText, final int sizeBits,
			final String dpt, final boolean read, final boolean write, final boolean transmit, final boolean update) {
		this(number, name, text, functionText, sizeBits, dpt, read, write, transmit, update, "", List.of());
	}

	public ObjDef {
		when = List.copyOf(when);
	}

	public ObjDef inGroup(final String page) {
		return new ObjDef(number, name, text, functionText, sizeBits, dpt, read, write, transmit, update, page, when);
	}

	/** Same object, shown only while parameter {@code param} has one of {@code values} (and all earlier conditions hold). */
	public ObjDef showWhen(final String param, final int... values) {
		final var l = new ArrayList<>(when);
		l.add(Cond.of(param, values));
		return new ObjDef(number, name, text, functionText, sizeBits, dpt, read, write, transmit, update, group, l);
	}

	/** ETS object-size string, e.g. "1 Bit", "4 Bit", "1 Byte". */
	public String etsObjectSize() {
		return sizeBits < 8 ? sizeBits + " Bit" : (sizeBits / 8) + (sizeBits == 8 ? " Byte" : " Bytes");
	}
}
