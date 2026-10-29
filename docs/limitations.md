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
