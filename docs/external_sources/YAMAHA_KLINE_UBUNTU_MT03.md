# Yamaha K-line Ubuntu reader — source review

- Source: https://github.com/smiddelkoop/yamaha-kline-ubuntu
- Reviewed: 2026-11-12
- Default branch: main
- License: MIT (includes attribution to upstream reverse-engineering projects)
- Target claimed by author: pre-2015 Euro 3 Yamaha, tested on MT-03 660 (2010)
- Decision: valuable passive-capture research reference; not a drop-in diagnostic transport.

## What is actually implemented

The repository contains a Python 3/Linux passive K-Line listener using a KKL 409.1 USB cable with FTDI FT232RL. It listens to traffic already polled by the motorcycle dashboard; it does not perform an ECU initialization sequence or actively request diagnostic data.

Files: mt03_kline.py (serial reader, live console, raw/CSV logging); kline_protocol.py (stream synchronizer, checksum and telemetry decoder); decode_log.py (offline raw-hex parser and CSV export); examples/ECU_Log_sample.txt (annotated real-capture excerpt); 99-ftdi-kkl.rules (Linux FTDI device rule).

Claimed frame formats:
- 6 bytes: 01 RPM VEL ERR TEMP CHK
- 5 bytes: RPM VEL ERR TEMP CHK
- Checksum: sum of RPM, VEL, ERR, TEMP modulo 256; leading 0x01 excluded.
- RPM: RPM byte multiplied by 50.
- Velocity and temperature are raw / require calibration.
- Default baud: 16064.

The README reports 4630 of 4656 frames (about 99.4%) checksum-valid in the author's original capture. The sample includes immobilizer and idle sequences. These are author-reported observations, not an independent reproduction.

## Useful findings for MOTO DIAG PRO

1. Passive K-Line capture can reveal dashboard-polled ECU telemetry without sending diagnostic requests.
2. The project supplies a real raw capture excerpt and an offline decoding/logging workflow.
3. Checksum-based stream resynchronization is a useful design concept for a future K-Line receiver.
4. It distinguishes status-byte values from DTCs and defaults fault-code interpretation to raw/off. This caution is important.
5. It records raw hex alongside decoded CSV, enabling later comparison and traceability.

## Limitations and code-review cautions

1. Narrow coverage: tested on MT-03 660 (2010); no evidence this frame layout applies to other Yamaha models or ECU generations.
2. Passive only: no ECU initialization, active DTC read/clear, service DIAG request, actuator test or live query protocol is implemented.
3. Not Viecar compatible: it expects a KKL FTDI USB serial interface and direct K-Line access, not the current Viecar VP002 Wi-Fi ELM-style connection.
4. DTC decoding is uncertain: README admits the on-wire error-byte coding is not officially confirmed. The error byte is treated as a status bitmask by default; optional BCD/decimal translation must not be used as a verified DTC channel.
5. DTC table provenance: the source comment says its table came from a user-provided “Core Yamaha ECU Fault Codes” list. It is not a model-specific factory-verified table. Do not import generic code descriptions as universal Yamaha truth.
6. Temperature calibration: temperature is raw byte plus a user-supplied offset; exact scaling/offset is not established.
7. FrameSync accepts a candidate frame when its byte sum matches the final byte. A checksum collision can create false synchronization. Also, decode_frame classifies repeated-payload or over-redline candidates as immo with checksum_ok=True before independently validating their checksum. Treat frame statistics and labels as provisional; harden and test this logic before porting.
8. Speed interpretation: the sample notes an apparent “8 bytes summed” conversion, while the decoder exposes velocity raw. Do not present it as km/h.
9. MIT permits reuse subject to retaining copyright and license notices. The LICENSE attributes the baud rate, frame layout and sample-log format to upstream Yamaha-K-line-Tool and Yamaha-K-line-sniffer projects; preserve those notices and check upstream terms before reusing their material.

## Hardware and safety boundary

This is a different future hardware path: 3-pin diagnostic connector + KKL FTDI cable + suitable K-Line interface. Do not connect an ordinary USB/TTL adapter directly to a motorcycle K-Line wire. Confirm exact bike wiring and use an automotive-rated transceiver/interface.

Keep the current MOTO DIAG PRO Viecar VP002 path unchanged. Do not infer compatibility with NMAX, XMAX, R-series, R9 or Mio GT from this MT-03 capture.

## Recommended use

Retain this as a passive K-Line research reference. If a future compatible Yamaha and KKL interface become available, first capture raw traffic on that exact ECU, validate framing/checksum independently, calibrate each field against known dashboard/service data, and only then add a model-specific passive profile. Keep DTC read/clear as a separate, explicitly validated active diagnostic feature.

No MOTO DIAG PRO runtime code was changed and no APK build was run for this review.