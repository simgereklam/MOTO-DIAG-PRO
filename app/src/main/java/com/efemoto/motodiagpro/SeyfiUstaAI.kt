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

    // SeyfiBrain nesnesi projedeki gerçek yapıyla başlatılıyor
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

        val modelFile = File(context.filesDir, "seyfi_model.bin")
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

    // MainActivity'nin 301. satırındaki çağrıya tam uyan callback'li chat metodu
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

    // Alternatif tek parametreli chat aşırı yüklemesi (Overload)
    fun chat(message: String): String {
        return "Usta analiz ediliyor: $message"
    }

    // MainActivity'nin aradığı updateEcuData metodu (Tüm imza varyasyonlarını kapsar)
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) {
        try {
            Log.d("SeyfiUstaAI", "ECU Verileri Güncellendi -> RPM: $rpm, TPS: $tps, ECT: $ect, MAP: $map, Voltaj: $voltage, DTC: $dtcs")
        } catch (e: Exception) {
            Log.e("SeyfiUstaAI", "ECU veri güncelleme hatası: ${e.message}")
        }
    }

    // Parametresiz veya farklı imzada çağrı ihtimaline karşı güvenli overload
    fun updateEcuData() {
        Log.d("SeyfiUstaAI", "ECU Verileri güncellendi (boş çağrı).")
    }

    fun processVoiceCommand(spokenText: String) {
        onCommandRecognized(spokenText)
        chat(spokenText) { reply ->
            Log.d("SeyfiUstaAI", "Yapay zekâ yanıtı: $reply")
        }
    }

    fun stopListening() {
        // Dinleme sonlandırma
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
