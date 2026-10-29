# KNX Explorer – vurdering af genbrug og licens

Undersøgt: `MrDahl/KNX` commit `3ee9a948c7512ffa48fed42a948d975fca9e4a2a` (React 19, TypeScript, Vite). Kilde for licensen: `LICENSE` i det repo. **Dette er ikke juridisk rådgivning.** Ingen licenser er ændret.

## Rettigheder
| Del | Status |
|---|---|
| Applikationskode og egen dokumentation | © Marc Sonne Dahl, *KNX Explorer Noncommercial Source Licence 1.0* (kun ikke-kommerciel brug, source-available, ikke OSI-open-source). Ejeren kan frit genlicensere **sin egen** kode. |
| CAD-/enhedssymboler `public/symbols/` | MIT (eget LICENSE) – kan bruges frit, også kommercielt. |
| Branding `assets/*.svg` | Udelukket fra genbrug. |
| `src/knx/dptCatalogue.json`, `dptExpanded.json`, `dptNames.json` | Rettighedsstatus **uafklaret**: indholdet kan være afledt af KNX Associations specifikationer (tredjepartsrettigheder, jf. §3 i licensen). Skal vurderes af ejeren før genbrug i en åben vKNX. |
| Tredjepartsafhængigheder | Egne licenser; bevar notitser. |
| Tidligere MIT-udgivelser | Forbliver MIT (LICENSE §4). |

## Teknisk genbrug i vKNX
- **Kan genbruges direkte (idé/UX):** telegramfortolkning og -visning (`src/knx/dptDecode.ts`, `dptInspection.ts`, `telegramComparison.ts`), undervisningsmodeller (`teachingModels.ts`), da/en-tekster (`src/locales`), CAD-symboler til senere plantegning.
- **Ikke direkte:** vKNX-backenden er Java; UI'et er i dag ren HTML/JS uden build-trin. En overgang til React/Vite er mulig senere (en ny `ui/`-mappe), men er ikke nødvendig for prototypen.
- vKNX er et **separat projekt**; KNX Explorer er uændret.

## Forslag til licensløsning (beslutning hos ejeren)
1. Hvis vKNX skal være åben for alle (inkl. kommerciel brug): relicensér *kun de dele af KNX Explorer, du selv ejer*, som du vil flytte over (f.eks. til MIT eller Apache-2.0), ved at tilføje en ny licensnotits i vKNX – uden at ændre KNX Explorer.
2. DPT-kataloget: undlad at kopiere det, før kilden er afklaret; generér i stedet tabeller selv fra offentligt tilgængelige oplysninger, eller behold afhængigheden som reference.
3. Hvis undervisningsbrug kun er målet, kan vKNX bevare den ikke-kommercielle licens, men GPL-linking mod Calimero giver ingen konflikt (Classpath-undtagelsen).
4. Tilføj ikke LICENSE-fil i vKNX, før ejeren har valgt (se `decisions.md` D9).
