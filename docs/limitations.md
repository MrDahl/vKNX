# Known limitations (status 0.3.0)

**Decisive:** a successful build and self-test is *not* proof of ETS compatibility. Only what the owner has tried in ETS counts as confirmed.

## Confirmed in ETS 6.4 (Build 8718) by the owner
Import, addressing and download for push button, switch actuator, dimmer, slider push button and multisensor (version 1); devices on other areas/lines; sensor cycles and dimmer reacting. The interface-line setting (Settings → Interface for ETS) was added to avoid the "E_DATA_VOID" error when ETS tries to write the interface address.

## Not yet tried in ETS
- Rocker, blind actuator and the 4-/8-channel switch actuators.
- **Version 2 of all products (0.3.0):** English texts, new multisensor with one ETS page per measurement (uses `choose/when` to show only the relevant parameters), `ReplacesVersions="1"`. If ETS complains on import, remove the old catalogue entries and re-import.
- The fix for partial downloads (ETS writes only changed parameters; before 0.3.0 the unchanged parameters were reset to 0, which could give a dimming step of 0 = "break" and switch status sending off).
- The Windows installer, the WebView2 window and the update button on real PCs (only smoke-tested on GitHub's Windows runner).

## Verified by our own tests (without ETS) [T]
`tools/selftest.sh` (mini-ETS in Java), `TunnelTest`/`TunnelLineTest` (real KNXnet/IP tunnel, other areas/lines), `ProductsTest` (all products, partial download, multisensor parameters), `AppTest` (settings, update parsing), and a browser test of the panel and floor plan.

## Functional limits
- No line coupler (filter table, hop count): all lines are directly connected.
- No scenes, rooms or simulation rules; no ETS project import.
- Memory writes are at most 10 data bytes per telegram (device max APDU length 15, Calimero default); ETS follows the property by itself.
- The multisensor value and interval are set in ETS only; the panel just shows them.

## Design
- Persistence is written on shutdown; a hard kill can lose changes.
- No login/isolation per student: each student runs their own local instance.
- Built for teaching only; no emulation of other manufacturers' firmware.
- The program is not code-signed, so Windows may show a SmartScreen warning; the first start asks for a firewall exception (UDP/TCP 3671).
