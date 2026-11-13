# -*- coding: utf-8 -*-
"""Fetch and verify the offline speech models for the Windows installer build."""
from __future__ import annotations
import hashlib
import shutil
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

    # Turkish small-tr-0.3 is an older Vosk v1 model: final.mdl and mfcc.conf
    # are in the model root (not in am/ as newer v2 models are).
    final_models = list(MODELS.rglob("final.mdl"))
    valid_models = [p for p in final_models
                    if (p.parent / "mfcc.conf").is_file()
                    or (p.parent / "am" / "final.mdl").is_file()]
    if not valid_models:
        raise RuntimeError("Vosk archive has no valid model: expected final.mdl + mfcc.conf.")
    final_model = valid_models[0]
    model_root = final_model.parent.parent if final_model.parent.name.lower() == "am" else final_model.parent
    if target.exists():
        shutil.rmtree(target)
    if model_root.resolve() != target.resolve():
        shutil.move(str(model_root), str(target))
    # Vosk supports both legacy flat v1 and nested v2 model layouts.
    valid_flat = (target / "final.mdl").is_file() and (target / "mfcc.conf").is_file()
    valid_nested = (target / "am" / "final.mdl").is_file() and (target / "conf" / "model.conf").is_file()
    if not (valid_flat or valid_nested):
        raise RuntimeError("Vosk model extraction is incomplete (legacy or v2 layout).")

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

def prepare_greeting() -> None:
    """Pre-render the welcome line so it is audible before the live TTS model loads."""
    from pocket_tts_turkish import TurkishTTS, save_wav
    model_dir = MODELS / "pocket-tts-turkish"
    output_dir = ROOT / "assets" / "audio"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_file = output_dir / "seyfi_greeting.wav"
    if output_file.is_file() and output_file.stat().st_size > 10000:
        print("Seyfi Usta startup greeting already rendered.")
        return
    print("Rendering Seyfi Usta Turkish startup greeting...")
    tts = TurkishTTS.from_pretrained(source=str(model_dir), device="cpu")
    text = ("Merhaba, ben Seyfi Usta. MOTO DIAG PRO çevrimdışı teşhis sistemine hoş geldiniz. "
            "Şimdi bağlantı cihazınızı kontrol ediyorum. Bana seslenerek arıza taraması "
            "yapmamı, canlı verileri yorumlamamı veya test ekranını açmamı söyleyebilirsiniz.")
    audio = tts.generate(text, voice="male_1", emotion="sakin")
    save_wav(str(output_file), audio, tts.sample_rate)
    # Windows winsound needs standard PCM WAV, not IEEE-float WAV.
    import soundfile as sf
    rendered, rate = sf.read(str(output_file), dtype="float32")
    sf.write(str(output_file), rendered, int(rate), subtype="PCM_16", format="WAV")
    if not output_file.is_file() or output_file.stat().st_size < 10000:
        raise RuntimeError("Startup greeting audio was not generated correctly.")

if __name__ == "__main__":
    prepare_vosk()
    prepare_tts()
    prepare_greeting()
    print("Offline Turkish voice assets are ready.")
