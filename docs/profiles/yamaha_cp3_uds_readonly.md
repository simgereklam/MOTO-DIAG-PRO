# UDS diagnostics (read-only)

Two control units answer UDS (ISO 14229) requests on the diagnostic connector. We used
ReadDataByIdentifier (`0x22`) after DiagnosticSessionControl (`0x10 01` default, `0x10 03`
extended), with TesterPresent (`0x3E`), and nothing else. No
security access was attempted, and this repository will not document any.

The identifiers found are identification data and two counters. No live sensor values and no
odometer were found over UDS.

## Who answers

One pass over the request IDs from 0x700 to 0x7FF (0x7E6 and 0x7E7 excepted) with a default-session request:

| Request | Response | Unit | Note |
|---|---|---|---|
| 0x7E0 | 0x7E8 | Engine ECU | Supplier ID `DEN`, system name `B5U1` |
| 0x7E3 | 0x7EB | ABS unit | Supplier ID `BOSC`, system name `B5U1` |
| 0x7E6 | 0x7EE | Unidentified | Answered a functional `0x7DF` request with `02 50 01`. Not addressed directly. |
| 0x740 | 0x760 | Unidentified | Answers `03 7F 10 33`: security access denied, even for a default session |
| 0x7E1, 0x7E2, 0x7E4, 0x7E5 | — | — | No answer |
| 0x7E7 | — | — | Not probed. We skipped it as a precaution. |

Everything else in 0x700–0x7FF stayed silent. The instrument cluster did not identify itself
on any address.

## Identifiers

`0x22` requests, default session. The full list is in
[signals.md](signals.md#uds-data-identifiers-read-only).

| DID | Engine ECU (0x7E0) | ABS unit (0x7E3) |
|---|---|---|
| 0xF190 VIN | 17 ASCII characters | not supported |
| 0xF18A supplier | `44 45 4E 00` ("DEN") | `42 4F 53 43` ("BOSC") |
| 0xF18C serial number | `FF FF FF FF` | `00 00 00 00` |
| 0xF192 hardware number | `FF FF FF FF` | `00 00 00 00` |
| 0xF194 software number | `12 16 50 03` | `12 16 51 80` |
| 0xF195 software version | `03` | `00` |
| 0xF197 system name | `42 35 55 31` ("B5U1") | `42 35 55 31` ("B5U1") |
| 0xF1A0 | `0C 01 00 05` | `0C 01 00 02` |
| 0xF1A1 | 32-bit seconds counter | not supported |
| 0xF1A2 | 16-bit slow counter | not supported |
| 0xF1EF | `00 00` | not supported |
| 0xF198, 0xF199 | NRC 0x22 conditionsNotCorrect | — |

Notes:

- "DEN" and "BOSC" are what the units return. Reading them as Denso and Bosch is our
  inference.
- **0xF1A1** counts seconds with the ignition on: it rose by 85 in the 84.3 s between two reads,
  and by 14 638 s (about four hours) over four days of occasional use.
- **0xF1A2** is constant within a session and a few counts higher some days later. It may count
  ignition cycles. Hypothesis.
- **0xF194** on the engine ECU is BCD for 12165003, the same number OBD-II mode 09 returns as
  the calibration ID.

## What was swept

On both units: 0xF180–0xF1FF, 0x2000–0x23E7, 0xF000–0xF0FF and 0xFD00–0xFDFF (1640
identifiers per unit), in the default and the extended session. Everything not in the table answered NRC 0x12. The extended session
(`10 03`) returned the same answers as the default one.

None of the answers contains the odometer reading in any common encoding. See
[open-questions.md](open-questions.md).

## Timing

The whole scan, about 6500 requests across both units and both sessions, took eight minutes
and ran without a timeout or a bus error. It was done with the ignition on and the engine off.
