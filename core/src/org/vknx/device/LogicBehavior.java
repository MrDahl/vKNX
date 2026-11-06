package org.vknx.device;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Logic module and time master. The 1 bit inputs and outputs are group objects that ETS shows once they are enabled; what
 * happens between them is the circuit drawn in the logic editor ({@link LogicEngine}), which is kept in the device
 * memory (so it survives an ETS download and a restart of vKNX). The time master runs on a simulated clock (the PC clock
 * until the user sets another time or a faster speed) and sends time and date and drives eight weekday timers.
 */
public final class LogicBehavior extends DeviceBehavior implements LogicEngine.Host {
	private static final int IN = Products.LOGIC_INPUTS, OUT = Products.LOGIC_OUTPUTS, TIMERS = Products.TIMERS;
	private static final ZoneId ZONE = ZoneId.systemDefault();

	private final LogicEngine engine = new LogicEngine(this);
	private final boolean[] timerOut = new boolean[TIMERS + 1];
	private volatile boolean loading;
	private ScheduledFuture<?> tickTask, cyclicTask;

	// simulated clock: sim = baseSim + (real - baseReal) * speed
	private LocalDateTime baseSim = LocalDateTime.now(ZONE);
	private long baseReal = System.currentTimeMillis();
	private double speed = 1;
	private boolean pcClock = true;
	private LocalDateTime lastMinute;
	private int sentTime;

	LogicBehavior(final VirtualDevice dev) { super(dev); }

	// ------------------------------------------------------------------ names and enabled flags

	private boolean inEnabled(final int i) { return dev.param("In" + i + "Enable") == 1; }

	private boolean outEnabled(final int i) { return dev.param("Out" + i + "Enable") == 1; }

	private String inName(final int i) { return dev.paramText("In" + i + "Name"); }

	private String outName(final int i) { return dev.paramText("Out" + i + "Name"); }

	private boolean timerEnabled(final int t) { return dev.param("Tm" + t + "Enable") == 1; }

	// ------------------------------------------------------------------ lifecycle

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		final String saved = new String(dev.readUserData(), StandardCharsets.UTF_8);
		loading = true;
		final String err = engine.load(saved);
		loading = false;
		if (err != null)
			dev.log("logic", "the saved circuit could not be loaded: " + err);
		int ins = 0, outs = 0, tms = 0;
		for (int i = 1; i <= IN; i++)
			if (inEnabled(i))
				ins++;
		for (int i = 1; i <= OUT; i++)
			if (outEnabled(i))
				outs++;
		for (int t = 1; t <= TIMERS; t++)
			if (timerEnabled(t))
				tms++;
		dev.log("logic", ins + " input(s) and " + outs + " output(s) enabled, circuit with " + engine.nodeCount() + " element(s), "
				+ tms + " timer(s)" + (ins + outs == 0 ? " (enable inputs and outputs in the ETS parameters)" : ""));
		java.util.Arrays.fill(timerOut, false);
		lastMinute = now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
		tickTask = dev.every(this::tick, 1, 1, TimeUnit.SECONDS);
		final int mode = dev.param("TimeObjects");
		if (mode != 0) {
			final int interval = dev.param("TimeInterval");
			if (dev.param("TimeAtStart") == 1)
				dev.later(this::sendTime, 3, TimeUnit.SECONDS);
			if (interval > 0)
				cyclicTask = dev.every(this::sendTime, 60L * interval, 60L * interval, TimeUnit.SECONDS);
		}
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		if (tickTask != null)
			tickTask.cancel(false);
		if (cyclicTask != null)
			cyclicTask.cancel(false);
		engine.stop();
	}

	// ------------------------------------------------------------------ logic

	@Override
	public ScheduledFuture<?> later(final Runnable r, final long ms) { return dev.later(r, ms, TimeUnit.MILLISECONDS); }

	@Override
	public void output(final int index, final boolean value) {
		if (loading)
			return;
		if (!outEnabled(index)) {
			dev.log("logic", "output " + index + " changed to " + (value ? 1 : 0) + " but it is not enabled in the ETS parameters");
			return;
		}
		dev.log("logic", "output " + index + " (" + outName(index) + ") = " + (value ? 1 : 0));
		dev.transmit(Products.logicOutputObject(index), value ? 1 : 0);
		dev.changed();
	}

	@Override
	void onObjectReceived(final int go, final int value) {
		if (go >= 1 && go <= IN) {
			if (!inEnabled(go))
				return;
			final boolean v = (value & 1) != 0;
			dev.log("logic", "input " + go + " (" + inName(go) + ") = " + (v ? 1 : 0));
			engine.setInput(go, v);
			dev.changed();
		}
	}

	/**
	 * Replaces the circuit (from the editor). Returns null when it was accepted and saved, otherwise the reason.
	 * Inputs and outputs that are not enabled in ETS stay in the circuit; they just do nothing until they are enabled.
	 */
	public String setCircuit(final String text) {
		final byte[] b = text.getBytes(StandardCharsets.UTF_8);
		if (b.length > 24_000)
			return "The circuit is too large to store (" + b.length + " bytes).";
		loading = true;
		final String err;
		try {
			err = engine.load(text);
		}
		finally {
			loading = false;
		}
		if (err != null)
			return err;
		dev.writeUserData(b);
		dev.log("logic", "circuit saved: " + engine.nodeCount() + " element(s)");
		dev.changed();
		return null;
	}

	public String circuit() { return engine.text(); }

	public Map<String, Boolean> live() { return engine.live(); }

	// ------------------------------------------------------------------ clock

	private synchronized LocalDateTime now() {
		final long dt = System.currentTimeMillis() - baseReal;
		return baseSim.plusNanos((long) (dt * speed * 1_000_000));
	}

	private synchronized void rebase(final LocalDateTime sim) {
		baseSim = sim;
		baseReal = System.currentTimeMillis();
		lastMinute = sim.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
	}

	/** Test hook: the clock stands at {@code t} and does not run until {@link #tick()} is called by the test. */
	public synchronized void setClockForTest(final LocalDateTime t) {
		speed = 0;
		pcClock = false;
		rebase(t);
	}

	/** Once per second: runs the timers for every simulated minute that has passed since the last call. */
	public synchronized void tick() {
		final LocalDateTime cur = now().truncatedTo(ChronoUnit.MINUTES);
		int guard = 0;
		while (lastMinute.isBefore(cur) && guard++ < 1500) {
			lastMinute = lastMinute.plusMinutes(1);
			runTimers(lastMinute);
		}
		if (lastMinute.isBefore(cur))
			lastMinute = cur;
		dev.changed();
	}

	private void runTimers(final LocalDateTime t) {
		final int day = t.getDayOfWeek().getValue() - 1;
		for (int i = 1; i <= TIMERS; i++) {
			if (!timerEnabled(i) || dev.param("Tm" + i + "Day" + day) != 1)
				continue;
			final boolean on = dev.param("Tm" + i + "OnEnable") == 1 && dev.param("Tm" + i + "OnHour") == t.getHour()
					&& dev.param("Tm" + i + "OnMinute") == t.getMinute();
			final boolean off = dev.param("Tm" + i + "OffEnable") == 1 && dev.param("Tm" + i + "OffHour") == t.getHour()
					&& dev.param("Tm" + i + "OffMinute") == t.getMinute();
			if (!on && !off)
				continue;
			timerOut[i] = on; // both in the same minute: on wins
			dev.log("timer", "timer " + i + " (" + dev.paramText("Tm" + i + "Name") + ") " + (on ? "on" : "off") + " at "
					+ String.format("%02d:%02d", t.getHour(), t.getMinute()));
			dev.transmit(Products.timerObject(i), on ? 1 : 0);
		}
	}

	/** Sends the time and/or date objects as set in the ETS parameters. */
	void sendTime() {
		final int mode = dev.param("TimeObjects");
		if (mode == 0)
			return;
		final LocalDateTime t = now();
		final int dow = t.getDayOfWeek().getValue(); // 1 = Monday ... 7 = Sunday, as in DPT 10.001 and 19.001
		sentTime++;
		if (mode == 1 || mode == 3)
			dev.transmitBytes(Products.OBJ_TIME, new byte[] { (byte) (dow << 5 | t.getHour()), (byte) t.getMinute(), (byte) t.getSecond() },
					String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond()), false);
		if (mode == 2 || mode == 3)
			dev.transmitBytes(Products.OBJ_DATE, new byte[] { (byte) t.getDayOfMonth(), (byte) t.getMonthValue(), (byte) (t.getYear() % 100) },
					t.toLocalDate().toString(), false);
		if (mode == 4) {
			final boolean dst = ZONE.getRules().isDaylightSavings(t.atZone(ZONE).toInstant());
			final int working = dow <= 5 ? 1 : 0;
			dev.transmitBytes(Products.OBJ_DATETIME, new byte[] { (byte) (t.getYear() - 1900), (byte) t.getMonthValue(), (byte) t.getDayOfMonth(),
				(byte) (dow << 5 | t.getHour()), (byte) t.getMinute(), (byte) t.getSecond(), (byte) (working << 6 | (dst ? 1 : 0)), (byte) 0x80 },
					t.toLocalDate() + " " + String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond()), false);
		}
		dev.changed();
	}

	@Override
	public void act(final String action, final int value) {
		switch (action) {
			case "setTime" -> {
				if (value < 0 || value > 1439)
					return;
				rebase(now().toLocalDate().atTime(value / 60, value % 60));
				pcClock = false;
			}
			case "setDay" -> {
				if (value < 0 || value > 6)
					return;
				LocalDateTime t = now();
				while (t.getDayOfWeek() != DayOfWeek.of(value + 1))
					t = t.plusDays(1);
				rebase(t);
				pcClock = false;
			}
			case "speed" -> {
				if (value < 1 || value > 3600)
					return;
				rebase(now());
				speed = value;
				pcClock = false;
			}
			case "pcClock" -> {
				rebase(LocalDateTime.now(ZONE));
				speed = 1;
				pcClock = true;
			}
			case "sendTime" -> sendTime();
			default -> {
				super.act(action, value);
				return;
			}
		}
		dev.changed();
	}

	// ------------------------------------------------------------------ reads and view

	@Override
	Integer valueOf(final int go) {
		if (go > IN && go <= IN + OUT && outEnabled(go - IN))
			return engine.output(go - IN) ? 1 : 0;
		if (go >= Products.timerObject(1) && go <= Products.timerObject(TIMERS) && timerEnabled(go - 35))
			return timerOut[go - 35] ? 1 : 0;
		return null;
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		for (int i = 1; i <= IN; i++) {
			final boolean e = inEnabled(i);
			m.put("ie" + i, e);
			m.put("in" + i, e ? inName(i) : "");
			m.put("iv" + i, e && engine.input(i));
		}
		for (int i = 1; i <= OUT; i++) {
			final boolean e = outEnabled(i);
			m.put("oe" + i, e);
			m.put("on" + i, e ? outName(i) : "");
			m.put("ov" + i, e && engine.output(i));
		}
		final LocalDateTime t = now();
		m.put("date", t.toLocalDate().toString());
		m.put("time", String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond()));
		m.put("dow", t.getDayOfWeek().getValue() - 1);
		m.put("speed", (int) speed);
		m.put("pcClock", pcClock);
		m.put("timeMode", dev.param("TimeObjects"));
		m.put("sentTime", sentTime);
		for (int i = 1; i <= TIMERS; i++) {
			final boolean e = timerEnabled(i);
			m.put("te" + i, e);
			m.put("tn" + i, e ? dev.paramText("Tm" + i + "Name") : "");
			m.put("tv" + i, e && timerOut[i]);
		}
		m.put("elements", engine.nodeCount());
		m.put("osc", engine.oscillating());
		return m;
	}
}
