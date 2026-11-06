
package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var released = false
    private var speechFinished: (() -> Unit)? = null

    private val turkish = Locale("tr", "TR")

    fun initialize() {
        released = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onStatus("Çevrimdışı konuşma tanıma için Android 12 veya üzeri gerekiyor.")
        } else if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            onStatus("Bu cihazda çevrimdışı konuşma tanıma servisi bulunamadı.")
        } else {
            try {
                recognizer =
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)

                recognizer?.setRecognitionListener(listener)

                onStatus("SEYFİ USTA çevrimdışı dinleme servisi hazır.")
            } catch (e: Exception) {
                onStatus("Konuşma servisi açılamadı: ${e.message}")
            }
        }

        tts = TextToSpeech(activity) { status ->
            if (status != TextToSpeech.SUCCESS || released) {
                onStatus("Ses motoru başlatılamadı.")
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech

            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        activity.runOnUiThread {
                            val callback = speechFinished
                            speechFinished = null
                            callback?.invoke()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        activity.runOnUiThread {
                            val callback = speechFinished
                            speechFinished = null
                            callback?.invoke()
                        }
                    }
                }
            )

            val voices = engine.voices ?: emptySet()

            val offlineTurkishVoices = voices.filter {
                it.locale.language == "tr" &&
                !it.isNetworkConnectionRequired
            }

            if (offlineTurkishVoices.isNotEmpty()) {
                val maleVoice = offlineTurkishVoices.firstOrNull {
                    val details = (
                        it.name + " " +
                        it.features.joinToString(" ")
                    ).lowercase(Locale.ROOT)

                    details.contains("male") ||
                    details.contains("erkek")
                }

                engine.voice = maleVoice ?: offlineTurkishVoices.first()
                engine.language = turkish

                ttsReady = true

                onStatus(
                    if (maleVoice != null) {
                        "SEYFİ USTA Türkçe çevrimdışı erkek sesi hazır."
                    } else {
                        "Türkçe çevrimdışı ses hazır. Erkek ses olduğu doğrulanamadı."
                    }
                )
            } else {
                ttsReady = false
                onStatus("Türkçe çevrimdışı ses paketi bulunamadı.")
            }
        }
    }

    fun startListening() {
        if (released) return

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

        if (listening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            listening = true
            onStatus("SEYFİ USTA dinliyor...")

            service.startListening(intent)

        } catch (e: Exception) {
            listening = false
            onStatus("Dinleme başlatılamadı: ${e.message}")
        }
    }

    fun stopListening() {
        listening = false

        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (_: Exception) {
        }

        onStatus("Dinleme durduruldu.")
    }

    fun speak(
        message: String,
        onDone: (() -> Unit)? = null
    ) {
        if (!ttsReady || message.isBlank() || released) {
            onStatus("Türkçe çevrimdışı ses kullanılamıyor.")
            onDone?.invoke()
            return
        }

        speechFinished = onDone

        try {
            val result = tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SEYFI_${System.currentTimeMillis()}"
            )

            if (result == TextToSpeech.ERROR) {
                speechFinished = null
                onStatus("Sesli cevap oluşturulamadı.")
                onDone?.invoke()
            }
        } catch (e: Exception) {
            speechFinished = null
            onStatus("Sesli cevap hatası: ${e.message}")
            onDone?.invoke()
        }
    }

    fun isListening(): Boolean = listening

    fun isSpeechReady(): Boolean = ttsReady

    fun isRecognitionReady(): Boolean = recognizer != null

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("SEYFİ USTA dinliyor...")
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

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Konuşma servisi meşgul."

                    else ->
                        "Dinleme hatası: $error"
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
        released = true
        listening = false
        speechFinished = null

        try {
            recognizer?.cancel()
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
    }
}
