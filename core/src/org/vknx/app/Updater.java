package org.vknx.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * "Check for updates": asks GitHub for the latest published release of vKNX, compares versions and, on request,
 * downloads the Windows installer and starts it. Nothing is ever downloaded or installed without the user's click.
 */
public final class Updater {
	public static final String API = "https://api.github.com/repos/MrDahl/vKNX/releases/latest";

	/** Result of a check. {@code asset} is the installer download URL ("" if the release has none). */
	public record Info(String current, String latest, boolean newer, String notes, String page, String asset) {}

	public static volatile String state = "idle"; // idle | checking | downloading | installing | error
	public static volatile long bytes, total;
	public static volatile String error = "";

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10)).build();

	private Updater() {}

	public static Info check() throws IOException, InterruptedException {
		final var req = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json").header("User-Agent", "vKNX-updater").build();
		final var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200)
			throw new IOException("GitHub answered HTTP " + resp.statusCode());
		return parse(resp.body(), Version.current());
	}

	/** Parses the GitHub "latest release" JSON (only the few fields we need, no JSON library). */
	public static Info parse(final String json, final String current) {
		final String tag = first(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
		final String body = unescape(first(json, "\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""));
		final String page = first(json, "\"html_url\"\\s*:\\s*\"([^\"]+)\"");
		final String asset = first(json, "\"browser_download_url\"\\s*:\\s*\"([^\"]*Setup[^\"]*\\.exe)\"");
		return new Info(current, tag, !tag.isEmpty() && Version.newer(tag, current), body, page, asset);
	}

	private static String first(final String s, final String re) {
		final var m = Pattern.compile(re).matcher(s);
		return m.find() ? m.group(1) : "";
	}

	private static String unescape(final String s) {
		final var sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				final char n = s.charAt(++i);
				switch (n) {
					case 'n' -> sb.append('\n');
					case 'r' -> { }
					case 't' -> sb.append('\t');
					case 'u' -> {
						sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
						i += 4;
					}
					default -> sb.append(n);
				}
			}
			else
				sb.append(c);
		}
		return sb.toString();
	}

	/** Downloads the installer and starts it silently; the installer closes the running vKNX and starts the new one. */
	public static void downloadAndInstall(final String assetUrl, final Runnable beforeExit) {
		if (!assetUrl.startsWith("https://github.com/MrDahl/vKNX/releases/download/"))
			throw new IllegalArgumentException("refusing to download from " + assetUrl);
		new Thread(() -> {
			try {
				state = "downloading";
				bytes = 0;
				total = 0;
				error = "";
				final Path tmp = Files.createTempDirectory("vknx-update").resolve("vKNX-Setup.exe");
				final var resp = HTTP.send(HttpRequest.newBuilder(URI.create(assetUrl)).header("User-Agent", "vKNX-updater").build(),
						HttpResponse.BodyHandlers.ofInputStream());
				if (resp.statusCode() != 200)
					throw new IOException("download failed, HTTP " + resp.statusCode());
				total = resp.headers().firstValueAsLong("content-length").orElse(0);
				try (InputStream in = resp.body(); var out = Files.newOutputStream(tmp)) {
					final byte[] buf = new byte[64 * 1024];
					int n;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
						bytes += n;
					}
				}
				state = "installing";
				new ProcessBuilder(tmp.toString(), "/SILENT", "/CLOSEAPPLICATIONS", "/NORESTART", "/SUPPRESSMSGBOXES").start();
				Thread.sleep(800);
				beforeExit.run();
			}
			catch (final Exception e) {
				state = "error";
				error = String.valueOf(e.getMessage());
			}
		}, "vknx-updater").start();
	}
}
