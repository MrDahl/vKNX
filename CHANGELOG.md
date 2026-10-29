# Changelog

All notable changes to vKNX. Version numbers follow `v<major>.<minor>.<patch>`.
The text under each version is also shown as the release notes on GitHub and in the program's "Check for updates".

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
