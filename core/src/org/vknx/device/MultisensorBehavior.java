package org.vknx.device;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;
import org.vknx.product.Products.Channel;

/**
 * Multisensor: six measurements (CO2, humidity, illuminance, temperature, air pressure, wind speed). Everything comes from
 * the ETS download: every channel has its own group object, its own send interval and either a fixed value or a random
 * range. The control panel only shows the values; it cannot change them. All objects are DPT 9 (2-byte float).
 */
public final class MultisensorBehavior extends DeviceBehavior {
	private static final List<Channel> CH = Products.CHANNELS;
	private final int n = CH.size();
	private final double[] value = new double[n];
	private final int[] sent = new int[n];
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[n];

	MultisensorBehavior(final VirtualDevice dev) {
		super(dev);
		for (int i = 0; i < n; i++)
			value[i] = CH.get(i).def() * CH.get(i).unitsPerStep();
	}

	private String p(final int i, final String what) { return CH.get(i).name() + what; }

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		for (int i = 0; i < n; i++) {
			final int ch = i;
			final Channel c = CH.get(i);
			final boolean random = dev.param(p(i, "Mode")) == 1;
			final int interval = dev.param(p(i, "Interval"));
			next(i); // set the starting value
			dev.log("sensor", c.name() + ": " + (random ? "random between " + dev.param(p(i, "Min")) + " and " + dev.param(p(i, "Max"))
					: "fixed " + dev.param(p(i, "Value"))) + " (parameter units), "
					+ (interval > 0 ? "sent every " + interval + " s" : "cyclic sending off (interval 0)"));
			if (interval > 0)
				task[i] = dev.every(() -> send(ch), 2, interval, TimeUnit.SECONDS);
		}
		dev.changed();
	}

	/** Computes the next value of channel i (fixed or random) from the ETS parameters, in engineering units. */
	private void next(final int i) {
		final Channel c = CH.get(i);
		int raw;
		if (dev.param(p(i, "Mode")) == 1) {
			int lo = dev.param(p(i, "Min")), hi = dev.param(p(i, "Max"));
			if (lo > hi) {
				final int t = lo;
				lo = hi;
				hi = t;
			}
			raw = lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
		}
		else
			raw = dev.param(p(i, "Value"));
		value[i] = raw * c.unitsPerStep();
	}

	@Override
	synchronized void onStop() {
		for (int i = 0; i < n; i++)
			if (task[i] != null)
				task[i].cancel(false);
	}

	private void send(final int i) {
		final Channel c = CH.get(i);
		next(i);
		sent[i]++;
		dev.transmit(i + 1, Dpt9.encode(Math.round(value[i] / c.unitsPerStep()) * c.toDpt()), true);
		dev.log("sensor", c.name() + " " + fmt(i) + " sent (#" + sent[i] + ")");
		dev.changed();
	}

	private String fmt(final int i) {
		final Channel c = CH.get(i);
		return String.format(java.util.Locale.ROOT, c.unitsPerStep() < 1 ? "%.1f %s" : "%.0f %s", value[i], c.unit());
	}

	@Override
	void onObjectReceived(final int go, final int v) { /* sensors have no inputs */ }

	@Override
	Integer valueOf(final int go) {
		if (go < 1 || go > n)
			return null;
		final Channel c = CH.get(go - 1);
		return Dpt9.encode(Math.round(value[go - 1] / c.unitsPerStep()) * c.toDpt());
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int i = 0; i < n; i++) {
			m.put("s" + i, Math.round(value[i] * 10) / 10.0);
			m.put("n" + i, sent[i]);
			final int interval = dev.param(p(i, "Interval"));
			m.put("i" + i, interval);
			m.put("r" + i, dev.param(p(i, "Mode")) == 1);
		}
		return m;
	}
}
