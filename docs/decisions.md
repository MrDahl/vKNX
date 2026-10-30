# Decisions

Evidence levels: **[S]** source, **[T]** own test (not ETS), **[P]** probable, **[U]** unsupported. See `research.md`.

## D1. Stack: Java 21 + Calimero (not thelsing/knx)
Reasons in `research.md` §2. In short: several devices per process, a KNXnet/IP server, Windows via the JVM, licence freedom (GPLv2 + Classpath exception). We do *not* implement both.

## D2. Build without Maven/Gradle
Calimero sources are fetched at pinned commits (`third_party/PINS.txt`) and compiled with plain `javac` (`tools/build.sh`). Reason: reproducible build, no dependency on Maven Central. Upgrading the pins requires a new self-test **and** a new ETS test.

## D3. Mask 0x07B0 (System B), manufacturer 0x00FA
Same choice as `knx-spring-boot-starter` [S]. 0x00FA is a test/experimental ID. ETS 6.4 has been confirmed to accept our unsigned `.knxprod` for the first products (owner's tests).

## D4. Fixed memory slots
Address table 0x0400, association table 0x0800, group object table 0x0C00 (each 0x400), application 0x1000 (0x1000). ETS uses *relative* allocation; we answer with these fixed addresses in TABLE_REFERENCE. The handling of additional load control `0x0B` is based on a recording of a real download from another (reference) device and has worked with ETS 6.4 for the products tried so far.
Allocation mode 1 (full download) initialises the segment; mode 0 (partial download, only changed parameters) keeps the existing content (fixed in 0.3.0).

## D5. The product definition is the single source of truth
`org.vknx.product.Products` → (a) the backend's parameters/objects, (b) the product XML via `ProductXmlGenerator`. This prevents ETS from exposing functions the backend ignores.
Run: `java -p build/core:build/device:build/server:build/vknx -m org.vknx/org.vknx.tools.ProductXmlGenerator products`.

## D6. One flat virtual network
Calimero's server acts as one line and drops frames to other lines/areas. A small local patch (`third_party/patches/`) makes the network "flat", so devices can have any area/line. The interface address is configurable (Settings → Interface for ETS) because ETS cannot write it on our server. A real line coupler (filter table, hop count) is a later phase.

## D7. Web UI via local backend
A browser cannot speak UDP/KNXnet/IP. UI = JDK `HttpServer` on `127.0.0.1` + static files (`index.html`, `css/`, `js/` ES modules, no build step), shown in a WebView2 window by the Windows launcher. Blue lines = real telegrams; grey = the device's own explanation. [T]

## D8. CRC-16 / MCB
`Crc16.ccitt` is implemented independently and cross-checked against thelsing's C function (`core/test/crc-vectors.txt`, 40 vectors) [T].

## D9. License (decided by the owner 2026-10-29)
The owner wanted "as open source as possible": vKNX's own code is **MIT**. This is possible because Calimero has a Classpath exception (code that links to it may use another license). Calimero and Java remain GPLv2+CPE and are not copied into the repo; see `THIRD-PARTY-NOTICES.md`. OpenKNXproducer (GPL-3.0) is only used as an external tool. Zennio reference code must never be published. KNX Explorer code is not reused (only the owner's own non-commercial license applies there).

## D10. English only (decided by the owner 2026-10-29)
The application, the product catalogue (`.knxprod`), the documentation and the GitHub pages are English only, so that anyone can understand and extend the project. Product application versions were raised to 2 for this release.

## D11. Fewer products, more channels (decided by the owner 2026-10-30, version 0.5.0)
Instead of many single-purpose products, there are four multi-channel products (push button 4 keys, switch actuator 8 outputs, dimming actuator 4 channels, blind actuator 4 channels) plus the multisensor, slider push button and the couplers. Every channel can be disabled and named. Consequences: (a) the old products were removed and stored devices are reset (`state-schema.txt`), because nobody else uses the program yet and compatibility was not worth keeping; (b) ETS shows only what is relevant through nested `choose/when` blocks (`Cond`, `ParamDef.showWhen`, `ObjDef.showWhen`) and the simulator evaluates the same conditions (`VirtualDevice.visible`), so a hidden setting cannot influence the simulation; (c) names are ETS **text parameters** (16 characters, `ParamDef.text`), because ETS cannot rename group objects from a parameter; (d) every object has one fixed type and size per channel, and objects are shown or hidden by the function chosen. Not verified in ETS at the time of the decision.
