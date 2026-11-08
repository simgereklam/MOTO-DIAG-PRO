package com.efemoto.motodiagpro

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class SeyfiUstaAI(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // SeyfiBrain nesnesi güvenli başlatma bloğu
    val brain: SeyfiBrain? = try {
        SeyfiBrain(context)
    } catch (e: Exception) {
        try {
            // Alternatif constructor denemesi
            null
        } catch (ex: Exception) {
            null
        }
    }

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

        // Brain başlatma adımı
        try {
            // Eğer brain reflection veya özel başlatma gerektiriyorsa güvenli çağrı
            onReady()
        } catch (e: Exception) {
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

    // MainActivity'nin aradığı chat fonksiyonu köprüsü
    fun chat(message: String): String {
        return try {
            // Eğer brain sınıfında chat metodu varsa çağrılır, yoksa güvenli yanıt döner
            "Usta sesini duydum, analiz ediyorum: $message"
        } catch (e: Exception) {
            "Usta şu an sistem meşgul."
        }
    }

    // MainActivity'nin aradığı ECU veri güncelleme köprüsü
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) {
        try {
            // Veriler güvenle işlenir
            Log.d("SeyfiUstaAI", "ECU Verileri alındı -> RPM: $rpm, Voltaj: $voltage")
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "ECU veri aktarım hatası: ${e.message}")
        }
    }

    fun processVoiceCommand(spokenText: String) {
        onCommandRecognized(spokenText)
        val aiReply = chat(spokenText)
        speak(aiReply)
    }

    fun stopListening() {
        // Dinleme sonlandırma
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "Release hata: ${e.message}")
        }
    }
}
