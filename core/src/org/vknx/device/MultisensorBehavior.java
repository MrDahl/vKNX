package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Multisensor: six simulated environment values (set from the control panel) sent cyclically, plus a random value
 * between two ETS-configured limits sent at its own ETS-configured interval. All objects are DPT 9 (2-byte float).
 */
public final class MultisensorBehavior extends DeviceBehavior {
	/** name, unit, default, min, max for the UI sliders (values are sent as DPT 9.xxx). */
	public static final String[][] SENSORS = {
		{ "CO2", "ppm", "600", "400", "5000" }, { "Humidity", "%", "45", "0", "100" },
		{ "Illuminance", "lux", "300", "0", "100000" }, { "Temperature", "°C", "21", "-30", "60" },
		{ "Pressure", "Pa", "101325", "30000", "110000" }, { "WindSpeed", "m/s", "3", "0", "50" } };
	private final double[] value = new double[6];
	private volatile int lastRandom;
	private volatile int cyclicSends;
	private ScheduledFuture<?> cyclic;
	private ScheduledFuture<?> random;

	MultisensorBehavior(final VirtualDevice dev) {
		super(dev);
		for (int i = 0; i < 6; i++)
			value[i] = Double.parseDouble(SENSORS[i][2]);
	}

	@Override
	public void act(final String action, final int v) {
		// "s0".."s5": new simulated value, scaled x10 so that one decimal can be set from the UI
		if (action.length() == 2 && action.charAt(0) == 's') {
			final int i = action.charAt(1) - '0';
			if (i >= 0 && i < 6) {
				value[i] = v / 10.0;
				dev.log("sensor", SENSORS[i][0] + " set to " + value[i] + " " + SENSORS[i][1] + " (sent at the next cycle)");
				dev.changed();
			}
		}
		else
			super.act(action, v);
	}

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		final int cycle = dev.param("CycleTime");
		final int rInt = dev.param("RandomInterval");
		if (cycle > 0) {
			dev.log("sensor", "cyclic sending every " + cycle + " s (parameter CycleTime)");
			cyclic = dev.every(this::sendCycle, 2, cycle, TimeUnit.SECONDS);
		}
		else
			dev.log("sensor", "cyclic sending off (parameter CycleTime=0)");
		if (rInt > 0) {
			dev.log("sensor", "random value every " + rInt + " s between " + dev.param("RandomMin") + " and "
					+ dev.param("RandomMax"));
			random = dev.every(this::sendRandom, 2, rInt, TimeUnit.SECONDS);
		}
		else
			dev.log("sensor", "random value off (parameter RandomInterval=0)");
	}

	@Override
	synchronized void onStop() {
		if (cyclic != null)
			cyclic.cancel(false);
		if (random != null)
			random.cancel(false);
	}

	private void sendCycle() {
		cyclicSends++;
		for (int i = 0; i < 6; i++)
			dev.transmit(i + 1, Dpt9.encode(value[i]), true);
		dev.log("sensor", "cycle " + cyclicSends + ": values sent to the associated group addresses (objects without address are skipped)");
		dev.changed();
	}

	private void sendRandom() {
		int lo = dev.param("RandomMin"), hi = dev.param("RandomMax");
		if (lo > hi) {
			final int t = lo;
			lo = hi;
			hi = t;
		}
		lastRandom = lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
		dev.transmit(7, Dpt9.encode(lastRandom), true);
		dev.log("sensor", "random value " + lastRandom + " (between " + lo + " and " + hi + ")");
		dev.changed();
	}

	@Override
	void onObjectReceived(final int go, final int v) { /* sensors have no inputs */ }

	@Override
	Integer valueOf(final int go) {
		return go >= 1 && go <= 6 ? Dpt9.encode(value[go - 1]) : go == 7 ? Dpt9.encode(lastRandom) : null;
	}

	@Override
	public Map<String, Object> view() {
		final var m = newView();
		for (int i = 0; i < 6; i++)
			m.put("s" + i, value[i]);
		m.put("random", lastRandom);
		m.put("cycles", cyclicSends);
		return m;
	}
}
