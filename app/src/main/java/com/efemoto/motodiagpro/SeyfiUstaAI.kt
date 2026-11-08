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
        onReady()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) {
            onDone?.invoke()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SeyfiUtterance")
    }

    // MainActivity'nin doğrudan eriştiği chat metodu
    fun chat(message: String): String {
        return "Usta sesini duydum: $message. Sistem aktif ve çalışıyor."
    }

    // MainActivity'nin doğrudan eriştiği updateEcuData metodu
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) {
        Log.d("SeyfiUstaAI", "ECU Verileri Güncellendi -> RPM: $rpm, Voltaj: $voltageV")
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
