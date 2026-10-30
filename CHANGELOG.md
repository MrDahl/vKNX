# Changelog

All notable changes to vKNX. Version numbers follow `v<major>.<minor>.<patch>`.
The text under each version is also shown as the release notes on GitHub and in the program's "Check for updates".

## [Unreleased]
### New: e-ink look
- The panel now looks like an e-ink display: warm paper background, dark ink text, flat surfaces with thin borders, no shadows, no glow and no animations. The dark theme is a matching low-contrast "E-ink (dark)" variant. Lamp yellow, the red programming indicator and the status colours keep their hues. A theme chosen earlier is kept (Light = paper, Dark = dark).

### New: redesigned control panel
- **Three workspaces:** *Installation* (device list with search, product catalogue, details), *Simulation* (operating cards or floor plan, with an optional telegram monitor docked below) and *Monitor* (full telegram monitor with group address overview). Settings, light/dark theme, version and connection status are separate from the navigation.
- **Light theme by default** (a theme you chose earlier is kept); calmer neutral surfaces, larger values, consistent spacing and buttons, visible keyboard focus, text next to every status colour.
- **Device cards:** name and symbol, the current value large (dimmer 65 %, relay on/off, temperature 22.4 °C, blind position and direction), only the controls the device really has, address as secondary information. A stable grid: live updates never move or rebuild a card, so sliders, held keys and keyboard focus are not disturbed. Delete is now *Remove device…* in the **⋯** menu, with a confirmation.
- **Device details:** product, physical address, a programming button with a red indicator, status (address / application / download), ETS parameters, communication objects with their group addresses, and *Show telegrams for this device*.
- **Add device** from a catalogue with readable names, icons and a one-line description; concrete error messages.
- **Floor plan:** *Operate* mode (maximum space, no editing tools, the ⓘ button opens details without operating anything) and *Edit plan* mode (channel list, drag or arrow keys to move, properties panel with label, device, channel and group addresses, *Remove from plan* separate from deleting the device). Fit to window, zoom (also Ctrl + mouse wheel), drag-to-pan, symbol size. Existing plans and placements keep working.
- **Telegram monitor:** columns Time, Source, Destination, Service, Value; details and raw data per row; pause/resume without stopping the simulation; follow-live; filters for device, group address, service and origin with *Reset filters*; *Clear view* only empties the list (the log file `vknx.log` is never touched). Device explanations are on a separate *Device log* tab, not mixed into the bus telegrams.
- **Values are decoded only when the datapoint type is known** from the product definition of the devices that have the group address (never guessed from the data length). Otherwise the raw data and "Unknown DPT" are shown.
- **Group address overview:** *configured* (downloaded by ETS) versus *observed* (telegrams actually seen). Selecting an address outlines the devices that have it, filters the monitor and links back to the devices. No permanent connection lines.
- **Honest connection status:** the header distinguishes the simulator (this window can reach vKNX), the KNX interface (the KNXnet/IP server is running, on which port) and an ETS/IP client (open connections reported by the server, last IP frame). On connection loss a banner appears, values are dimmed as possibly outdated, failed actions show an error and are never shown as done, and recovery is announced.
- Status per device from facts the device can document: no address, no application, download in progress / partial / complete / failed, programming mode.

### Changed (technical)
- The single 450-line `index.html` is now `css/app.css` and ES modules under `js/` (served from the jar under `/web/`; no CDN, works offline and in the installed Windows version).
- New backend fields: structured telegrams in `/api/log` (`tg`: origin, source, destination, service, TPDU, priority, hops, DPT, decoded value), `link` and `plan` in `/api/state`, `downloadState` per device, group object `function`/`dpt` in associations, `/api/catalog`.
- Saved installations, `devices.txt`, `*.state` files and `plan-layout.json` keep their format.

## [0.3.1] - 2026-10-29
### New
- **Penguin icon:** the program, installer and shortcuts now use the penguin. The original blue "V" icon is still inside `vKNX.exe`: right-click a vKNX shortcut → *Properties* → *Change Icon…* to switch (the `.ico` files are also in the `icons` folder).
- README and ETS test guide now state that ETS shows "KNX Association" as manufacturer only because vKNX uses the test manufacturer ID `0x00FA`; the products are not made by or certified by KNX Association.

### Fixed
- **"Check for updates" showed a fetch error** when the latest release had long release notes: the parser overflowed the stack. It now reads the release JSON without regular expressions, never drops the connection on an unexpected error, and says "vKNX is up to date" when there is no newer version. A test uses the real v0.3.0 release JSON.

### Known / not tested
- The icon switch in the Windows "Change Icon" dialog is verified only by a build check (the exe must contain two icon groups and the penguin must be the default); it has not been tried on a real PC.

## [0.3.0] - 2026-10-29
English only, a reworked multisensor, and fixes for dimming and status.

### New
- **Multisensor rework:** the six measurements (CO2, humidity, illuminance, temperature, air pressure, wind speed) each have their own group object and their own ETS parameter page. Per measurement you choose in ETS: fixed value or random value between a minimum and maximum, and an individual send interval in seconds (0 = never). The control panel no longer changes values; it only shows them.
- **English everywhere:** control panel, ETS product catalogue (`products/*.xml` and the `.knxprod` you build from them), documentation and GitHub pages. The language button is gone.
- Product application version is now 2 (texts and parameter layout changed). Import the new `.knxprod` files in ETS and update or re-add the devices.

### Fixed
- **Relative dimming sent only "break":** when ETS changes only some parameters (partial download), vKNX reset all other parameters to 0, so the dimming step became 0 (= break) and "send status" turned off. Partial downloads now keep the unchanged parameters; values that are not valid choices fall back to the default. The device log now prints the parameter memory after each download.
- **Slider push button and dimmer status:** the button now follows the slider (a press after moving the slider toggles correctly), and the missing status feedback caused by the reset above is gone.
- **Update check failed with `handshake_failure`:** the bundled Java runtime was missing the modules for GitHub's TLS (`jdk.crypto.ec`). They are now included.

### Known / not tested
- The new multisensor pages (they use ETS `choose/when`) and version 2 of all products have not been tried in ETS yet. Rocker, blind actuator and 4-/8-channel actuators are also still untested in ETS.
- The update button can only be tested with a release newer than the installed one.

## [0.2.0] - 2026-10-29
First versioned release with installer and update button.

### New
- **Windows application:** installer (`vKNX-Setup-0.2.0.exe`) with start-menu shortcut and uninstaller, installed without administrator rights. vKNX runs in one window (WebView2) – no console and no separate browser.
- **Check for updates:** the button under Settings (cog icon) asks GitHub for a newer version, shows the changes and can download and install it.
- **Interface line for ETS:** Settings → "Interface for ETS". Enter e.g. `2.9` so the interface address (2.9.241) matches a line in your ETS project and ETS does not complain.
- **Floor plan:** zoom in and out (30–300 %) after upload; the zoom level is saved.
- New, smaller and clearer control panel; dark/light theme; Material Design Icons with fixed colours; dimmable light with an outline that fades in.
- Devices can have any area and line (flat network).

### Fixed
- The slider of the slider push button now updates, also after it has been used (focus on the slider blocked the update).

### Known / not tested
- ETS compatibility is confirmed by the owner for: push button, switch actuator, dimmer, slider push button and multisensor (import, addressing, download). Blind actuator, rocker and 4-/8-channel actuators have not been tried in ETS yet.
- The installer and automatic update were only tested on GitHub's Windows machine, not on school PCs. The program is not code-signed, so Windows may show a SmartScreen warning.
- Line coupler (filter table, hop count) is not built.
