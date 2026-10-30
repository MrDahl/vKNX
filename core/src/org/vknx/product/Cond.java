package org.vknx.product;

import java.util.List;

/**
 * A visibility condition: the parameter or object is only shown in ETS (and only matters to the simulator) while parameter
 * {@code param} has one of {@code values}. Several conditions on one item must all hold (ETS: nested choose/when).
 */
public record Cond(String param, List<Integer> values) {
	public Cond {
		values = List.copyOf(values);
	}

	public static Cond of(final String param, final int... values) {
		final var l = new java.util.ArrayList<Integer>();
		for (final int v : values)
			l.add(v);
		return new Cond(param, l);
	}
}
