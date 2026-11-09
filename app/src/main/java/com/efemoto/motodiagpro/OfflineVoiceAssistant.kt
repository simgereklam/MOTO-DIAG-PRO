package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService as VoskSpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seyfi Usta için gerçek çevrimdışı Türkçe konuşma tanıma (Vosk)
 * ve cihazdaki Türkçe TTS. Ses hiçbir konuşma servisine gönderilmez.
 */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turkish = Locale("tr", "TR")
    private val utteranceIds = AtomicInteger(0)
    private val pendingDone = ConcurrentHashMap<String, () -> Unit>()

    @Volatile private var voskModel: Model? = null
    @Volatile private var voskRecognizer: Recognizer? = null
    @Volatile private var speechService: VoskSpeechService? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningEnabled = false
    @Volatile private var processingRequested = false
    @Volatile private var speakingRequested = false
    @Volatile private var currentStatus = "Türkçe çevrimdışı ses sistemi hazırlanıyor."

    private fun reportStatus(message: String) {
        currentStatus = message
        onStatus(message)
    }

    fun getStatus(): String = currentStatus

    private var voskInitializationFinished = false
    private var ttsInitializationFinished = false
    private var initializedCallback: (() -> Unit)? = null

    fun initialize(onInitialized: (() -> Unit)? = null) {
        initializedCallback = onInitialized
        reportStatus("Türkçe çevrimdışı konuşma modeli hazırlanıyor...")

        // Model dosyaları APK assets içindeki model-tr klasöründen açılır.
        // İlk açılıştan sonra uygulamanın özel dosya alanında saklanır.
        try {
            StorageService.unpack(
                activity,
                "model-tr",
                "seyfi-voice",
                { model ->
                    voskModel = model
                    voskInitializationFinished = true
                    reportStatus("Türkçe çevrimdışı ses tanıma hazır.")
                    startSpeechServiceIfPossible()
                    completeInitializationIfReady()
                },
                { error ->
                    voskInitializationFinished = true
                    reportStatus("Türkçe ses modeli açılamadı: ${error.message}")
                    completeInitializationIfReady()
                }
            )
        } catch (e: Exception) {
            voskInitializationFinished = true
            reportStatus("Türkçe ses modeli başlatılamadı: ${e.message}")
            completeInitializationIfReady()
        }

        try {
            tts = TextToSpeech(activity) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    try {
                        engine?.language = turkish
                    } catch (_: Exception) {
                    }

                    val offlineTurkishVoices = engine?.voices.orEmpty().filter {
                        it.locale.language.equals("tr", ignoreCase = true) &&
                            !it.isNetworkConnectionRequired
                    }
                    val maleVoice = offlineTurkishVoices.firstOrNull {
                        val voiceName = it.name.lowercase(Locale.ROOT)
                        voiceName.contains("male") || voiceName.contains("erkek")
                    }
                    val selectedVoice = maleVoice ?: offlineTurkishVoices.firstOrNull()

                    if (selectedVoice != null) {
                        engine?.voice = selectedVoice
                        ttsReady = true
                        reportStatus(
                            if (maleVoice != null) "Türkçe çevrimdışı erkek sesi hazır."
                            else "Türkçe çevrimdışı ses hazır."
                        )
                    } else {
                        ttsReady = false
                        reportStatus("Türkçe çevrimdışı ses paketi yüklü değil.")
                    }

                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post { reportStatus("Seyfi Usta konuşuyor...") }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                speakingRequested = false
                                utteranceId?.let { pendingDone.remove(it)?.invoke() }
                                reportStatus("Seyfi Usta dinlemeye hazır.")
                                resumeRecognition()
                            }
                        }

                        @Deprecated("Deprecated by Android")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                speakingRequested = false
                                utteranceId?.let { pendingDone.remove(it)?.invoke() }
                                reportStatus("Sesli yanıt tamamlanamadı.")
                                resumeRecognition()
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            mainHandler.post {
                                speakingRequested = false
                                utteranceId?.let { pendingDone.remove(it)?.invoke() }
                                reportStatus("Sesli yanıt hatası: $errorCode")
                                resumeRecognition()
                            }
                        }
                    })
                } else {
                    ttsReady = false
                    reportStatus("Metin okuma motoru başlatılamadı.")
                }
                ttsInitializationFinished = true
                completeInitializationIfReady()
            }
        } catch (e: Exception) {
            ttsInitializationFinished = true
            ttsReady = false
            reportStatus("Metin okuma motoru açılamadı: ${e.message}")
            completeInitializationIfReady()
        }
    }

    private fun completeInitializationIfReady() {
        if (voskInitializationFinished && ttsInitializationFinished) {
            val callback = initializedCallback
            initializedCallback = null
            callback?.invoke()
        }
    }

    fun startListening() {
        listeningEnabled = true
        processingRequested = false
        startSpeechServiceIfPossible()
    }

    /** Uyandırma sözü tanındı; komut/AI yanıtı bitene kadar ses akışını duraklat. */
    fun pauseForProcessing() {
        processingRequested = true
        speechService?.setPause(true)
    }

    private fun startSpeechServiceIfPossible() {
        if (!listeningEnabled || processingRequested || speechService != null) return
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            reportStatus("Mikrofon izni gerekli.")
            return
        }

        val model = voskModel ?: run {
            reportStatus("Türkçe ses modeli yüklenemedi. Ses modeli açılamadığı için mikrofon dinlemesi başlamadı.")
            return
        }
        try {
            val recognizer = Recognizer(model, 16000.0f)
            voskRecognizer = recognizer
            val service = VoskSpeechService(recognizer, 16000.0f)
            speechService = service
            service.startListening(voskListener)
            reportStatus("Seyfi Usta çevrimdışı dinliyor...")
        } catch (e: Exception) {
            releaseSpeechService()
            reportStatus("Çevrimdışı mikrofon başlatılamadı: ${e.message}")
            if (listeningEnabled && !processingRequested) {
                mainHandler.postDelayed(restartRecognition, 1200)
            }
        }
    }

    private val restartRecognition = Runnable {
        if (listeningEnabled && !processingRequested && !speakingRequested) {
            startSpeechServiceIfPossible()
        }
    }

    private fun extractText(json: String, field: String): String {
        return try {
            JSONObject(json).optString(field).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Kısmi metin ekranda gösterilmez; sadece tamamlanan cümle işlenir.
        }

        override fun onResult(hypothesis: String?) {
            val recognizedText = hypothesis?.let { extractText(it, "text") }.orEmpty()
            if (recognizedText.isNotBlank() && listeningEnabled && !processingRequested) {
                onRecognized(recognizedText)
                if (processingRequested || speakingRequested) {
                    speechService?.setPause(true)
                }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            val recognizedText = hypothesis?.let { extractText(it, "text") }.orEmpty()
            if (recognizedText.isNotBlank() && listeningEnabled && !processingRequested) {
                onRecognized(recognizedText)
            }
        }

        override fun onError(exception: Exception?) {
            reportStatus("Çevrimdışı ses tanıma hatası: ${exception?.message}")
            if (listeningEnabled && !processingRequested) {
                mainHandler.removeCallbacks(restartRecognition)
                mainHandler.postDelayed({
                    releaseSpeechService()
                    startSpeechServiceIfPossible()
                }, 900)
            }
        }

        override fun onTimeout() {
            // Sürekli dinleme modunda zaman aşımı kullanılmıyor.
        }
    }

    private fun resumeRecognition() {
        if (!listeningEnabled) return
        processingRequested = false
        val service = speechService
        if (service != null) {
            service.setPause(false)
            reportStatus("Seyfi Usta çevrimdışı dinliyor...")
        } else {
            mainHandler.removeCallbacks(restartRecognition)
            mainHandler.postDelayed(restartRecognition, 350)
        }
    }

    fun stopListening() {
        listeningEnabled = false
        processingRequested = true
        mainHandler.removeCallbacks(restartRecognition)
        releaseSpeechService()
        reportStatus("Seyfi Usta dinlemeyi durdurdu.")
    }

    private fun releaseSpeechService() {
        val service = speechService
        speechService = null
        try {
            service?.cancel()
        } catch (_: Exception) {
        }
        try {
            service?.shutdown()
        } catch (_: Exception) {
        }
        try {
            voskRecognizer?.close()
        } catch (_: Exception) {
        }
        voskRecognizer = null
    }

    fun speak(message: String, onDone: (() -> Unit)? = null) {
        if (message.isBlank()) {
            onDone?.invoke()
            resumeRecognition()
            return
        }

        if (!ttsReady || tts == null) {
            reportStatus("Türkçe çevrimdışı ses kullanılamıyor.")
            onDone?.invoke()
            resumeRecognition()
            return
        }

        processingRequested = true
        speechService?.setPause(true)
        speakingRequested = true
        val id = "SEYFI_${utteranceIds.incrementAndGet()}"
        if (onDone != null) pendingDone[id] = onDone

        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            speakingRequested = false
            pendingDone.remove(id)?.invoke()
            reportStatus("Sesli yanıt başlatılamadı.")
            resumeRecognition()
        }
    }

    fun isListening(): Boolean =
        listeningEnabled && !processingRequested && speechService != null

    fun release() {
        listeningEnabled = false
        processingRequested = true
        mainHandler.removeCallbacksAndMessages(null)
        releaseSpeechService()
        try {
            voskModel?.close()
        } catch (_: Exception) {
        }
        voskModel = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        speakingRequested = false
        pendingDone.clear()
        initializedCallback = null
    }
}
