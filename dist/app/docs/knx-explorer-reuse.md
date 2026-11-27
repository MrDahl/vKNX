# KNX Explorer – assessment of reuse and license

Examined: `MrDahl/KNX` commit `3ee9a948c7512ffa48fed42a948d975fca9e4a2a` (React 19, TypeScript, Vite). Source of the license: `LICENSE` in that repo. **This is not legal advice.** vKNX uses the MIT license for its own code (see `decisions.md` D9); no KNX Explorer code has been copied into vKNX.

## Rights
| Part | Status |
|---|---|
| Application code and own documentation | © Marc Sonne Dahl, *KNX Explorer Noncommercial Source Licence 1.0* (non-commercial use only, source-available, not OSI open source). The owner can freely relicense **their own** code. |
| CAD/device symbols `public/symbols/` | MIT (own LICENSE) – free to use, also commercially. |
| Branding `assets/*.svg` | Excluded from reuse. |
| `src/knx/dptCatalogue.json`, `dptExpanded.json`, `dptNames.json` | Rights status **unclear**: the content may be derived from KNX Association specifications (third-party rights, see §3 of the license). The owner must assess this before reuse in open vKNX. |
| Third-party dependencies | Own licenses; keep the notices. |
| Earlier MIT releases | Remain MIT (LICENSE §4). |

## Technical reuse in vKNX
- **Can be reused directly (idea/UX):** telegram interpretation and display (`src/knx/dptDecode.ts`, `dptInspection.ts`, `telegramComparison.ts`), teaching models (`teachingModels.ts`), CAD symbols for the floor plan.
- **Not directly:** the vKNX backend is Java; the UI is plain HTML/JS without a build step. A move to React/Vite is possible later (a new `ui/` folder) but is not needed for the prototype.
- vKNX is a **separate project**; KNX Explorer is unchanged.

## License notes
1. To reuse parts of KNX Explorer in open vKNX, relicense *only the parts you own* that you want to move (e.g. to MIT or Apache-2.0) by adding a new license notice in vKNX – without changing KNX Explorer.
2. DPT catalogue: do not copy it until its source is clarified; generate tables yourself from publicly available information, or keep it as a reference dependency.
