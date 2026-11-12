# External source review: Yamaha RL78 R5F10PLH BIN files

- Upstream: https://github.com/m44rif/FILE-BIN-YAMAHA-RL78-R5F10PLH
- Default branch: main
- Repository description: BIN files for Yamaha ECUs using Renesas RL78 R5F10PLH.
- Latest repository push observed: 2025-01-05.
- License: GitHub reports no declared license; no license file or reuse permission was identified.
- README note (Indonesian): the BIN files need checksum recalculation; it mentions HxD or a future BIN-to-MOT converter with automatic checksum.

## Contents observed

The repository contains 14 BIN paths, each 27,648 bytes:

- GRANDE_B8A0.bin
- GRANDE_B8A1-40.bin
- GRANDE_B8A1.bin
- GRANDE_B8B0.bin
- GRANDE_B8B0_31.bin
- GRANDE_B8B0_THAI.bin
- GRANDE_B8B1_41.bin
- MT_15_B8D0.bin
- MT_15_BHM0.bin
- MX_KING_2PV1.bin
- MX_KING_B5V0.bin
- MX_KING_BKV1.bin
- WR155_B3M0.bin
- YZF-R125_B5G0.bin

Some paths are byte-identical duplicates according to the Git tree blob IDs. The README is only a checksum warning. No model-year mapping, ECU part numbers, readout provenance, protocol documentation, memory map, DTC table, OBD PID definitions, CAN identifiers, or diagnostic procedures are supplied. The binaries were not downloaded, modified, checksum-corrected, or tested.

## Technical and safety assessment

These are firmware/calibration image files, not diagnostic data. A filename alone does not establish exact motorcycle compatibility or ECU hardware/software revision. The upstream README explicitly warns that checksums need recalculation. An incorrect or incompatible image, bad checksum, interrupted write, or wrong ECU target can render an ECU unusable. The repository provides no validated checksum algorithm, recovery procedure, or provenance for each dump.

## MOTO DIAG PRO decision

- Do not bundle, write, patch, checksum-correct, or otherwise use these BIN files in MOTO DIAG PRO.
- ECU flashing and firmware updates remain out of scope.
- Do not derive DTC definitions or live-data decoding from these filenames or opaque binaries.
- No diagnostic protocol/profile or validated service data can be added from this repository.
- This review does not change the current Viecar VP002 Wi-Fi OBD path or the passive Yamaha R9 CAN decoder.
- Reconsider only if the author supplies licensed, inspectable diagnostic protocol documentation or verified non-firmware diagnostic data. Any such data must be validated independently.
