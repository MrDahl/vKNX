# Source-based research (Calimero, thelsing/knx and others)

Every conclusion carries an **evidence level**:

- **[S]** documented in the project's sources (path + commit)
- **[T]** confirmed by our own tests (our backend only – *never* ETS, unless stated)
- **[P]** probable but not verified
- **[U]** not supported / out of scope

Commits examined (all fetched 2026-10-29):

| Project | Commit | License |
|---|---|---|
| calimero-project/calimero-core | `66edcc439957c2964c55cbba9f8bb1f805932e1b` | GPLv2 + Classpath exception |
| calimero-project/calimero-device | `3062293f794f28012411df9a2012c133e447b5c6` | GPLv2 + Classpath exception |
| calimero-project/calimero-server | `3d6730bd496199e15d5fc0752c56c965439998ab` | GPLv2 + Classpath exception |
| calimero-project/introduction | `cb0c657777f03f8bfc5d1fd3182320f99ba21575` | GPLv2 + Classpath exception |
| thelsing/knx | `980c047ad7fc5e27bf2fae95e48acde5d5e0b4fd` | GPL-3.0 |
| OpenKNX/OpenKNXproducer | `7f23a63ef641bb1c5501697af62d330181e3e23f` | GPL-3.0 |
| OpenKNX/Kaenx-Creator | `077a64e8e0ead13fec0f401a4550c814243033f4` | MIT |
| OpenKNX/Kaenx-Creator-Console | `202a894063b6ea3e7246e3df3f6e181b1027be94` | see repo |
| OpenKNX/Kaenx-Creator-Share | `9ad35d1254306682434d9a90941e4ec77bb53500` | see repo |
| OpenKNX/OpenKNX.Toolbox.Sign | `016088ff0927573c199155e5b398f7c1d9c46460` | see repo |
| georgesberscheid/knx-spring-boot-starter | `2e3c968daa91661fae8469b614c96fa20534a42c` | see repo |
| isisdaude/simulator-knx | `c21f3af02d906f642ab07f44343e2f0d6e3b0c4f` | no LICENSE file found |
| knxd/PyKNyX | `af9dc85f0dc56eab50187ad25753adef1d754121` | GPL-3.0 |
| leadrien/actuasim | `6c8d3bacabe48ad276e218d4d5aec24d9f2443dd` | no LICENSE file found |
| leadrien/knxnet | `440d40498f490a75601b69bd3f547dd8b48b67d3` | no LICENSE file found |
| jgrahamc/koolenex | `bf8803d13e766876be876bb9a7e442de133efede` | MIT |
| abeggled/openbridgeserver | `6278e499e033315879f127076abda710ebcf667e` | MIT |
| MrDahl/KNX (KNX Explorer) | `3ee9a948c7512ffa48fed42a948d975fca9e4a2a` | noncommercial source-available |

## 1. Calimero – the 12 questions

Paths are relative to the pinned source trees (`third_party/src/<name>`, fetched with `tools/fetch-calimero.sh`).

| # | Question | Answer | Level |
|---|---|---|---|
| 1 | How is a programmable device created? | `io.calimero.device.BaseKnxDevice` + a `KnxDeviceServiceLogic` subclass; see `introduction/src/main/java/ProgrammableDevice.java`. We use the constructor with a device descriptor (`BaseKnxDevice(name, DD0, logic, logic, iosUri, pwd)`). | S, T |
| 2 | Descriptor / mask version | `DD0.TYPE_07B0` (System B). The example uses `TYPE_0701`. We use 07B0 because `knx-spring-boot-starter` (`KnxDeviceManager.java:118`) and the OpenKNX template do, and because the memory tables are simple. ETS has since accepted it for the products tried. | S (choice), T |
| 3 | Several devices on one subnet | `VirtualLink.createDeviceLink(...)` gives each device its own link on one shared virtual subnet. We run the devices + the KNXnet/IP server on the same link. | S, T |
| 4 | Individual address, programming state, per-device configuration | Address, programming state and tables live in the device's Interface Object Server (IOS); `KnxDeviceServiceLogic.setProgrammingMode/inProgrammingMode`. The address is assigned with A_IndividualAddress_Write, which is *only* accepted in programming mode (our `VirtualDevice.writeAddress`). | S, T |
| 5 | Management services | `KnxDeviceServiceLogic` implements A_DeviceDescriptor_Read, A_Property_Read/Write, A_FunctionProperty_Command, A_Memory(Extended)_Read/Write, A_Restart, A_IndividualAddress_*, A_Authorize and more. | S |
| 6 | Download flow | Calimero has **no** ready ETS download logic for tables: `PropertyServices` only writes properties. We implemented the load state machine (`VirtualDevice.loadControl`) and the MCB table (PID 27) ourselves. | S (gap), T |
| 7 | Table storage | IOS objects 1–4 (address, association, group object table, application). Our fixed memory locations: 0x0400 / 0x0800 / 0x0C00 / 0x1000. | T |
| 8 | Parameters → behaviour | The application's parameter area (object 4) is read by `DeviceBehavior`; `ParamDef.read` unpacks the bits. A change needs a new download. | T |
| 9 | Persistence | `BaseKnxDevice.close()` writes the IOS to XML. We have a shutdown hook. A hard-killed process loses unsaved changes. | S, T |
| 10 | Windows ETS without a physical interface | `calimero-server` provides a KNXnet/IP server container with tunnelling + discovery; subnet type `user` can point at our own class (`SharedVirtualLink`). | S, T (via Calimero client; ETS confirmed by the owner later) |
| 11 | Packaging with Java | Plain JDK 21; `jlink`/`jpackage` work (used in the Windows build). | T |
| 12 | Licenses | GPLv2 + Classpath exception: vKNX can link and be published under any license; changes *to Calimero itself* must stay GPL. | S |

### `virtual` vs. `emulate` in the sources

- *Virtual* (`VirtualLink`, `calimero-server` subnet `virtual`): a pure software subnet without a physical medium. This is what we use. [S]
- *Emulate* (`calimero-server` "emulate"/KNX interface emulation towards an underlying physical line): not relevant without hardware. [S]

## 2. thelsing/knx vs. Calimero

| Criterion | Calimero | thelsing/knx |
|---|---|---|
| Language | Java 21 | C++ (mainly embedded: Arduino/ESP32/RP2040/Linux) [S: `src/*_platform.cpp`] |
| Windows platform | The JVM runs on Windows | No Windows platform in `src/` – only embedded + Linux [S] |
| Several devices per process | Yes (several `BaseKnxDevice`) | One global device per process (singleton `knx`) [S: `src/knx_facade.*`] |
| ETS download | No ready table logic (we wrote it) | Ready: tables, load state, MCB/CRC (`application_layer.cpp`, `bits.cpp`) – used in many real ETS projects [S] |
| KNXnet/IP server | Yes (`calimero-server`) | Only as a *device* (routing/tunnel client), not as an interface for ETS [S] |
| License | GPLv2+CPE | GPL-3.0 (copyleft) |

**Decision:** Calimero. Reasons: several devices in one process, a KNXnet/IP server, Windows via the JVM, and a license that gives us freedom. thelsing/knx is used as an **answer key** for table formats, the group object flag byte and the CRC (not as code). See `decisions.md`.

## 3. Product databases

- **OpenKNXproducer** (GPL-3.0): command-line tool; needs ETS installed (reads `Knx.Ets.XmlSigning.dll` etc.; `Program.cs:95`, `Signing/HardwareSigner.cs`). Its README lists ETS 4–6.3 as supported; the owner has since used it with ETS 6.4 (Build 8718) for our XML files. [S/T]
- **Kaenx-Creator** (MIT, .NET/Avalonia): GUI editor that exports `.knxprod` through the ETS DLLs. Same dependency on ETS. [S]
- **knx-spring-boot-starter**: `KnxProductGenerator.java` uses `MV-07B0`, `MergedProcedure`, `LdCtrlRelSegment` (MergeId 2) and `LdCtrlWriteRelMem` (MergeId 4), manufacturer `M-00FA`, and an ETS signing tool (`KnxProdSigner.exe`) – the *same* pattern as our XML. Its README claims ETS 4/5 use. [S]
- Manufacturer ID `0x00FA` ("KNX Association – for tests/experiments") does **not** solve signing, import or identity; ETS 6 checks the signature in the `.knxprod`, which the converter creates. [S]
- **Conclusion:** We generate the ETS XML from one product definition (`ProductXmlGenerator`) and use ETS's own converter/signer (through OpenKNXproducer or Kaenx-Creator) to make the `.knxprod` **on a machine with ETS**. This step cannot run in the cloud. [S]

## 4. Lower-priority projects (short assessment)

| Project | What | Useful for vKNX? |
|---|---|---|
| isisdaude/simulator-knx | Python smart-home simulator (physical model, CLI/GUI) | No for ETS programming; interesting as UI inspiration. No license file. Last updated 2022. |
| georgesberscheid/knx-spring-boot-starter | Calimero-based virtual device + `.knxprod` generator | **Yes as a reference** (same stack, same mask). Not as a dependency (Spring, ETS 4/5). |
| knxd/PyKNyX | Python KNX stack, virtual devices via multicast | No: no ETS download, GPL-3.0, last updated 2021. |
| leadrien/actuasim + knxnet | Python gateway replacement with blinds/valves | No: no ETS download, no license, 2016–2017. |
| jgrahamc/koolenex (MIT) | Imports `.knxproj`, live bus | Possible *later* for optional ETS project import. |
| abeggled/openbridgeserver (MIT) | Multi-protocol bridge | Out of scope. |

## 5. What Calimero lacks (concretely)

1. No ETS-compatible load state machine with additional load control (`0x0B`) → **implemented** in `VirtualDevice.loadControl`.
2. No computation of the MCB table (PID 27) with CRC-16 → **implemented** (`Crc16`, `VirtualDevice.memoryControlBlock`).
3. No interpretation of address/association/group object tables into behaviour → **implemented** (`Tables`).
4. `Subnet type "user"` + `SharedVirtualLink` was needed to share one virtual subnet between server and devices.
5. The server acts as one line and drops frames to other lines/areas → local patch `third_party/patches/calimero-server-flat-network.patch`.
