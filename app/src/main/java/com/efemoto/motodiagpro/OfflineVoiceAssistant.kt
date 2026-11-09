package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seyfi Usta için cihaz üstü konuşma tanıma ve çevrimdışı Türkçe ses.
 * Dinleme, yanıt konuşurken durur; yanıt tamamlanınca yeniden başlar.
 */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turkish = Locale("tr", "TR")
    private val utteranceIds = AtomicInteger(0)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var listeningEnabled = false
    @Volatile private var speakingRequested = false

    fun initialize(onInitialized: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        ) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity).also {
                it.setRecognitionListener(listener)
            }
            onStatus("Çevrimdışı Türkçe konuşma tanıma hazır.")
        } else {
            onStatus("Bu cihazda Android çevrimdışı konuşma tanıma servisi bulunamadı.")
        }

        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val offlineVoices = engine?.voices.orEmpty().filter {
                    it.locale.language.equals("tr", ignoreCase = true) &&
                        !it.isNetworkConnectionRequired
                }
                val selectedVoice = offlineVoices.firstOrNull {
                    val name = it.name.lowercase(Locale.ROOT)
                    name.contains("male") || name.contains("erkek")
                } ?: offlineVoices.firstOrNull()

                if (selectedVoice != null) {
                    engine?.voice = selectedVoice
                    ttsReady = true
                    onStatus("Türkçe çevrimdışı ses hazır.")
                } else {
                    ttsReady = false
                    onStatus("Türkçe çevrimdışı ses paketi yüklü değil. Cihazın metin okuma ayarlarını kontrol edin.")
                }

                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { onStatus("Seyfi Usta konuşuyor...") }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            speakingRequested = false
                            utteranceId?.let { pendingDone.remove(it)?.invoke() }
                            onStatus("Seyfi Usta dinlemeye hazır.")
                            if (listeningEnabled) scheduleListening(350)
                        }
                    }

                    @Deprecated("Deprecated by Android")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            speakingRequested = false
                            utteranceId?.let { pendingDone.remove(it)?.invoke() }
                            if (listeningEnabled) scheduleListening(500)
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            speakingRequested = false
                            utteranceId?.let { pendingDone.remove(it)?.invoke() }
                            onStatus("Sesli yanıt tamamlanamadı.")
                            if (listeningEnabled) scheduleListening(500)
                        }
                    }
                })
            } else {
                ttsReady = false
                onStatus("Metin okuma motoru başlatılamadı.")
            }
            onInitialized?.invoke()
        }
    }

    fun startListening() {
        listeningEnabled = true
        startListeningInternal()
    }

    private fun startListeningInternal() {
        if (!listeningEnabled || listening || speakingRequested || tts?.isSpeaking == true) return

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Mikrofon izni gerekli.")
            return
        }

        val service = recognizer ?: run {
            onStatus("Çevrimdışı konuşma servisi kullanılamıyor.")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            listening = true
            onStatus("Seyfi Usta dinliyor...")
            service.startListening(intent)
        } catch (e: Exception) {
            listening = false
            onStatus("Dinleme başlatılamadı: ${e.message}")
            scheduleListening(1200)
        }
    }

    private fun scheduleListening(delayMs: Long) {
        mainHandler.removeCallbacks(restartListening)
        mainHandler.postDelayed(restartListening, delayMs)
    }

    private val restartListening = Runnable { startListeningInternal() }

    fun stopListening() {
        listeningEnabled = false
        listening = false
        mainHandler.removeCallbacks(restartListening)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        onStatus("Seyfi Usta dinlemeyi durdurdu.")
    }

    fun speak(message: String, onDone: (() -> Unit)? = null) {
        if (message.isBlank()) {
            onDone?.invoke()
            if (listeningEnabled) scheduleListening(250)
            return
        }

        if (!ttsReady || tts == null) {
            onStatus("Türkçe çevrimdışı ses kullanılamıyor.")
            onDone?.invoke()
            if (listeningEnabled) scheduleListening(500)
            return
        }

        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }

        speakingRequested = true
        val id = "SEYFI_${utteranceIds.incrementAndGet()}"
        if (onDone != null) pendingDone[id] = onDone
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            speakingRequested = false
            pendingDone.remove(id)?.invoke()
            onStatus("Sesli yanıt başlatılamadı.")
            if (listeningEnabled) scheduleListening(500)
        }
    }

    private val pendingDone = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            onStatus("Dinliyorum...")
        }

        override fun onBeginningOfSpeech() {
            onStatus("Konuşmanı algıladım.")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
            onStatus("Konuşma çözümleniyor...")
        }

        override fun onError(error: Int) {
            listening = false
            onStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Konuşma anlaşılamadı."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni yok."
                    else -> "Dinleme hatası: $error"
                }
            )
            if (listeningEnabled) scheduleListening(900)
        }

        override fun onResults(results: android.os.Bundle?) {
            listening = false
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = words?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) {
                onRecognized(text)
                if (listeningEnabled && !speakingRequested) scheduleListening(700)
            } else if (listeningEnabled) {
                scheduleListening(500)
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    fun isListening(): Boolean = listening

    fun release() {
        listeningEnabled = false
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        speakingRequested = false
        pendingDone.clear()
    }
}
