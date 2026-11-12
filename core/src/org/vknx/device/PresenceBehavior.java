package org.vknx.device;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.vknx.product.Products;

/**
 * Presence detector. Objects: 1 light switch (output), 2 presence for heating / ventilation (output), 3 brightness in lux
 * (output), 4 lock (input). Movement comes from the card: a single movement ("motion") or a person who stays in the room
 * ("occupied", which keeps triggering). The light is switched on at movement only when the room is darker than the
 * threshold; once it is on, every movement restarts the follow-up time, whatever the brightness (a real detector also
 * measures its own light). The presence object ignores the brightness and has its own follow-up time.
 */
public final class PresenceBehavior extends DeviceBehavior {
	private static final int TICK_MS = 250;
	private boolean lightOn, presence, occupied, locked;
	private long lightUntil, presenceUntil; // System.nanoTime() deadlines
	private int lux = -1;
	private int motions;
	private ScheduledFuture<?> tick, luxTask;

	PresenceBehavior(final VirtualDevice dev) { super(dev); }

	private static long after(final int seconds) { return System.nanoTime() + (long) (seconds * 1e9 / timeScale); }

	private int followS() { return Products.PRESENCE_FOLLOW_S[Math.min(7, dev.param("FollowTime"))]; }

	private int hvacS() { return Products.PRESENCE_HVAC_S[Math.min(5, dev.param("HvacFollow"))]; }

	private boolean hvac() { return dev.param("HvacEnable") == 1; }

	public boolean lightOn() { return lightOn; }

	public boolean presence() { return presence; }

	@Override
	synchronized void onApplicationLoaded() {
		onStop();
		if (lux < 0)
			lux = dev.param("LuxStart");
		lightOn = false;
		presence = false;
		dev.log("presence", "application started: follow-up time " + followS() + " s, switches on below "
				+ (dev.param("Threshold") == 0 ? "any brightness" : dev.param("Threshold") + " lux") + ", room brightness " + lux + " lux"
				+ (hvac() ? ", presence object with " + hvacS() + " s" : ", presence object off"));
		tick = dev.every(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
		final int interval = dev.param("LuxInterval");
		if (interval > 0)
			luxTask = dev.every(() -> dev.transmit(3, Dpt9.encode(lux), true), 2, interval, TimeUnit.SECONDS);
		dev.changed();
	}

	@Override
	synchronized void onStop() {
		if (tick != null)
			tick.cancel(false);
		if (luxTask != null)
			luxTask.cancel(false);
	}

	@Override
	public synchronized void act(final String action, final int value) {
		switch (action) {
			case "lux" -> {
				lux = Math.max(0, Math.min(2000, value));
				dev.log("presence", "room brightness set to " + lux + " lux on the card");
				if (dev.applicationLoaded())
					dev.transmit(3, Dpt9.encode(lux), true);
			}
			case "occupied" -> {
				occupied = value != 0;
				if (!dev.applicationLoaded()) {
					dev.log("presence", "operated, but no application is loaded: nothing happens");
					break;
				}
				dev.log("presence", occupied ? "a person stays in the room: movement is detected all the time"
						: "the person left the room: the follow-up times run from now");
				if (occupied)
					motion(true);
			}
			case "motion" -> {
				if (!dev.applicationLoaded()) {
					dev.log("presence", "operated, but no application is loaded: nothing happens");
					break;
				}
				motion(true);
			}
			default -> super.act(action, value);
		}
		dev.changed();
	}

	/** A movement was seen. */
	private void motion(final boolean log) {
		motions++;
		if (hvac()) {
			presenceUntil = after(hvacS());
			if (!presence) {
				presence = true;
				dev.log("presence", "movement -> presence object sends 1 (occupied)");
				dev.transmit(2, 1);
			}
		}
		if (locked) {
			if (log)
				dev.log("presence", "movement, but the light channel is locked (lock object = 1): nothing is sent");
			return;
		}
		final int th = dev.param("Threshold");
		if (lightOn) {
			lightUntil = after(followS());
			if (log)
				dev.log("presence", "movement -> the follow-up time (" + followS() + " s) starts again");
		}
		else if (th == 0 || lux < th) {
			lightOn = true;
			lightUntil = after(followS());
			dev.log("presence", "movement, room brightness " + lux + " lux " + (th == 0 ? "(threshold off)" : "is below the threshold " + th + " lux")
					+ " -> sending ON, follow-up time " + followS() + " s");
			dev.transmit(1, 1);
		}
		else if (log)
			dev.log("presence", "movement, but the room is bright enough (" + lux + " lux, threshold " + th + " lux): the light stays off");
	}

	private synchronized void tick() {
		if (!dev.applicationLoaded())
			return;
		if (occupied)
			motion(false);
		final long now = System.nanoTime();
		boolean changed = false;
		if (lightOn && now - lightUntil >= 0) {
			lightOn = false;
			changed = true;
			if (dev.param("SendOff") == 1) {
				dev.log("presence", "no movement for " + followS() + " s -> sending OFF");
				dev.transmit(1, 0);
			}
			else
				dev.log("presence", "no movement for " + followS() + " s: parameter SendOff=sends nothing");
		}
		if (presence && (now - presenceUntil >= 0 || !hvac())) {
			presence = false;
			changed = true;
			dev.log("presence", "no movement for " + hvacS() + " s -> presence object sends 0 (not occupied)");
			dev.transmit(2, 0);
		}
		if (changed || lightOn || presence)
			dev.changed(); // the remaining time on the card
	}

	@Override
	synchronized void onObjectReceived(final int go, final int value) {
		if (go != 4)
			return;
		final boolean lock = (value & 1) != 0;
		if (lock == locked)
			return;
		locked = lock;
		if (!lock) {
			dev.log("presence", "lock object = 0: the light channel works again");
			return;
		}
		if (dev.param("LockReaction") == 1 && lightOn) {
			dev.log("presence", "lock object = 1, parameter LockReaction=sends OFF -> sending OFF, the light channel is locked");
			dev.transmit(1, 0);
		}
		else
			dev.log("presence", "lock object = 1: the light channel is locked" + (lightOn ? " (the light stays as it is)" : ""));
		lightOn = false;
	}

	@Override
	synchronized Integer valueOf(final int go) {
		return switch (go) {
			case 1 -> lightOn ? 1 : 0;
			case 2 -> hvac() ? (presence ? 1 : 0) : null;
			case 3 -> Dpt9.encode(Math.max(0, lux));
			default -> null;
		};
	}

	private static int left(final boolean active, final long until) {
		return active ? (int) Math.max(0, Math.ceil((until - System.nanoTime()) * timeScale / 1e9)) : 0;
	}

	@Override
	public synchronized Map<String, Object> view() {
		final var m = newView();
		m.put("light", lightOn);
		m.put("presence", presence);
		m.put("hvac", hvac());
		m.put("occupied", occupied);
		m.put("locked", locked);
		m.put("lux", lux < 0 ? dev.param("LuxStart") : lux);
		m.put("threshold", dev.param("Threshold"));
		m.put("left", left(lightOn, lightUntil));
		m.put("pleft", left(presence, presenceUntil));
		m.put("follow", followS());
		m.put("motions", motions);
		return m;
	}
}
