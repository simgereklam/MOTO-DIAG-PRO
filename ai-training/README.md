# Seyfi Usta – ECU data and machine-learning starter

This folder is the first data-learning layer for MOTO DIAG PRO.

## Data collection
The Android app discovers the Mode 01 PID support bitmaps (0100, 0120, ... 01C0),
then requests every PID the ECU advertises as supported. Known standard PIDs are
decoded into engineering values; unknown/manufacturer-specific PIDs are retained
as raw hex instead of being silently discarded. The app must never treat an
unsupported PID as a fault.

The app currently uses the existing Viecar VP002 Wi-Fi path. The full sweep is
performed during an explicit ECU scan, not on every live-data refresh, so the
live display remains responsive. Only read-only requests are added.

## Training data
The first classifier is intentionally a conventional ExtraTrees model, not an
LLM. It learns patterns from real, labelled ECU sessions. It cannot be honestly
trained until there are enough confirmed examples.

You can also prepare a CSV with one row per time sample and at least these columns:
`session_id,fault_label,timestamp,rpm,tps,coolant,map,voltage,stft,ltft`
Add any other numeric sensor columns you actually captured. Use one stable
`session_id` per ride/session. Label only after a mechanic confirms the cause.
Use `healthy` for verified healthy sessions. Do not label a P-code alone as
proof that a particular component failed.

Install and train:
```bash
python -m pip install -r requirements.txt
python train_model.py --csv labelled_sessions.jsonl --out seyfi_fault_model.joblib
```

The script holds out complete sessions (not random rows) to reduce data leakage.
It refuses to train classes with fewer than three independent sessions. Three
is only a technical minimum; meaningful evaluation needs substantially more
sessions per fault and multiple motorcycles/model-years.

## Safety / limitations
- This is an experimental decision-support tool, not a definitive mechanic.
- No ECU flashing, active actuator commands, or Mode 04 clearing are part of
  this data capture.
- Standard Mode 01 cannot expose every manufacturer-specific motorcycle signal.
  ABS, DCT, immobilizer and proprietary ECU data require validated model-specific
  protocols, addresses and adapter support.
- Raw unknown PID bytes are preserved for later decoding. Never guess units or
  apply car PID formulas to proprietary motorcycle data.


## Seyfi's own Turkish language model (from scratch)

The separate `train_seyfi_transformer.py` script creates a small causal Transformer
with random initialization and its own UTF-8 byte tokenizer. It does not load
Qwen, Gemma, Llama, pretrained weights, or a third-party tokenizer.

Install its separate training dependency:
```bash
python -m pip install -r requirements-transformer.txt
python train_seyfi_transformer.py --data seyfi_turkish_corpus.jsonl --steps 20000 --out seyfi_transformer.pt
```

JSONL input format: one document per line, for example
`{"text":"Motosiklet ..."}`. Use licensed, accurate Turkish language and
motorcycle-service documents. Do not mix unverified repair claims into the corpus.
Training starts from random weights. The checkpoint is not automatically installed
in Android: Android inference, quantization, memory/performance tests, and a
real labelled ECU dataset remain separate required steps. No trained checkpoint
is included or claimed by this repository yet.
