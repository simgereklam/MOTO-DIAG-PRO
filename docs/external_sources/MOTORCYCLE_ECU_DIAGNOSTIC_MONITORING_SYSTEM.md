# Motorcycle ECU Diagnostic & Monitoring System — source review

- Source: https://github.com/TanakonKetsakron/Motorcycle-ECU-Diagnostic-Monitoring-System
- Reviewed: 2026-11-12
- Decision: reference only; do not import code or protocol tables.

## What the repository actually contains

This is a public project showcase, not a source-code repository. Its README explicitly says the firmware and Flutter app source are private. The visible tree contains only README.md, docs/ARCHITECTURE.md, and screenshots. No firmware, Flutter/Dart implementation, protocol vectors, ECU maps, DTC database, or downloadable build is exposed.

The author describes a real ESP32 smart gauge plus Flutter companion app for Honda PGM-FI and Yamaha KWP2000 motorcycles over K-line (ISO 9141 / ISO 14230). The gauge is described as detecting the protocol, polling live data, reading/clearing DTCs, validating checksums, and forwarding data to the phone over BLE or Wi-Fi UDP.

## Potentially useful design ideas

- Keep a single owner/task responsible for the K-line bus; queue UI diagnostic requests rather than allowing concurrent bus access.
- Use an explicit connection state machine (scanning, connecting, pairing, ready) with visible recovery hints.
- Represent missing ECU values as unavailable, not as numeric zero.
- Validate response checksums before accepting decoded values.
- Separate transport, protocol parsing, connection/state management, UI, alerts, and logging.
- Queue spoken alerts so a new alert does not interrupt an existing one.
- Keep ECU commands on a fixed, read-only whitelist; avoid write/control operations.
- Capture raw ECU data and mark real-world events to help research unknown signals.

These are architecture-level ideas only; there is no public implementation or message-level detail to reuse.

## Limitations and compatibility with MOTO DIAG PRO

- The repository does not publish the K-line wake-up sequences, request/response bytes, service IDs, PID/local-ID maps, checksum algorithms, ECU address maps, or model/year coverage.
- The claimed 80+ Honda DTC descriptions are not included.
- The project uses a custom ESP32 gauge/transceiver and its own BLE/Wi-Fi link. It is not a ready-made Android client for our current Viecar VP002 Wi-Fi ELM-compatible path.
- The Flutter application and ESP32 firmware source are private; screenshots and architecture diagrams cannot be integrated as executable code.
- No license is declared in repository metadata and the README reserves all rights. Do not copy its screenshots, code, protocol data, or other assets into MOTO DIAG PRO without permission.

## MOTO DIAG PRO decision

Do not change the current Viecar connection path and do not claim Honda/Yamaha K-line support based on this showcase. The useful next step would be obtaining permission and a public/licensed technical specification from the author: supported model/year list, ECU protocol initialization, exact diagnostic requests/responses, checksum rules, DTC definitions, and test logs. Any future implementation must be validated on the exact motorcycle/ECU before being enabled.

The architecture principles above may inform future refactoring, but this repository contributes no directly integrable diagnostic data or code.
