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
