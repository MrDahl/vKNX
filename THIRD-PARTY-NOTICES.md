# Third-party components

vKNX's own code is under MIT (see `LICENSE`). The following is not vKNX code and has its own licenses:

| Component | License | How it is used |
|---|---|---|
| Calimero (core, device, server), pinned commits in `third_party/PINS.txt` | GPLv2 + Classpath exception | Fetched at build time; included as library jars in the Windows package. The Classpath exception allows code that links to Calimero to use another license. Calimero's license text comes with the sources in `third_party/src` after `fetch-calimero.sh`. One local patch is published in `third_party/patches/`. |
| Java runtime (OpenJDK) | GPLv2 + Classpath exception | Bundled by jpackage in the Windows package. |
| OpenKNXproducer | GPL-3.0 | External tool the user downloads; not part of vKNX. |
| Material Design Icons (Pictogrammers, `@mdi/js` 7.4.47) | Apache-2.0 (Pictogrammers Free License) | A few icon paths are embedded as inline SVG in `core/resources/web/index.html`; no network access needed. Source: https://pictogrammers.com/library/mdi/ |
| ETS (KNX Association) | Commercial | Not included. |

KNX and ETS are trademarks of KNX Association. vKNX is independent and not certified.
No code from KNX Explorer (the owner's non-commercial license) or Zennio has been copied in.

The Windows application also uses (installed builds only): Microsoft Edge WebView2 (Microsoft Software License Terms; the runtime is part of Windows 10/11) and the NuGet package `Microsoft.Web.WebView2` (BSD-3-Clause-like license), plus Inno Setup (Inno Setup License) for the installer.
