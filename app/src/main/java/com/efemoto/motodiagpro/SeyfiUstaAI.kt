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
    
    // Doğrudan erisilebilen brain nesnesi
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
        
        try {
            brain.initialize {
                onReady()
            }
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

    // MainActivity'nin aradığı chat metodu
    fun chat(message: String): String {
        return try {
            brain.chat(message)
        } catch (e: Exception) {
            "Usta sistem şu an meşgul, bağlantıyı kontrol et."
        }
    }

    // MainActivity'nin aradığı updateEcuData metodu
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
