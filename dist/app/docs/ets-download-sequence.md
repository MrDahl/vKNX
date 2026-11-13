# ETS download sequence and what vKNX does

Source: a recording of a real ETS download to a reference device (mask 07B0), analysed earlier (project document `analyse-zennio-dx1`; the reference device's code/data is not published). Level [S] for the sequence *as recorded*; vKNX's answers are verified [T] against our mini-ETS and, for the products tried so far, against ETS 6.4.

| Step | ETS sends | vKNX code |
|---|---|---|
| 1 | A_IndividualAddress_Read (broadcast) | answers only in programming mode (`readAddress`) |
| 2 | A_IndividualAddress_Write | accepted only in programming mode (`writeAddress`) |
| 3 | A_IndividualAddress_Read (verification) | as 1 |
| 4 | T_Connect, A_DeviceDescriptor_Read | `0x07B0` |
| 5 | A_Property_Read (identity, load states) | `readProperty` |
| 6 | A_Restart | `restart` (clears programming mode) |
| 7 | UNLOAD (event 4) on obj 4,3,2,1 (full download only) | `loadControl` |
| 8 | START_LOADING (1) + additional load control `[3][0x0B][size u32][mode][fill]` | `allocate` – mode 1 initialises the segment, mode 0 (partial download) keeps its content |
| 9 | Read TABLE_REFERENCE | answers with fixed slots |
| 10 | A_MemoryExtendedWrite, up to 10 data bytes at a time (device max APDU length) | `writeMemory` |
| 11 | Write PROG_VERSION on obj 4 | stored |
| 12 | LOAD_COMPLETED (2) | `afterTablesChanged()` + `onApplicationLoaded` |
| 13 | Read MCB_TABLE (PID 27) `[segment size u32][CRC control 0][access 0xFF][CRC-16]` | `memoryControlBlock` |
| 14 | A_Restart | persistence |

Load states: 0 unloaded, 1 loaded, 2 loading, 3 error.

A **partial download** (only changed parameters) skips the unload, allocates with mode 0 and writes only the changed bytes. Until 0.3.0 vKNX zeroed the segment on every allocation, which lost all unchanged parameters.

## Table formats [S: thelsing/knx; T: own parsing]
- Address table: u16 count + u16 group address per entry
- Association table: u16 count + (u16 group address index, u16 group object number)
- Group object table: u16 count + 2 bytes per object (flag byte, value type)
- Flag byte: 0x80 update, 0x40 transmit, 0x20 readOnInit, 0x10 write, 0x08 read, 0x04 comm, 0x03 priority.

## Remaining uncertainties against ETS 6.4
1. Exact structure of the additional load control for products with several parameter pages (multisensor v2).
2. Whether ETS verifies the MCB/CRC the way we expect for every product.
3. Whether ETS expects further load procedures (e.g. hardware type comparison, MergeId 1).
Clarify these with logs from real ETS (see `ets-test-guide.md`).
