# ETS-downloadsekvens og hvad vKNX gør

Kilde: optagelse af en rigtig ETS-download til en referenceenhed (maske 07B0), analyseret tidligere (projektdokument `analyse-zennio-dx1`; referenceenhedens kode/data publiceres ikke). Niveau [K] for sekvensen *som optaget*; vKNX's svar er [T] mod vores mini-ETS, **ikke** mod ETS.

| Trin | ETS sender | vKNX-kode |
|---|---|---|
| 1 | A_IndividualAddress_Read (broadcast) | svarer kun i programmeringstilstand (`readAddress`) |
| 2 | A_IndividualAddress_Write | accepteres kun i programmeringstilstand (`writeAddress`) |
| 3 | A_IndividualAddress_Read (verifikation) | som 1 |
| 4 | T_Connect, A_DeviceDescriptor_Read | `0x07B0` |
| 5 | A_Property_Read (identitet, load-states) | `readProperty` |
| 6 | A_Restart | `restart` (rydder programmeringstilstand) |
| 7 | UNLOAD (event 4) på obj 4,3,2,1 | `loadControl` |
| 8 | START_LOADING (1) + additional load control `[3][0x0B][størrelse u32][doFill][fill]` | `allocate` |
| 9 | Læs TABLE_REFERENCE | svarer med faste slots |
| 10 | A_MemoryExtendedWrite, 50 byte ad gangen | `writeMemory` |
| 11 | Skriv PROG_VERSION på obj 4 | gemmes |
| 12 | LOAD_COMPLETED (2) | `afterTablesChanged()` + `onApplicationLoaded` |
| 13 | Læs MCB_TABLE (PID 27) `[segmentstørrelse u32][CRC-kontrol 0][adgang 0xFF][CRC-16]` | `memoryControlBlock` |
| 14 | A_Restart | persistens |

Load-states: 0 unloaded, 1 loaded, 2 loading, 3 error.

## Tabelformater [K: thelsing/knx; T: egen parsing]
- Adressetabel: u16 antal + u16 GA pr. post
- Associationstabel: u16 antal + (u16 GA-indeks, u16 GO-nummer)
- GO-tabel: u16 antal + 2 byte pr. objekt (flagbyte, værdityp)
- Flagbyte: 0x80 update, 0x40 transmit, 0x20 readOnInit, 0x10 write, 0x08 read, 0x04 comm, 0x03 prioritet.

## Kendte usikkerheder mod ETS 6.4
1. Præcis opbygning af additional load control for vores produkt-XML.
2. Om ETS verificerer MCB/CRC som vi forventer.
3. Om ETS forventer yderligere load-procedurer (fx hardware-type-sammenligning, MergeId 1).
Disse skal afklares med log fra rigtig ETS (se `ets-test-guide.md`).
