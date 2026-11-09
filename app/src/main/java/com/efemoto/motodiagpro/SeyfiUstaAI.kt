package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File

/**
 * Seyfi Usta'nın tek giriş noktası:
 * - OfflineVoiceAssistant: mikrofon, sürekli dinleme ve Türkçe TTS
 * - SeyfiBrain: model dosyası varsa yerel LLM yanıtları
 * - MainActivity: gerçek ECU komutlarını çalıştırır
 */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit
) {
    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text -> onCommandRecognized(text) },
        onStatus = { status -> Log.d("SeyfiUstaAI", status) }
    )

    val brain = SeyfiBrain(activity.applicationContext)

    @Volatile private var initialized = false

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true

        // Sesli komut sistemi model dosyasından bağımsız çalışır.
        voice.initialize {
            onReady()
        }

        // Yerel üretken yapay zekâ yalnızca gerçek model dosyası varsa açılır.
        val modelFile = File(activity.filesDir, "seyfi_model.bin")
        brain.initialize(modelFile) { success, message ->
            if (success) {
                Log.i("SeyfiUstaAI", message)
            } else {
                Log.w("SeyfiUstaAI", "$message Komut tabanlı çevrimdışı işlevler kullanılabilir.")
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        voice.speak(text, onDone)
    }

    fun startListening() {
        voice.startListening()
    }

    fun stopListening() {
        voice.stopListening()
    }

    fun isListening(): Boolean = voice.isListening()

    fun chat(message: String, onAnswer: (String) -> Unit) {
        if (brain.isReady()) {
            brain.chat(message) { answer ->
                onAnswer(answer)
                speak(answer)
            }
        } else {
            val fallback =
                "Yerel yapay zekâ modeli henüz yüklenmedi. Gerçek ECU taraması, arıza kodları ve canlı sensör verileriyle ilgili komutları kullanabilirim."
            onAnswer(fallback)
            speak(fallback)
        }
    }

    fun chat(message: String): String {
        return if (brain.isReady()) {
            "Sorunuzu yerel yapay zekâya iletiyorum: $message"
        } else {
            "Yerel yapay zekâ modeli henüz yüklenmedi."
        }
    }

    fun updateEcuData(
        rpm: Int,
        tps: Float,
        ect: Int,
        map: Float,
        voltage: Float,
        dtcs: List<String>
    ) {
        brain.updateEcuData(
            rpm = rpm,
            tps = tps.toInt(),
            coolant = ect,
            mapKpa = map.toInt(),
            voltage = voltage.toDouble(),
            faults = dtcs,
            connected = true
        )
    }

    fun processVoiceCommand(spokenText: String) {
        onCommandRecognized(spokenText)
    }

    fun release() {
        voice.release()
        brain.close()
        initialized = false
    }
}
