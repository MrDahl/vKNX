# Known limitations (status 0.5.0)

**Decisive:** a successful build and self-test is *not* proof of ETS compatibility. Only what the owner has tried in ETS counts as confirmed.

## Confirmed in ETS 6.4 (Build 8718) by the owner
Import, addressing and download for push button, switch actuator, dimmer, slider push button and multisensor (version 1); devices on other areas/lines; sensor cycles and dimmer reacting. The interface-line setting (Settings → Interface for ETS) was added to avoid the "E_DATA_VOID" error when ETS tries to write the interface address.

## Not yet tried in ETS
- **The coupler (new, unreleased, 0.6.0) in ETS:** import of `vknx-coupler.xml` (schema 23, mask MV-0912 as in the KNX Virtual TP coupler), placing it on x.y.0 / x.0.0, address assignment, the download (router object load state machine, properties 52-55, clearing and writing the group filter table at 0x200, 8192 bytes) and ETS' own filter table calculation. Everything was written from the MV-0912 procedure in `knx_master.xml` and the KNX Virtual coupler application and checked only against our own mini-ETS (`CouplerTest`). If ETS refuses something, send the exact ETS message and the monitor log. Known open points: ETS may not set the x.y.0 address automatically for a line coupler added in the topology (the user said manual addressing is fine); `CouplerCapabilities` and schema 23 are copied from the KNX Virtual hardware file and not tried on the ETS converter.
- **All products of 0.5.0 (breaking redesign):** push button with 4 keys, switch actuator with 8 outputs, dimming actuator with 4 channels, blind actuator with 4 channels and the multisensor with enable settings. Specific risks in ETS: (1) parameters shown/hidden through nested `choose/when` blocks, with the same parameter or object listed in several `when` branches; (2) text parameters (`TypeText`, 16 characters) used for names, and how ETS pads/terminates them in memory; (3) a large number of group objects per device (20 for the push button and the dimmer); (4) the parameter bit layout of the new products (the ETS bit order was proven from a log for the old layout; the new layout follows the same rule but has only been checked by our own tests). If ETS refuses the import or a download, send the exact message and the Group monitor.
- **Version 2 of all products (0.3.0):** English texts, new multisensor with one ETS page per measurement (uses `choose/when` to show only the relevant parameters), `ReplacesVersions="1"`. If ETS complains on import, remove the old catalogue entries and re-import.
- The fix for partial downloads (ETS writes only changed parameters; before 0.3.0 the unchanged parameters were reset to 0, which could give a dimming step of 0 = "break" and switch status sending off).
- The Windows installer, the WebView2 window and the update button on real PCs (only smoke-tested on GitHub's Windows runner).

## Verified by our own tests (without ETS) [T]
`tools/selftest.sh` (mini-ETS in Java), `TunnelTest`/`TunnelLineTest` (real KNXnet/IP tunnel, other areas/lines), `ProductsTest` (all products, partial download, multisensor parameters), `TopologyTest` and `CouplerTest` (line routing, coupler download with filter table), `AppTest` (settings, update parsing), and a browser test of the panel and floor plan.

## Control panel redesign (unreleased) - what is tested and what is not
- **Tested without Windows/ETS** (headless Chromium against the running app, `tools/` fixture that acts like ETS): navigation, installation list/search, catalogue and add/remove with confirmation, programming button, press / held rocker key / slider while live updates run, details, dock (resize, pause/resume, filters, clear view), floor plan (upload, place, drag, rename, reload, operate vs. edit, remove from plan), group address overview, connection loss and recovery (simulated by blocking the API), light/dark theme, sizes 1366x768, 1920x1080, about 1093x614 and 911x512 (Windows scaling 125 % / 150 %) and 600x400.
- **Not tested:** the WebView2 window itself (same engine family as Chromium but not run), real ETS sessions with the new panel, a real Windows installation, touch screens.
- **Status wording:** "ETS / IP client connected" means the KNXnet/IP server reports an open connection. It does not prove that a download will succeed. "Interface ready" only means the server is running.
- **Download status** is derived from the load states of the device's four tables (empty / in progress / partial / complete / error). It says nothing about how far ETS is.
- **DPT decoding** uses the types declared in the product definitions. A group address used by devices with different types, or by none, is shown as "Unknown DPT" with raw data. Telegrams that ETS sends to addresses no device uses are therefore not decoded.
- **Device filter in the monitor** shows telegrams sent by the device or addressed to one of its configured group addresses. The second kind is not proof that the device received them.
- The monitor keeps the latest 3000 telegrams in memory; older ones are only in `vknx.log`.
- Rooms and room filters are not part of this version.

## Functional limits
- **Line routing (0.6.0).** With *Settings → Route telegrams between lines through couplers* switched on (default), a device only hears telegrams from another line if every coupler on the way exists with an x.y.0 / x.0.0 address and lets the telegram pass: group telegrams by *route / block / filter* (filter = the group address must be in the filter table that ETS downloaded), individually addressed telegrams by the physical setting ETS writes (filter = only towards the addresses behind the coupler), and the hop count is lowered by every coupler (7 = unlimited, 0 = telegram discarded). Switched off, the network is one flat line as before.
- **Modelling choices that are not from a standard:** (1) a coupler that has never been downloaded routes everything (factory state "route all", empty filter table); (2) an *unloaded* coupler keeps the routing bytes ETS wrote during the unload procedure (filter, empty table), so it blocks group telegrams but still passes individually addressed ones for its own subtree; (3) a coupler routes according to its stored settings even while the load state machine says "unloaded"; (4) broadcasts follow the broadcast bits of the physical setting; (5) the repeat, acknowledge and "routing counter" details of real couplers are not simulated; (6) tunnel connections from ETS always sit on the interface line (Settings), so ETS can only reach other lines through couplers that exist, are placed and let physical telegrams pass: download the couplers from the interface line outwards.
- No scenes, rooms or simulation rules; no ETS project import.
- Memory writes are at most 10 data bytes per telegram (device max APDU length 15, Calimero default); ETS follows the property by itself.
- The multisensor value and interval are set in ETS only; the panel just shows them.

## Design
- Persistence is written on shutdown; a hard kill can lose changes.
- No login/isolation per student: each student runs their own local instance.
- Built for teaching only; no emulation of other manufacturers' firmware.
- The program is not code-signed, so Windows may show a SmartScreen warning; the first start asks for a firewall exception (UDP/TCP 3671).
