package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Heating actuator with six channels for thermal valve drives. Every channel can be disabled and named in ETS.
 * <ul>
 * <li>Control value on/off (1 bit): the valve is open while the value is 1.</li>
 * <li>Control value 0-100 % (1 byte): pulse width modulation. In every cycle (PWM cycle time, parameter) the valve is open
 * for the percentage of the cycle and closed for the rest.</li>
 * <li>Valve type: a normally closed drive is energised to open, a normally open drive is energised to close. The valve
 * status object reports whether the valve is open, whatever the drive type.</li>
 * <li>Monitoring: if no control value arrives within the timeout the channel goes to the emergency position (a fixed
 * percentage) and the alarm object is sent; the next control value ends it.</li>
 * </ul>
 * Object numbers of channel c (1..6): 3(c-1)+1 control value (1 bit), +2 control value (1 byte), +3 valve status; 19 alarm.
 * A thermal drive needs a few minutes to open or close; that delay is not simulated.
 */
public final class HeatingActuatorBehavior extends DeviceBehavior {
	private static final int N = Products.HA_CHANNELS;
	private final double[] pct = new double[N];
	private final boolean[] open = new boolean[N], emergency = new boolean[N];
	private final double[] lastRx = new double[N], cycleStart = new double[N];
	private boolean fault;
	private ScheduledFuture<?> task;
	private final long t0 = System.nanoTime();

	HeatingActuatorBehavior(final VirtualDevice dev) { super(dev); }

	private static String pre(final int c) { return "Ch" + (c + 1); }

	private boolean enabled(final int c) { return dev.param(pre(c) + "Enable") == 1; }

	private boolean pwm(final int c) { return dev.param(pre(c) + "Type") == 1; }

	private String who(final int c) { return "valve " + (c + 1) + " (" + dev.paramText(pre(c) + "Name") + ")"; }

	/** Simulated seconds since the device was created. */
	private double now() { return (System.nanoTime() - t0) / 1e9 * timeScale; }

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		final double now = now();
		fault = false;
		for (int c = 0; c < N; c++) {
			pct[c] = 0;
			open[c] = false;
			emergency[c] = false;
			lastRx[c] = now;
			cycleStart[c] = now;
			if (!enabled(c))
				continue;
			final int mon = dev.param(pre(c) + "Monitor");
			dev.log("heating", who(c) + ": " + (pwm(c) ? "control value 0-100 %, PWM cycle " + dev.param(pre(c) + "Cycle") + " min" : "control value on/off")
					+ ", " + (dev.param(pre(c) + "ValveType") == 0 ? "normally closed" : "normally open") + ", monitoring "
					+ (mon == 0 ? "off" : mon + " min, emergency position " + dev.param(pre(c) + "Emergency") + " %"));
			dev.transmit(3 * c + 3, 0, true);
		}
		dev.transmit(Products.HA_FAULT_OBJECT, 0, true);
		// once a second; faster when the tests run the clock faster, so that the PWM edges are not missed
		task = dev.every(this::tick, 20, Math.max(20, (long) (1000 / timeScale)), TimeUnit.MILLISECONDS);
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		if (task != null)
			task.cancel(false);
	}

	private synchronized void tick() {
		evaluate();
		dev.changed();
	}

	/** Checks the monitoring and moves the valves (PWM phase). Called every second and after every control value. */
	private void evaluate() {
		final double now = now();
		boolean anyEmergency = false;
		for (int c = 0; c < N; c++) {
			if (!enabled(c))
				continue;
			final int mon = dev.param(pre(c) + "Monitor");
			if (mon > 0 && !emergency[c] && now - lastRx[c] > mon * 60.0) {
				emergency[c] = true;
				pct[c] = dev.param(pre(c) + "Emergency");
				dev.log("heating", who(c) + ": no control value for " + mon + " minutes (parameter Monitor) -> emergency position " + (int) pct[c] + " %");
			}
			anyEmergency |= emergency[c];
			final boolean o;
			if (!pwm(c))
				o = pct[c] > 0;
			else {
				final double cycle = Math.max(1, dev.param(pre(c) + "Cycle")) * 60.0;
				final double phase = (now - cycleStart[c]) % cycle;
				o = phase < cycle * pct[c] / 100.0;
			}
			if (o != open[c]) {
				open[c] = o;
				dev.log("heating", who(c) + " " + (o ? "opens" : "closes") + (pwm(c) ? " (PWM, control value " + (int) Math.round(pct[c]) + " %)" : ""));
				dev.transmit(3 * c + 3, o ? 1 : 0, true);
			}
		}
		if (anyEmergency != fault) {
			fault = anyEmergency;
			dev.log("heating", "alarm \"control value missing\" " + (fault ? "ON" : "OFF"));
			dev.transmit(Products.HA_FAULT_OBJECT, fault ? 1 : 0, true);
		}
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		if (go < 1 || go > 3 * N)
			return;
		final int c = (go - 1) / 3, kind = (go - 1) % 3;
		if (kind == 2)
			return;
		if (!enabled(c)) {
			dev.log("heating", "valve " + (c + 1) + ": telegram ignored, parameter " + pre(c) + "Enable=No");
			return;
		}
		pct[c] = kind == 0 ? ((value & 1) != 0 ? 100 : 0) : Math.min(100, value * 100.0 / 255.0);
		lastRx[c] = now();
		dev.log("heating", who(c) + " control value " + (kind == 0 ? ((value & 1) != 0 ? "ON" : "OFF") : (int) Math.round(pct[c]) + " %") + " received");
		if (emergency[c]) {
			emergency[c] = false;
			dev.log("heating", who(c) + ": control value received again, emergency position ended");
		}
		evaluate();
	}

	@Override
	synchronized Integer valueOf(final int go) {
		if (go == Products.HA_FAULT_OBJECT)
			return fault ? 1 : 0;
		return go >= 1 && go <= 3 * N && (go - 1) % 3 == 2 ? (open[(go - 1) / 3] ? 1 : 0) : null;
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		for (int c = 0; c < N; c++) {
			final int n = c + 1;
			m.put("e" + n, enabled(c));
			m.put("n" + n, dev.paramText(pre(c) + "Name"));
			m.put("pct" + n, (int) Math.round(pct[c]));
			m.put("open" + n, open[c]);
			m.put("energised" + n, open[c] == (dev.param(pre(c) + "ValveType") == 0)); // NC: energised to open, NO: energised to close
			m.put("pwm" + n, pwm(c));
			m.put("nc" + n, dev.param(pre(c) + "ValveType") == 0);
			m.put("em" + n, emergency[c]);
			m.put("mon" + n, dev.param(pre(c) + "Monitor"));
		}
		m.put("fault", fault);
		return m;
	}
}
