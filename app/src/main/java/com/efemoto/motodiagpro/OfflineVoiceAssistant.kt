
package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import java.util.Locale

class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false

    private val turkish = Locale("tr", "TR")

    fun initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onStatus("Çevrimdışı konuşma tanıma için Android 12 veya üzeri gerekiyor.")
        } else if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            onStatus("Bu cihazda çevrimdışı konuşma tanıma servisi bulunamadı.")
        } else {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(listener)
            onStatus("Çevrimdışı dinleme sistemi hazır.")
        }

        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech

                val voices = engine.voices ?: emptySet<Voice>()
                val offlineVoice = voices.firstOrNull {
                    it.locale.language == "tr" &&
                    !it.isNetworkConnectionRequired
                }

                if (offlineVoice != null) {
                    engine.voice = offlineVoice
                    ttsReady = true
                    onStatus("Türkçe çevrimdışı konuşma hazır.")
                } else {
                    ttsReady = false
                    onStatus("Türkçe çevrimdışı ses paketi bulunamadı.")
                }
            } else {
                onStatus("Ses motoru başlatılamadı.")
            }
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Mikrofon izni gerekli.")
            return
        }

        val service = recognizer ?: run {
            onStatus("Çevrimdışı konuşma servisi hazır değil.")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            listening = true
            onStatus("Seni dinliyorum...")
            service.startListening(intent)

        } catch (e: Exception) {
            listening = false
            onStatus("Dinleme başlatılamadı: ${e.message}")
        }
    }

    fun stopListening() {
        listening = false
        recognizer?.stopListening()
    }

    fun speak(message: String) {
        if (!ttsReady || message.isBlank()) {
            onStatus("Türkçe çevrimdışı ses kullanılamıyor.")
            return
        }

        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "MOTO_DIAG_RESPONSE"
        )
    }

    fun isListening(): Boolean = listening

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("Dinliyorum...")
        }

        override fun onBeginningOfSpeech() {
            onStatus("Konuşmanı algıladım.")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
            onStatus("Konuşma tamamlandı.")
        }

        override fun onError(error: Int) {
            listening = false
            onStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Konuşma anlaşılamadı."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Ses algılanmadı."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Mikrofon izni yok."
                    else -> "Dinleme hatası: $error"
                }
            )
        }

        override fun onResults(results: Bundle?) {
            listening = false

            val words = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

            val text = words?.firstOrNull()?.trim()

            if (!text.isNullOrBlank()) {
                onRecognized(text)
            } else {
                onStatus("Konuşma metne dönüştürülemedi.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun release() {
        listening = false
        recognizer?.destroy()
        recognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
