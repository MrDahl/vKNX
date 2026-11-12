package org.vknx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Do the checked-in {@code knxprod/*.knxprod} files (made by hand on Windows with ETS, tools/make-knxprod.ps1) still match
 * {@code products/*.xml} (generated from Products.java)? ETS only rewrites the ids (it puts a fingerprint in the application
 * id), so after that id is made neutral every object, parameter, type and memory element must be the same in both.
 * A release with old .knxprod files gives devices that ETS downloads with the wrong layout.
 */
public final class KnxprodCheck {
	private static final String[] ELEMENTS = { "ApplicationProgram", "ComObject", "ComObjectRef", "Parameter", "ParameterRef", "Enumeration",
		"TypeNumber", "TypeText", "TypeRestriction", "Memory", "Union", "AbsoluteSegment", "LdCtrlWriteProp", "LdCtrlWriteMem" };
	private static final Pattern APP_ID = Pattern.compile("(M-[0-9A-F]{4}_A-[0-9A-F]{4}-[0-9A-F]{2})-[0-9A-F]{4}");

	static List<String> elements(final String xml) {
		final String neutral = APP_ID.matcher(xml).replaceAll("$1-0000").replaceAll(" Hash=\"[^\"]*\"", "");
		final List<String> out = new ArrayList<>();
		for (final String e : ELEMENTS) {
			final var m = Pattern.compile("<" + e + "[ >][^>]*>").matcher(neutral);
			while (m.find())
				out.add(m.group().replaceAll("\\s+", " ").replaceAll(" ?/?>$", ""));
		}
		Collections.sort(out);
		return out;
	}

	/** @return null if the file matches, else what is wrong */
	static String compare(final Path productXml, final Path knxprod) throws Exception {
		if (!Files.exists(knxprod))
			return "missing (run tools/make-knxprod.ps1 on Windows with ETS and commit the file)";
		String app = null;
		try (var zip = new ZipFile(knxprod.toFile())) {
			for (final var en = zip.entries(); en.hasMoreElements();) {
				final var e = en.nextElement();
				if (e.getName().matches("M-[0-9A-F]{4}/M-[0-9A-F]{4}_A-.*\\.xml"))
					app = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		if (app == null)
			return "has no application program inside";
		final var want = elements(Files.readString(productXml));
		final var have = elements(app);
		if (want.equals(have))
			return null;
		final var onlyWant = new ArrayList<>(want);
		onlyWant.removeAll(have);
		final var onlyHave = new ArrayList<>(have);
		onlyHave.removeAll(want);
		final String ex = !onlyWant.isEmpty() ? "now: " + onlyWant.get(0) : "old: " + onlyHave.get(0);
		return "is older than Products.java (" + onlyWant.size() + " elements changed or new, " + onlyHave.size() + " gone; first " + cut(ex) + ")";
	}

	private static String cut(final String s) { return s.length() > 160 ? s.substring(0, 160) + "..." : s; }

	public static void main(final String[] args) throws Exception {
		final Path products = Path.of("products"), dir = Path.of("knxprod");
		int bad = 0, n = 0;
		try (var files = Files.newDirectoryStream(products, "vknx-*.xml")) {
			final List<Path> sorted = new ArrayList<>();
			files.forEach(sorted::add);
			Collections.sort(sorted);
			for (final Path xml : sorted) {
				n++;
				final String name = xml.getFileName().toString().replaceAll("\\.xml$", ".knxprod");
				final String problem = compare(xml, dir.resolve(name));
				System.out.println((problem == null ? "PASS  " : "FAIL  ") + name + (problem == null ? " matches Products.java" : " " + problem));
				if (problem != null)
					bad++;
			}
		}
		System.out.println(bad == 0 ? "all " + n + " .knxprod files match" : bad + " of " + n + " .knxprod files must be rebuilt: tools\\make-knxprod.ps1, then commit the knxprod folder");
		System.exit(bad == 0 ? 0 : 1);
	}
}
