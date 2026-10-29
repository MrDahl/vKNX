# Kendte begrænsninger (status 2026-10-29)

**Afgørende:** Intet i vKNX er testet mod rigtig ETS endnu. En vellykket build og selvtest er *ikke* bevis for ETS-kompatibilitet.

## Verificeret (egen test, uden ETS) [T]
`tools/selftest.sh` (mini-ETS i Java) og `tools/selftest.sh TunnelTest` (via ægte KNXnet/IP-tunnel):
adressetildeling kun i programmeringstilstand, deskriptor 0x07B0, komplet download af to enheder, tryk → telegram fra 1.1.1 → aktuator → status, ændret associering ændrer kommunikation, parameter (RelayLogic) ændrer adfærd, adresse/applikation/parametre/association overlever genstart.

## Uverificeret mod ETS 6.4.8718 [P]
- Importerer produkt-XML'en overhovedet (namespace `project/20`; OpenKNXproducer nævner ETS ≤ 6.3)?
- Accepteres usigneret/eksperimentelt `.knxprod` med producent 0x00FA?
- Fungerer discovery og forbindelse (loopback vs. netværksinterface, Windows-firewall, UDP 3671)? Serveren binder interface-IP, ikke 127.0.0.1.
- Præcis additional load control, MCB/CRC-accept, evt. ekstra load-procedurer.
- Engelske tekster i produktkataloget findes ikke (kun dansk).

## Funktionelt ikke bygget endnu
Dæmper, slider-dæmper, persienne, multisensor (cyklisk), linjekobler med filtertabel/hop count, flere instanser pr. elev, scener, rum, gemte opsætninger, plantegning, ETS-projektimport. Windows-pakning (jlink/jpackage) og Windows-scripts er skrevet men **ikke kørt på Windows**.

## Design
- Alle enheder på linje 1.1 (D6).
- Persistens skrives ved lukning; hård nedlukning kan miste ændringer.
- Intet login/isolering pr. elev: hver elev kører sin egen lokale instans.
- Bygget kun til undervisning; ingen emulering af andre producenters firmware.

## Opdatering: 9 produkter (kun testet uden ETS)
- **Bekræftet i ETS 6.4.8718 af ejeren [T]:** import, adressetildeling og fuld download for de to første produkter (Trykknap 1-fold, Kontakt-aktuator 1-fold).
- **Bygget og enhedstestet, IKKE testet i ETS [P]:** Vippe (dæmp 4 bit), Skyder-tryk, Kontakt-aktuator 4/8, Dæmper, Persienne 2 kanaler, Multisensor. Nyt i XML: 4-bit objekter, 16-bit tal-parametre (signed/unsigned), DPT-9 objekter, parametre pakket i bits. Multisensor "Tilfældig værdi" bruger `DPT-9` uden undertype – ETS kan afvise den.
- **Kendt begrænsning:** enhedens max APDU-længde er Calimeros standard 15, så hukommelsesskrivning er maks. 10 databytes pr. telegram (ETS følger egenskaben selv; egen test-ETS skriver 10 bytes ad gangen).
- Linjekobler (filtertabel, hop count) er ikke bygget.
- Enheder tilføjes/fjernes i panelet og gemmes i `devices.txt` i data-mappen. `--no-browser` starter uden at åbne browseren.

## Opdatering: vilkårligt område/linje (2026-10-29)
- Fejl rapporteret af ejeren: download fejler, når en enhed får anden adresse end 1.1.x. Årsag (reproduceret med `TunnelLineTest`): Calimeros KNXnet/IP-server opfører sig som én linje og dropper telegrammer til andre linjer/områder. Rettet med en lille lokal patch (`third_party/patches/`), så hele den virtuelle installation er "flad" og kan have alle adresser. [T uden ETS; skal bekræftes i ETS]
- Der er stadig ingen rigtig linjekobler (filtertabel, hop count) – alle linjer er direkte forbundet.
- Timer-opgaver (dæmpning, cyklisk sending, persienner) logger nu fejl i stedet for at stoppe lydløst.

## Opdatering: nyt kontrolpanel (2026-10-29)
- Panelet er redesignet: mørkt/lyst tema (standard mørkt), enhedsliste grupperet efter linje, oversigt over gruppeadresser (fra den downloadede konfiguration), kort-layout, farvet telegrammonitor, tilpasset projektor/stor skærm.
- Ikke bygget endnu: plantegning med ikon-placering, scener/miljøpanel, simulerings-regler. Gruppeadresse-oversigten er ikke afprøvet med en rigtig ETS-download.

## Opdatering: plantegning (2026-10-29)
- Fane "Plantegning": indlæs PNG/JPG/SVG/WebP/GIF (maks. 12 MB), placér enkelte udgange/indgange (lamper, tryk, vippetaster, skyder, persienner, sensorer) ved at trække dem fra listen ind på tegningen. I kørselstilstand kan man betjene tryk og se lamper, dæmpning, persienneposition og sensorværdier live.
- Gemmes i datamappen (`plan.<ext>`, `plan-layout.json`) – en mappe pr. elev/instans.
- Testet i browser uden ETS (upload, placering, træk, genindlæsning). Ikke bygget: egne ikonsæt, rum/zoner, tegne-værktøj, scener.
