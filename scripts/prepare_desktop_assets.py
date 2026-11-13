# -*- coding: utf-8 -*-
"""Fetch and verify the offline speech models for the Windows installer build."""
from __future__ import annotations
import hashlib
import urllib.request
import zipfile
from pathlib import Path
from huggingface_hub import snapshot_download

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "assets" / "models"
VOSK_NAME = "vosk-model-small-tr-0.3"
VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
VOSK_SHA256 = "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5"

def prepare_vosk():
    target = MODELS / VOSK_NAME
    if (target / "am").is_dir():
        print("Vosk Turkish model already prepared.")
        return
    MODELS.mkdir(parents=True, exist_ok=True)
    archive = MODELS / (VOSK_NAME + ".zip")
    print("Downloading Vosk Turkish offline recognition model...")
    urllib.request.urlretrieve(VOSK_URL, archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != VOSK_SHA256:
        archive.unlink(missing_ok=True)
        raise RuntimeError(f"Vosk model SHA-256 mismatch: {digest}")
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(MODELS)
    archive.unlink(missing_ok=True)
    if not (target / "am").is_dir():
        raise RuntimeError("Vosk model extraction did not produce the expected folder.")

def prepare_tts():
    target = MODELS / "pocket-tts-turkish"
    if (target / "config.yaml").is_file():
        print("Pocket TTS Turkish model already prepared.")
        return
    print("Downloading Pocket TTS Turkish offline synthesis model...")
    snapshot_download(repo_id="wite-tech/pocket-tts-turkish", local_dir=str(target),
        allow_patterns=["config.yaml", "model.safetensors", "tokenizer.model",
                        "voices/*.wav", "README.md", "LICENSE*"])
    if not (target / "config.yaml").is_file() or not (target / "model.safetensors").is_file():
        raise RuntimeError("Pocket TTS model files are incomplete.")

if __name__ == "__main__":
    prepare_vosk()
    prepare_tts()
    print("Offline Turkish voice assets are ready.")
