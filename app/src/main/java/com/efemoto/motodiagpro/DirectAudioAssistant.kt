package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct audio path for Seyfi Usta.
 * Microphone PCM is sent to Gemma audio input without Vosk/ASR transcription.
 * Speech is segmented locally using a simple energy-based VAD.
 */
class DirectAudioAssistant(
    private val activity: Activity,
    private val onAudioCaptured: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "DirectAudioAssistant"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1024
        private const val SPEECH_THRESHOLD = 850.0
        private const val END_SILENCE_MS = 850
        private const val MAX_UTTERANCE_MS = 20_000
        private const val MIN_UTTERANCE_MS = 450
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val utteranceIds = AtomicInteger(0)
    private val turkish = Locale("tr", "TR")

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningRequested = false
    @Volatile private var paused = true
    @Volatile private var released = false
    @Volatile private var captureStarted = false

    private val ttsCallbacks = HashMap<String, () -> Unit>()

    fun initialize(onReady: () -> Unit) {
        try {
            val created = TextToSpeech(activity.applicationContext) { result ->
                mainHandler.post {
                    if (released) return@post
                    val engine = tts
                    if (result == TextToSpeech.SUCCESS && engine != null) {
                        try {
                            engine.setLanguage(turkish)
                            engine.setSpeechRate(0.92f)
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit
                                override fun onDone(utteranceId: String?) = finishSpeech(utteranceId)
                                override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeech(utteranceId)
                                @Deprecated("Deprecated by Android")
                                override fun onError(utteranceId: String?) = finishSpeech(utteranceId)
                                override fun onError(utteranceId: String?, errorCode: Int) = finishSpeech(utteranceId)
                            })
                            ttsReady = true
                            report("Seyfi Usta doğrudan ses motoru hazır.")
                            onReady()
                        } catch (e: Exception) {
                            report("Türkçe ses çıkışı hazırlanamadı: ${e.message}")
                        }
                    } else {
                        report("Telefonun metin okuma motoru başlatılamadı: $result")
                    }
                }
            }
            tts = created
        } catch (e: Exception) {
            report("Ses motoru açılamadı: ${e.message}")
        }
    }

    private fun report(message: String) {
        mainHandler.post { if (!released) onStatus(message) }
    }

    fun startListening() {
        if (released) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            report("Mikrofon izni gerekli.")
            return
        }
        listeningRequested = true
        paused = false
        if (!captureStarted) {
            captureStarted = true
            captureExecutor.execute { captureLoop() }
        }
        report("Seyfi Usta doğrudan sesi dinliyor; konuşmanızı normal şekilde söyleyin.")
    }

    fun pauseForProcessing() {
        paused = true
        report("Ses doğrudan yapay zekâya gönderiliyor...")
    }

    fun stopListening() {
        listeningRequested = false
        paused = true
        try { recorder?.stop() } catch (_: Exception) {}
        report("Seyfi Usta dinlemeyi durdurdu.")
    }

    fun isListening(): Boolean = listeningRequested && !paused && !released

    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            captureStarted = false
            report("Mikrofon tamponu oluşturulamadı: $minBuffer")
            return
        }

        val bufferBytes = maxOf(minBuffer, CHUNK_SAMPLES * 2 * 2)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: Exception) {
            captureStarted = false
            report("Mikrofon açılamadı: ${e.message}")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            captureStarted = false
            report("Mikrofon başlatılamadı.")
            return
        }

        recorder = audioRecord
        val samples = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<ByteArray>()
        val pcm = ByteArrayOutputStream()
        var speechStarted = false
        var highChunks = 0
        var silenceMs = 0
        var speechMs = 0

        try {
            audioRecord.startRecording()
            while (listeningRequested && !released) {
                if (paused) {
                    preRoll.clear()
                    pcm.reset()
                    speechStarted = false
                    highChunks = 0
                    silenceMs = 0
                    speechMs = 0
                    Thread.sleep(60)
                    continue
                }

                val read = audioRecord.read(samples, 0, samples.size)
                if (read <= 0) {
                    if (read < 0) report("Mikrofon okuma hatası: $read")
                    Thread.sleep(20)
                    continue
                }

                val chunkDuration = (read * 1000L / SAMPLE_RATE).toInt()
                var sum = 0.0
                for (i in 0 until read) {
                    val value = samples[i].toInt()
                    sum += value.toDouble() * value.toDouble()
                }
                val rms = kotlin.math.sqrt(sum / read)
                val bytes = ByteArray(read * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, read)

                if (!speechStarted) {
                    preRoll.addLast(bytes)
                    while (preRoll.size > 7) preRoll.removeFirst()
                    if (rms >= SPEECH_THRESHOLD) highChunks++ else highChunks = 0
                    if (highChunks >= 2) {
                        speechStarted = true
                        while (preRoll.isNotEmpty()) {
                            val old = preRoll.removeFirst()
                            pcm.write(old)
                            speechMs += old.size * 1000 / (SAMPLE_RATE * 2)
                        }
                        report("Konuşma algılandı; ses doğrudan Gemma'ya aktarılıyor.")
                    }
                } else {
                    pcm.write(bytes)
                    speechMs += chunkDuration
                    if (rms < SPEECH_THRESHOLD) silenceMs += chunkDuration else silenceMs = 0
                }

                if (speechStarted && (silenceMs >= END_SILENCE_MS || speechMs >= MAX_UTTERANCE_MS)) {
                    val utterance = pcm.toByteArray()
                    if (speechMs >= MIN_UTTERANCE_MS && utterance.isNotEmpty()) {
                        paused = true
                        val wav = makeWav(utterance)
                        report("Ses kaydı tamamlandı; yapay zekâ konuşmayı yorumluyor.")
                        mainHandler.post {
                            if (!released) {
                                try {
                                    onAudioCaptured(wav)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Audio callback failed", e)
                                    report("Ses yapay zekâya aktarılamadı: ${e.message}")
                                    startListening()
                                }
                            }
                        }
                    }
                    preRoll.clear()
                    pcm.reset()
                    speechStarted = false
                    highChunks = 0
                    silenceMs = 0
                    speechMs = 0
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop failed", e)
            report("Doğrudan mikrofon hatası: ${e.message}")
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            recorder = null
            captureStarted = false
            if (listeningRequested && !released) startListening()
        }
    }

    private fun makeWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (released) return
        paused = true
        if (text.isBlank()) {
            onDone?.invoke()
            if (listeningRequested) paused = false
            return
        }
        mainHandler.post {
            val engine = tts
            if (!ttsReady || engine == null) {
                onDone?.invoke()
                if (listeningRequested) paused = false
                return@post
            }
            val id = "seyfi-${utteranceIds.incrementAndGet()}"
            if (onDone != null) ttsCallbacks[id] = onDone
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) finishSpeech(id)
        }
    }

    private fun finishSpeech(id: String?) {
        mainHandler.post {
            if (released) return@post
            val callback = id?.let { ttsCallbacks.remove(it) }
            callback?.invoke()
            if (listeningRequested) {
                paused = false
                report("Seyfi Usta yeniden dinliyor.")
            }
        }
    }

    fun release() {
        released = true
        listeningRequested = false
        paused = true
        try { recorder?.stop() } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        captureExecutor.shutdownNow()
    }
}
