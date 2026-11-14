package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log

/** Seyfi Usta'nın çevrimdışı ses tanıma, TTS ve hızlı komut yönlendirme katmanı. */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {}
) {
    companion object { private const val TAG = "SeyfiUstaAI" }
    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                try { onCommandRecognized(text) }
                catch (e: Exception) {
                    Log.e(TAG, "Sesli komut çalıştırılamadı: $text", e)
                    android.widget.Toast.makeText(activity,
                        "Seyfi komutu çalıştıramadı: ${e.message ?: "beklenmeyen hata"}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        },
        onStatus = { status ->
            Log.d(TAG, status)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onVoiceStatusChanged(status)
            }
        }
    )
    @Volatile private var initialized = false

    fun initializeVoiceOnly(onReady: () -> Unit) {
        if (initialized) {
            activity.runOnUiThread { onReady() }
            return
        }
        initialized = true
        voice.initialize {
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onReady()
            }
        }
    }
    fun initialize(onReady: () -> Unit) = initializeVoiceOnly(onReady)
    fun refreshRecognition(onReady: (() -> Unit)? = null) = voice.refreshRecognition(onReady)
    fun isTtsReady(): Boolean = voice.isTtsReady()
    fun isRecognitionAvailable(): Boolean = voice.isRecognitionAvailable()
    fun getVoiceStatus(): String = voice.getStatus()
    fun startListening() = voice.startListening()
    fun listenDirectAudio(onAnswer: (String) -> Unit = {}) {
        voice.startListening()
        onAnswer("Seyfi Usta dinlemeye hazır.")
    }
    fun speak(text: String, onDone: (() -> Unit)? = null) = voice.speak(text, onDone)
    fun listenForDtcCode(onResult: (List<String>) -> Unit) = voice.listenForDtcCode { phrases ->
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) onResult(phrases)
        }
    }
    fun pauseForProcessing() = voice.pauseForProcessing()
    fun stopListening() = voice.stopListening()
    fun isListening(): Boolean = voice.isListening()
    fun runCompatibilitySelfTest(): List<String> {
        val failures = mutableListOf<String>()
        if (!voice.isRecognitionAvailable()) failures += "Cihaz içi konuşma tanıma servisi bulunamadı."
        if (!voice.isTtsReady()) failures += "Türkçe metin okuma motoru hazır değil."
        failures += SeyfiUstaCommandHandler().selfTest()
        return failures
    }
    // Eski teşhis ekranı çağrılarıyla kaynak uyumluluğu; burada yapay zekâ çalıştırılmaz.
    fun updateVehicleProfile(modelYear: String) = Unit
    fun updateDiagnosticTestResult(result: String) = Unit
    fun updateDiagnosticPlan(planContext: String) = Unit
    fun updateEcuData(rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>) = Unit
    fun updateEcuDataSnapshot(values: Map<String, Double>, dtcs: List<String>) = Unit
    fun processVoiceCommand(spokenText: String) = onCommandRecognized(spokenText)
    fun release() {
        voice.release()
        initialized = false
    }
}
