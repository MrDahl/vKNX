package org.vknx.product;

/**
 * One ETS communication object (group object) of a product. {@code number} is the ETS object number (1-based).
 * {@code group} is the ETS parameter page the object is listed on ("" = "General").
 */
public record ObjDef(int number, String name, String text, String functionText, int sizeBits, String dpt,
		boolean read, boolean write, boolean transmit, boolean update, String group) {

	public ObjDef(final int number, final String name, final String text, final String functionText, final int sizeBits,
			final String dpt, final boolean read, final boolean write, final boolean transmit, final boolean update) {
		this(number, name, text, functionText, sizeBits, dpt, read, write, transmit, update, "");
	}

	public ObjDef inGroup(final String page) {
		return new ObjDef(number, name, text, functionText, sizeBits, dpt, read, write, transmit, update, page);
	}

	/** ETS object-size string, e.g. "1 Bit", "4 Bit", "1 Byte". */
	public String etsObjectSize() {
		return sizeBits < 8 ? sizeBits + " Bit" : (sizeBits / 8) + (sizeBits == 8 ? " Byte" : " Bytes");
	}
}
