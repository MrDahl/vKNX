# Manual test list for the redesigned control panel

These steps need Windows, the installed program and ETS; they were **not** run by the developer (only the browser tests described in `limitations.md` were).

1. Start vKNX. The window opens in the *light* theme, with *Installation* selected. (If you chose the dark theme earlier, it stays dark.)
2. **Window sizes:** maximise, then make the window as small as Windows allows. All three workspace buttons, the cog and the theme button must stay visible. Try Windows scaling 125 % and 150 %.
3. **Add device:** *Add device* → pick *Dimming actuator* → keep or change the name → *Add device*. Try a name with a space or an existing name: you should get a clear message.
4. **Programming:** select the device, press *Programming button*. The red indicator and the text "Programming mode is ON" appear. In ETS, assign the physical address; the indicator goes out and the address shows in the details.
5. **Download from ETS:** the status chip changes from *No address* to *No application* to *Ready*. Check that *Download* in the details says "Complete".
6. **ETS parameters / objects:** the details list the parameters ETS wrote and the group addresses per object. The *Group addresses* chips jump to the Monitor.
7. **Simulation:** operate a button; the lamp card follows. Move the slider card while the dimmer card is changing: neither must jump. Hold a push-button key set to relative dimming (long press) and release.
8. **Telegram monitor:** open it under the simulation (*Telegram monitor*), drag the divider to resize, hide it again. In *Monitor*: filter on one device, on one group address, *Pause* while pressing buttons (the counter shows new telegrams), *Resume live*, *Clear view* (the file `vknx.log` in the data folder must be unchanged).
9. **ETS group monitor next to vKNX:** values shown for known DPTs must match ETS; an address no device uses shows "Unknown DPT" and raw data.
10. **Floor plan:** *Floor plan* → *Edit plan* → upload an image → place symbols, drag, rename in the properties panel → *Operate*. Quit and restart vKNX: the placements must still be there. Press symbols; the ⓘ button must open details without operating.
11. **Connection status:** click the status button in the header. With ETS connected to the vKNX interface it should list the connection; with ETS closed it should say none is connected. (This is the one thing the developer could not check against real ETS.)
12. **Connection loss:** close the vKNX background process from Task Manager while the window is open: a red banner appears and values dim. Start vKNX again; the window recovers or must be reopened.
13. **Existing data:** start the new version with the `data` folder of version 0.3.1 (devices, floor plan): devices and placements must appear unchanged.

## Added in 0.7.7 (diagnostics, start guide, livelier cards) - not run against real ETS

13. **Status strip (Installation):** connect ETS; the strip must say "ETS connected · 1 connection". Set ETS' interface address to a line other than the one of an addressed device (or set the vKNX interface line in Settings): the strip must name the device and the line and offer a fix. Give two devices the same address (not possible from ETS in one project; use two ETS projects): a duplicate warning must appear.
14. **"Device does not respond" hints:** in ETS try to address a device with its programming button off: the hint "ETS tried to give <device> an address, but the programming button was not pressed" must appear within a few seconds. Read an address no vKNX device has: "ETS is talking to <address>, but no vKNX device has that address".
15. **Setup checklist and last ETS action:** during a download the steps tick off one by one; the line "Last ETS action" must show the download ("Download: 4 of 4 parts written ...", or "Partial download ..." for a partial one). The lock box on secure devices must show "Secure commissioned" after the secure download, and the secure badge must turn green.
16. **Monitor:** after a secure download operate a secure input: the telegram must show the lock and "Encrypted · <device> accepted". *Explain this telegram* must give a sensible sentence for group writes, ETS services and secure telegrams. The filter "Encrypted (Data Secure) only" must hide all plain telegrams. (The recognition of secure telegrams is based on the telegram format; whether it matches what ETS shows for the same telegram has to be compared in the ETS group monitor.)
17. **Look:** Settings → Appearance: Light, Dark, Paper, Normal and Large. In Large the text and buttons must stay readable on a projector without cut-off texts.

## Added after 0.7.8 (data and cleanup) - Windows behaviour not tested by the developer

18. **Settings → Data and cleanup:** sizes must match the files in `%LOCALAPPDATA%\vKNX\data` (logs, `*_EC*.state` backups, plan). *Delete logs* must empty `vknx.log` and `console.log` while vKNX runs (this has only been tested on Linux: on Windows the open `console.log` must be emptied, not fail). *Delete backups* removes the `_EC` files and nothing else.
19. **Start a new installation:** with addressed devices and a secure commissioning done, the button must show the warning, and after confirming all devices must be at 15.15.255 with no download; secure devices must show a new certificate. In ETS the old project no longer matches (new FDSK): add the devices again. With the checkbox the floor plan is gone too, without it the plan and placements stay.
20. **Log limit:** make `vknx.log` larger than 10 MB (copy text into it while vKNX is closed) and start vKNX: the old file must be `vknx.log.1` and a new small `vknx.log` must exist.

## Added after 0.7.9 (icons) - the Windows/WebView2 window has not been tried by the developer

21. **Change icons:** device menu **... → Change icons...** opens one row per channel. **Change...** opens the picker (short list for the kind of thing, tabs, search over all icons). Choose an icon for an output: card, device list and floor plan must show it at once; an icon with an "off" variant must change between on and off, other icons must look dimmer when off. **Reset** and "Use the built-in icon" bring the original back.
22. **Icons on the floor plan:** in Edit plan select a symbol, **Icon → Change...**; the symbol and the channel list must show the new icon. Restart vKNX: the icons must still be there (`icons.json` in the data folder). Remove the device: its icons must be gone from `icons.json`. Settings → Data and cleanup → remove floor plan and icons must delete `icons.json` too.
23. **Picker speed:** the first opening loads the 2.7 MB icon list; searching "window" or "blind" must answer without noticeable delay, also in the installed program.

## Added after 0.7.9 (redesign) - Windows/WebView2 and a real ETS session not tried by the developer

24. **Installation, device view:** the header must show name, status and the Programming button at all times; "Next: ..." must name the first open step ("Connect ETS to continue" until ETS is connected, then "Press the programming button", "Assign the physical address in ETS", "Download the application from ETS"). Run a real ETS addressing and download: the steps must tick off, only the current step shows an explanation, and "Ready. Operate it in Simulation." must appear when everything is done. Check the tabs Overview, Parameters, Group objects and Diagnostics (last ETS action, wiring to a line, related telegrams).
25. **Devices | Topology switch** in the device list, **Show in topology** in the Overview tab, and the overview without a selected device ("Next: ..." first, topology fold-out, "Need help connecting ETS?" only while ETS is not connected).
26. **Add device:** search ("secure", "dimmer"), category buttons, Secure badge, detail pane, name field and Add button always visible; add a device and check that the next free name is suggested.
27. **Cards:** the dimmer shows a bar without a handle that cannot be dragged; "How to operate" folds open and closed; a device without an address shows no address text in the footer.
28. **Floor plan:** in Edit plan select a symbol: the properties panel opens and can be closed with the cross; **Plan settings** offers Replace and Remove plan image; Operate shows no side panels.
29. **Monitor:** **Pause view** freezes the list while the status shows "View paused"; the group address list can be folded away and stays folded after a reload; **Show related telegrams** in the device menu filters the monitor and shows the filter chip, which removes the filter with one click.
30. **Settings:** the five sections (Appearance, Connection, Simulation, Maintenance, About) each work; Save and restart in Connection still restarts vKNX.
