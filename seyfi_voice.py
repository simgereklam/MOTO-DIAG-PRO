# -*- coding: utf-8 -*-
"""Seyfi Usta: fully local Turkish speech I/O.

Runtime models are shipped beside the Windows application:
  assets/models/vosk-model-small-tr-0.3/
  assets/models/pocket-tts-turkish/
No network access is used by this module.
"""
from __future__ import annotations

import json
import os
import queue
import sys
import threading
import tempfile
import wave
from pathlib import Path
from typing import Callable, Optional


def app_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def model_path(name: str) -> Path:
    # PyInstaller one-folder builds keep the models beside the executable.
    return app_root() / "assets" / "models" / name


class SeyfiVoice:
    """Offline TTS + Vosk Turkish command recognition, both CPU-capable."""

    COMMAND_GRAMMAR = [
        "seyfi", "seyfi usta", "merhaba seyfi", "yardım", "komutlar",
        "bağlan", "adaptöre bağlan", "bağlantıyı kes", "bağlantıyı durdur",
        "tekrar bağlan", "yeniden bağlan", "taramaya başla", "arıza tara",
        "arızaları oku", "arızaları sil", "arıza kodlarını sil",
        "canlı verileri oku", "motor verilerini oku", "test yap",
        "testleri aç", "durumu söyle", "rapor oluştur",
        "seyfi usta taramaya başla", "seyfi usta arıza tara",
        "seyfi usta arızaları oku", "seyfi usta canlı verileri oku",
        "seyfi usta bağlan", "seyfi usta bağlantıyı kes", "seyfi usta test yap",
    ]

    def __init__(
        self,
        on_command: Callable[[str], None],
        on_status: Optional[Callable[[str], None]] = None,
        voice: str = "male_1",
        voice_file: Optional[str] = None,
    ):
        self.on_command = on_command
        self.on_status = on_status or (lambda _message: None)
        self.voice_name = voice
        self.voice_file = voice_file
        self._tts = None
        self._voice_state = None
        self._tts_queue: queue.Queue[str | None] = queue.Queue()
        self._audio_queue: queue.Queue[bytes | None] = queue.Queue(maxsize=40)
        self._stop = threading.Event()
        self._greeting_finished = threading.Event()
        self._started = False
        self._start_lock = threading.Lock()

    def start(self, greeting: str) -> None:
        with self._start_lock:
            if self._started:
                return
            self._started = True
        greeting_wav = app_root() / "assets" / "audio" / "seyfi_greeting.wav"
        if greeting_wav.is_file():
            threading.Thread(target=self._play_pre_rendered_greeting,
                             args=(greeting_wav,), daemon=True,
                             name="Seyfi-Greeting").start()
            tts_greeting = ""
        else:
            tts_greeting = greeting
        threading.Thread(target=self._tts_worker, args=(tts_greeting,), daemon=True,
                         name="Seyfi-TTS").start()
        threading.Thread(target=self._asr_worker, daemon=True,
                         name="Seyfi-ASR").start()

    def speak(self, phrase: str) -> None:
        phrase = str(phrase or "").strip()
        if phrase and not self._stop.is_set():
            self._tts_queue.put(phrase)

    def stop(self) -> None:
        self._stop.set()
        self._tts_queue.put(None)
        try:
            self._audio_queue.put_nowait(None)
        except queue.Full:
            pass

    def _play_pre_rendered_greeting(self, path: Path) -> None:
        try:
            # On Windows use the native WAV player first: it avoids PortAudio/default-device
            # selection problems and does not depend on an installed speech voice.
            if os.name == "nt":
                import winsound
                winsound.PlaySound(str(path), winsound.SND_FILENAME | winsound.SND_SYNC)
            else:
                import soundfile as sf
                import sounddevice as sd
                audio, sample_rate = sf.read(str(path), dtype="float32")
                if audio.size:
                    sd.play(audio, samplerate=int(sample_rate), blocking=True)
            self.on_status("Seyfi Usta karşılama konuşması tamamlandı.")
        except Exception as exc:
            self.on_status("HAZIR KARŞILAMA SESİ ÇALINAMADI: " + str(exc)[:180])
        finally:
            self._greeting_finished.set()

    def _tts_worker(self, greeting: str) -> None:
        try:
            from pocket_tts_turkish import TurkishTTS
            local_model = model_path("pocket-tts-turkish")
            if not (local_model / "config.yaml").is_file():
                raise FileNotFoundError(
                    f"Türkçe ses modeli bulunamadı: {local_model}. "
                    "Kurulum paketinde model klasörü bulunmalı."
                )
            self._tts = TurkishTTS.from_pretrained(source=str(local_model), device="cpu")
            if self.voice_file and Path(self.voice_file).is_file():
                self._voice_state = self._tts.voice_from_file(self.voice_file)
            self.on_status("Türkçe çevrimdışı ses motoru hazır.")
            if greeting:
                self._synthesize_and_play(greeting)
                self._greeting_finished.set()
            while not self._stop.is_set():
                phrase = self._tts_queue.get()
                try:
                    if phrase is None:
                        return
                    self._synthesize_and_play(phrase)
                finally:
                    self._tts_queue.task_done()
        except Exception as exc:
            self._greeting_finished.set()
            self.on_status("SES MOTORU HATASI: " + str(exc)[:240])

    def _synthesize_and_play(self, phrase: str) -> None:
        import numpy as np

        voice = self._voice_state if self._voice_state is not None else self.voice_name
        audio = self._tts.generate(phrase, voice=voice, emotion="sakin")
        samples = np.asarray(audio, dtype=np.float32).squeeze()
        if not samples.size:
            return

        sample_rate = int(self._tts.sample_rate)
        if os.name == "nt":
            # Windows output is played through the native audio API. This avoids
            # relying on sounddevice/PortAudio to select the correct speaker.
            pcm = np.clip(samples, -1.0, 1.0)
            pcm16 = (pcm * 32767.0).astype("<i2")
            if pcm16.ndim == 1:
                channels = 1
            elif pcm16.ndim == 2:
                channels = int(pcm16.shape[1])
            else:
                pcm16 = pcm16.reshape(-1)
                channels = 1

            temp_path = None
            try:
                with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp:
                    temp_path = temp.name
                with wave.open(temp_path, "wb") as wav:
                    wav.setnchannels(channels)
                    wav.setsampwidth(2)
                    wav.setframerate(sample_rate)
                    wav.writeframes(pcm16.tobytes())
                import winsound
                winsound.PlaySound(
                    temp_path,
                    winsound.SND_FILENAME | winsound.SND_SYNC,
                )
            finally:
                if temp_path:
                    try:
                        os.remove(temp_path)
                    except OSError:
                        pass
        else:
            import sounddevice as sd
            sd.play(samples, samplerate=sample_rate, blocking=True)

    def _asr_worker(self) -> None:
        try:
            from vosk import Model, KaldiRecognizer
            import sounddevice as sd
            model_dir = model_path("vosk-model-small-tr-0.3")
            # vosk-model-small-tr-0.3 is a legacy flat-layout model.
            valid_flat = (model_dir / "final.mdl").is_file() and (model_dir / "mfcc.conf").is_file()
            valid_nested = (model_dir / "am" / "final.mdl").is_file() and (model_dir / "conf" / "model.conf").is_file()
            if not (valid_flat or valid_nested):
                raise FileNotFoundError(
                    f"Türkçe Vosk modeli eksik veya bozuk: {model_dir}. "
                    "final.mdl ve mfcc.conf dosyaları doğrulanamadı."
                )
            model = Model(str(model_dir))
            grammar = json.dumps(self.COMMAND_GRAMMAR + ["[unk]"], ensure_ascii=False)
            recognizer = KaldiRecognizer(model, 16000, grammar)
            recognizer.SetWords(False)

            def audio_callback(indata, frames, time_info, status):
                if self._stop.is_set():
                    raise sd.CallbackStop()
                try:
                    self._audio_queue.put_nowait(bytes(indata))
                except queue.Full:
                    pass

            # Hoparlörden gelen açılış cümlesini komut sanmaması için mikrofonu beklet.
            self._greeting_finished.wait(timeout=45)
            if self._stop.is_set():
                return
            self.on_status("Seyfi Usta mikrofonu dinliyor. Çevrimdışı komut tanıma hazır.")
            with sd.RawInputStream(samplerate=16000, blocksize=8000, dtype="int16",
                                   channels=1, callback=audio_callback):
                while not self._stop.is_set():
                    chunk = self._audio_queue.get()
                    if chunk is None:
                        break
                    if recognizer.AcceptWaveform(chunk):
                        result = json.loads(recognizer.Result() or "{}")
                        phrase = str(result.get("text", "")).strip()
                        if phrase:
                            self.on_command(phrase)
        except Exception as exc:
            self.on_status("MİKROFON/KOMUT TANIMA HATASI: " + str(exc)[:240])
