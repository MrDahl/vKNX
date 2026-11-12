package org.vknx.device;

import java.util.ArrayList;
import java.util.List;

import io.calimero.GroupAddress;

/**
 * Parsed view of the three tables ETS downloads into a device: the group address table, the association table (which
 * group address belongs to which group object) and the configuration flags of the group objects.
 */
record Tables(int[] groupAddresses, int[][] associations, int[] goConfig) {
	static final Tables EMPTY = new Tables(new int[0], new int[0][], new int[0]);

	int indexOf(final GroupAddress ga) {
		for (int i = 0; i < groupAddresses.length; i++)
			if (groupAddresses[i] == ga.getRawAddress())
				return i + 1;
		return 0;
	}

	/** Object numbers associated with the 1-based address-table index, in association-table order. */
	List<Integer> objectsOf(final int gaIndex) {
		final var l = new ArrayList<Integer>();
		for (final int[] a : associations)
			if (a[0] == gaIndex)
				l.add(a[1]);
		return l;
	}

	/** Sending group address of an object: the first association entry of that object. */
	GroupAddress sendAddress(final int objectNumber) {
		for (final int[] a : associations)
			if (a[1] == objectNumber && a[0] >= 1 && a[0] <= groupAddresses.length)
				return new GroupAddress(groupAddresses[a[0] - 1]);
		return null;
	}

	int config(final int objectNumber) {
		return objectNumber >= 1 && objectNumber <= goConfig.length ? goConfig[objectNumber - 1] : 0;
	}
}
