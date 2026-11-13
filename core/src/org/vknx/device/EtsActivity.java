package org.vknx.device;

/**
 * The last thing ETS did to a device, in one line for the device card ("Partial download: 3 of 4 parts written"), and
 * the bookkeeping of a download session behind it: which of the four parts (address table, association table, group
 * object table, application) were started and finished, how many memory writes there were, whether it was a partial
 * download and whether something failed.
 */
final class EtsActivity {
	private static final int PARTS = 4;
	private final Runnable onAction;
	private volatile String last = "";
	private volatile String warning = "";
	private volatile long lastAt;
	private final java.util.Set<Integer> started = new java.util.TreeSet<>(), done = new java.util.TreeSet<>();
	private int writes;
	private boolean partial, errors;
	private long sessionAt;

	/** @param onAction called after every action (the device saves its state then) */
	EtsActivity(final Runnable onAction) { this.onAction = onAction; }

	/** One line about the most recent ETS action, or "" if ETS has done nothing yet. */
	String last() { return last; }

	long lastAt() { return lastAt; }

	/** True if the last action was a download that has not been followed by a restart yet. */
	boolean downloadJustEnded() {
		final String l = last;
		return (l.startsWith("Download") || l.startsWith("Partial download")) && !l.endsWith("device restarted");
	}

	/** A warning that stays on every line about the download until it is cleared with "" (for example "wrong application"). */
	synchronized void warn(final String text) {
		warning = text == null ? "" : text;
		if (!last.isEmpty())
			action(last);
	}

	synchronized void action(final String text) {
		last = warning.isEmpty() || text.contains(warning) ? text : text + " - " + warning;
		lastAt = System.currentTimeMillis();
		onAction.run();
	}

	/** A new download session starts when the previous activity is more than 20 s ago. */
	private void tick() {
		final long now = System.currentTimeMillis();
		if (now - sessionAt > 20_000) {
			started.clear();
			done.clear();
			writes = 0;
			partial = false;
			errors = false;
		}
		sessionAt = now;
	}

	/** ETS wrote into the device memory. */
	synchronized void memoryWritten() {
		tick();
		writes++;
		if (!started.isEmpty())
			summary();
	}

	/** ETS allocated the memory of a part; without the fill byte it is a partial download (only what changed is written). */
	synchronized void allocated(final boolean partialDownload) {
		tick();
		if (partialDownload)
			partial = true;
	}

	/**
	 * A load event for one part.
	 *
	 * @param unloadText the action text if the event was an unload (then no summary is written), else null
	 */
	synchronized void loadEvent(final int part, final boolean start, final boolean completed, final boolean error, final String unloadText) {
		tick();
		if (start)
			started.add(part);
		else if (completed)
			done.add(part);
		if (error)
			errors = true;
		if (unloadText != null)
			action(unloadText);
		else
			summary();
	}

	private void summary() {
		final String kind = partial ? "Partial download" : "Download";
		if (errors)
			action(kind + " failed (see the device log)");
		else if (started.size() > done.size())
			action(kind + " in progress: " + done.size() + " of " + PARTS + " parts finished");
		else
			action(kind + ": " + done.size() + " of " + PARTS + " parts written, " + writes + " memory write" + (writes == 1 ? "" : "s"));
	}
}
