package com.efemoto.motodiagpro

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

class SeyfiUstaAI(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // SeyfiBrain nesnesi doğrudan projedeki gerçek imza ile başlatılıyor
    val brain = SeyfiBrain(context)

    fun initialize(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("tr", "TR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("SeyfiUstaAI", "Türkçe dil desteği TTS için eksik.")
                } else {
                    isTtsReady = true
                }
            }
        }

        // Model dosyasının olası konumu (assets veya cache dizini kontrolü yapılabilir)
        // SeyfiBrain'in beklediği modelFile parametresi sağlanıyor
        val modelFile = File(context.filesDir, "seyfi_model.bin") // Örnek dosya yolu
        
        try {
            brain.initialize(modelFile) { success, message ->
                Log.d("SeyfiUstaAI", "Brain init: $success - $message")
                onReady()
            }
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "Brain init hata: ${e.message}")
            onReady()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) {
            onDone?.invoke()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SeyfiUtterance")
    }

    // MainActivity veya dışarıdan metin tabanlı soru sormak için köprü
    fun chat(message: String, onAnswer: (String) -> Unit) {
        if (brain.isReady()) {
            brain.ask(message) { answer ->
                onAnswer(answer)
                speak(answer)
            }
        } else {
            val fallback = "Usta, yapay zekâ beyni şu an hazır değil."
            onAnswer(fallback)
            speak(fallback)
        }
    }

    // MainActivity'nin sesli komut akışını karşılayan ana fonksiyon
    fun processVoiceCommand(spokenText: String) {
        onCommandRecognized(spokenText)
        chat(spokenText) { reply ->
            // Cevap zaten chat içinde seslendiriliyor
            Log.d("SeyfiUstaAI", "Yapay zekâ yanıtı: $reply")
        }
    }

    fun stopListening() {
        // Dinleme sonlandırma işlemleri
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            brain.close()
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "Release hata: ${e.message}")
        }
    }
}
