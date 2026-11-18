# OBD-II

The engine ECU answers standard OBD-II (SAE J1979) requests on the same bus.

| | |
|---|---|
| Request | `0x7DF` (functional) or `0x7E0` (engine ECU) |
| Response | `0x7E8` |
| Framing | ISO 15765-4, 11-bit, 500 kbit/s |
| Modes that answered | 01, 03, 09 |

A request for PID 0x0C looks like `7DF: 02 01 0C 00 00 00 00 00`, and the answer like
`7E8: 04 41 0C 17 0D 00 00 00`. Every value below comes from a response we captured; the full
list with example data is in [signals.md](signals.md#obd-ii-mode-01).

The survey of PIDs was done with the bike parked; engine speed and coolant were also polled
during short low-speed runs to compare them with the broadcast frames. Do not send requests
while riding without a reason like that: the broadcast
frames already carry speed, rpm, throttle and coolant temperature at a much
higher rate.

The test bike has an aftermarket full exhaust system without a catalytic converter. The example values on this page (fuel
trims, oxygen sensor voltage, intake pressure, load) are from that bike and should not be
taken as reference values for a standard one.

## Which PIDs are supported

The ECU declares its supported PIDs in the usual bit masks:

| Request | Answer | PIDs declared |
|---|---|---|
| 01 00 | `BE 3E B0 11` | 01, 03, 04, 05, 06, 07, 0B, 0C, 0D, 0E, 0F, 11, 13, 14, 1C, 20 |
| 01 20 | `80 00 20 01` | 21, 33, 40 |
| 01 40 | `CA C8 00 41` | 41, 42, 45, 47, 49, 4A, 4D, 5A, 60 |
| 01 60 | `08 00 00 00` | 65 |

## What is worth reading

Most of the list duplicates the broadcast frames. These do not:

| PID | Quantity | Note |
|---|---|---|
| 0x42 | Control module voltage (the bike's supply voltage) | `(256 × A + B) / 1000` V. Reads 0.000 with the engine off. The only voltage on the bus. |
| 0x0B | Intake manifold pressure | kPa |
| 0x33 | Barometric pressure | kPa |
| 0x0F | Intake air temperature | °C |
| 0x0E | Ignition timing advance | degrees |
| 0x06, 0x07 | Short- and long-term fuel trim | The ECU returns two data bytes; standard decoding uses the first. |
| 0x14 | Oxygen sensor voltage | `A / 200` V |
| 0x04 | Calculated engine load | % |
| 0x49, 0x4A | Accelerator position sensors | % |

Coolant temperature (0x05) and engine speed (0x0C) were used to calibrate the broadcast
frames 0x23E and 0x209.

## What is not there

- **Fuel level** (0x2F), **odometer** (0xA6), **oil temperature** (0x5C), **engine run time**
  (0x1F), **ambient air temperature** (0x46) and **fuel type** (0x51) are not declared. 0x2F
  and 0xA6 were requested anyway: NRC 0x12 on the first sessions, no answer on later ones.
  0x5C, 0x1F, 0x46 and 0x51 were never requested.
- **PID 0x1C** (OBD standard) returns 0x25. We have not matched that code to a standard.
- PIDs 0x01, 0x03, 0x13, 0x21 and 0x41 are declared and answer; we have not looked at them.

## Mode 03 and mode 09

- **Mode 03** (stored fault codes) answered `43 00`: no codes on the test bike.
- **Mode 09, 0x04** (calibration ID): `12165003`, the same number as UDS identifier 0xF194.
- **Mode 09, 0x06** (calibration verification number): `06 90 FF FF`. The ECU first sends
  several "response pending" frames (NRC 0x78); allow 4 seconds.
- **Mode 09, 0x0A** (ECU name): `ECM`, a zero byte, `-EngineControl`.
- **Mode 09, 0x02** (VIN) is not declared. The VIN is available over UDS, see [uds.md](uds.md).

Multi-frame answers use standard ISO-TP: after the first frame (`10 ..`) the tester must send
a flow-control frame (`30 00 00`) to the ECU's request ID before the rest arrives.
