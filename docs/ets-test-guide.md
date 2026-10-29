# ETS-testvejledning (ETS 6.4 på Windows)

> **Status: ikke gennemført.** Resultatet er ukendt. Dette er proceduren, så testen kan køres og logs sendes tilbage. Ret ikke i XML/koden, før logs er indsamlet.

## 0. Forudsætninger
- Windows 10/11 med ETS 6.4 og **Java 21 (JDK)**, Git.
- Hent repoet: `git clone https://github.com/MrDahl/vKNX` og kør `tools\build.ps1` (se README).

## 1. Opret `.knxprod` (kræver ETS på maskinen)
1. `tools\gen-products.ps1` → `products\vknx-push-button.xml`, `products\vknx-switch-actuator.xml`.
2. Konvertér til `.knxprod` med **OpenKNXproducer** (`OpenKNXproducer knxprod <fil.xml>`) eller **Kaenx-Creator**. Notér fejlbeskeder ordret.
   - Hvis konverteren afviser namespace `project/20`: notér ETS' fejltekst; send den til os.
3. Notér: lykkedes konvertering? Blev filen signeret?

## 2. Start simulatoren
`tools\start.ps1` (åbner kontrolpanel på http://127.0.0.1:8080/). Notér de udskrevne IP-adresser og tunneladresser (1.1.241–244), port 3671.
Tillad Java i Windows Firewall (privat netværk) når Windows spørger.

## 3. I ETS
1. Katalog → Importér → vælg begge `.knxprod`. *Noter eventuelle advarsler.*
2. Nyt projekt, linje 1.1; tilføj "Tryk" og "Lampe" fra kataloget.
3. Forbindelser → Ny → KNXnet/IP: vælger ETS interfacet automatisk? Ellers manuelt (IP fra trin 2, port 3671). Notér hvad der virker.
4. Tildel individuelle adresser (1.1.1 og 1.1.2) → tryk **Programmeringsknap** på den ene enhed i vKNX → "Programmér individuel adresse".
   *Forventet:* kun den valgte enhed reagerer.
5. Sæt gruppeadresser: Tryk objekt 1 og Lampe objekt 1 → 1/0/1; Lampe objekt 2 → 1/0/2 og Tryk objekt 2 → 1/0/2.
6. **Programmér applikation** på begge enheder. Notér om ETS melder succes eller fejl (ordret).
7. Klik "Tryk" i vKNX. Lampen bør skifte. Skift parameter (Relælogik → inverteret) og download igen.
8. Genstart vKNX (Ctrl+C) og start igen: er adresser/applikation bevaret? Kan ETS downloade en ændring bagefter?

## 4. Accepttest (12 punkter)
Notér for hvert punkt: ✔ / ✘ / ikke nået.
1 ETS forbinder · 2 produkter importeres og kan tilføjes · 3 kun valgt enhed i prog-mode · 4 adresser til begge · 5 fuld download · 6 ændret gruppeassociering ændrer kommunikation · 7 en downloadet parameter ændrer adfærd · 8 tryk sender fra knappens adresse · 9 aktuator reagerer efter downloadet konfiguration · 10 status følger produktet · 11 konfiguration overlever genstart · 12 senere ændring + download virker.

## 5. Det vi skal bruge tilbage
1. Screenshot/tekst af alle ETS-fejl/advarsler (import, download).
2. ETS **Gruppemonitor** og **Busmonitor/Diagnostik** under download (eksportér `.txt`).
3. vKNX-loggen: `runtime\vknx.log` (kopiér) og udskrift fra panelets monitor.
4. ETS-version, Windows-version, Java-version (`java -version`).
5. De genererede `.xml`/`.knxprod`-filer.
Med disse kan vi justere XML, load control og MCB uden at gætte.
