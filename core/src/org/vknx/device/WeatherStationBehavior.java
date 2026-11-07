package org.vknx.device;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;
import org.vknx.product.Products.Channel;

/**
 * Weather station: outdoor temperature, wind speed and brightness (fixed value or random range with its own send interval,
 * exactly like the multisensor), a rain sensor and a wind alarm. Nothing can be changed on the control panel; every value
 * comes from the ETS download.
 * <ul>
 * <li>Rain: always dry, always raining, or random (a chance in percent at every send). Sent cyclically and when it
 * changes. 1 = rain.</li>
 * <li>Wind alarm: on when the wind speed reaches the threshold, off again below threshold minus hysteresis. Sent when it
 * changes and cyclically (0 s = only on change). 1 = alarm. A blind or awning actuator with this alarm on its wind alarm
 * object retracts the awnings.</li>
 * </ul>
 * Objects: 1 temperature, 2 wind speed, 3 brightness (DPT 9), 4 rain, 5 wind alarm.
 */
public final class WeatherStationBehavior extends DeviceBehavior {
	private static final List<Channel> CH = Products.WEATHER_CHANNELS;
	private static final int WIND = 1;
	private final int n = CH.size();
	private final double[] value = new double[n];
	private final int[] sent = new int[n];
	private final ScheduledFuture<?>[] task = new ScheduledFuture<?>[n];
	private boolean raining, windAlarm;
	private int rainSent, alarmSent;
	private ScheduledFuture<?> rainTask, alarmTask, startTask;

	WeatherStationBehavior(final VirtualDevice dev) {
		super(dev);
		for (int i = 0; i < n; i++)
			value[i] = CH.get(i).def() * CH.get(i).unitsPerStep() + Products.WEATHER_TENTHS_DEFAULT.getOrDefault(CH.get(i).name(), 0) / 10.0;
	}

	private String p(final int i, final String what) { return CH.get(i).name() + what; }

	private boolean enabled(final int i) { return dev.param(p(i, "Enable")) == 1; }

	private boolean rainEnabled() { return dev.param("RainEnable") == 1; }

	private boolean alarmEnabled() { return enabled(WIND) && dev.param("AlarmEnable") == 1; }

	private boolean hasTenths(final int i) { return Products.WEATHER_TENTHS_OFFSET.containsKey(CH.get(i).name()); }

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		raining = false;
		windAlarm = false;
		for (int i = 0; i < n; i++) {
			final int ch = i;
			final Channel c = CH.get(i);
			if (!enabled(i)) {
				dev.log("weather", c.page() + ": disabled (parameter " + p(i, "Enable") + "=No)");
				continue;
			}
			final boolean random = dev.param(p(i, "Mode")) == 1;
			final int interval = dev.param(p(i, "Interval"));
			next(i);
			dev.log("weather", c.page() + ": " + (random ? "random between " + dev.param(p(i, "Min")) + " and " + dev.param(p(i, "Max"))
					: "fixed " + dev.param(p(i, "Value"))) + " (parameter units), "
					+ (interval > 0 ? "sent every " + interval + " s" : "cyclic sending off (interval 0)"));
			if (interval > 0)
				task[i] = dev.every(() -> send(ch), 2, interval, TimeUnit.SECONDS);
		}
		if (rainEnabled()) {
			rain();
			final int interval = dev.param("RainInterval");
			dev.log("weather", "Rain: " + switch (dev.param("RainMode")) { case 0 -> "always dry"; case 1 -> "always raining";
					default -> "random, chance " + dev.param("RainChance") + " %"; } + ", " + (interval > 0 ? "sent every " + interval + " s" : "cyclic sending off"));
			if (interval > 0)
				rainTask = dev.every(this::rainTick, 2, interval, TimeUnit.SECONDS);
		}
		else
			dev.log("weather", "Rain: disabled (parameter RainEnable=No)");
		if (alarmEnabled()) {
			evaluateAlarm();
			final int interval = dev.param("AlarmInterval");
			dev.log("weather", "Wind alarm: threshold " + dev.param("AlarmThreshold") + " m/s, ends below " + (dev.param("AlarmThreshold") - dev.param("AlarmHysteresis"))
					+ " m/s, " + (interval > 0 ? "sent every " + interval + " s and on change" : "sent on change only"));
			if (interval > 0)
				alarmTask = dev.every(this::sendAlarm, 3, interval, TimeUnit.SECONDS);
		}
		else
			dev.log("weather", "Wind alarm: disabled (parameter AlarmEnable=No or wind speed disabled)");
		startTask = dev.later(this::announce, 1, TimeUnit.SECONDS); // the states are known to the bus from the start
		dev.changed();
	}

	/** Sends the two states once after the start (like a real station after bus voltage return). */
	private synchronized void announce() {
		if (rainEnabled())
			sendRain();
		if (alarmEnabled())
			sendAlarm();
	}

	/** Computes the next value of channel i (fixed or random) from the ETS parameters, in engineering units. */
	private void next(final int i) {
		final Channel c = CH.get(i);
		if (dev.param(p(i, "Mode")) == 1) {
			int lo = dev.param(p(i, "Min")), hi = dev.param(p(i, "Max"));
			if (lo > hi) {
				final int t = lo;
				lo = hi;
				hi = t;
			}
			if (hasTenths(i)) {
				final int a = lo * 10, b = hi * 10;
				value[i] = (a == b ? a : ThreadLocalRandom.current().nextInt(a, b + 1)) / 10.0;
			}
			else
				value[i] = (lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1)) * c.unitsPerStep();
			return;
		}
		final int raw = dev.param(p(i, "Value"));
		if (hasTenths(i)) {
			final int tenths = Math.max(0, Math.min(9, dev.param(p(i, "Tenths"))));
			value[i] = raw < 0 ? raw - tenths / 10.0 : raw + tenths / 10.0;
		}
		else
			value[i] = raw * c.unitsPerStep();
	}

	private double onBus(final int i) {
		return hasTenths(i) ? Math.round(value[i] * 10) / 10.0 : Math.round(value[i] / CH.get(i).unitsPerStep()) * CH.get(i).toDpt();
	}

	private synchronized void send(final int i) {
		next(i);
		sent[i]++;
		dev.transmit(i + 1, Dpt9.encode(onBus(i)), true);
		dev.log("weather", CH.get(i).page() + " " + fmt(i) + " sent (#" + sent[i] + ")");
		if (i == WIND && alarmEnabled() && evaluateAlarm())
			sendAlarm();
		dev.changed();
	}

	private String fmt(final int i) {
		final Channel c = CH.get(i);
		return String.format(java.util.Locale.ROOT, hasTenths(i) ? "%.1f %s" : "%.0f %s", value[i], c.unit());
	}

	/** Wind alarm with hysteresis. Returns true if the alarm state changed. */
	private boolean evaluateAlarm() {
		final int threshold = dev.param("AlarmThreshold"), hyst = dev.param("AlarmHysteresis");
		final double w = value[WIND];
		final boolean before = windAlarm;
		if (!windAlarm && w >= threshold)
			windAlarm = true;
		else if (windAlarm && w < threshold - hyst)
			windAlarm = false;
		if (windAlarm != before)
			dev.log("weather", "wind " + fmt(WIND) + (windAlarm ? " reaches the threshold " + threshold + " m/s -> WIND ALARM ON"
					: " is below " + (threshold - hyst) + " m/s -> wind alarm off"));
		return windAlarm != before;
	}

	private synchronized void sendAlarm() {
		alarmSent++;
		dev.transmit(Products.OBJ_WIND_ALARM, windAlarm ? 1 : 0, true);
		dev.log("weather", "wind alarm " + (windAlarm ? "ON" : "off") + " sent (#" + alarmSent + ")");
		dev.changed();
	}

	/** Decides the rain state: fixed, or random with the chance of the parameter. */
	private void rain() {
		raining = switch (dev.param("RainMode")) {
			case 0 -> false;
			case 1 -> true;
			default -> ThreadLocalRandom.current().nextInt(100) < dev.param("RainChance");
		};
	}

	private synchronized void rainTick() {
		final boolean before = raining;
		rain();
		if (raining != before)
			dev.log("weather", raining ? "it starts to rain" : "the rain stops");
		sendRain();
	}

	private void sendRain() {
		rainSent++;
		dev.transmit(Products.OBJ_RAIN, raining ? 1 : 0, true);
		dev.log("weather", "rain " + (raining ? "RAIN" : "dry") + " sent (#" + rainSent + ")");
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		for (int i = 0; i < n; i++)
			if (task[i] != null)
				task[i].cancel(false);
		for (final var t : new ScheduledFuture<?>[] { rainTask, alarmTask, startTask })
			if (t != null)
				t.cancel(false);
	}

	@Override
	void onObjectReceived(final int go, final int v) { /* a weather station has no inputs */ }

	@Override
	synchronized Integer valueOf(final int go) {
		if (go >= 1 && go <= n)
			return enabled(go - 1) ? Dpt9.encode(onBus(go - 1)) : null;
		if (go == Products.OBJ_RAIN)
			return rainEnabled() ? (raining ? 1 : 0) : null;
		if (go == Products.OBJ_WIND_ALARM)
			return alarmEnabled() ? (windAlarm ? 1 : 0) : null;
		return null;
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		for (int i = 0; i < n; i++) {
			m.put("e" + i, enabled(i));
			m.put("s" + i, Math.round(value[i] * 10) / 10.0);
			m.put("n" + i, sent[i]);
			m.put("r" + i, dev.param(p(i, "Mode")) == 1);
		}
		m.put("rainE", rainEnabled());
		m.put("rain", raining);
		m.put("alarmE", alarmEnabled());
		m.put("alarm", windAlarm);
		m.put("threshold", dev.param("AlarmThreshold"));
		return m;
	}
}
