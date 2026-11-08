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
    
    // SeyfiBrain nesnesi projedeki standart yapıya uygun olarak başlatılıyor
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
        
        // SeyfiBrain başlatma metodu (imza uyumsuzluklarını önlemek için güvenli çağrı)
        try {
            brain.initialize {
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

    // MainActivity'nin eriştiği chat metodu
    fun chat(message: String): String {
        return try {
            brain.chat(message)
        } catch (e: Exception) {
            "Usta şu an beyin modülünde bir yoğunluk var, bağlantıyı kontrol et."
        }
    }

    // MainActivity'nin eriştiği ECU veri güncelleme köprüsü
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) {
        try {
            brain.updateEcuData(rpm, tps, ect, map, voltage, dtcs)
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
            brain.release()
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "Release hata: ${e.message}")
        }
    }
}
