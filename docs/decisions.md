# Beslutninger

Evidensniveauer: **[K]** kilde, **[T]** egen test (ikke ETS), **[P]** sandsynligt, **[U]** ikke understøttet. Se `research.md`.

## D1. Stak: Java 21 + Calimero (ikke thelsing/knx)
Begrundelse i `research.md` §2. Kort: flere enheder pr. proces, KNXnet/IP-server, Windows via JVM, licensfrihed (GPLv2 + Classpath-undtagelse). Vi implementerer *ikke* begge.

## D2. Build uden Maven/Gradle
Calimero-kilder hentes ved pinnede commits (`third_party/PINS.txt`) og kompileres med almindelig `javac` (`tools/build.sh`). Grund: reproducerbar build, ingen afhængighed af Maven Central. Opgradering af pins kræver ny selvtest **og** ny ETS-test.

## D3. Maske 0x07B0 (System B), producent 0x00FA
Samme valg som `knx-spring-boot-starter` [K]. 0x00FA er et test-/eksperiment-ID; det er **ikke** bevist, at ETS 6.4 accepterer usigneret/eksperimentel signering [P].

## D4. Faste lagerslots
Adressetabel 0x0400, association 0x0800, GO-tabel 0x0C00 (hver 0x400), applikation 0x1000 (0x1000). ETS bruger *relativ* allokering; vi svarer med disse faste adresser i TABLE_REFERENCE. Håndteringen af additional load control `0x0B` bygger på optagelse af en rigtig download fra en anden (reference-)enhed – den er **ikke** afprøvet mod ETS 6.4 [P].

## D5. Produktdefinition er eneste sandhed
`org.vknx.product.Products` → (a) backendens parametre/objekter, (b) produkt-XML via `ProductXmlGenerator`. Det forhindrer, at ETS udstiller funktioner, backend ignorerer.
Kør: `java -p build/core:build/device:build/server:build/vknx -m org.vknx/org.vknx.tools.ProductXmlGenerator products`.

## D6. Én delt virtuel linje 1.1
ETS tunnelinterface = `1.1.0` (tunnel 1.1.241–244), og alle elever-enheder ligger på linje 1.1, ellers droppes P2P-frames (set i test) [T]. Linjekobler kræver ekstra containere og er senere fase.

## D7. Web-UI via lokal backend
Browseren kan ikke tale UDP/KNXnet/IP. UI = JDK `HttpServer` på `127.0.0.1` + `index.html` (da/en). Blå linjer = rigtige telegrammer; grå = enhedens forklaring. [T]

## D8. CRC-16 / MCB
`Crc16.ccitt` er uafhængigt implementeret og krydstjekket mod thelsings C-funktion (`core/test/crc-vectors.txt`, 40 vektorer) [T]. At *ETS accepterer* MCB-indholdet er ikke testet [P].

## D9. Licens (besluttet af ejeren 2026-10-29)
Ejeren ønskede "så open source som muligt": vKNX's egen kode er **MIT**. Det er muligt, fordi Calimero har Classpath-undtagelse (linkende kode kan have anden licens). Calimero og Java forbliver GPLv2+CPE og er ikke kopieret ind i repoet; se `THIRD-PARTY-NOTICES.md`. OpenKNXproducer (GPL-3.0) bruges kun som eksternt værktøj. Zennio-referencekode må aldrig publiceres. KNX Explorer-kode er ikke genbrugt (kun ejerens egen ikke-kommercielle licens dér).
