# Tredjepartskomponenter

vKNX's egen kode er under MIT (se `LICENSE`). Følgende er ikke vKNX-kode og har egne licenser:

| Komponent | Licens | Hvordan det indgår |
|---|---|---|
| Calimero (core, device, server), pinnede commits i `third_party/PINS.txt` | GPLv2 + Classpath-undtagelse | Hentes ved build; indgår som biblioteks-jar i Windows-pakken. Classpath-undtagelsen tillader, at kode der linker mod Calimero har anden licens. Calimeros licenstekst følger med kildekoden i `third_party/src` efter `fetch-calimero.sh`. |
| Java-runtime (OpenJDK) | GPLv2 + Classpath-undtagelse | Pakkes ind af jpackage i exe-udgaven. |
| OpenKNXproducer | GPL-3.0 | Eksternt værktøj, brugeren henter det selv; indgår ikke i vKNX. |
| Material Design Icons (Pictogrammers, `@mdi/js` 7.4.47) | Apache-2.0 (Pictogrammers Free License) | Enkelte ikon-stier er indlejret som inline-SVG i `core/resources/web/index.html`; ingen netværksadgang nødvendig. Kilde: https://pictogrammers.com/library/mdi/ |
| ETS (KNX Association) | Kommerciel | Ikke inkluderet. |

KNX og ETS er varemærker tilhørende KNX Association. vKNX er uafhængigt og ikke certificeret.
Ingen kode fra KNX Explorer (ejerens ikke-kommercielle licens) eller Zennio er kopieret ind.
