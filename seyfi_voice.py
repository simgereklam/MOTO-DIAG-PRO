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
        self._started = False
        self._start_lock = threading.Lock()

    def start(self, greeting: str) -> None:
        with self._start_lock:
            if self._started:
                return
            self._started = True
        threading.Thread(target=self._tts_worker, args=(greeting,), daemon=True,
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
            while not self._stop.is_set():
                phrase = self._tts_queue.get()
                try:
                    if phrase is None:
                        return
                    self._synthesize_and_play(phrase)
                finally:
                    self._tts_queue.task_done()
        except Exception as exc:
            self.on_status("SES MOTORU HATASI: " + str(exc)[:240])

    def _synthesize_and_play(self, phrase: str) -> None:
        import numpy as np
        import sounddevice as sd
        voice = self._voice_state if self._voice_state is not None else self.voice_name
        audio = self._tts.generate(phrase, voice=voice, emotion="sakin")
        samples = np.asarray(audio, dtype=np.float32)
        if samples.size:
            sd.play(samples, samplerate=int(self._tts.sample_rate), blocking=True)

    def _asr_worker(self) -> None:
        try:
            from vosk import Model, KaldiRecognizer
            import sounddevice as sd
            model_dir = model_path("vosk-model-small-tr-0.3")
            if not (model_dir / "am").is_dir():
                raise FileNotFoundError(
                    f"Türkçe komut tanıma modeli bulunamadı: {model_dir}. "
                    "Kurulum paketinde Vosk modeli bulunmalı."
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
