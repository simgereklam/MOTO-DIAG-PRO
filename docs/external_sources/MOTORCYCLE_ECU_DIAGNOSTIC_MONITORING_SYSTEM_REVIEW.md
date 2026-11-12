# Motorcycle ECU Diagnostic & Monitoring System — Source Review

Reviewed repository: https://github.com/TanakonKetsakron/Motorcycle-ECU-Diagnostic-Monitoring-System
Reviewed default branch: `main`
Review date: 2026-11-12

## Executive conclusion

This is a promising *architecture and product reference* for a future Honda/Yamaha K-Line diagnostic path, but it is **not directly integrable** into MOTO DIAG PRO from the public repository. The author explicitly states that implementation source is private; only a showcase README, high-level architecture document and screenshots are public. The repository has no declared license. Do not copy code, protocol maps, screenshots or other assets into MOTO DIAG PRO.

The project description says the gauge and Flutter app work on real Honda PGM-FI and Yamaha KWP2000 motorcycles. That is an author claim, not independently verifiable from the public source: no firmware, app source, raw ECU captures, model/ECU compatibility table, test logs or hardware schematics are published.

## What the public documentation describes

- ESP32 gauge with FreeRTOS tasks and a K-Line transceiver.
- Honda PGM-FI and Yamaha KWP2000 support, with protocol auto-detection or user-selected brand.
- Live values including RPM, speed, gear, temperatures, battery, AFR and fuel trims.
- DTC read/clear; README advertises 80+ Honda codes.
- BLE transport and optional Wi-Fi UDP from gauge to Flutter phone app.
- Dashboard, sensor page, live graph, DTC page, ride logging to CSV and raw ECU explorer.
- Thai spoken alerts and configurable thresholds.
- Documented challenge-response pairing using HMAC-SHA256, replay protection and lockout.
- Architecture says ECU commands are read-only and restricted to a fixed whitelist, while also describing DTC clear. Exact command whitelist and clear semantics are not public.

## Important limitations and verification gaps

1. **No source code:** the README explicitly says source is private. Protocol framing, init sequences, checksum algorithms, parsers, DTC definitions and sensor formulas cannot be inspected.
2. **No license:** repository metadata reports no license; README states all rights reserved. Treat it as reference-only. Do not copy implementation or images.
3. **Insufficient compatibility detail:** no supported Honda/Yamaha model years, ECU part numbers, connector pinouts, baud rates, wake-up timing or transceiver schematic are disclosed.
4. **No public raw captures:** claims that byte layouts/formulas were verified against ride logs cannot be reproduced or independently checked from this repository.
5. **DTC clear needs careful treatment:** documentation mentions read/clear but does not publish exact services, confirmation flow, persistence behavior or read-back validation. Keep clear operations gated and report the actual ECU response.
6. **Demo mode exists in the mobile architecture:** the Flutter app supports BLE, Wi-Fi and built-in demo transport. Any future evaluation must keep demo data visibly separated from live ECU data.
7. **Hardware differs from current setup:** this system uses an ESP32 K-Line gauge and BLE/Wi-Fi link to the phone. It is not an ELM327/Viecar VP002 Wi-Fi adapter implementation.

## Relevance to MOTO DIAG PRO

| Area | Assessment |
|---|---|
| Honda PGM-FI / Yamaha KWP2000 | Useful future research direction, but not enough public technical detail to implement |
| Live data / DTC screens / graphs / CSV logs | Useful feature and UX reference |
| ECU explorer / raw byte capture | Valuable future research concept |
| BLE / Wi-Fi transport | Different architecture from current direct Viecar VP002 connection |
| Offline operation | Potentially compatible in principle; public docs do not establish all app behavior |
| DTC database | “80+ Honda codes” is a claim; no public list or provenance |
| Firmware/code reuse | Not permitted on the evidence available; no license and source private |

## Integration decision

- **Do not change the current Viecar VP002 path.** The public project uses a separate ESP32 K-Line gauge architecture and does not document an ELM327/Viecar transport.
- Do not add unverified K-Line init sequences, DTC mappings, sensor scaling or active ECU commands based on this showcase.
- Keep this repository as a design reference for a later, separately validated Honda/Yamaha K-Line adapter profile.
- Before implementation, require actual model/ECU identifiers, connector and transceiver details, protocol traces, checksum examples, sensor scaling evidence, DTC read/clear request-response captures, and permission/licensing for any material intended for reuse.
- Preserve the existing no-ECU-flashing/no-firmware-writing scope.

## Sources inspected

- `README.md`
- `docs/ARCHITECTURE.md`
- Repository metadata and recursive file tree on `main`

Public repository: https://github.com/TanakonKetsakron/Motorcycle-ECU-Diagnostic-Monitoring-System
