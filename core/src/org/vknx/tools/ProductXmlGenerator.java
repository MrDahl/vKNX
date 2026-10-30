package org.vknx.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.vknx.product.ObjDef;
import org.vknx.product.ParamDef;
import org.vknx.product.ProductDef;
import org.vknx.product.Products;

/**
 * Writes the ETS product-database XML (input for the ETS converter that creates the .knxprod) from the same
 * {@link ProductDef} the simulator runs on. Layout follows the System B (MV-07B0) structure used by the OpenKNX
 * projects (OpenKNXproducer/TemplateApplication.xml, commit 7f23a63): one relative segment for the parameter
 * area (load state machine 4), merged load procedures 2/4/7, 16-bit group address / association tables.
 *
 * <p>Verified in ETS 6.4.1 for the single-page products; the multi-page multisensor (choose/when) is new in 0.3.0.
 */
public final class ProductXmlGenerator {
	private ProductXmlGenerator() {}

	public static void main(final String[] args) throws IOException {
		final Path out = Path.of(args.length > 0 ? args[0] : "products");
		Files.createDirectories(out);
		for (final ProductDef p : Products.ALL.values()) {
			final Path f = out.resolve("vknx-" + p.key() + ".xml");
			Files.writeString(f, generate(p), StandardCharsets.UTF_8);
			System.out.println("wrote " + f);
		}
	}

	static String hex(final int v, final int digits) { return String.format(Locale.ROOT, "%0" + digits + "X", v); }

	/** ETS ID rule (as implemented in OpenKNXproducer Program.GetEncoded): special characters become ".XX" (hex). */
	static String enc(final String in) {
		final var sb = new StringBuilder();
		for (final char c : in.toCharArray())
			if (".% !\"#$&()+-/:;<>=?@[\\]^_{|}".indexOf(c) >= 0) sb.append('.').append(String.format("%02X", (int) c));
			else sb.append(c);
		return sb.toString();
	}

	public static String generate(final ProductDef p) {
		if (p.coupler())
			return generateCoupler(p);
		final String mfr = "M-" + hex(Products.MANUFACTURER_ID, 4);
		final String aid = mfr + "_A-" + hex(p.appNumber(), 4) + "-" + hex(p.appVersion(), 2) + "-0000";
		final String hwId = mfr + "_H-" + enc(String.valueOf(p.hardwareSerial())) + "-1";
		final String prodId = hwId + "_P-" + enc(p.orderNumber());
		final String h2p = hwId + "_HP-" + hex(p.appNumber(), 4) + "-" + hex(p.appVersion(), 2) + "-0000";
		final String ciId = h2p + "_CI-" + enc(p.orderNumber()) + "-1";
		final String seg = aid + "_RS-04-00000";

		final var x = new StringBuilder();
		x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
		x.append("<KNX xmlns=\"http://knx.org/xml/project/20\">\n");
		x.append("  <ManufacturerData>\n    <Manufacturer RefId=\"").append(mfr).append("\">\n");

		x.append("      <Catalog>\n        <CatalogSection Id=\"").append(mfr)
				.append("_CS-vKNX\" Name=\"vKNX\" Number=\"vKNX\" DefaultLanguage=\"en-US\">\n");
		x.append("          <CatalogItem Id=\"").append(ciId).append("\" Name=\"").append(esc(p.name()))
				.append("\" Number=\"1\" ProductRefId=\"").append(prodId).append("\" Hardware2ProgramRefId=\"")
				.append(h2p).append("\" DefaultLanguage=\"en-US\" />\n");
		x.append("        </CatalogSection>\n      </Catalog>\n");

		x.append("      <ApplicationPrograms>\n");
		x.append("        <ApplicationProgram Id=\"").append(aid).append("\" ApplicationNumber=\"").append(p.appNumber())
				.append("\" ApplicationVersion=\"").append(p.appVersion())
				.append("\" ProgramType=\"ApplicationProgram\" MaskVersion=\"MV-07B0\" Name=\"").append(esc(p.name()))
				.append("\" LoadProcedureStyle=\"MergedProcedure\" PeiType=\"0\" DefaultLanguage=\"en-US\" ")
				.append("DynamicTableManagement=\"false\" Linkable=\"true\" MinEtsVersion=\"5.0\" ReplacesVersions=\"")
				.append(replaces(p.appVersion())).append("\">\n");
		x.append("          <Static>\n");
		x.append("            <Code>\n              <RelativeSegment Id=\"").append(seg)
				.append("\" Name=\"Parameters\" Offset=\"0\" Size=\"").append(p.paramAreaSize())
				.append("\" LoadStateMachine=\"4\" />\n            </Code>\n");

		x.append("            <ParameterTypes>\n");
		for (final ParamDef pr : p.params()) {
			x.append("              <ParameterType Id=\"").append(aid).append("_PT-").append(pr.name()).append("\" Name=\"")
					.append(pr.name()).append("\">\n");
			if (pr.isText()) {
				x.append("                <TypeText SizeInBit=\"").append(pr.bits()).append("\" />\n              </ParameterType>\n");
				continue;
			}
			if (pr.choices().isEmpty()) {
				x.append("                <TypeNumber SizeInBit=\"").append(pr.bits()).append("\" Type=\"").append(pr.min() < 0 ? "signedInt" : "unsignedInt").append("\" minInclusive=\"")
						.append(pr.min()).append("\" maxInclusive=\"").append(pr.max()).append("\" />\n              </ParameterType>\n");
				continue;
			}
			x.append("                <TypeRestriction Base=\"Value\" SizeInBit=\"").append(pr.bits()).append("\">\n");
			for (final var c : pr.choices())
				x.append("                  <Enumeration Text=\"").append(esc(c.text())).append("\" Value=\"")
						.append(c.value()).append("\" Id=\"").append(aid).append("_PT-").append(pr.name()).append("_EN-")
						.append(c.value()).append("\" />\n");
			x.append("                </TypeRestriction>\n              </ParameterType>\n");
		}
		x.append("            </ParameterTypes>\n");

		x.append("            <Parameters>\n");
		for (final ParamDef pr : p.params())
			x.append("              <Parameter Id=\"").append(aid).append("_P-").append(pr.id()).append("\" Name=\"")
					.append(pr.name()).append("\" ParameterType=\"").append(aid).append("_PT-").append(pr.name())
					.append("\" Text=\"").append(esc(pr.text())).append("\" Value=\"")
					.append(pr.isText() ? esc(pr.defaultText()) : String.valueOf(pr.defaultValue()))
					.append("\">\n                <Memory CodeSegment=\"").append(seg).append("\" Offset=\"")
					.append(pr.byteOffset()).append("\" BitOffset=\"").append(pr.bitOffset())
					.append("\" />\n              </Parameter>\n");
		x.append("            </Parameters>\n");

		x.append("            <ParameterRefs>\n");
		for (final ParamDef pr : p.params())
			x.append("              <ParameterRef Id=\"").append(aid).append("_P-").append(pr.id()).append("_R-")
					.append(pr.id()).append("\" RefId=\"").append(aid).append("_P-").append(pr.id()).append("\" />\n");
		x.append("            </ParameterRefs>\n");

		if (!p.objects().isEmpty()) {
		x.append("            <ComObjectTable>\n");
		for (final ObjDef o : p.objects())
			x.append("              <ComObject Id=\"").append(aid).append("_O-").append(o.number()).append("\" Name=\"")
					.append(o.name()).append("\" Text=\"").append(esc(o.text())).append("\" Number=\"")
					.append(o.number()).append("\" FunctionText=\"").append(o.functionText()).append("\" ObjectSize=\"")
					.append(o.etsObjectSize()).append("\" ReadFlag=\"").append(flag(o.read())).append("\" WriteFlag=\"")
					.append(flag(o.write())).append("\" CommunicationFlag=\"Enabled\" TransmitFlag=\"")
					.append(flag(o.transmit())).append("\" UpdateFlag=\"").append(flag(o.update()))
					.append("\" ReadOnInitFlag=\"Disabled\" DatapointType=\"").append(o.dpt()).append("\" />\n");
		x.append("            </ComObjectTable>\n");
		x.append("            <ComObjectRefs>\n");
		for (final ObjDef o : p.objects())
			x.append("              <ComObjectRef Id=\"").append(aid).append("_O-").append(o.number()).append("_R-")
					.append(o.number()).append("\" RefId=\"").append(aid).append("_O-").append(o.number()).append("\" />\n");
		x.append("            </ComObjectRefs>\n");
		}

		x.append("            <AddressTable MaxEntries=\"255\" />\n");
		x.append("            <AssociationTable MaxEntries=\"255\" />\n");
		x.append("            <LoadProcedures>\n");
		x.append("              <LoadProcedure MergeId=\"2\">\n");
		x.append("                <LdCtrlRelSegment LsmIdx=\"4\" Size=\"").append(p.paramAreaSize())
				.append("\" Mode=\"1\" Fill=\"0\" AppliesTo=\"full\" />\n");
		x.append("                <LdCtrlRelSegment LsmIdx=\"4\" Size=\"").append(p.paramAreaSize())
				.append("\" Mode=\"0\" Fill=\"0\" AppliesTo=\"par\" />\n");
		x.append("              </LoadProcedure>\n");
		x.append("              <LoadProcedure MergeId=\"4\">\n");
		x.append("                <LdCtrlWriteRelMem ObjIdx=\"4\" Offset=\"0\" Size=\"").append(p.paramAreaSize())
				.append("\" Verify=\"true\" AppliesTo=\"full,par\" />\n");
		x.append("              </LoadProcedure>\n");
		x.append("              <LoadProcedure MergeId=\"7\">\n");
		for (int i = 1; i <= 4; i++)
			x.append("                <LdCtrlLoadImageProp ObjIdx=\"").append(i).append("\" PropId=\"27\" />\n");
		x.append("              </LoadProcedure>\n");
		x.append("            </LoadProcedures>\n");
		x.append("            <Options />\n");
		x.append("          </Static>\n");

		x.append("          <Dynamic>\n            <ChannelIndependentBlock>\n");
		// one ETS parameter page per group, in order of first appearance; "" = "General"
		final var pages = new java.util.LinkedHashSet<String>();
		for (final ParamDef pr : p.params())
			pages.add(pr.group());
		for (final ObjDef o : p.objects())
			pages.add(o.group());
		int pb = 0;
		for (final String page : pages) {
			pb++;
			final String title = page.isEmpty() ? "General" : page;
			x.append("              <ParameterBlock Id=\"").append(aid).append("_PB-").append(pb).append("\" Name=\"")
					.append(title.replace(" ", "")).append("\" Text=\"").append(esc(title)).append("\">\n");
			for (final ParamDef pr : p.params()) {
				if (!pr.group().equals(page))
					continue;
				guarded(x, p, aid, pr.when(), "<ParameterRefRef RefId=\"" + aid + "_P-" + pr.id() + "_R-" + pr.id() + "\" />");
			}
			for (final ObjDef o : p.objects())
				if (o.group().equals(page))
					guarded(x, p, aid, o.when(), "<ComObjectRefRef RefId=\"" + aid + "_O-" + o.number() + "_R-" + o.number() + "\" />");
			x.append("              </ParameterBlock>\n");
		}
		x.append("            </ChannelIndependentBlock>\n          </Dynamic>\n");
		x.append("        </ApplicationProgram>\n      </ApplicationPrograms>\n");

		x.append("      <Hardware>\n        <Hardware Id=\"").append(hwId).append("\" Name=\"").append(esc(p.name()))
				.append("\" SerialNumber=\"").append(p.hardwareSerial())
				.append("\" VersionNumber=\"1\" BusCurrent=\"10\" HasIndividualAddress=\"true\" HasApplicationProgram=\"true\"")
				.append(p.coupler() ? " IsCoupler=\"true\"" : "").append(">\n");
		x.append("          <Products>\n            <Product Id=\"").append(prodId).append("\" Text=\"")
				.append(esc(p.name())).append("\" OrderNumber=\"").append(p.orderNumber())
				.append("\" IsRailMounted=\"false\" DefaultLanguage=\"en-US\">\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" />\n            </Product>\n          </Products>\n");
		x.append("          <Hardware2Programs>\n            <Hardware2Program Id=\"").append(h2p)
				.append("\" MediumTypes=\"MT-0\">\n              <ApplicationProgramRef RefId=\"").append(aid).append("\" />\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" RegistrationNumber=\"0001/11\" />\n");
		x.append("            </Hardware2Program>\n          </Hardware2Programs>\n");
		x.append("        </Hardware>\n      </Hardware>\n");
		x.append("    </Manufacturer>\n  </ManufacturerData>\n</KNX>\n");
		return x.toString();
	}

	/**
	 * The coupler is a TP/TP coupler for ETS (mask MV-0912, as in the KNX Virtual "TP Coupler" application): no code
	 * segment, no tables, the routing settings are router object properties 54 (main to sub) and 55 (sub to main).
	 * ETS creates the filter table itself from the group addresses used on each line.
	 */
	static String generateCoupler(final ProductDef p) {
		final String mfr = "M-" + hex(Products.MANUFACTURER_ID, 4);
		final String aid = mfr + "_A-" + hex(p.appNumber(), 4) + "-" + hex(p.appVersion(), 2) + "-0000";
		final String hwId = mfr + "_H-" + enc(String.valueOf(p.hardwareSerial())) + "-1";
		final String prodId = hwId + "_P-" + enc(p.orderNumber());
		final String h2p = hwId + "_HP-" + hex(p.appNumber(), 4) + "-" + hex(p.appVersion(), 2) + "-0000";
		final String ciId = h2p + "_CI-" + enc(p.orderNumber()) + "-1";
		final String pt = aid + "_PT-Routing";
		final String[][] prm = { // id, name, text, property, bit offset (ETS counts from the most significant bit)
			{ "1", "MainSubHigh", "Main groups 14-31, main to sub", "54", "4" },
			{ "2", "MainSub", "Main to sub", "54", "6" },
			{ "3", "SubMainHigh", "Main groups 14-31, sub to main", "55", "4" },
			{ "4", "SubMain", "Sub to main", "55", "6" } };

		final var x = new StringBuilder();
		x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
		x.append("<KNX xmlns=\"http://knx.org/xml/project/23\">\n");
		x.append("  <ManufacturerData>\n    <Manufacturer RefId=\"").append(mfr).append("\">\n");
		x.append("      <Catalog>\n        <CatalogSection Id=\"").append(mfr)
				.append("_CS-vKNX\" Name=\"vKNX\" Number=\"vKNX\" DefaultLanguage=\"en-US\">\n");
		x.append("          <CatalogItem Id=\"").append(ciId).append("\" Name=\"").append(esc(p.name()))
				.append("\" Number=\"1\" ProductRefId=\"").append(prodId).append("\" Hardware2ProgramRefId=\"")
				.append(h2p).append("\" DefaultLanguage=\"en-US\" />\n");
		x.append("        </CatalogSection>\n      </Catalog>\n");

		x.append("      <ApplicationPrograms>\n        <ApplicationProgram Id=\"").append(aid)
				.append("\" ApplicationNumber=\"").append(p.appNumber()).append("\" ApplicationVersion=\"")
				.append(p.appVersion()).append("\" ProgramType=\"ApplicationProgram\" MaskVersion=\"MV-0912\" Name=\"")
				.append(esc(p.name())).append("\" LoadProcedureStyle=\"MergedProcedure\" PeiType=\"0\" ")
				.append("DefaultLanguage=\"en-US\" DynamicTableManagement=\"false\" Linkable=\"false\" MinEtsVersion=\"6.0\">\n");
		x.append("          <Static>\n            <ParameterTypes>\n              <ParameterType Id=\"").append(pt)
				.append("\" Name=\"Routing\">\n                <TypeRestriction Base=\"Value\" SizeInBit=\"2\">\n");
		final String[] en = { "", "Route all", "Block", "Filter (use filter table)" };
		for (int v = 1; v <= 3; v++)
			x.append("                  <Enumeration Text=\"").append(en[v]).append("\" Value=\"").append(v).append("\" Id=\"")
					.append(pt).append("_EN-").append(v).append("\" />\n");
		x.append("                </TypeRestriction>\n              </ParameterType>\n            </ParameterTypes>\n");
		x.append("            <Parameters>\n");
		for (final String[] r : prm)
			x.append("              <Parameter Id=\"").append(aid).append("_P-").append(r[0]).append("\" Name=\"")
					.append(r[1]).append("\" ParameterType=\"").append(pt).append("\" Text=\"").append(r[2])
					.append("\" Value=\"3\">\n                <Property ObjectType=\"6\" PropertyId=\"").append(r[3])
					.append("\" Offset=\"0\" BitOffset=\"").append(r[4]).append("\" />\n              </Parameter>\n");
		x.append("            </Parameters>\n            <ParameterRefs>\n");
		for (final String[] r : prm)
			x.append("              <ParameterRef Id=\"").append(aid).append("_P-").append(r[0]).append("_R-").append(r[0])
					.append("\" RefId=\"").append(aid).append("_P-").append(r[0]).append("\"")
					.append(r[0].equals("2") ? " Text=\"Main line to sub line: \"" : r[0].equals("4") ? " Text=\"Sub line to main line: \"" : "")
					.append(" />\n");
		x.append("            </ParameterRefs>\n");
		x.append("            <Options LineCoupler0912NewProgrammingStyle=\"true\" MaxRoutingApduLength=\"66\" Comparable=\"true\" Reconstructable=\"true\" />\n");
		x.append("          </Static>\n          <Dynamic>\n            <ChannelIndependentBlock>\n");
		final String ref2 = aid + "_P-2_R-2", ref4 = aid + "_P-4_R-4";
		x.append("              <ParameterBlock Id=\"").append(aid).append("_PB-1\" Name=\"GroupTelegramRouting\" Text=\"Group telegram routing\">\n");
		x.append("                <ParameterRefRef RefId=\"").append(ref2).append("\" />\n");
		x.append("                <ParameterRefRef RefId=\"").append(ref4).append("\" />\n");
		for (final String[] pair : new String[][] { { ref2, aid + "_P-1_R-1" }, { ref4, aid + "_P-3_R-3" } }) {
			x.append("                <choose ParamRefId=\"").append(pair[0]).append("\">\n");
			for (int v = 1; v <= 3; v++)
				x.append("                  <when test=\"").append(v).append("\">\n                    <Assign TargetParamRefRef=\"")
						.append(pair[1]).append("\" Value=\"").append(v).append("\" />\n                  </when>\n");
			x.append("                </choose>\n");
		}
		x.append("              </ParameterBlock>\n            </ChannelIndependentBlock>\n          </Dynamic>\n");
		x.append("        </ApplicationProgram>\n      </ApplicationPrograms>\n");

		x.append("      <Hardware>\n        <Hardware Id=\"").append(hwId).append("\" Name=\"").append(esc(p.name()))
				.append("\" SerialNumber=\"").append(p.hardwareSerial()).append("\" VersionNumber=\"1\" BusCurrent=\"10\" ")
				.append("Tp256=\"true\" HasIndividualAddress=\"true\" HasApplicationProgram=\"true\" IsCoupler=\"true\">\n");
		x.append("          <Products>\n            <Product Id=\"").append(prodId).append("\" Text=\"").append(esc(p.name()))
				.append("\" OrderNumber=\"").append(p.orderNumber()).append("\" IsRailMounted=\"false\" DefaultLanguage=\"en-US\">\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" />\n            </Product>\n          </Products>\n");
		x.append("          <Hardware2Programs>\n            <Hardware2Program Id=\"").append(h2p)
				.append("\" MediumTypes=\"MT-0\">\n              <ApplicationProgramRef RefId=\"")
				.append(aid).append("\" />\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" RegistrationNumber=\"0001/11\" />\n");
		x.append("            </Hardware2Program>\n          </Hardware2Programs>\n");
		x.append("        </Hardware>\n      </Hardware>\n");
		x.append("    </Manufacturer>\n  </ManufacturerData>\n</KNX>\n");
		return x.toString();
	}

	/**
	 * Writes {@code element} inside nested choose/when blocks, one per condition (all must hold). A condition with several
	 * values gets one when per value (plain equality tests only, as in the KNX Virtual programs).
	 */
	private static void guarded(final StringBuilder x, final ProductDef p, final String aid, final java.util.List<org.vknx.product.Cond> conds,
			final String element) {
		guarded(x, p, aid, conds, 0, 16, element);
	}

	private static void guarded(final StringBuilder x, final ProductDef p, final String aid, final java.util.List<org.vknx.product.Cond> conds,
			final int i, final int indent, final String element) {
		final String pad = " ".repeat(indent);
		if (i == conds.size()) {
			x.append(pad).append(element).append('\n');
			return;
		}
		final var c = conds.get(i);
		final ParamDef other = p.param(c.param());
		x.append(pad).append("<choose ParamRefId=\"").append(aid).append("_P-").append(other.id()).append("_R-").append(other.id())
				.append("\">\n");
		for (final int v : c.values()) {
			x.append(pad).append("  <when test=\"").append(v).append("\">\n");
			guarded(x, p, aid, conds, i + 1, indent + 4, element);
			x.append(pad).append("  </when>\n");
		}
		x.append(pad).append("</choose>\n");
	}

	/** Versions this application replaces: "0" for version 1, otherwise 1..version-1 (so ETS can update devices in place). */
	private static String replaces(final int version) {
		if (version <= 1)
			return "0";
		final var sb = new StringBuilder();
		for (int v = 1; v < version; v++)
			sb.append(sb.length() == 0 ? "" : " ").append(v);
		return sb.toString();
	}

	private static String flag(final boolean b) { return b ? "Enabled" : "Disabled"; }

	private static String esc(final String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
