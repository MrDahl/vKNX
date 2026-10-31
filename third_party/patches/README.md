# Local patches for Calimero
Applied automatically after fetching the pinned commits (`tools/fetch-calimero.sh`, `tools/build.ps1`).
Calimero is GPLv2 + Classpath exception; the patches are published here.

| Patch | Why |
|---|---|
| `calimero-server-flat-network.patch` | Calimero's KNXnet/IP server acts as one line and drops telegrams to addresses outside the server's own line (1.1.x). With `-Dvknx.flatNetwork=true` (set by vKNX) devices can have any area/line, so ETS can download to e.g. 2.3.4. The same flag lets ETS give the KNXnet/IP interface any individual address (tunneling feature `IndividualAddress`), as on a real interface. |
