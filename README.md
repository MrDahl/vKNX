# vKNX

Open-source teaching simulator for KNX. Goal: students program **virtual devices from real ETS** (import the products, assign an individual address, set parameters and group addresses, press the programming button, download) without any physical KNX hardware – and then operate buttons, lamps, dimmers, blinds and sensors in a single control panel. Windows is the target platform; only our own generic teaching products are included.

> ## Status: prototype
> Confirmed in ETS 6.4 by the project owner: connection, import, addressing and download for push button, switch actuator, dimmer, slider push button and multisensor (the multisensor was reworked in 0.3.0 and the new version still has to be tried in ETS). The other products and the installer/auto-update have not been tried on real student PCs. See `docs/limitations.md`. Nothing may be described as "tested with ETS" before that test has been run.

## What is included
- Java 21 + [Calimero](https://github.com/calimero-project) (KNXnet/IP server, virtual devices) – see `docs/decisions.md`
- 9 products: push button, rocker, slider push button, switch actuator 1/4/8, dimming actuator, blind actuator (2 channels), multisensor (6 measurements, each with its own group object and ETS page)
- ETS product XML generated from the same product definition the backend runs on
- Control panel (light/dark) with three workspaces: *Installation* (catalogue, device details, programming button, parameters, objects), *Simulation* (operating cards or floor plan, compact telegram monitor underneath) and *Monitor* (telegram table with filters, group address overview with configured-vs-observed)
- Windows application with installer, its own window (WebView2) and "Check for updates" – see `CHANGELOG.md`
- Not yet: line coupler, scenes/environment panel (see `docs/limitations.md`)

## Build and run
Requires JDK 21 and git (no Maven/Gradle).

Linux/macOS (used for development):
```
tools/build.sh          # fetches the pinned Calimero sources, compiles everything
tools/selftest.sh       # backend self-test (NOT an ETS test); also: TunnelTest, CrcCrossCheck
tools/run.sh            # starts simulator + control panel on http://127.0.0.1:8080/
```
**Windows application:** download `vKNX-Setup-<version>.exe` from [Releases](https://github.com/MrDahl/vKNX/releases/latest) and run it (no administrator rights needed; Windows may warn about an "unknown publisher" because the program is not code-signed – choose "Run anyway"). Start vKNX from the start menu and allow it through Windows Firewall on private networks so ETS can find the interface. Java and the WebView2 window are included. To update: cog icon → "Check for updates". Alternatively download `vKNX-<version>-windows.zip` (unzip and run without installing). Data (devices, floor plan, log) lives in `%LOCALAPPDATA%\vKNX\data`.

**Releases (for the maintainer):** write the changes in `CHANGELOG.md` under `## [x.y.z]`, then run the GitHub Actions workflow "windows-exe" with *Run workflow* and the version number (or push a tag `vx.y.z`). It builds, smoke-tests, creates the installer and zip, and publishes the release with the CHANGELOG text as release notes.

Windows (PowerShell; the build is exercised in GitHub Actions on Windows): `tools\build.ps1`, `tools\selftest.ps1`, `tools\start.ps1`, `tools\gen-products.ps1`, `tools\make-knxprod.ps1`.

Options: `--port 3671 --web 8080 --name "vKNX" --dir runtime --line 1.1 --no-browser --device product:name`. Log file: `runtime/vknx.log`.

## Getting the products into ETS
1. Generate the product XML (already included in `products/`; regenerate with `tools/gen-products.ps1` after changing `Products.java`).
2. Convert all XML files to `.knxprod` with `tools/make-knxprod.ps1` (needs OpenKNXproducer in `%USERPROFILE%\bin` and ETS installed).
3. Import the `.knxprod` files in ETS (Catalogs → Import), add the devices to a project, add a KNXnet/IP interface pointing at the vKNX address shown in the panel, and program the devices.

> **Note on the manufacturer name in ETS:** ETS shows these products with the manufacturer **"KNX Association"**. That is only because vKNX uses manufacturer ID `0x00FA`, the ID KNX Association reserves for tests and experiments; ETS looks the name up from the ID. **The products and `.knxprod` files are not made by KNX Association** – they are made by the vKNX project and are not certified or endorsed by KNX Association. A different manufacturer name would need an officially assigned KNX manufacturer ID.

## Icon
vKNX uses a penguin as its program icon. The original blue "V" icon is built into `vKNX.exe` too: right-click the vKNX shortcut → *Properties* → *Change Icon…* and pick the one you like (the `.ico` files are also in the `icons` folder).

## What we use for what
| Part | Used for | License / status |
|---|---|---|
| **Java 21 (JDK)** | Programming language and runtime. Everything is built with plain `javac` – no Maven/Gradle (Maven Central is not needed) | GPLv2+CPE (OpenJDK) |
| **[Calimero](https://github.com/calimero-project)** (`calimero-core`, `calimero-device`, `calimero-server`) | The KNX protocol in Java: telegrams, datapoint types, virtual devices with memory and tables (`calimero-device`), and the KNXnet/IP server that ETS sees as an IP interface (`calimero-server`). Fetched at build time at the pinned commits in `third_party/PINS.txt`, not copied into the repo. One small local patch (`third_party/patches/`) lets devices live on any area/line | GPLv2 + Classpath exception |
| **vKNX code** (`core/`) | Product descriptions, device behaviour (buttons, actuators, dimmer, blinds, multisensor), ETS download handling, persistence, web panel and telegram monitor | MIT |
| **Web panel** (built-in HTTP server + plain HTML/JS) | Operating buttons, showing lamps/dimmers/blinds/sensors and the telegram monitor | part of vKNX |
| **Product XML generator** (`ProductXmlGenerator`) | Writes the ETS product files (`products/*.xml`) from the same product definitions the simulator uses, so ETS parameters and simulator cannot drift apart | part of vKNX |
| **[OpenKNXproducer](https://github.com/OpenKNX/OpenKNXproducer)** | Converts product XML to `.knxprod`, which ETS can import. Called through `tools/make-knxprod.ps1`. Needs ETS installed on the PC because it uses ETS's own signing DLLs | GPL-3.0 (used as an external tool, not bundled) |
| **ETS 6 (KNX Association)** | Programs the virtual devices (group addresses, parameters, download) – exactly as in a real installation | commercial, not included |
| **jpackage + GitHub Actions** | Builds the Windows application (Java runtime included), so nothing has to be installed to run the simulator | part of the build process |
| **WebView2 launcher** (`launcher/`) | Small Windows program that shows the control panel in its own window and starts/stops the simulator in the background | MIT; uses Microsoft WebView2 |
| **Inno Setup** | Builds the Windows installer | Inno Setup license (free) |
| **[Material Design Icons](https://pictogrammers.com/library/mdi/)** | Icons in the control panel | Apache-2.0 |
| **PowerShell scripts** (`tools/*.ps1`) | Start, build, self-test and conversion on Windows | part of vKNX |
| **[KNX Explorer](https://github.com/MrDahl/KNX)** | The owner's own teaching app. vKNX is a *separate* project and only refers to it as background and possible reuse (see `docs/knx-explorer-reuse.md`) | owner's own code |
| **Claude (Anthropic, via Claude Code)** | AI assistant that researched, wrote and tested the code and documentation together with the owner. Commits are marked with `Co-Authored-By: Claude`. Claude cannot run ETS; ETS compatibility is only what the owner has confirmed | – |

**Read as reference only (not used in the code):** [thelsing/knx](https://github.com/thelsing/knx) (GPL-3.0), [Kaenx-Creator](https://github.com/OpenKNX/Kaenx-Creator) (MIT) and the OpenKNX templates – used to understand the ETS product format. The Zennio DIMinBOX DX1 is a functional reference only; no Zennio code is included.

**Evidence level:** ETS import, addressing and download are confirmed by the owner in ETS 6.4 for the push button, switch actuator, dimmer, slider push button and (version 1 of) the multisensor. The other products are tested without ETS. See `docs/limitations.md`.

## Documentation
| File | Contents |
|---|---|
| `docs/research.md` | Source-based research (Calimero, thelsing/knx, product tools, other projects) with commits and evidence levels |
| `docs/decisions.md` | Choice of stack and reasons |
| `docs/ets-download-sequence.md` | The ETS download sequence and vKNX's answers |
| `docs/ets-test-guide.md` | **Exact ETS test guide** + which logs we need back |
| `docs/knx-explorer-reuse.md` | Reuse of KNX Explorer and licence suggestion |
| `docs/limitations.md` | Known limitations |

## License
vKNX's own code is under the **MIT license** (`LICENSE`) – free to use, change and share, also commercially. Third-party components (Calimero, Java, OpenKNXproducer) have their own licenses, see `THIRD-PARTY-NOTICES.md`. KNX and ETS are registered trademarks of KNX Association; vKNX is independent and not certified.
