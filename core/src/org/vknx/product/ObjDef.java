package org.vknx.product;

/** One ETS communication object (group object) of a product. {@code number} is the ETS object number (1-based). */
public record ObjDef(int number, String name, String textDa, String textEn, String functionTextEn, int sizeBits,
		String dpt, boolean read, boolean write, boolean transmit, boolean update) {

	/** ETS object-size string, e.g. "1 Bit", "4 Bit", "1 Byte". */
	public String etsObjectSize() {
		return sizeBits < 8 ? sizeBits + " Bit" : (sizeBits / 8) + (sizeBits == 8 ? " Byte" : " Bytes");
	}
}
