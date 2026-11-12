package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Energy meter with a simulated consumption. Objects: 1 active power (W, 4 byte float), 2 energy (Wh), 3 energy (kWh),
 * 4 current (mA, 2 byte float), 5 reset of the energy counter (input), 6 power limit exceeded (alarm), 7..10 the states
 * of the four monitored loads (inputs). Power = base load + every monitored load whose input is on + the extra load set
 * on the card, with a random fluctuation. The current is power / 230 V. The energy counter runs while the application
 * runs; it is not kept when vKNX is restarted.
 */
public final class EnergyMeterBehavior extends DeviceBehavior {
	private static final int N = Products.METER_LOADS, FIRST = Products.METER_LOAD_OBJ;
	private static final double VOLT = 230;
	private final boolean[] loadOn = new boolean[N];
	private int extra; // W, from the card
	private double power, sentPower = -1, energyWh;
	private boolean alarm;
	private long lastTick;
	private ScheduledFuture<?> tick, powerTask, energyTask;

	EnergyMeterBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int l) { return "Load" + (l + 1); }

	private boolean enabled(final int l) { return dev.param(pre(l) + "Enable") == 1; }

	/** Power right now in watt (tests). */
	public synchronized double power() { return power; }

	/** Energy counted so far in Wh (tests). */
	public synchronized double energyWh() { return energyWh; }

	public synchronized boolean alarm() { return alarm; }

	/** The power without the random fluctuation. */
	private double nominal() {
		double p = dev.param("BaseLoad") + extra;
		for (int l = 0; l < N; l++)
			if (enabled(l) && loadOn[l])
				p += dev.param(pre(l) + "Power");
		return p;
	}

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		lastTick = System.nanoTime();
		sentPower = -1;
		alarm = false;
		power = nominal();
		final var loads = new StringBuilder();
		for (int l = 0; l < N; l++)
			if (enabled(l))
				loads.append(loads.length() == 0 ? "" : ", ").append(dev.paramText(pre(l) + "Name")).append(' ').append(dev.param(pre(l) + "Power")).append(" W");
		dev.log("meter", "application started: base load " + dev.param("BaseLoad") + " W, monitored loads: " + (loads.length() == 0 ? "none" : loads)
				+ (dev.param("Limit") > 0 ? ", alarm above " + dev.param("Limit") + " W" : ", no power limit"));
		tick = dev.every(this::tick, 1, 1, TimeUnit.SECONDS);
		final int pi = dev.param("PowerInterval"), ei = dev.param("EnergyInterval");
		if (pi > 0)
			powerTask = dev.every(() -> sendPower("cyclic"), 2, pi, TimeUnit.SECONDS);
		if (ei > 0)
			energyTask = dev.every(this::sendEnergy, 3, ei, TimeUnit.SECONDS);
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		for (final var t : new ScheduledFuture<?>[] { tick, powerTask, energyTask })
			if (t != null)
				t.cancel(false);
	}

	private synchronized void tick() {
		if (!dev.applicationLoaded())
			return;
		final long now = System.nanoTime();
		final double hours = (now - lastTick) / 3.6e12 * timeScale;
		lastTick = now;
		energyWh += power * hours; // the power of the last second
		final int fl = dev.param("Fluctuation");
		final double nominal = nominal();
		power = fl == 0 || nominal == 0 ? nominal : Math.max(0, nominal * (1 + ThreadLocalRandom.current().nextDouble(-fl, fl) / 100.0));
		final int onChange = dev.param("SendOnChange");
		if (onChange > 0 && (sentPower < 0 || Math.abs(power - sentPower) > Math.max(1, sentPower * onChange / 100.0)))
			sendPower("changed by more than " + onChange + " %");
		final int limit = dev.param("Limit");
		final boolean over = limit > 0 && power > limit;
		if (over != alarm) {
			alarm = over;
			dev.log("meter", over ? "power " + Math.round(power) + " W is above the limit " + limit + " W -> sending alarm 1"
					: "power " + Math.round(power) + " W is below the limit again -> sending alarm 0");
			dev.transmit(6, over ? 1 : 0);
		}
		dev.changed();
	}

	private static int milliAmp(final double watt) { return Dpt9.encode(Math.round(watt / VOLT * 1000)); }

	private synchronized void sendPower(final String why) {
		sentPower = power;
		final boolean quiet = why.equals("cyclic");
		final float watt = (float) (Math.round(power * 10) / 10.0);
		if (dev.transmitBytes(1, java.nio.ByteBuffer.allocate(4).putFloat(watt).array(), watt + " W", quiet) && !quiet)
			dev.log("meter", "power " + Math.round(power) + " W sent (" + why + ")");
		dev.transmit(4, milliAmp(power), true);
	}

	private synchronized void sendEnergy() {
		dev.transmit(2, (int) Math.round(energyWh), true);
		dev.transmit(3, (int) Math.round(energyWh / 1000), true);
	}

	@Override
	public synchronized void act(final String action, final int value) {
		switch (action) {
			case "extra" -> {
				extra = Math.max(0, Math.min(10000, value));
				dev.log("meter", "extra load set to " + extra + " W on the card");
			}
			case "reset" -> reset("on the card");
			default -> super.act(action, value);
		}
		dev.changed();
	}

	private void reset(final String how) {
		dev.log("meter", "energy counter reset " + how + " (was " + Math.round(energyWh) + " Wh)");
		energyWh = 0;
		if (dev.applicationLoaded())
			sendEnergy();
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		if (go == 5) {
			if ((value & 1) != 0)
				reset("by telegram");
			return;
		}
		final int l = go - FIRST;
		if (l < 0 || l >= N)
			return;
		if (!enabled(l)) {
			dev.log("meter", "load " + (l + 1) + ": telegram ignored, parameter " + pre(l) + "Enable=No");
			return;
		}
		final boolean on = (value & 1) != 0;
		if (on == loadOn[l])
			return;
		loadOn[l] = on;
		dev.log("meter", "load " + (l + 1) + " (" + dev.paramText(pre(l) + "Name") + ") is " + (on ? "ON: +" : "OFF: -") + dev.param(pre(l) + "Power")
				+ " W -> " + Math.round(nominal()) + " W in total");
	}

	@Override
	synchronized Integer valueOf(final int go) {
		return switch (go) {
			case 1 -> Float.floatToIntBits((float) (Math.round(power * 10) / 10.0));
			case 2 -> (int) Math.round(energyWh);
			case 3 -> (int) Math.round(energyWh / 1000);
			case 4 -> milliAmp(power);
			case 6 -> alarm ? 1 : 0;
			default -> null;
		};
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		m.put("power", Math.round(power));
		m.put("wh", Math.round(energyWh * 10) / 10.0);
		m.put("amp", Math.round(power / VOLT * 100) / 100.0);
		m.put("extra", extra);
		m.put("base", dev.param("BaseLoad"));
		m.put("limit", dev.param("Limit"));
		m.put("alarm", alarm);
		for (int l = 0; l < N; l++) {
			final int n = l + 1;
			m.put("e" + n, enabled(l));
			m.put("n" + n, dev.paramText(pre(l) + "Name"));
			m.put("p" + n, dev.param(pre(l) + "Power"));
			m.put("on" + n, loadOn[l]);
		}
		return m;
	}
}
