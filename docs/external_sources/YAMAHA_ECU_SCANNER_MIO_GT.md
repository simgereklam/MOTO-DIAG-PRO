# YAMAHA ECU SCANNER MIO GT — source review

- Source: https://github.com/AdrianVallenz/YAMAHA-ECU-SCANNER-MIO-GT
- Reviewed: 2026-11-12
- Default branch: main
- Language: Python
- License: none declared
- Decision: research reference only; do not integrate protocol commands or calibration/write features into MOTO DIAG PRO.

## Scope and potential relevance

This repository publishes a modular Python implementation. It explicitly targets Yamaha Mio GT 115cc YMJET-FI, model years 2013–2015, ECU part numbers 54P-H591A-00 / 2BJ-H591A-00. It claims Yamaha Y-DIS over K-Line (ISO 14230/KWP2000), 10400 baud default, with a serial K-Line adapter.

Published modules include K-Line serial adapter and Arduino/ESP32 bridge firmware; Y-DIS framing, initialization and checksum routines; DTC read/clear and DIAG item query services; live telemetry parser and CSV logging; CO trim / RPM limiter access and local mapping-profile files; mock ECU adapter and a small test suite.

The DTC map contains 17 numeric codes (12, 13, 14, 15, 16, 21, 22, 24, 28, 30, 33, 39, 41, 42, 44, 46, 50). These are author-provided Mio GT definitions, not independently verified factory tables.

## Critical validation gaps found

1. Protocol not independently validated. Frame formats and command constants are hard-coded; no trace captures, official Yamaha protocol reference, or verified physical ECU test report is supplied. Mock tests repeat the same assumed protocol and cannot validate a real ECU.
2. Fast-init implementation is incomplete/uncertain. It sends a serial BREAK pulse and then a framed 0x81 command; the full electrical timing/initialization sequence is not demonstrated on the target ECU.
3. Response parsing needs hardening. parse_response_frame accepts extra trailing bytes after the declared frame length. The DTC parser does not reject truncated count fields or verify that all declared codes arrived.
4. Telemetry format is an assumption. SensorData.from_raw_bytes hard-codes a 14-byte layout and derived conversions (TPS voltage/angle, O2 lambda, fuel consumption, status bits). No captured raw frame or official definition proves these offsets/scaling for the stated ECU.
5. DIAG item metadata is not established. The d01/d03/d05/d08/d09 labels, units and ranges need cross-checking against the exact Mio GT service manual and ECU version.
6. DTC definitions may be overgeneralized. Explanations and probable causes have no source citations. Never infer a failed part from a code alone.
7. Write/tuning functions are unsafe to reuse. The project exposes CO trim changes, calibration/map editing, raw Y-DIS terminal access and actuator output tests. These are ECU-changing or physically actuating operations and are excluded from MOTO DIAG PRO.
8. Live stream error handling silently catches all exceptions, potentially hiding disconnections and invalid frames.
9. No license is declared. Do not copy source code, DTC text, firmware, screenshots or other assets without permission.

## Compatibility with MOTO DIAG PRO

- Expects a USB serial K-Line adapter on a COM port with a suitable 12V K-Line transceiver (e.g. L9637D/MC33660); it is not an ELM327/Viecar Wi-Fi client.
- Keep the current Viecar VP002 path (Wi-Fi ELM-compatible adapter, 192.168.0.10:35000/OBD) unchanged.
- Scope is Mio GT 115cc YMJET-FI 2013–2015 only; it does not establish compatibility with NMAX, XMAX, R-series, R9 or other Yamaha models.
- No Android/Kotlin implementation is supplied.

## Recommended use

Keep as a research lead for a possible separate future K-Line transport. Before implementation, obtain exact ECU identification, real-ECU raw request/response captures for initialization/DTC/DIAG/live data, independent checksum validation, service-manual cross-checks, and an explicit code license/permission.

For MOTO DIAG PRO, retain read-only safety boundaries. Do not add CO trim writes, map flashing/editing, raw command console or actuator activation based on this repository. No current Viecar code or APK source was changed, and no APK build was run.
