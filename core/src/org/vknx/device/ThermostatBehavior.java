package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Room thermostat for heating. Everything but the keys on the control panel comes from the ETS download.
 * <ul>
 * <li>Room temperature: a simulated room (warms up while the control value is above 0, cools towards the outside
 * temperature), a fixed value, or the value of an external sensor object.</li>
 * <li>Operating mode (comfort, standby, economy, building protection) with a setpoint each; the mode can be changed with
 * the keys on the card or with the operating mode object (DPT 20.102); an open window (window contact object) switches to
 * building protection. The keys on the card also shift the setpoint in steps of 0.5 K (at most 3 K).</li>
 * <li>Control value: two-point with hysteresis (1 bit) or continuous PI (1 byte, 0-100 %).</li>
 * </ul>
 * The simulated room is a simple model: 4 K per hour at full heating, loss towards the outside temperature with a time
 * constant of 3 hours. It is only there to show the control loop; it is not a building simulation.
 */
public final class ThermostatBehavior extends DeviceBehavior {
	static final double HEAT_K_PER_HOUR = 4.0, ROOM_TAU_HOURS = 3.0, MAX_SHIFT = 3.0;
	private static final String[] MODE_NAMES = { "", "Comfort", "Standby", "Economy", "Building protection" };

	private double temp = Double.NaN;
	private int localMode = 1;
	private double shift;
	private boolean windowOpen, heatOn;
	private double ctrl, integral;
	private long lastTick;
	private double lastSentTemp = Double.NaN, lastSentSetpoint = Double.NaN;
	private int lastSentMode = -1;
	private double lastSentCtrl = -1;
	private long lastTempSend, lastCtrlSend;
	private boolean loud;
	private ScheduledFuture<?> task;

	ThermostatBehavior(final VirtualDevice dev) { super(dev); }

	private int source() { return dev.param("TempSource"); }

	private boolean pi() { return dev.param("ControlType") == 1; }

	private double speed() { return source() == 0 ? dev.param("RoomSpeed") : 1; }

	int effectiveMode() { return dev.param("WindowUsed") == 1 && windowOpen ? 4 : localMode; }

	private double baseSetpoint(final int mode) {
		return switch (mode) {
			case 1 -> dev.param("ComfortSetpoint") + 0.5 * dev.param("ComfortHalf");
			case 2 -> dev.param("StandbySetpoint") + 0.5 * dev.param("StandbyHalf");
			case 3 -> dev.param("EconomySetpoint") + 0.5 * dev.param("EconomyHalf");
			default -> dev.param("ProtectionSetpoint");
		};
	}

	double setpoint() {
		final int m = effectiveMode();
		return baseSetpoint(m) + (m == 4 ? 0 : shift);
	}

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		localMode = Math.max(1, Math.min(4, dev.param("StartMode")));
		shift = 0;
		windowOpen = false;
		heatOn = false;
		ctrl = 0;
		integral = 0;
		temp = switch (source()) {
			case 0 -> dev.param("StartTemp");
			case 1 -> dev.param("FixedTemp") + dev.param("FixedTenths") / 10.0;
			default -> Double.NaN;
		};
		lastSentTemp = Double.NaN;
		lastSentSetpoint = Double.NaN;
		lastSentMode = -1;
		lastSentCtrl = -1;
		lastTempSend = lastCtrlSend = 0;
		lastTick = System.nanoTime();
		loud = true;
		dev.log("thermostat", "started: room temperature " + (source() == 0 ? "simulated room, start " + temp + " °C, outside " + dev.param("Ambient")
				+ " °C, " + (int) speed() + "x" : source() == 1 ? "fixed " + temp + " °C" : "from the external sensor object (none received yet)")
				+ ", mode " + MODE_NAMES[localMode] + " (setpoint " + fmt(setpoint()) + " °C), "
				+ (pi() ? "PI controller, proportional band " + dev.param("PBand") + " K, integral time " + dev.param("IntTime") + " min"
						: "two-point controller, hysteresis " + dev.param("Hysteresis") / 10.0 + " K"));
		task = dev.every(this::tick, 1, 1, TimeUnit.SECONDS);
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		if (task != null)
			task.cancel(false);
	}

	private static String fmt(final double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

	/** One second of the room model and the controller; public work happens in {@link #step(double)} so a test can drive it. */
	private synchronized void tick() {
		final long now = System.nanoTime();
		final double dt = (now - lastTick) / 1e9;
		lastTick = now;
		step(dt);
	}

	/** Advances the model by {@code realSeconds} of real time (the room speed turns it into simulated time). */
	private void step(final double realSeconds) {
		final double simHours = realSeconds * speed() / 3600.0;
		if (source() == 0 && !Double.isNaN(temp))
			temp += (HEAT_K_PER_HOUR * ctrl / 100.0 - (temp - dev.param("Ambient")) / ROOM_TAU_HOURS) * simHours;
		control(simHours * 60);
		publish();
		dev.changed();
	}

	private void control(final double simMinutes) {
		if (Double.isNaN(temp)) {
			ctrl = 0;
			heatOn = false;
			return;
		}
		final double sp = setpoint();
		if (pi()) {
			final double kp = 100.0 / dev.param("PBand");
			final double e = sp - temp, tn = dev.param("IntTime");
			if (tn > 0)
				integral = Math.max(0, Math.min(100, integral + kp * e * simMinutes / tn));
			ctrl = Math.max(0, Math.min(100, kp * e + integral));
		}
		else {
			final double half = dev.param("Hysteresis") / 20.0;
			if (temp <= sp - half)
				heatOn = true;
			else if (temp >= sp + half)
				heatOn = false;
			ctrl = heatOn ? 100 : 0;
		}
	}

	/** Sends what changed (and what is due cyclically). */
	private void publish() {
		final long now = System.currentTimeMillis();
		final boolean quiet = !loud;
		loud = false;
		if (!Double.isNaN(temp)) {
			final int cycle = dev.param("TempCycle");
			final double rounded = Math.round(temp * 10) / 10.0;
			if (Double.isNaN(lastSentTemp) || Math.abs(rounded - lastSentTemp) >= 0.2 || (cycle > 0 && now - lastTempSend >= cycle * 1000L)) {
				lastSentTemp = rounded;
				lastTempSend = now;
				dev.transmit(1, Dpt9.encode(rounded), quiet);
			}
		}
		final int m = effectiveMode();
		if (m != lastSentMode) {
			if (lastSentMode != -1)
				dev.log("thermostat", "operating mode " + MODE_NAMES[m] + (m == 4 && localMode != 4 ? " (window open)" : ""));
			lastSentMode = m;
			dev.transmit(4, m, quiet);
		}
		final double sp = setpoint();
		if (Double.isNaN(lastSentSetpoint) || sp != lastSentSetpoint) {
			lastSentSetpoint = sp;
			dev.transmit(6, Dpt9.encode(sp), quiet);
		}
		if (Double.isNaN(temp))
			return;
		final int cycle = dev.param("ControlCycle");
		final boolean due = cycle > 0 && now - lastCtrlSend >= cycle * 1000L;
		if (pi()) {
			final double shown = Math.round(ctrl);
			if (lastSentCtrl < 0 || Math.abs(shown - lastSentCtrl) >= 5 || (shown != lastSentCtrl && (shown == 0 || shown == 100)) || due) {
				lastSentCtrl = shown;
				lastCtrlSend = now;
				dev.transmit(8, (int) Math.round(shown * 255.0 / 100.0), quiet);
				dev.log("thermostat", "control value " + (int) shown + " % sent (temperature " + fmt(temp) + " °C, setpoint " + fmt(sp) + " °C)");
			}
		}
		else {
			final double v = heatOn ? 1 : 0;
			if (lastSentCtrl < 0 || v != lastSentCtrl || due) {
				final boolean changed = v != lastSentCtrl;
				lastSentCtrl = v;
				lastCtrlSend = now;
				dev.transmit(7, (int) v, quiet);
				if (changed)
					dev.log("thermostat", "heating " + (heatOn ? "ON" : "OFF") + " sent (temperature " + fmt(temp) + " °C, setpoint " + fmt(sp) + " °C)");
			}
		}
	}

	@Override
	public synchronized void act(final String action, final int value) {
		if (!dev.applicationLoaded()) {
			dev.log("thermostat", "key pressed, but no application is loaded: nothing happens");
			return;
		}
		switch (action) {
			case "shift" -> {
				shift = Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, shift + (value >= 0 ? 0.5 : -0.5)));
				dev.log("thermostat", "setpoint shift " + (shift > 0 ? "+" : "") + fmt(shift) + " K -> setpoint " + fmt(setpoint()) + " °C");
			}
			case "mode" -> {
				if (value < 1 || value > 4)
					return;
				localMode = value;
				shift = 0;
				dev.log("thermostat", "key: operating mode " + MODE_NAMES[value] + ", setpoint " + fmt(setpoint()) + " °C");
			}
			default -> {
				super.act(action, value);
				return;
			}
		}
		control(0);
		publish();
		dev.changed();
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		switch (go) {
			case 2 -> {
				if (source() != 2) {
					dev.log("thermostat", "external temperature received but parameter TempSource is not 'External sensor': ignored");
					return;
				}
				temp = Dpt9.decode(value);
				dev.log("thermostat", "external temperature " + fmt(temp) + " °C received");
			}
			case 3 -> {
				if (value < 0 || value > 4) {
					dev.log("thermostat", "operating mode " + value + " is not defined (DPT 20.102: 0-4): ignored");
					return;
				}
				localMode = value == 0 ? 1 : value;
				shift = 0;
				dev.log("thermostat", "operating mode object: " + (value == 0 ? "Auto (treated as Comfort)" : MODE_NAMES[value]));
			}
			case 5 -> {
				if (dev.param("WindowUsed") != 1) {
					dev.log("thermostat", "window contact received but parameter WindowUsed=Not used: ignored");
					return;
				}
				windowOpen = (value & 1) != 0;
				dev.log("thermostat", "window " + (windowOpen ? "open -> building protection" : "closed -> back to " + MODE_NAMES[localMode]));
			}
			default -> { return; }
		}
		control(0);
		publish();
	}

	@Override
	synchronized Integer valueOf(final int go) {
		return switch (go) {
			case 1 -> Double.isNaN(temp) ? null : Dpt9.encode(Math.round(temp * 10) / 10.0);
			case 4 -> effectiveMode();
			case 6 -> Dpt9.encode(setpoint());
			case 7 -> heatOn ? 1 : 0;
			case 8 -> (int) Math.round(ctrl * 255.0 / 100.0);
			default -> null;
		};
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		m.put("temp", Double.isNaN(temp) ? null : Math.round(temp * 10) / 10.0);
		m.put("sp", Math.round(setpoint() * 10) / 10.0);
		m.put("mode", effectiveMode());
		m.put("local", localMode);
		m.put("shift", shift);
		m.put("win", windowOpen);
		m.put("winUsed", dev.param("WindowUsed") == 1);
		m.put("ctrl", (int) Math.round(ctrl));
		m.put("pi", pi());
		m.put("src", source());
		m.put("heat", ctrl > 0);
		return m;
	}
}
