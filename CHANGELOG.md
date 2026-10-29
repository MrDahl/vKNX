# Changelog

Alle væsentlige ændringer i vKNX. Versionsnumre følger `v<major>.<minor>.<patch>`.
Teksten under hver version vises også som release-noter på GitHub og i programmets "Søg efter opdateringer".

## [0.2.0] - 2026-10-29
Første versionerede udgave med installer og opdateringsknap.

### Nyt
- **Windows-program:** installer (`vKNX-Setup-0.2.0.exe`) med startmenu-genvej og afinstallering, installeres uden administratorrettigheder. vKNX kører i ét vindue (WebView2) – ingen konsol og ingen separat browser.
- **Søg efter opdateringer:** knappen under Indstillinger (tandhjulet) spørger GitHub efter nyere version, viser ændringerne og kan hente og installere den.
- **Interface-linje til ETS:** Indstillinger → "Interface til ETS". Skriv fx `2.9`, så interfacets adresse (2.9.241) passer til en linje i dit ETS-projekt og ETS ikke klager.
- **Plantegning:** zoom ind og ud (30–300 %) efter upload; zoomniveauet gemmes.
- Nyt, mindre og mere overskueligt kontrolpanel; mørkt/lyst tema; Material Design Icons med faste farver; dæmpbart lys med omrids der toner ind.
- Enheder kan have vilkårligt område og linje (fladt netværk).

### Rettet
- Skyderen på skydedæmper-trykket opdateres nu, også efter at den har været brugt (fokus på skyderen blokerede opdateringen).

### Kendt / ikke testet
- ETS-kompatibilitet er bekræftet af ejeren for: tryk, kontaktaktuator, dæmper, skydetryk og multisensor (import, adressering, download). Persienne, vippe og 4-/8-kanals aktuatorer er endnu ikke prøvet i ETS.
- Installer og automatisk opdatering er kun testet på GitHubs Windows-maskine, ikke på skole-pc'er. Programmet er ikke kodesigneret, så Windows kan vise en SmartScreen-advarsel.
- Linjekobler (filtertabel, hop count) er ikke bygget.
