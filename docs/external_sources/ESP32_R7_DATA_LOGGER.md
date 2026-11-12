# External source review: ESP32-R7-Data-Logger

- Upstream: https://github.com/davidrfloydii/ESP32-R7-Data-Logger
- Reviewed branch: main
- Repository description: ESP32 telemetry/data logger for Yamaha R7, intended to combine motorcycle data with 25 Hz GNSS.
- Review status: source architecture reference only; not a CAN decoder or DTC source.
- License: no LICENSE file / repository license metadata was present at review time. Do not copy or adapt upstream source code until its license is clarified.

## What the checked-in source actually implements

Files reviewed:
- README.MD
- platformio.ini
- src/globals.h
- src/main.cpp
- src/gnssHelper.h
- src/sdHelper.h
- src/webPage.h

Implemented:
- ESP32 Arduino / PlatformIO project.
- u-blox GNSS over I2C using SparkFun u-blox GNSS Arduino Library.
- GNSS navigation frequency configured with setNavigationFrequency(50).
- GNSS fields collected for CSV logging: latitude, longitude, altitude, ground speed, date/time and epoch-derived value.
- SD_MMC storage and log file naming based on GNSS date/time.
- A mode-selection input (GPIO 33) selects recording versus an intended access-point mode.

Not implemented in the reviewed source:
- No CAN controller/driver initialization.
- No CAN frame receive loop or CAN ID parsing.
- No Yamaha R7 CAN signal definitions or scaling formulas.
- No ECU diagnostic session, DTC read/clear, or fault-code database.
- CAN_RX (GPIO 16) and CAN_TX (GPIO 17) are only macros in globals.h; they are not used by a CAN stack in the reviewed files.
- The web page helper exists, but the reviewed main.cpp does not start a Wi-Fi access point or HTTP server.

## MOTO DIAG PRO integration decision

- Do not add this project as a Yamaha R7 CAN profile.
- Do not import its code while the upstream license is unspecified.
- Its general logging concept can inform a future independent MOTO DIAG PRO trip/session recorder: timestamp, GNSS position, speed, and synchronized ECU samples.
- Keep that future recorder separate from the current Viecar VP002 Wi-Fi ELM connection and current ECU diagnostic flow.
- No DTC definitions or verified R7 CAN signals were extracted from this repository.

## Engineering cautions observed

- Startup waits indefinitely for a GNSS 3D fix before SD initialization; this is unsuitable as-is for a diagnostic app.
- SD initialization failure can halt logging.
- The CSV header advertises fields that do not clearly match the row formatter; verify column count and units before reusing the format.
- The GNSS source comments and configured navigation frequency should be checked against actual module capability and measured output rate.
