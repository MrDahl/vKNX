# Kildebaseret research (Calimero, thelsing/knx m.fl.)

Alle konklusioner er mærket med et **evidensniveau**:

- **[K]** dokumenteret i projektets kilder (sti + commit)
- **[T]** bekræftet af vores egne tests (kun vores backend – *aldrig* ETS, medmindre det står der)
- **[P]** sandsynligt, men ikke verificeret
- **[U]** ikke understøttet / uden for scope

Undersøgte commits (alle hentet 2026-10-29):

| Projekt | Commit | Licens |
|---|---|---|
| calimero-project/calimero-core | `66edcc439957c2964c55cbba9f8bb1f805932e1b` | GPLv2 + Classpath-undtagelse |
| calimero-project/calimero-device | `3062293f794f28012411df9a2012c133e447b5c6` | GPLv2 + Classpath-undtagelse |
| calimero-project/calimero-server | `3d6730bd496199e15d5fc0752c56c965439998ab` | GPLv2 + Classpath-undtagelse |
| calimero-project/introduction | `cb0c657777f03f8bfc5d1fd3182320f99ba21575` | GPLv2 + Classpath-undtagelse |
| thelsing/knx | `980c047ad7fc5e27bf2fae95e48acde5d5e0b4fd` | GPL-3.0 |
| OpenKNX/OpenKNXproducer | `7f23a63ef641bb1c5501697af62d330181e3e23f` | GPL-3.0 |
| OpenKNX/Kaenx-Creator | `077a64e8e0ead13fec0f401a4550c814243033f4` | MIT |
| OpenKNX/Kaenx-Creator-Console | `202a894063b6ea3e7246e3df3f6e181b1027be94` | se repo |
| OpenKNX/Kaenx-Creator-Share | `9ad35d1254306682434d9a90941e4ec77bb53500` | se repo |
| OpenKNX/OpenKNX.Toolbox.Sign | `016088ff0927573c199155e5b398f7c1d9c46460` | se repo |
| georgesberscheid/knx-spring-boot-starter | `2e3c968daa91661fae8469b614c96fa20534a42c` | se repo |
| isisdaude/simulator-knx | `c21f3af02d906f642ab07f44343e2f0d6e3b0c4f` | ingen LICENSE-fil fundet |
| knxd/PyKNyX | `af9dc85f0dc56eab50187ad25753adef1d754121` | GPL-3.0 |
| leadrien/actuasim | `6c8d3bacabe48ad276e218d4d5aec24d9f2443dd` | ingen LICENSE-fil fundet |
| leadrien/knxnet | `440d40498f490a75601b69bd3f547dd8b48b67d3` | ingen LICENSE-fil fundet |
| jgrahamc/koolenex | `bf8803d13e766876be876bb9a7e442de133efede` | MIT |
| abeggled/openbridgeserver | `6278e499e033315879f127076abda710ebcf667e` | MIT |
| MrDahl/KNX (KNX Explorer) | `3ee9a948c7512ffa48fed42a948d975fca9e4a2a` | noncommercial source-available |

## 1. Calimero – de 12 spørgsmål

Stier er relative til de pinnede kildetræer (`third_party/src/<navn>`, hentes med `tools/fetch-calimero.sh`).

| # | Spørgsmål | Svar | Niveau |
|---|---|---|---|
| 1 | Hvordan oprettes en programmerbar enhed? | `io.calimero.device.BaseKnxDevice` + en `KnxDeviceServiceLogic`-underklasse; se `introduction/src/main/java/ProgrammableDevice.java`. Vi bruger konstruktøren med enhedsdeskriptor (`BaseKnxDevice(name, DD0, logic, logic, iosUri, pwd)`). | K, T |
| 2 | Deskriptor / maskeversion | `DD0.TYPE_07B0` (System B). Eksemplet bruger `TYPE_0701`. Vi bruger 07B0, fordi `knx-spring-boot-starter` (`KnxDeviceManager.java:118`) og OpenKNX-skabelonen gør det, og fordi hukommelsestabellerne er enkle. ETS accepterer det *ikke* nødvendigvis – skal testes. | K (valg), P (ETS) |
| 3 | Flere enheder på ét subnet | `VirtualLink.createDeviceLink(...)` giver hver enhed sit eget link på ét delt virtuelt subnet. Vi kører to enheder + KNXnet/IP-server på samme link. | K, T |
| 4 | Individuel adresse, programmeringsstatus, konfiguration pr. enhed | Adresse, programmeringsstatus og tabeller ligger i enhedens Interface Object Server (IOS); `KnxDeviceServiceLogic.setProgrammingMode/inProgrammingMode`. Adresse tildeles via A_IndividualAddress_Write, som *kun* accepteres i programmeringstilstand (vores `VirtualDevice.writeAddress`). | K, T |
| 5 | Managementservices | `KnxDeviceServiceLogic` implementerer bl.a. A_DeviceDescriptor_Read, A_Property_Read/Write, A_FunctionProperty_Command, A_Memory(Extended)_Read/Write, A_Restart, A_IndividualAddress_*, A_Authorize. | K |
| 6 | Download-flow | Calimero har **ingen** færdig ETS-download-logik for tabeller: `PropertyServices` skriver blot egenskaber. Vi har selv implementeret load-state-maskinen (`VirtualDevice.loadControl`) og MCB-tabellen (PID 27). | K (mangel), T |
| 7 | Tabellagring | IOS-objekter 1–4 (adresse-, association-, gruppeobjekttabel, applikation). Vores faste lagerplaceringer: 0x0400 / 0x0800 / 0x0C00 / 0x1000. | T |
| 8 | Parametre → adfærd | Applikationens parameterområde (objekt 4) læses af `DeviceBehavior`; `ParamDef.read` pakker bits ud. Ændring kræver en ny download. | T |
| 9 | Persistens | `BaseKnxDevice.close()` skriver IOS til XML. Vi har en shutdown-hook. Hårdt nedlukket proces = tab af ikke-gemte ændringer. | K, T |
| 10 | Windows-ETS uden fysisk interface | `calimero-server` leverer et KNXnet/IP-servercontainer med tunnelling + discovery; subnettype `user` kan pege på en egen klasse (`SharedVirtualLink`). | K, T (via Calimero-klient, ikke ETS) |
| 11 | Pakning med Java | Rent JDK 21; `jlink`/`jpackage` er mulige. Ikke afprøvet på Windows. | P |
| 12 | Licenser | GPLv2 + Classpath-undtagelse: vKNX kan linkes og udgives under valgfri licens, ændringer *i Calimero selv* skal forblive GPL. | K |

### `virtual` vs. `emulate` i kilderne

- *Virtual* (`VirtualLink`, `calimero-server` subnet `virtual`): et ren software-subnet uden fysisk medie. Det er det, vi bruger. [K]
- *Emulate* (`calimero-server` "emulate"/KNX-interface-emulering mod en bagvedliggende fysisk linje): ikke relevant uden hardware. [K]

## 2. thelsing/knx vs. Calimero

| Kriterium | Calimero | thelsing/knx |
|---|---|---|
| Sprog | Java 21 | C++ (primært embedded: Arduino/ESP32/RP2040/Linux) [K: `src/*_platform.cpp`] |
| Windows-platform | JVM kører på Windows | Ingen Windows-platform i `src/` – kun embedded + Linux [K] |
| Flere enheder pr. proces | Ja (flere `BaseKnxDevice`) | Én global enhed pr. proces (singleton `knx`) [K: `src/knx_facade.*`] |
| ETS-download | Ingen færdig tabellogik (vi har lavet den) | Færdig: tabeller, load-state, MCB/CRC (`application_layer.cpp`, `bits.cpp`) – anvendt i mange rigtige ETS-projekter [K] |
| KNXnet/IP-server | Ja (`calimero-server`) | Kun som *enhed* (routing/tunnel-klient), ikke som interface for ETS [K] |
| Licens | GPLv2+CPE | GPL-3.0 (smitter) |

**Beslutning:** Calimero. Begrundelse: flere enheder i én proces, KNXnet/IP-server, Windows via JVM, og en licens, der giver os frihed. thelsing/knx er brugt som **facitliste** for tabelformater, GO-flagbyte og CRC (ikke som kode). Se `decisions.md`.

## 3. Produktdatabaser

- **OpenKNXproducer** (GPL-3.0): kommandolinjeværktøj; kræver installeret ETS (læser `Knx.Ets.XmlSigning.dll` mv.; `Program.cs:95`, `Signing/HardwareSigner.cs`). README oplister ETS 4–6.3 som understøttet; ETS 6.4 er *ikke* nævnt (**risiko**). Inputformatet er OpenKNX's skabelon-XML; om rå ETS-XML kan gives til verbet `knxprod`, er **ikke verificeret**. [K/P]
- **Kaenx-Creator** (MIT, .NET/Avalonia): GUI-editor, der eksporterer til `.knxprod` via ETS-DLL'er. Samme afhængighed af ETS. [K]
- **knx-spring-boot-starter**: `KnxProductGenerator.java` bruger `MV-07B0`, `MergedProcedure`, `LdCtrlRelSegment` (MergeId 2) og `LdCtrlWriteRelMem` (MergeId 4), producent `M-00FA`, og et ETS-signeringsværktøj (`KnxProdSigner.exe`) – altså *samme* mønster som vores XML. README hævder ETS 4/5-brug. Det er uafhængig støtte for designet, men *ikke* en test af ETS 6.4. [K]
- Producent-ID `0x00FA` ("KNX Association – til test/eksperimenter") løser **ikke** signering, import eller identitet; ETS 6 kontrollerer signaturen i `.knxprod`. [K/P]
- **Konklusion:** Vi genererer ETS-XML fra én produktdefinition (`ProductXmlGenerator`) og bruger ETS' egen konverter/signer (via OpenKNXproducer eller Kaenx-Creator) til `.knxprod` **på en maskine med ETS**. Dette trin kan ikke køres i skyen. [K]

## 4. Lavere prioriterede projekter (kort vurdering)

| Projekt | Hvad | Brugbart for vKNX? |
|---|---|---|
| isisdaude/simulator-knx | Python-simulator af smart home (fysisk model, CLI/GUI) | Nej til ETS-programmering; interessant som UI-inspiration. Ingen licensfil. Sidst opdateret 2022. |
| georgesberscheid/knx-spring-boot-starter | Calimero-baseret virtuel enhed + `.knxprod`-generator | **Ja som reference** (samme stak, samme maske). Ikke som afhængighed (Spring, ETS 4/5). |
| knxd/PyKNyX | Python KNX-stak, virtuelle enheder via multicast | Nej: ingen ETS-download, GPL-3.0, sidst opdateret 2021. |
| leadrien/actuasim + knxnet | Python-gateway-erstatning med persienner/ventiler | Nej: ingen ETS-download, ingen licens, 2016–2017. |
| jgrahamc/koolenex (MIT) | Importerer `.knxproj`, live bus | Mulig *senere* til valgfri import af ETS-projekt (§10 i specifikationen). |
| abeggled/openbridgeserver (MIT) | Multiprotokol-bro | Uden for scope. |

## 5. Hvad der mangler i Calimero (konkret)

1. Ingen ETS-kompatibel load-state-maskine med additional load control (`0x0B`) → **implementeret** i `VirtualDevice.loadControl`.
2. Ingen beregning af MCB-tabellen (PID 27) med CRC-16 → **implementeret** (`Crc16`, `VirtualDevice.memoryControlBlock`).
3. Ingen tolkning af adresse-/association-/GO-tabeller til adfærd → **implementeret** (`Tables`).
4. `Subnet type "user"` + `SharedVirtualLink` var nødvendigt for at dele ét virtuelt subnet mellem server og enheder.
