package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Uses Android's built-in SpeechRecognizer and TextToSpeech.
 * No Vosk model, external speech SDK, or network speech service is bundled.
 */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turkish = Locale("tr", "TR")
    private val utteranceIds = AtomicInteger(0)
    private val pendingSpeechCallbacks = ConcurrentHashMap<String, () -> Unit>()
    @Volatile private var activeUtteranceId: String? = null

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningEnabled = false
    @Volatile private var processingRequested = false
    @Volatile private var speakingRequested = false
    @Volatile private var released = false
    @Volatile private var currentStatus = "Android ses sistemi hazırlanıyor."
    private var initializedCallback: (() -> Unit)? = null
    private var ttsInitialized = false
    private var recognitionInitialized = false

    private fun reportStatus(message: String) {
        currentStatus = message
        if (Looper.myLooper() == Looper.getMainLooper()) onStatus(message)
        else mainHandler.post { onStatus(message) }
    }

    fun getStatus(): String = currentStatus

    fun initialize(onInitialized: (() -> Unit)? = null) {
        released = false
        initializedCallback = onInitialized
        reportStatus("Telefonun yerleşik Türkçe ses sistemi hazırlanıyor...")

        initializeRecognition()
        initializeTts()
        completeInitializationIfReady()
    }

    private fun initializeRecognition() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                reportStatus("Bu telefonda konuşma tanıma servisi bulunamadı.")
                recognitionInitialized = true
                completeInitializationIfReady()
                return
            }

            recognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
            recognizer?.setRecognitionListener(listener)
            reportStatus("Telefonun konuşma tanıma servisi hazır.")
        } catch (e: Exception) {
            reportStatus("Konuşma servisi açılamadı: ${e.message}")
        } finally {
            recognitionInitialized = true
            completeInitializationIfReady()
        }
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(activity) { status ->
                if (released) return@TextToSpeech
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    try {
                        val voices = engine.voices.orEmpty()
                        val offlineTurkish = voices.filter {
                            it.locale.language.equals("tr", ignoreCase = true) &&
                                !it.isNetworkConnectionRequired
                        }
                        val selected = offlineTurkish.firstOrNull { voice ->
                            val details = (voice.name + " " +
                                voice.features.orEmpty().joinToString(" "))
                                .lowercase(Locale.ROOT)
                            details.contains("male") || details.contains("erkek")
                        } ?: offlineTurkish.firstOrNull()

                        if (selected != null) engine.voice = selected
                        val languageResult = engine.setLanguage(turkish)
                        ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                mainHandler.post { reportStatus("Seyfi Usta konuşuyor...") }
                            }

                            override fun onDone(utteranceId: String?) {
                                finishUtterance(utteranceId, "Seyfi Usta dinlemeye hazır.")
                            }

                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                finishUtterance(
                                    utteranceId,
                                    if (interrupted) "Sesli yanıt kesildi." else "Seyfi Usta dinlemeye hazır."
                                )
                            }

                            @Deprecated("Deprecated by Android")
                            override fun onError(utteranceId: String?) {
                                finishUtterance(utteranceId, "Sesli yanıt tamamlanamadı.")
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                finishUtterance(utteranceId, "Sesli yanıt hatası: $errorCode")
                            }
                        })

                        reportStatus(
                            when {
                                !ttsReady -> "Türkçe metin okuma dili telefonda yüklü değil."
                                offlineTurkish.isNotEmpty() -> "Telefonun Türkçe çevrimdışı sesi hazır."
                                else -> "Telefonun Türkçe ses motoru hazır; çevrimdışı ses paketi ayrıca doğrulanamadı."
                            }
                        )
                    } catch (e: Exception) {
                        ttsReady = false
                        reportStatus("Türkçe ses motoru hazırlanamadı: ${e.message}")
                    }
                } else {
                    ttsReady = false
                    reportStatus("Telefonun metin okuma motoru başlatılamadı.")
                }
                ttsInitialized = true
                completeInitializationIfReady()
            }
        } catch (e: Exception) {
            ttsReady = false
            ttsInitialized = true
            reportStatus("Metin okuma motoru açılamadı: ${e.message}")
            completeInitializationIfReady()
        }
    }

    private fun finishUtterance(utteranceId: String?, status: String) {
        mainHandler.post {
            val callback = utteranceId?.let { pendingSpeechCallbacks.remove(it) }
            if (utteranceId == activeUtteranceId) {
                activeUtteranceId = null
                speakingRequested = false
                reportStatus(status)
                callback?.invoke()
                resumeRecognition()
            } else {
                callback?.invoke()
            }
        }
    }

    private fun completeInitializationIfReady() {
        if (recognitionInitialized && ttsInitialized) {
            val callback = initializedCallback
            initializedCallback = null
            callback?.invoke()
        }
    }

    fun startListening() {
        listeningEnabled = true
        processingRequested = false
        startRecognition()
    }

    fun pauseForProcessing() {
        processingRequested = true
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private fun startRecognition() {
        if (released || !listeningEnabled || processingRequested || speakingRequested) return
        if (recognizer == null) {
            reportStatus("Mikrofon servisi hazır değil. Android konuşma tanıma hizmetini kontrol edin.")
            return
        }

        if (ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            reportStatus("Mikrofon izni gerekli.")
            return
        }

        mainHandler.removeCallbacks(restartRecognition)
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            }
            listening = true
            reportStatus("Seyfi Usta dinliyor...")
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            reportStatus("Mikrofon dinlemesi başlatılamadı: ${e.message}")
            scheduleRestart(1200)
        }
    }

    private var listening = false

    private val restartRecognition = Runnable {
        if (listeningEnabled && !processingRequested && !speakingRequested && !released) {
            startRecognition()
        }
    }

    private fun scheduleRestart(delayMs: Long = 500L) {
        if (!listeningEnabled || processingRequested || speakingRequested || released) return
        mainHandler.removeCallbacks(restartRecognition)
        mainHandler.postDelayed(restartRecognition, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            reportStatus("Seyfi Usta dinliyor...")
        }

        override fun onBeginningOfSpeech() {
            reportStatus("Konuşmanı algıladım.")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
            reportStatus("Konuşma tamamlandı, çözümleniyor...")
        }

        override fun onError(error: Int) {
            listening = false
            reportStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Konuşma anlaşılamadı; yeniden dinliyorum."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı; yeniden dinliyorum."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni yok."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Konuşma servisi meşgul; yeniden denenecek."
                    SpeechRecognizer.ERROR_AUDIO -> "Mikrofon ses hatası; yeniden denenecek."
                    else -> "Dinleme hatası ($error); yeniden denenecek."
                }
            )
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 650L)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val recognized = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()

            if (!recognized.isNullOrBlank() && listeningEnabled && !processingRequested) {
                try {
                    onRecognized(recognized)
                    // MainActivity pauses only after the wake word is detected.
                    if (!processingRequested) scheduleRestart()
                } catch (e: Exception) {
                    reportStatus("Komut işlenemedi: ${e.message}")
                    processingRequested = false
                    scheduleRestart()
                }
            } else {
                scheduleRestart()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resumeRecognition() {
        if (!listeningEnabled || released) return
        processingRequested = false
        scheduleRestart(350L)
    }

    fun stopListening() {
        listeningEnabled = false
        processingRequested = true
        listening = false
        mainHandler.removeCallbacks(restartRecognition)
        try { recognizer?.cancel() } catch (_: Exception) {}
        reportStatus("Seyfi Usta dinlemeyi durdurdu.")
    }

    fun speak(message: String, onDone: (() -> Unit)? = null) {
        if (message.isBlank()) {
            onDone?.invoke()
            resumeRecognition()
            return
        }
        val engine = tts
        if (!ttsReady || engine == null || released) {
            reportStatus("Türkçe telefon sesi kullanılamıyor.")
            onDone?.invoke()
            resumeRecognition()
            return
        }

        processingRequested = true
        listening = false
        mainHandler.removeCallbacks(restartRecognition)
        try { recognizer?.cancel() } catch (_: Exception) {}
        speakingRequested = true

        val id = "SEYFI_${utteranceIds.incrementAndGet()}"
        activeUtteranceId = id
        if (onDone != null) pendingSpeechCallbacks[id] = onDone
        val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            pendingSpeechCallbacks.remove(id)
            activeUtteranceId = null
            speakingRequested = false
            reportStatus("Sesli yanıt başlatılamadı.")
            onDone?.invoke()
            resumeRecognition()
        }
    }

    fun isListening(): Boolean = listeningEnabled && listening && !processingRequested
    fun isSpeechReady(): Boolean = ttsReady
    fun isRecognitionReady(): Boolean = recognizer != null

    fun release() {
        released = true
        listeningEnabled = false
        processingRequested = true
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        speakingRequested = false
        activeUtteranceId = null
        pendingSpeechCallbacks.clear()
        initializedCallback = null
    }
}
