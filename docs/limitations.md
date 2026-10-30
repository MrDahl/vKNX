# Known limitations (status 0.5.0)

**Decisive:** a successful build and self-test is *not* proof of ETS compatibility. Only what the owner has tried in ETS counts as confirmed.

## Confirmed in ETS 6.4 (Build 8718) by the owner
Import, addressing and download for push button, switch actuator, dimmer, slider push button and multisensor (version 1); devices on other areas/lines; sensor cycles and dimmer reacting. The interface-line setting (Settings → Interface for ETS) was added to avoid the "E_DATA_VOID" error when ETS tries to write the interface address.

## Not yet tried in ETS
- **Line and area coupler (new, unreleased):** ETS import, placing the coupler on x.y.0 / x.0.0, addressing and download are untested. The products use the same System B base as the other products (mask 07B0) with `IsCoupler="true"`; ETS may refuse that combination or ignore the routing parameters. If so, send the exact ETS message. The product has no group objects, so the object/association tables are empty.
- **All products of 0.5.0 (breaking redesign):** push button with 4 keys, switch actuator with 8 outputs, dimming actuator with 4 channels, blind actuator with 4 channels and the multisensor with enable settings. Specific risks in ETS: (1) parameters shown/hidden through nested `choose/when` blocks, with the same parameter or object listed in several `when` branches; (2) text parameters (`TypeText`, 16 characters) used for names, and how ETS pads/terminates them in memory; (3) a large number of group objects per device (20 for the push button and the dimmer); (4) the parameter bit layout of the new products (the ETS bit order was proven from a log for the old layout; the new layout follows the same rule but has only been checked by our own tests). If ETS refuses the import or a download, send the exact message and the Group monitor.
- **Version 2 of all products (0.3.0):** English texts, new multisensor with one ETS page per measurement (uses `choose/when` to show only the relevant parameters), `ReplacesVersions="1"`. If ETS complains on import, remove the old catalogue entries and re-import.
- The fix for partial downloads (ETS writes only changed parameters; before 0.3.0 the unchanged parameters were reset to 0, which could give a dimming step of 0 = "break" and switch status sending off).
- The Windows installer, the WebView2 window and the update button on real PCs (only smoke-tested on GitHub's Windows runner).

## Verified by our own tests (without ETS) [T]
`tools/selftest.sh` (mini-ETS in Java), `TunnelTest`/`TunnelLineTest` (real KNXnet/IP tunnel, other areas/lines), `ProductsTest` (all products, partial download, multisensor parameters), `AppTest` (settings, update parsing), and a browser test of the panel and floor plan.

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
- **Couplers do not route (yet).** The line and area coupler store and show their ETS settings, but the simulated network is one flat line, so every device reaches every other device regardless of the coupler. Real routing (separate lines, filter table, hop count) is planned as step 2.
- No line coupler (filter table, hop count): all lines are directly connected.
- No scenes, rooms or simulation rules; no ETS project import.
- Memory writes are at most 10 data bytes per telegram (device max APDU length 15, Calimero default); ETS follows the property by itself.
- The multisensor value and interval are set in ETS only; the panel just shows them.

## Design
- Persistence is written on shutdown; a hard kill can lose changes.
- No login/isolation per student: each student runs their own local instance.
- Built for teaching only; no emulation of other manufacturers' firmware.
- The program is not code-signed, so Windows may show a SmartScreen warning; the first start asks for a firewall exception (UDP/TCP 3671).
