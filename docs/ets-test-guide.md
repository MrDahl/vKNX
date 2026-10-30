# ETS test guide (ETS 6.4 on Windows)

> **Status:** the first products have been confirmed in ETS 6.4 (Build 8718) by the owner. Version 2 of the products (0.3.0) and the products listed under "Not yet tried" in `limitations.md` still need this test. Use this procedure and send back the logs listed in §5.

## 0. Prerequisites
- Windows 10/11 with ETS 6.4 and the vKNX installer (or, for development, JDK 21 + git and `tools\build.ps1`).

## 1. Create the `.knxprod` files (needs ETS on the machine)
The product XML files are in the repo (`products/`) and in the Windows zip (`vKNX\products\`). They do not have to be generated again.
1. Download the latest **OpenKNXproducer** from https://github.com/OpenKNX/OpenKNXproducer/releases, unzip it, and run `Install-OpenKNX-Tools.ps1` (right-click → *Run with PowerShell*; on a signature error: `PowerShell.exe -ExecutionPolicy Bypass -File .\Install-OpenKNX-Tools.ps1`). The tool uses ETS's own DLLs for conversion and signing and finds ETS in `C:\Program Files (x86)\ETS6`.
2. In PowerShell run `.\make-knxprod.ps1` (in the `vKNX` folder), or manually for each file:
   `OpenKNXproducer knxprod vknx-push-button.xml -o vknx-push-button.knxprod`
   Use `knxprod`, not `create`/`check` (those are for OpenKNX's own template format).
3. Keep the whole console output. If XSD validation reports errors or the converter rejects a file, send us the text.

## 2. Start the simulator
Start vKNX from the start menu (or `tools\start.ps1`). Note the interface address shown at the top (e.g. 5.7.241) and allow it through Windows Firewall (private network) when Windows asks. In Settings you can change the interface line (e.g. `2.9`) so it matches a line in your ETS project.

## 3. In ETS
1. Catalogs → Import → choose the `.knxprod` files. *Note any warnings.* (ETS lists the manufacturer as "KNX Association" because vKNX uses the test ID `0x00FA`; the products are not made or certified by KNX Association.) If an older vKNX version is already in the catalog, ETS may offer an update; if it complains, remove the old catalog entries first.
2. New project; add a push button and a switch actuator from the catalog.
3. Connections → New → KNXnet/IP: does ETS find the interface automatically? Otherwise enter it manually (IP and port 3671).
4. Assign individual addresses → press **Programming button** on one device in vKNX → "Program individual address". *Expected:* only the selected device reacts.
5. Set group addresses and **program the application** on both devices. Note whether ETS reports success or an error (verbatim).
6. Click the button in vKNX. The lamp should switch. Change a parameter (relay logic → inverted) and download again; this is a *partial download*.
7. Restart vKNX and start it again: are addresses and application kept? Can ETS download a change afterwards?

## 4. Acceptance test (12 points)
For each point note ✔ / ✘ / not reached.
1 ETS connects · 2 products import and can be added · 3 only the selected device is in programming mode · 4 addresses for both · 5 full download · 6 a changed group association changes communication · 7 a downloaded parameter changes behaviour · 8 the button sends from its own address · 9 the actuator reacts to the downloaded configuration · 10 status follows the product · 11 configuration survives a restart · 12 a later change + download works.

## 5. What we need back
1. Screenshot/text of every ETS error/warning (import, download).
2. ETS **Group monitor** and **Bus monitor/diagnostics** during the download (export `.txt`).
3. The vKNX log: `console.log` and `vknx.log` in `%LOCALAPPDATA%\vKNX\data` and the output of the panel's monitor. Since 0.3.0 the log prints the parameter memory after each download.
4. ETS version and Windows version.
5. The generated `.xml`/`.knxprod` files.

## 6. Testing the products one at a time
1. Run `make-knxprod.ps1` again (builds all `.knxprod` files, one per product: 8 including the couplers). Remove the old vKNX products from the catalog and import the new ones (version 3).
2. Start with **Dimming actuator + Push button**: set key 1 of the push button to *Relative dimming* (brighter) and key 2 to *Relative dimming* (darker); link *relative dimming* on a group address to *Relative dimming* of dimmer channel 1, and the *switch* objects to the dimmer's *switch*. Download both. Short press = on/off, long press = dim up/down in the chosen step (e.g. 12.5 %); channel 2-4 of the dimmer can be disabled to check that their objects disappear.
3. Then slider push button → dimmer (1 byte, with the status address linked back), the blind actuator, the 8-output switch actuator.
   **Blinds:** set a push button key to function *Blinds*. Link its *Move up/down* to the blind actuator's *up/down* object and its *Step / stop* to the *stop* object (two group addresses). Short press = step/stop, hold = move. Check separate travel times up and down on the blind actuator.
   **Names and disabling:** give a key/output/channel a name in ETS, disable another one, download, and check that the name appears in the vKNX panel and the disabled item is gone from ETS and the panel.
4. **Multisensor:** each measurement has its own ETS page. Set a fixed value or a random range and an interval, link each group object to its own group address and watch the Group monitor; change one value and download again (partial download).
5. **Line coupler / area coupler (new, untested in ETS):** import the two new `.knxprod` files, put a line coupler on line 1.1 (address 1.1.0) and an area coupler at 2.0.0 in the topology, press the programming button on the coupler card, assign the address and download. Report: does ETS accept the product import, does it allow x.y.0, and does the download finish. Telegrams are not routed yet, so the coupler cannot be tested for filtering.
6. On errors: send the verbatim ETS error, the group monitor, `console.log`/`vknx.log`, and which product it concerned.
