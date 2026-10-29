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
 * <p>Status: <b>not yet verified against ETS</b>. Verification is step 3 of docs/ets-test-guide.md.
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
				.append("_CS-vKNX\" Name=\"vKNX\" Number=\"vKNX\" DefaultLanguage=\"da-DK\">\n");
		x.append("          <CatalogItem Id=\"").append(ciId).append("\" Name=\"").append(esc(p.nameDa()))
				.append("\" Number=\"1\" ProductRefId=\"").append(prodId).append("\" Hardware2ProgramRefId=\"")
				.append(h2p).append("\" DefaultLanguage=\"da-DK\" />\n");
		x.append("        </CatalogSection>\n      </Catalog>\n");

		x.append("      <ApplicationPrograms>\n");
		x.append("        <ApplicationProgram Id=\"").append(aid).append("\" ApplicationNumber=\"").append(p.appNumber())
				.append("\" ApplicationVersion=\"").append(p.appVersion())
				.append("\" ProgramType=\"ApplicationProgram\" MaskVersion=\"MV-07B0\" Name=\"").append(esc(p.nameDa()))
				.append("\" LoadProcedureStyle=\"MergedProcedure\" PeiType=\"0\" DefaultLanguage=\"da-DK\" ")
				.append("DynamicTableManagement=\"false\" Linkable=\"true\" MinEtsVersion=\"4.0\" ReplacesVersions=\"\">\n");
		x.append("          <Static>\n");
		x.append("            <Code>\n              <RelativeSegment Id=\"").append(seg)
				.append("\" Name=\"Parameters\" Offset=\"0\" Size=\"").append(p.paramAreaSize())
				.append("\" LoadStateMachine=\"4\" />\n            </Code>\n");

		x.append("            <ParameterTypes>\n");
		for (final ParamDef pr : p.params()) {
			x.append("              <ParameterType Id=\"").append(aid).append("_PT-").append(pr.name()).append("\" Name=\"")
					.append(pr.name()).append("\">\n");
			x.append("                <TypeRestriction Base=\"Value\" SizeInBit=\"").append(pr.bits()).append("\">\n");
			for (final var c : pr.choices())
				x.append("                  <Enumeration Text=\"").append(esc(c.textDa())).append("\" Value=\"")
						.append(c.value()).append("\" Id=\"").append(aid).append("_PT-").append(pr.name()).append("_EN-")
						.append(c.value()).append("\" />\n");
			x.append("                </TypeRestriction>\n              </ParameterType>\n");
		}
		x.append("            </ParameterTypes>\n");

		x.append("            <Parameters>\n");
		for (final ParamDef pr : p.params())
			x.append("              <Parameter Id=\"").append(aid).append("_P-").append(pr.id()).append("\" Name=\"")
					.append(pr.name()).append("\" ParameterType=\"").append(aid).append("_PT-").append(pr.name())
					.append("\" Text=\"").append(esc(pr.textDa())).append("\" Value=\"").append(pr.defaultValue())
					.append("\">\n                <Memory CodeSegment=\"").append(seg).append("\" Offset=\"")
					.append(pr.byteOffset()).append("\" BitOffset=\"").append(pr.bitOffset())
					.append("\" />\n              </Parameter>\n");
		x.append("            </Parameters>\n");

		x.append("            <ParameterRefs>\n");
		for (final ParamDef pr : p.params())
			x.append("              <ParameterRef Id=\"").append(aid).append("_P-").append(pr.id()).append("_R-")
					.append(pr.id()).append("\" RefId=\"").append(aid).append("_P-").append(pr.id()).append("\" />\n");
		x.append("            </ParameterRefs>\n");

		x.append("            <ComObjectTable>\n");
		for (final ObjDef o : p.objects())
			x.append("              <ComObject Id=\"").append(aid).append("_O-").append(o.number()).append("\" Name=\"")
					.append(o.name()).append("\" Text=\"").append(esc(o.textDa())).append("\" Number=\"")
					.append(o.number()).append("\" FunctionText=\"").append(o.functionTextEn()).append("\" ObjectSize=\"")
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
		x.append("              <ParameterBlock Id=\"").append(aid).append("_PB-1\" Name=\"General\" Text=\"Generelt\">\n");
		for (final ParamDef pr : p.params())
			x.append("                <ParameterRefRef RefId=\"").append(aid).append("_P-").append(pr.id()).append("_R-")
					.append(pr.id()).append("\" />\n");
		for (final ObjDef o : p.objects())
			x.append("                <ComObjectRefRef RefId=\"").append(aid).append("_O-").append(o.number()).append("_R-")
					.append(o.number()).append("\" />\n");
		x.append("              </ParameterBlock>\n");
		x.append("            </ChannelIndependentBlock>\n          </Dynamic>\n");
		x.append("        </ApplicationProgram>\n      </ApplicationPrograms>\n");

		x.append("      <Hardware>\n        <Hardware Id=\"").append(hwId).append("\" Name=\"").append(esc(p.nameDa()))
				.append("\" SerialNumber=\"").append(p.hardwareSerial())
				.append("\" VersionNumber=\"1\" BusCurrent=\"10\" HasIndividualAddress=\"true\" HasApplicationProgram=\"true\">\n");
		x.append("          <Products>\n            <Product Id=\"").append(prodId).append("\" Text=\"")
				.append(esc(p.nameDa())).append("\" OrderNumber=\"").append(p.orderNumber())
				.append("\" IsRailMounted=\"false\" DefaultLanguage=\"da-DK\">\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" />\n            </Product>\n          </Products>\n");
		x.append("          <Hardware2Programs>\n            <Hardware2Program Id=\"").append(h2p)
				.append("\" MediumTypes=\"MT-0\">\n              <ApplicationProgramRef RefId=\"").append(aid).append("\" />\n");
		x.append("              <RegistrationInfo RegistrationStatus=\"Registered\" RegistrationNumber=\"0001/11\" />\n");
		x.append("            </Hardware2Program>\n          </Hardware2Programs>\n");
		x.append("        </Hardware>\n      </Hardware>\n");
		x.append("    </Manufacturer>\n  </ManufacturerData>\n</KNX>\n");
		return x.toString();
	}

	private static String flag(final boolean b) { return b ? "Enabled" : "Disabled"; }

	private static String esc(final String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
