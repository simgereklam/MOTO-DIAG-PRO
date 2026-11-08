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
    
    // Beyin modülü
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
        brain.initialize {
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

    // MainActivity'nin doğrudan eriştiği chat metodu sarmalandı
    fun chat(message: String): String {
        return brain.chat(message)
    }

    // MainActivity'nin çağırdığı ECU veri güncelleme köprüsü eklendi
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) {
        brain.updateEcuData(rpm, tps, ect, map, voltage, dtcs)
    }

    fun processVoiceCommand(spokenText: String) {
        onCommandRecognized(spokenText)
        val aiReply = brain.chat(spokenText)
        speak(aiReply)
    }

    fun stopListening() {
        // Dinleme oturumu sonlandırma
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        brain.release()
    }
}
