package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log

/** Seyfi Usta'nın tek uygulama giriş noktası: kendi yerel teşhis çekirdeği ve Android ses katmanı. */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {}
) {
    companion object { private const val TAG = "SeyfiUstaAI" }
    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text ->
            try { onCommandRecognized(text) } catch (e: Exception) {
                Log.e(TAG, "Sesli komut aktarılamadı", e)
            }
        },
        onStatus = { status ->
            Log.d(TAG, status)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onVoiceStatusChanged(status)
            }
        }
    )
    val brain = SeyfiBrain(activity.applicationContext)
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
    fun getModelStatus(): String =
        "Hazır dil modeli kullanılmıyor. Bağımsız yerel teşhis çekirdeği etkin; öğrenilmiş sinir ağı henüz eğitilmedi."

    fun listenDirectAudio(onAnswer: (String) -> Unit = {}) {
        voice.startListening()
        onAnswer("Seyfi Usta dinlemeye hazır.")
    }
    fun speak(text: String, onDone: (() -> Unit)? = null) = voice.speak(text, onDone)
    fun startListening() = voice.startListening()
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

    fun runModelSelfTest(onResult: (Boolean, String) -> Unit) {
        val errors = SeyfiUstaCommandHandler().selfTest()
        val ok = errors.isEmpty() && brain.isReady()
        onResult(ok, if (ok) "Bağımsız yerel teşhis çekirdeği ve komut sözlüğü hazır." else errors.joinToString())
    }
    fun analyzeCurrentEngineData(onResult: (Boolean, String) -> Unit) {
        brain.analyzeCurrentEngineData { answer ->
            val failed = answer.contains("ECU bağlantısı yok", true) ||
                answer.contains("geçerli canlı ölçüm alınmamış", true)
            onResult(!failed, answer)
            if (!failed) speak(answer)
        }
    }
    fun chat(message: String, onAnswer: (String) -> Unit) {
        pauseForProcessing()
        brain.chat(message) { answer ->
            activity.runOnUiThread {
                try { onAnswer(answer) } finally { speak(answer) }
            }
        }
    }
    fun updateVehicleProfile(modelYear: String) = brain.updateVehicleProfile(modelYear)
    fun updateDiagnosticTestResult(result: String) = brain.updateDiagnosticTestResult(result)
    fun updateDiagnosticPlan(planContext: String) = brain.updateDiagnosticPlan(planContext)
    fun updateEcuData(
        rpm: Int, tps: Float, ect: Int, map: Float, voltage: Float, dtcs: List<String>
    ) = brain.updateEcuData(rpm, tps.toInt(), ect, map.toInt(), voltage.toDouble(), dtcs, true)
    fun updateEcuDataSnapshot(values: Map<String, Double>, dtcs: List<String>) =
        brain.updateEcuDataSnapshot(values, dtcs, connected = true)
    fun processVoiceCommand(spokenText: String) = onCommandRecognized(spokenText)
    fun release() {
        voice.release()
        brain.close()
        initialized = false
    }
}
