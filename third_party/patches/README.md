# Local patches for Calimero
Applied automatically after fetching the pinned commits (`tools/fetch-calimero.sh`, `tools/build.ps1`).
Calimero is GPLv2 + Classpath exception; the patches are published here.

| Patch | Why |
|---|---|
| `calimero-server-flat-network.patch` | Calimero's KNXnet/IP server acts as one line and drops telegrams to addresses outside the server's own line (1.1.x). With `-Dvknx.flatNetwork=true` (set by vKNX) devices can have any area/line, so ETS can download to e.g. 2.3.4. The same flag lets ETS give the KNXnet/IP interface any individual address (tunneling feature `IndividualAddress`), as on a real interface. |
| `calimero-core-secure-plain-drop.patch` | KNX Data Secure: a device must ignore a plain group telegram sent to a group object that requires security. Calimero passed such telegrams on as ordinary ones. The patch drops them in `SecureApplicationLayer.extract` when the device's group object security flags (`GoSecurityFlags`) for that address are set. Only devices that report such flags are affected (vKNX's secure products after ETS commissioning). |
| `calimero-server-fixed-apdu.patch` | Calimero lets any device-management client write the interface's maximum APDU length (pid 68 of the cEMI server object, pid 56 of the device object). ETS keeps its own copy and wrote 15 back, after which every download that needs long frames (KNX Data Secure: "Insufficient APDU Length, restricted to 15 by capabilities of the bus interface") failed until vKNX was restarted. With `-Dvknx.flatNetwork=true` these two writes are refused (read only). |
