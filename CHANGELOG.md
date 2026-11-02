# Changelog

All notable changes to vKNX. Version numbers follow `v<major>.<minor>.<patch>`.
The text under each version is also shown as the release notes on GitHub and in the program's "Check for updates".

## [0.7.9] - 2026-11-02

### Added
- **Settings → Data and cleanup.** Shows what vKNX keeps in its data folder (logs, old device backups, floor plan, device state) with sizes, and has buttons to delete the logs, delete the old device backups and remove the floor plan. **Start a new installation…** resets every device to factory state (no address, no download, no keys; secure devices get a new factory key and certificate), keeps the devices with their names and lines, and clears logs and backups; a checkbox also removes the floor plan. It asks for confirmation first.

### Changed
- The log files can no longer grow without limit: `vknx.log` is moved to `vknx.log.1` when it passes 10 MB (an older `.1` is replaced), and so is `console.log` at each start. A log of 35 MB, as seen in testing, is rotated at the next start.
- Calimero keeps the old state file of a device every time ETS resets it (`<name>_<date>-<time>_EC7.state`, 138 KB each, several per secure download). Nothing used them and they piled up. Only the newest one per device is kept now, and removing a device deletes its backups too.

## [0.7.8] - 2026-11-02

### Fixed
- **The interface's maximum APDU length kept dropping from 254 to 15**, after which ETS refused every download that needs long frames (KNX Data Secure: "Insufficient APDU Length (restricted to 15 by capabilities of the bus interface, required: 34)") until vKNX was restarted. Cause: Calimero lets any device-management client write that property (cEMI server object pid 68, device object pid 56), and ETS keeps its own copy of the value and writes it back. The two properties are now read only (new patch `calimero-server-fixed-apdu.patch`); a write attempt is refused and logged, and the test suite checks that the value stays at 254. If ETS still shows 15 in "Local Interface Settings" from earlier, let ETS discover the interface again (or re-add the connection) so it reads 254 afresh.
- Tests now also check the device side: a secure device reports a maximum APDU length of 254, a management write of 15 to it is refused, and the value is still 254 after a download and after a factory reset. No download changes the length in either direction.

### Changed
- The status strip on the Installation tab is smaller: every warning is one line that opens for the details, and the whole list can be hidden and shown again with the button "n messages" (the choice is remembered). Several "programming button was not pressed" hints are combined into one, and disappear when the device has an address or its button is on.

## [0.7.7] - 2026-11-02

### Added
- **Warnings and diagnostics (Installation tab).** A status strip shows how many ETS connections are open and the line the interface sits on, and warns about what typically ends in "Device does not respond" or "address already in use": a device on a line the interface cannot reach through the existing couplers ("Tryk1 (5.7.3) cannot reach the interface line 1.1 ... set the interface to 5.7 ..."), two devices with the same address, a device with the address of an ETS tunnel, and several open connections. From the recent traffic it also explains what ETS just tried: an address assignment while the programming button was off, a request to an address no device has, a device that did not answer (with the likely cause: unreachable line, secure commissioning on, no application), a failed download step or a refusal in the secure layer. The button "ETS messages explained" lists the common ETS messages with their usual causes with vKNX. These hints are derived from what vKNX can see on its own side; they are not read from ETS.
- **Setup checklist per device** (ETS connected, interface can reach the device, programming button pressed, address assigned, application downloaded, secure commissioning for secure products), ticked from the live state. When no device is selected, the Installation tab shows the next step for the first device that is not ready.
- **Device details: status badges** (address, application, download, lock for secure devices) and a one-line **last ETS action** per device ("Partial download: 3 of 4 parts written, 5 memory writes", "Physical address 1.1.5 assigned", "Master reset ...", "Address refused: the programming button was not pressed"). Secure devices show a clear lock box that explains what secure commissioning means, and that group telegrams are only encrypted for group addresses marked secure in ETS.
- **Topology diagram** (Installation tab, empty selection and the topology view): backbone, main lines, lines and couplers, the ETS interface marked, lines that cannot be reached from the interface outlined in red, lines that carried a telegram a moment ago glowing.
- **Telegram monitor:** device and object names next to addresses (group addresses show "Device: object"; the ETS interface is labelled), **encrypted (Data Secure) telegrams are recognised** (lock symbol, key type, sequence number; when a vKNX device accepted the telegram it is shown, which proves the key and sequence number were right), a filter for encrypted telegrams only, and **"Explain this telegram"** in plain words for group telegrams, ETS management services and secure telegrams. The content of an encrypted telegram is only shown if a vKNX device accepted it.
- **Operating cards:** push buttons show real key caps with a status LED, dimmers show the brightness as slider, blinds move smoothly and are marked while moving, every card lists its group addresses (and the channels show theirs); a lock marks commissioned secure devices. The simulation opens on the floor plan when a plan with placed devices exists (unless you chose a view before).

### Changed
- **New default look:** a bright, high-contrast light theme that works on projectors, larger buttons and rows, more space. The previous e-ink look is still available as "Paper (e-ink)" in Settings, and a "Large (projector)" size makes text and controls bigger. Animation is on, except in the Paper theme.

### Fixed
- Partial downloads from ETS lost the parameters and group objects that had not changed. ETS unloads the application and the tables, then writes only what differs; vKNX cleared the memory on the unload, so e.g. a button or output that stayed enabled came back disabled, and the group object table was empty (no buttons or outputs on the operating card after the download). The memory is now kept on unload.

## [0.7.6] - 2026-11-01

### Fixed
- Deactivating "Secure Commissioning" in ETS (second attempt): after ETS' reset with erase code 7, Calimero itself deleted the tool key and key tables, but ETS goes on with the tool key (to switch security mode off) and got no answer to its sync request ("Device does not respond"). The devices now keep their tool key, key tables and security mode through that reset, so ETS can switch security mode off and then download in plain. The previous fix (0.7.5) only covered vKNX's own handling and was not enough. Found by checking the MAC of ETS' messages in the supplied logs.
- A device removed in vKNX stayed on the simulated bus and kept answering with its old address. ETS then reported "The individual address is already in use by another device" for a new device with that address, and received every answer twice (the old and the new device both answered), which made ETS acknowledge twice, the device drop the connection and downloads fail with "Communication quality ... too bad" or run slowly. Removing a device now takes it off the bus.
- The two default devices (Tryk1, Lampe1) came back at every start after you had removed them. They are now only created on the very first start; an installation you emptied stays empty.

## [0.7.5] - 2026-11-01

### Fixed
- Deactivating "Secure Commissioning" in ETS: ETS sends a factory reset without address (code 7) and then keeps talking with the tool key to switch security mode off. vKNX deleted the keys on that reset, so ETS got no answer ("Device does not respond"). Now only a full factory reset deletes the keys.

### Added
- **Secure devices now write what their KNX Data Secure layer does into their own log** (Monitor tab, category "secure"): received secured telegrams, sync requests and answers, MAC and sequence number errors. This is for finding out why ETS and a secure device do not agree after a reset ("Device does not respond" when Secure Commissioning is switched off and the device is downloaded in plain). Together with the "A_Restart (master reset ...)" line the log shows which erase code ETS sent.

## [0.7.4] - 2026-11-01

### Fixed
- **ETS aborted the secure download with "No key available to decrypt the message".** ETS writes the new tool key and then sends a "confirmed restart" (a master reset of the restart-only kind) to activate it. The master-reset handling added in 0.7.0 deleted the keys on every master reset, so the device answered with the old factory key and ETS could not read the answer. Now only a factory reset (with or without the individual address) deletes the keys, and only after the answer has been sent. Found by decrypting the communication log from ETS with the device's FDSK; covered by our own test, not yet tried in ETS.

## [0.7.3] - 2026-11-01

### Fixed
- **The device certificate could not be typed into ETS.** ETS's "Add Device Certificate" field does not take the 32 hex digits we showed (it has no 0 or 1). It wants a 36-character Base32 code in six blocks of six (letters A-Z, digits 2-7) built from the serial number, the FDSK and a 4-bit CRC. vKNX now shows this **Device certificate** code in the device details, with a Copy button; the serial number and FDSK stay below it for reference. Our test reproduces the example code from the KNX Secure course exactly, but the CRC details are only checked against that one example, so ETS may still reject a code: it shows a check mark when the code is valid.

## [0.7.2] - 2026-11-01

### Fixed
- **Long (extended) frames were lost when they crossed a coupler.** ETS now sends the memory writes of a secure application as long frames (up to 254 bytes of APDU). The coupler model rebuilt every routed frame as a short frame, which fails for anything over 16 bytes, so the frame never reached the device and ETS reported "Communication quality with 1.x.y is too bad for a stable connection". Routed long frames now stay long frames. Covered by our own tests (a 40-byte frame through two line couplers, and a long memory write through the KNXnet/IP server); not yet tried in ETS.

## [0.7.1] - 2026-11-01

### Fixed
- **Secure products could not be downloaded from ETS** ("Insufficient APDU Length (restricted to 15 by capabilities of the bus interface, required: 34)"). The KNXnet/IP interface reported a maximum APDU of 15 bytes, which is Calimero's default for TP1, and ETS refused before sending anything to the device. The interface now reports 254 (tunnelling DIB, cEMI server object and device object). Our test checks the value the server announces; whether ETS then accepts the secure download must be tried in ETS.

## [0.7.0] - 2026-11-01

### Secure products: course-material review
- The serial number is now shown the way ETS shows it in "Add Device Certificate" (`00FA:00000033`, 2 bytes + colon + 4 bytes).
- A master reset (what ETS sends when Secure commissioning is switched off/on) now deletes the tool key, group keys, group object security flags and security mode on secure devices, so the factory key (FDSK) is in use again. FDSK and serial number never change. Covered by our own test (Calimero client), not yet tried in ETS.
- **New: KNX Data Secure products** *Secure push button* and *Secure switch actuator* (same parameters and group objects as the plain products, ETS application flagged `IsSecureEnabled`). Each device gets a random **FDSK** and shows its **serial number and FDSK** in the device details (Copy buttons), which ETS needs for secure commissioning. After ETS has commissioned a device, group telegrams from secure group objects are sent authenticated and encrypted, and plain telegrams to them are ignored. Import the two new files `products/vknx-secure-push-button.xml` and `products/vknx-secure-switch-actuator.xml` (build the `.knxprod` as for the other products).
- Local Calimero patch `calimero-core-secure-plain-drop.patch` (a plain group telegram to a secured group object is dropped; Calimero let it through).
- Tested with the new `SecureTest` (Calimero's own secure client plays ETS: FDSK commissioning, tool key, group key, security mode, secure and plain group telegrams). **Not tried in ETS:** import, entering serial number/FDSK, the real ETS secure download. See `docs/limitations.md`.

## [0.6.4] - 2026-11-01
- **Fix: the structure was flat, so couplers had no effect.** 0.6.2/0.6.3 put every new device on the line of the ETS interface (for example 1.0), so couplers and devices all sat on the same line and telegrams never needed to cross a coupler. Now devices are placed **automatically by their physical address** as soon as ETS assigns it (1.1.4 on line 1.1, a line coupler 1.1.0 between 1.1 and 1.0, an area coupler 1.0.0 between 1.0 and the backbone) and move when ETS gives them a new address. A device that loses its address (15.15.255, or 15.15.0 as ETS leaves a coupler after an unload) stays where it was. Devices stored by 0.6.2/0.6.3 are placed by their address again.
- **New: Topology view** in the Installation tab (button *Topology*): areas, lines and the devices on them, like ETS' topology window; devices without an address are listed as *Not addressed yet*.
- The physical line can still be set by hand (Add device, device details; empty = automatic), for example to test a wrong address. Stored as `@a.l!` in `devices.txt`.
- Tested with `TopologyTest`, `CouplerTest` (an unaddressed coupler stays on its line) and `ProductsTest` (registry); topology view checked headless. **Not tried in ETS.**

## [0.6.3] - 2026-11-01
- **Fix: an unloaded coupler kept routing.** Unloading a coupler's application in ETS only sent the UNLOAD event, and vKNX left the old routing settings and filter table in place, so the light kept working through an unloaded coupler. An unloaded coupler now falls back to *filter* with an empty table (group telegrams blocked). Tested with `CouplerTest`; **not tried in ETS.** A coupler without address still sits on the line it is wired to; its routing then follows the same settings.

## [0.6.2] - 2026-11-01
- **Realistic physical lines and couplers (replaces the 0.6.1 workaround).** Every device is now *wired* to a physical line, independent of its address (Add device: "Physical line", default = the line of the ETS interface; change later under *Physical line* in the device details). Couplers are wired to the sub line they serve; the main line is its parent. Telegrams only leave a line through couplers, **including ETS' own traffic and broadcasts**; the v0.6.1 bypass for ETS management traffic is removed. The workflow is therefore as in a real installation: **download the couplers first** (a factory-new coupler routes everything, so addressing and download work), then the devices behind them.
- **New coupler ETS settings:** *Individually addressed telegrams* (route all / block / filter, per direction) and *Broadcast telegrams* (route / lock, per direction). Filter for individually addressed telegrams only passes telegrams towards the addresses behind the coupler. Group routing is unchanged.
- **Coupler card** shows the lines it joins and warns if its address does not match its line (a coupler should be x.y.0 for line x.y, x.0.0 for area x). Devices from older versions are placed on the line of their address.
- Tested with `TopologyTest` (no bypass, placement independent of address), `CouplerTest`, `TunnelTest`, `TunnelLineTest`, `SelfTest`, `AppTest`, `ProductsTest`. **Not tried in ETS yet:** especially a download order coupler-first with *block*/*filter* for individually addressed telegrams, and the new coupler parameters (import the new `vknx-coupler.xml`/`.knxprod` and download the couplers again).
- **Fix: partial download to a coupler failed** ("Device does not respond", the coupler stayed in *Download in progress*). ETS writes the group filter table of a 0912 coupler with the older services A_Open_Routing_Table (0x3C0) and A_Write_Routing_Table (0x3C3) and expects A_Read_Routing_Table_Res (0x3C2) as verification. vKNX did not answer them, so a download only worked while the filter table was empty (no group address to route). Found in the ETS telegram log of a failed download. vKNX now writes the table, answers the verification and also answers A_Read_Routing_Table. Download the couplers again (*Download all* clears a coupler stuck in *Download in progress*).
- **Fix: the interface address can be changed in ETS** (Local Interface Settings, for example 7.0.24 on an interface in area 1). It failed with "Failed to write Feature IndividualAddress7018: E_DATA_VOID" because the Calimero server only accepted addresses in its own line. Any address that is free (not another tunnel of the interface) is accepted now, as on a real interface. The line ETS sits on follows the new address.
- **Fix: broadcast lock of the coupler.** The coupler read bits 3-2 of the physical routing byte (PID 52/53) as one field, so a broadcast lock combined with the repeat bit (0x7F) still let broadcasts through. Only bit 3 is the lock. ETS' default (0x77) was not affected. Found by comparing with the ETS-exported TP coupler application of another manufacturer and the router object bit layout.
- Tested with `CouplerTest` (the mini-ETS now writes the filter table with 0x3C0/0x3C3 and checks the verification) and `TunnelTest` (feature set of the interface address through the real KNXnet/IP server; refused address). **Not tried in ETS yet.** Open point: whether ETS needs more than the verification response for the routing-table write; the log showed no other step.

## [0.6.1] - 2026-10-30
- **Fix:** with 0.6.0 devices behind a coupler stayed in programming mode and couplers could not be downloaded, because ETS' own telegrams (addressing, reading the address back, download) were stopped by couplers that had no download yet. ETS' management traffic (broadcasts and individually addressed telegrams from or to the tunnel addresses .241-.244) now passes couplers; group telegrams and telegrams between devices are still routed through the couplers' filter tables and hop count.
- Tested with `TopologyTest` and `CouplerTest`; not tried in ETS.

## [0.6.0] - 2026-10-30
**One coupler, real line routing and a real filter table.** Before this version telegrams crossed between lines whether or not a coupler existed or had been downloaded. Now they only do if the couplers are there and let them pass, so a missing coupler download causes the same kind of problem as on a real installation. **Not tried in ETS yet:** the coupler is built like a TP/TP coupler (mask 0912) from the KNX Virtual application and the MV-0912 procedure; only our own tests have run (see "What is checked").

### Changes
- **One product, "vKNX Coupler"**, replaces the line coupler and the area coupler. The role follows from the address: x.y.0 is a line coupler (line x.y to main line x.0), x.0.0 is an area coupler (main line x.0 to the backbone). A coupler without such an address is not part of the line structure. Import the new `vknx-coupler.xml` / `.knxprod` and remove the two old coupler products; stored devices are reset by the new settings schema (add the coupler again).
- **ETS settings:** routing of group telegrams per direction (main to sub, sub to main): *route all*, *block* or *filter* (default). In *filter* mode ETS writes the group filter table with the download.
- **Real routing in the simulator:** a telegram only reaches another line through couplers that exist, are placed on x.y.0 / x.0.0 and let it pass (group: route/block/filter table; individually addressed: ETS' physical setting, filter = only towards the addresses behind the coupler; broadcasts follow the broadcast bits). The hop count is lowered by every coupler (7 = unlimited, 0 = discarded). ETS (the KNXnet/IP interface) is on the interface line from Settings, so it needs the couplers too to reach other lines: download the couplers from the interface line outwards.
- **Coupler card:** role, the four routing settings, counters (routed / blocked per direction), the group addresses in the filter table and the last blocked telegram with the reason, for example "not in the filter table".
- **New setting** *Route telegrams between lines through couplers* (Settings, default on). Off = one flat line as in earlier versions; couplers then have no effect.
- A coupler that has never been downloaded routes everything (factory state). After an ETS *unload* it stays in filter mode with an empty table (this is what ETS writes), so group telegrams are blocked.

### What is checked
- `TopologyTest` (paths between lines, hop count, missing coupler) and `CouplerTest` (our mini-ETS performs the MV-0912 download: unload, load, properties 52-55, clear and write the 8192-byte filter table, load completed; then empty table blocks, one missing entry blocks, both entries pass, unload blocks, block and route modes, physical filter, restart keeps everything, routing off = flat). All older suites still pass.
- **Not checked:** the import of the coupler XML in ETS (schema 23, `CouplerCapabilities`), that ETS runs the download exactly like our mini-ETS, and ETS' own filter table calculation. See `docs/limitations.md` and `docs/ets-test-guide.md` (section 6, step 5).

## [0.5.0] - 2026-10-30
**Breaking change: fewer products, more channels.** The product range is rebuilt around four multi-channel products. All old products are removed, devices saved by an earlier version are reset (the first start shows a message in the log), and **all `.knxprod` files must be imported into ETS again** (new application version 3). **Not tried in ETS yet:** this is the biggest change so far. The new products use parameters that are shown or hidden depending on other parameters (nested `choose/when`) and text parameters for names; only our own tests have run (see "What is checked" below).

### New and changed products
- **Push button, 4 keys** (replaces push button, rocker and blind rocker). Every key can be disabled, given a name and one function: *Switch* (on, off or alternating on/off, optionally following its status object), *Relative dimming* (short press switches, long press dims, release sends stop; direction and step are set per key) or *Blinds* (Up, Down, Up/Down alternating, or Steps; short press = step/stop, long press = move). An alternating blind key starts with DOWN.
- **Switch actuator, 8 outputs** (replaces the 1-/4-/8-fold actuators). Each output has a switch and a status object, can be disabled and named, and has its own relay logic. Startup state and "send status" are common.
- **Dimming actuator, 4 channels.** Each channel has switch, relative dimming (4 bit), brightness value (1 byte) and switch/brightness status objects, its own dimming time, on level, startup state and status setting, and can be disabled and named.
- **Blind actuator, 4 channels** (was 2). Each blind has up/down, stop and position status, its **own travel time up and its own travel time down** (1-255 seconds), and can be disabled and named.
- **Multisensor:** unchanged, but every measurement can now be disabled (the object and the settings disappear in ETS).
- **Slider push button, line coupler and area coupler:** unchanged.
- Disabled keys, outputs, channels and blinds disappear from ETS (no objects, no settings), are ignored by the simulator and are hidden in the control panel and on the floor plan.
- **Names are set in ETS** (text parameter, up to 16 characters) and shown in the control panel, in the log and on the floor plan. They are not copied to the object names in ETS.

### Control panel
- New cards: four keys with their names and functions, eight outputs, four dimmer channels with level bars, four blinds. Each key, output, channel and blind can be placed separately on the floor plan. Saved floor plan symbols of the removed products are not carried over.

### Upgrading
- Import the new `.knxprod` files (`products/` or the zip). Remove the old vKNX products from the ETS catalogue first.
- The device list is reset on the first start: add the new products again.

### What is checked
- Our own tests (mini-ETS, in-process and over a real KNXnet/IP tunnel) pass for all products, including disabled channels, names, per-key functions, alternating blind key, partial downloads, and a default parameter round-trip for every parameter of every product.
- The generated product XML is well-formed. It has **not** been imported into ETS.

## [0.4.2] - 2026-10-30
Fixes the in-program update that closed vKNX but left the old version installed. **Not tried on Windows yet** (the release build runs an automatic silent install test, but updating from inside the program has to be tried by hand).

### Fixed: update from the settings dialog
- The installer now closes a running vKNX itself: it asks the launcher to close (which saves the server), waits up to 10 seconds, and ends whatever is still running before it replaces the files. Before, files still in use could stop the installation without any message, so the old version stayed.
- If the installer ends immediately, the program now shows an error with the download link instead of closing silently.
- This installer change also works when updating *from* 0.4.0 or 0.4.1, because the installer script is part of the new installer.

## [0.4.1] - 2026-10-30
Fixes the status objects that did not send, and adds a blind rocker. Found from a real ETS group-monitor log. **Not yet tried in real ETS:** the new blind rocker product (it must be imported as a new `.knxprod`); the parameter fix is checked against the bytes ETS actually wrote, but only by our own tests.

### Fixed: ETS settings were read with the wrong bit order
- ETS stores several settings in one byte and counts the bits **from the top** (the first setting sits in the highest bits). vKNX counted from the bottom, so every setting that shares a byte was read as the wrong value. In the dimmer this meant **"Send status" was read as No** and "Level when switched on" as 100 %, whatever was chosen in ETS. That is why the dimmer never sent its status telegrams, and why a slider push button or rocker that follows the status never updated.
- Other products with more than one setting in a byte (rocker, push button, slider, switch actuators, blind actuator, multisensor, couplers) were affected in the same way and now read correctly. No ETS product data changed, so existing `.knxprod` files and ETS projects keep working. Devices that were already downloaded do not need a new download: the stored bytes are simply read correctly now.
- New automatic checks use the exact bytes from the ETS log (dimmer `0x44`, rocker `0x20 0x20`).

### New: blind rocker
- New product **Blind rocker (two keys)**: a short press sends step/stop (1 bit, DPT 1.007), a long press sends move up/down (1 bit, DPT 1.008). Link its objects to the blind actuator's *up/down* and *stop* objects. ETS settings: which key is up, and the long-press time.
- The rocker for dimming (relative dimming, 4 bit) is unchanged and is not meant for blinds. The new product must be imported into ETS as a new `.knxprod` (`products/vknx-push-blind-rocker.xml`).

## [0.4.0] - 2026-10-30
New control panel with an e-ink look, and line/area coupler products. **Not yet tried in real ETS:** the new panel together with ETS, the coupler products, rocker, blinds and the 4-/8-channel switch actuators. Known problem being investigated: status objects and blind movement are reported not to work in ETS (log requested).

### New: e-ink look
- The panel now looks like an e-ink display: warm paper background, dark ink text, flat surfaces with thin borders, no shadows, no glow and no animations. The dark theme is a matching low-contrast "E-ink (dark)" variant. Lamp yellow, the red programming indicator and the status colours keep their hues. A theme chosen earlier is kept (Light = paper, Dark = dark).


### New: line coupler and area coupler (step 1, no routing yet)
- Two new products, **Line coupler** (address area.line.0) and **Area coupler** (address area.0.0). They are flagged as couplers in the ETS product data (`IsCoupler`), so ETS lets them be placed on a line/area address. ETS parameters: block / route all / filter for group and for individually addressed telegrams in both directions, repeat on errors, acknowledge.
- The settings that ETS downloads are decoded and shown on the device card and in the details.
- **Not included yet:** the coupler does **not** route or filter telegrams. The simulated network is still one flat line (see `docs/limitations.md`). The coupler products are **not tested in ETS** (our own tests only).
### New: redesigned control panel
- **Three workspaces:** *Installation* (device list with search, product catalogue, details), *Simulation* (operating cards or floor plan, with an optional telegram monitor docked below) and *Monitor* (full telegram monitor with group address overview). Settings, light/dark theme, version and connection status are separate from the navigation.
- **Paper (e-ink) theme by default** (a theme you chose earlier is kept); calmer neutral surfaces, larger values, consistent spacing and buttons, visible keyboard focus, text next to every status colour.
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
