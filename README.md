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
Endnu ikke valgt (ejeren beslutter; se `docs/decisions.md` D9). Calimero (GPLv2 + Classpath-undtagelse) hentes ved build og er ikke kopieret ind i repoet. KNX og ETS er registrerede varemærker tilhørende KNX Association; vKNX er uafhængigt og ikke certificeret.
