# External source review: Yamaha ECU Flash Program RL78

- Upstream: https://github.com/dr-tech-21/Yamaha-Ecu-Flash-Program-RL78
- Reviewed: 2026-11-12
- Repository default branch: `main`
- Upstream description: Yamaha ECU flash program for Renesas RL78 MCUs.

## Repository contents observed

The repository contains a short Indonesian README, a `GAMBAR/` folder with BMP/JPG images, and two Windows executables:

- `YamahaRL78.exe`
- `YamahaM16C.exe`

The README says the YamahaRL78 license and YamahaM16C are in one folder together with the image folder. No source code, protocol specification, ECU identification table, diagnostic trouble-code data, CAN/OBD PID catalogue, connector pinout, or verifiable service procedure is provided in the repository tree.

## MOTO DIAG PRO decision

**Do not integrate this project into MOTO DIAG PRO.** Its stated purpose is ECU flash programming, while this project explicitly excludes ECU flashing/firmware updates. The repository also provides only opaque executable binaries, with no inspectable implementation or clearly declared repository license. Do not redistribute, execute, reverse engineer, or copy its binaries/assets into this Android project.

This source does not establish support for RL78-based Yamaha ECU diagnostics, DTC reading/clearing, live data, or any specific Yamaha model. MCU family identification alone is not enough to infer a diagnostic protocol or safe ECU access method.

## Possible future research value

If the author later publishes source code and a clear license, a separate review could determine whether any *read-only* ECU identification or diagnostic communication details are documented. Any such details would still require independent verification and must not enable flash/write operations.

## Compatibility and safety boundary

- Current Viecar VP002 Wi-Fi / ELM-style OBD path remains unchanged.
- No flash, erase, write, bootloader, or firmware-update commands are to be added.
- No claim of RL78 ECU diagnostic compatibility is made.
