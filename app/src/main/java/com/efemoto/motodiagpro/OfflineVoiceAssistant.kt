package com.efemoto.motodiagpro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Sherpa-ONNX ve çok dilli Whisper Base INT8 ile yerel Türkçe ses tanıma. */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureRunning = AtomicBoolean(false)
    private val ids = java.util.concurrent.atomic.AtomicInteger()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var modelReady = false
    @Volatile private var listening = false
    @Volatile private var processing = false
    @Volatile private var speaking = false
    @Volatile private var released = false
    @Volatile private var status = "Türkçe çevrimdışı ses modeli hazırlanıyor."

    private var modelInitialized = false
    private var ttsInitialized = false
    private var initCallback: (() -> Unit)? = null
    private var activeUtterance: String? = null

    private fun setStatus(value: String) {
        status = value
        handler.post { if (!released) onStatus(value) }
    }

    fun getStatus(): String = status

    fun initialize(onInitialized: (() -> Unit)? = null) {
        if (released) return
        initCallback = onInitialized
        setStatus("Sherpa-ONNX Türkçe konuşma modeli yükleniyor...")
        worker.execute {
            try {
                val dir = File(activity.filesDir, "seyfi-whisper-base")
                if (!dir.exists() && !dir.mkdirs()) error("Ses modeli klasörü oluşturulamadı.")
                val encoder = copyAsset("sherpa-whisper-base/base-encoder.int8.onnx",
                    File(dir, "base-encoder.int8.onnx"), 20_000_000L)
                val decoder = copyAsset("sherpa-whisper-base/base-decoder.int8.onnx",
                    File(dir, "base-decoder.int8.onnx"), 100_000_000L)
                val tokens = copyAsset("sherpa-whisper-base/base-tokens.txt",
                    File(dir, "base-tokens.txt"), 500_000L)
                val model = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        language = "tr",
                        task = "transcribe"
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "whisper"
                )
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(modelConfig = model)
                )
                modelReady = true
                setStatus("Sherpa-ONNX Türkçe konuşma modeli hazır.")
            } catch (e: Exception) {
                modelReady = false
                setStatus("Türkçe ses modeli başlatılamadı: ${e.message}")
            } finally {
                modelInitialized = true
                completeInitialization()
            }
        }
        initializeTts()
    }

    private fun copyAsset(path: String, target: File, minBytes: Long): File {
        if (target.isFile && target.length() >= minBytes) return target
        target.delete()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        activity.assets.open(path).use { input ->
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
        val length = part.length()
        if (length < minBytes) {
            part.delete()
            error("${target.name} eksik veya bozuk: $length bayt.")
        }
        if (!part.renameTo(target)) {
            part.delete()
            error("${target.name} uygulama belleğine kopyalanamadı.")
        }
        return target
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(activity) { result ->
                if (released) return@TextToSpeech
                val engine = tts
                if (result == TextToSpeech.SUCCESS && engine != null) {
                    try {
                        val languageResult = engine.setLanguage(Locale("tr", "TR"))
                        val languageOk = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                        val offline = engine.voices.orEmpty().filter {
                            it.locale.language.equals("tr", true) && !it.isNetworkConnectionRequired
                        }
                        val selected = offline.firstOrNull { v ->
                            val desc = (v.name + " " + v.features.orEmpty().joinToString(" "))
                                .lowercase(Locale.ROOT)
                            desc.contains("male") || desc.contains("erkek")
                        } ?: offline.firstOrNull()
                        if (selected != null) engine.setVoice(selected)
                        engine.setSpeechRate(0.92f)
                        ttsReady = languageOk
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) =
                                finishSpeech(utteranceId, "Seyfi Usta konuşmayı tamamladı.")
                            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                                finishSpeech(utteranceId, "Seyfi Usta konuşması durdu.")
                            @Deprecated("Deprecated by Android")
                            override fun onError(utteranceId: String?) =
                                finishSpeech(utteranceId, "Telefonun ses motoru yanıt vermedi.")
                            override fun onError(utteranceId: String?, errorCode: Int) =
                                finishSpeech(utteranceId, "Telefonun ses motoru hata verdi: $errorCode.")
                        })
                        setStatus(when {
                            !languageOk -> "Telefonda Türkçe metin okuma dili yüklü değil."
                            selected != null -> "Türkçe çevrimdışı ses hazır: ${selected.name}"
                            else -> "Türkçe TTS hazır; çevrimdışı Türkçe ses bulunamadı."
                        })
                    } catch (e: Exception) {
                        ttsReady = false
                        setStatus("Türkçe ses motoru hazırlanamadı: ${e.message}")
                    }
                } else {
                    ttsReady = false
                    setStatus("Telefonun metin okuma motoru başlatılamadı.")
                }
                ttsInitialized = true
                completeInitialization()
            }
        } catch (e: Exception) {
            ttsReady = false
            ttsInitialized = true
            setStatus("Metin okuma motoru açılamadı: ${e.message}")
            completeInitialization()
        }
    }

    private fun completeInitialization() {
        if (!modelInitialized || !ttsInitialized || released) return
        val callback = initCallback
        initCallback = null
        handler.post { if (!released) callback?.invoke() }
    }

    fun startListening() {
        if (released) return
        listening = true
        processing = false
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Mikrofon izni gerekli.")
            return
        }
        if (!modelReady || recognizer == null) {
            setStatus("Türkçe çevrimdışı konuşma modeli hazır değil.")
            return
        }
        startCapture()
    }

    fun pauseForProcessing() {
        processing = true
        handler.removeCallbacks(restart)
        stopCapture()
        setStatus("Seyfi Usta komutu işliyor...")
    }

    private fun startCapture() {
        if (released || !listening || processing || speaking || !modelReady || recognizer == null) return
        if (!captureRunning.compareAndSet(false, true)) return
        Thread({ captureLoop() }, "SeyfiAudioCapture").start()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var local: AudioRecord? = null
        var phrase: FloatArray? = null
        var failure: String? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) error("Mikrofon tamponu açılamadı.")
            local = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 8)
            )
            if (local.state != AudioRecord.STATE_INITIALIZED) error("Mikrofon başlatılamadı.")
            recorder = local
            local.startRecording()

            val pcm = ShortArray(FRAME)
            val preRoll = ArrayDeque<FloatArray>()
            val samples = ArrayList<Float>(RATE * 7)
            var inSpeech = false
            var silenceFrames = 0

            while (!released && listening && !processing && !speaking) {
                val n = local.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (processing || speaking || !listening || released) break
                    error("Mikrofon akışı kesildi ($n).")
                }
                val chunk = FloatArray(n)
                var energy = 0.0
                for (i in 0 until n) {
                    val value = pcm[i] / 32768.0f
                    chunk[i] = value
                    energy += value * value
                }
                val rms = sqrt(energy / n)
                if (rms >= THRESHOLD) {
                    if (!inSpeech) {
                        while (preRoll.isNotEmpty()) {
                            for (sample in preRoll.removeFirst()) samples.add(sample)
                        }
                        inSpeech = true
                    }
                    silenceFrames = 0
                    for (sample in chunk) samples.add(sample)
                } else if (inSpeech) {
                    silenceFrames++
                    for (sample in chunk) samples.add(sample)
                } else {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL) preRoll.removeFirst()
                }

                if (inSpeech && (silenceFrames >= SILENCE_FRAMES ||
                    samples.size >= MAX_SAMPLES)
                ) {
                    if (samples.size >= MIN_SAMPLES) {
                        phrase = samples.toFloatArray()
                        processing = true
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (!released && listening && !processing && !speaking) {
                failure = e.message ?: "Bilinmeyen mikrofon hatası."
            }
        } finally {
            try {
                if (local?.recordingState == AudioRecord.RECORDSTATE_RECORDING) local.stop()
            } catch (_: Exception) {}
            try { local?.release() } catch (_: Exception) {}
            if (recorder === local) recorder = null
            captureRunning.set(false)
        }

        when {
            phrase != null && !released -> decodePhrase(phrase!!)
            failure != null -> {
                setStatus("Mikrofon hatası: $failure")
                scheduleRestart()
            }
            listening && !processing && !speaking && !released -> scheduleRestart()
        }
    }

    private fun decodePhrase(samples: FloatArray) {
        worker.execute {
            try {
                val active = recognizer ?: error("Ses modeli hazır değil.")
                val stream = active.createStream()
                val text = try {
                    stream.acceptWaveform(samples, RATE)
                    active.decode(stream)
                    active.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                handler.post {
                    if (released) return@post
                    if (text.isNotBlank()) {
                        setStatus("Seyfi Usta duydu: $text")
                        try {
                            onRecognized(text)
                        } catch (e: Exception) {
                            setStatus("Ses komutu aktarılamadı: ${e.message}")
                            resumeListening()
                        }
                    } else resumeListening()
                }
            } catch (e: Exception) {
                setStatus("Konuşma çözümlenemedi: ${e.message}")
                resumeListening()
            }
        }
    }

    private val restart = Runnable {
        if (listening && !processing && !speaking && !released) startCapture()
    }

    private fun scheduleRestart() {
        if (!listening || processing || speaking || released) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, 600L)
    }

    private fun stopCapture() {
        try {
            val current = recorder
            if (current?.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop()
        } catch (_: Exception) {}
    }

    private fun finishSpeech(id: String?, message: String) {
        handler.post {
            if (released) return@post
            val callback = id?.let { callbacks.remove(it) }
            if (id == activeUtterance) {
                activeUtterance = null
                speaking = false
                setStatus(message)
                callback?.invoke()
                resumeListening()
            } else callback?.invoke()
        }
    }

    private fun resumeListening() {
        if (!listening || released) return
        processing = false
        speaking = false
        handler.removeCallbacks(restart)
        handler.postDelayed({
            if (listening && !processing && !speaking && !released) startCapture()
        }, 400L)
    }

    fun stopListening() {
        listening = false
        processing = true
        handler.removeCallbacks(restart)
        stopCapture()
        setStatus("Seyfi Usta dinlemeyi durdurdu.")
    }

    fun speak(message: String, onDone: (() -> Unit)? = null) {
        if (released) return
        if (message.isBlank()) {
            onDone?.invoke()
            resumeListening()
            return
        }
        processing = true
        speaking = true
        handler.removeCallbacks(restart)
        stopCapture()
        val engine = tts
        if (!ttsReady || engine == null) {
            setStatus("Türkçe telefon sesi kullanılamıyor.")
            onDone?.invoke()
            speaking = false
            resumeListening()
            return
        }
        val id = "SEYFI_${ids.incrementAndGet()}"
        activeUtterance = id
        if (onDone != null) callbacks[id] = onDone
        if (engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            callbacks.remove(id)
            activeUtterance = null
            speaking = false
            setStatus("Telefonun ses motoru konuşma isteğini kabul etmedi.")
            onDone?.invoke()
            resumeListening()
        } else setStatus("Seyfi Usta yanıt veriyor.")
    }

    fun isListening(): Boolean =
        listening && !processing && !speaking && captureRunning.get()

    fun isSpeechReady(): Boolean = ttsReady
    fun isRecognitionReady(): Boolean = modelReady && recognizer != null

    fun release() {
        released = true
        listening = false
        processing = true
        handler.removeCallbacksAndMessages(null)
        stopCapture()
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        modelReady = false
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        speaking = false
        activeUtterance = null
        callbacks.clear()
        initCallback = null
        worker.shutdownNow()
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 320
        const val PRE_ROLL = 10
        const val SILENCE_FRAMES = 30
        const val MIN_SAMPLES = RATE * 3 / 10
        const val MAX_SAMPLES = RATE * 7
        const val THRESHOLD = 0.010
    }
}
