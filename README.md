# vKNX

Open-source undervisningssimulator til KNX. Mål: elever programmerer **virtuelle enheder fra rigtig ETS** (importér produkter, tildel adresse, sæt parametre og gruppeadresser, tryk programmeringsknap, download) uden fysisk KNX-hardware – og betjener derefter knapper, lamper m.m. i et enkelt browser-kontrolpanel. Kun Windows er målplatform; kun egne generiske undervisningsprodukter.

> ## ⚠ Status: prototype, ikke testet med ETS
> Backend og download-logik består egne tests (mini-ETS i Java, også via ægte KNXnet/IP-tunnel), men **kompatibilitet med ETS 6.4 er ikke verificeret**. Se `docs/limitations.md` og `docs/ets-test-guide.md`. Intet må beskrives som "testet med ETS", før testen er kørt.

## Hvad er med nu
- Java 21 + [Calimero](https://github.com/calimero-project) (KNXnet/IP-server, virtuelle enheder) – `docs/decisions.md`
- Produkter: **Tryk (1-fold)** og **Kontakt-aktuator (1-fold)** med parametre (knapfunktion, relælogik, startværdi, statusafsendelse)
- ETS-produkt-XML genereret fra samme produktdefinition som backenden bruger
- Kontrolpanel (da/en): programmeringsknap + LED, adresse, downloadstatus, tryk-knap, lampe, telegrammonitor med filter og forklaringer
- Ikke endnu: dæmper, slider, persienne, multisensor, linjekobler, plantegning (se `docs/limitations.md`)

## Byg og kør
Kræver JDK 21 og git (ingen Maven/Gradle).

Linux/macOS (bruges i udvikling):
```
tools/build.sh          # henter pinnede Calimero-kilder, kompilerer alt
tools/selftest.sh       # backend-selvtest (IKKE ETS-test); også: TunnelTest, CrcCrossCheck
tools/run.sh            # starter simulator + kontrolpanel på http://127.0.0.1:8080/
```
**Windows-exe:** hent `vKNX-windows.zip` fra [Releases](https://github.com/MrDahl/vKNX/releases/tag/latest), udpak til fx Dokumenter, start `vKNX\vKNX.exe` (browseren åbner selv kontrolpanelet; tillad Windows Firewall for privat netværk). Java er indbygget. Prototype – ikke ETS-testet.

Windows (PowerShell; bygning er kørt i GitHub Actions på Windows): `tools\build.ps1`, `tools\selftest.ps1`, `tools\start.ps1`, `tools\gen-products.ps1`.

Valg: `--port 3671 --web 8080 --name "vKNX" --dir runtime --device produkt:navn`. Logfil: `runtime/vknx.log`.

## Hvad vi bruger til hvad
| Del | Bruges til | Licens / status |
|---|---|---|
| **Java 21 (JDK)** | Programmeringssprog og kørsel. Alt bygges med almindelig `javac` – ingen Maven/Gradle (Maven Central er ikke nødvendig) | GPLv2+CPE (OpenJDK) |
| **[Calimero](https://github.com/calimero-project)** (`calimero-core`, `calimero-device`, `calimero-server`) | KNX-protokollen i Java: telegrammer, datapunkttyper, virtuelle enheder med hukommelse og tabeller (`calimero-device`), og KNXnet/IP-serveren som ETS ser som et IP-interface (`calimero-server`). Hentes ved build i de fastlåste commits i `third_party/PINS.txt` og er ikke kopieret ind i repoet | GPLv2 + Classpath-undtagelse |
| **vKNX-kode** (`core/`) | Produktbeskrivelser, enhedsadfærd (tryk, aktuator, dæmper, persienne, multisensor), download-håndtering til ETS, persistens, webpanel og telegrammonitor | ejeren vælger licens |
| **Webpanel** (indbygget HTTP-server + ren HTML/JS) | Betjening af knapper, visning af lamper/dæmpere/persienner og telegrammonitor – på dansk og engelsk | del af vKNX |
| **Produkt-XML-generator** (`ProductXmlGenerator`) | Laver ETS-produktfiler (`products/*.xml`) ud fra de samme produktdefinitioner som simulatoren bruger, så ETS-parametre og simulator ikke kan komme ud af trit | del af vKNX |
| **[OpenKNXproducer](https://github.com/OpenKNX/OpenKNXproducer)** | Konverterer produkt-XML til `.knxprod`, som ETS kan importere. Kaldes via `tools/make-knxprod.ps1`. Kræver at ETS er installeret på pc'en, fordi den bruger ETS's egne signerings-DLL'er | GPL-3.0 (bruges som eksternt værktøj, ikke indbygget) |
| **ETS 6 (KNX Association)** | Programmerer de virtuelle enheder (gruppeadresser, parametre, download) – præcis som i en rigtig installation | kommerciel, ikke inkluderet |
| **jpackage + GitHub Actions** | Bygger en Windows-mappe med `vknx.exe` (indeholder Java), så man ikke skal installere noget for at køre simulatoren | del af byggeprocessen |
| **PowerShell-scripts** (`tools/*.ps1`) | Start, bygning, selvtest og konvertering på Windows | del af vKNX |
| **[KNX Explorer](https://github.com/MrDahl/KNX)** | Ejerens egen undervisningsapp. vKNX er et *separat* projekt og henviser blot til den som baggrund og mulig genbrug (se `docs/knx-explorer-genbrug.md`) | ejerens egen kode |
| **Claude (Anthropic, Claude Sonnet 5.5, via Claude Code)** | AI-assistent der har researchet, skrevet og testet koden og dokumentationen i samarbejde med ejeren. Alle commits er markeret med `Co-Authored-By: Claude`. Claude kan ikke køre ETS; ETS-kompatibilitet er kun det, ejeren selv har bekræftet | – |

**Kun læst som reference (ikke brugt i koden):** [thelsing/knx](https://github.com/thelsing/knx) (GPL-3.0), [Kaenx-Creator](https://github.com/OpenKNX/Kaenx-Creator) (MIT) og OpenKNX-skabelonerne – brugt til at forstå ETS' produktformat. Zennio DIMinBOX DX1 er kun funktionel reference; ingen Zennio-kode indgår.

**Evidensniveau:** ETS-import, adressering og download er bekræftet af ejeren i ETS 6.4 for Trykknap og Kontakt-aktuator 1-fold. De øvrige produkter er testet uden ETS. Se `docs/limitations.md`.

## Dokumentation
| Fil | Indhold |
|---|---|
| `docs/research.md` | Kildebaseret research (Calimero, thelsing/knx, produktværktøjer, øvrige projekter) med commits og evidensniveauer |
| `docs/decisions.md` | Valg af stak og begrundelser |
| `docs/ets-download-sequence.md` | ETS-downloadsekvensen og vKNX's svar |
| `docs/ets-test-guide.md` | **Præcis ETS-testvejledning** + hvilke logs vi skal have tilbage |
| `docs/knx-explorer-genbrug.md` | Genbrug af KNX Explorer og licensforslag |
| `docs/limitations.md` | Kendte begrænsninger |

## Licens
vKNX's egen kode er under **MIT-licensen** (`LICENSE`) – frit at bruge, ændre og dele, også kommercielt. Tredjepartskomponenter (Calimero, Java, OpenKNXproducer) har deres egne licenser, se `THIRD-PARTY-NOTICES.md`. KNX og ETS er registrerede varemærker tilhørende KNX Association; vKNX er uafhængigt og ikke certificeret.
