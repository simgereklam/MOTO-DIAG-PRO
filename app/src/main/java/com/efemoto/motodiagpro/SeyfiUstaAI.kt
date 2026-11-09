package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta'nın uygulamadaki tek asistan giriş noktası.
 *
 * - Mikrofon ve Türkçe konuşma: OfflineVoiceAssistant
 * - Yerel üretken model: LiteRT-LM / Qwen3 0.6B
 * - ECU komutları: MainActivity ve SeyfiUstaCommandHandler
 *
 * Model APK assets içindedir. İlk açılışta uygulama özel alanına kopyalanır;
 * cihazda yerel çalışır ve çalışma sırasında internetten model indirmez.
 */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit
) {
    companion object {
        private const val TAG = "SeyfiUstaAI"
        private const val MODEL_FILE_NAME = "seyfi_model.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_SHA256 =
            "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
        private const val MIN_MODEL_BYTES = 300L * 1024L * 1024L
    }

    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text -> onCommandRecognized(text) },
        onStatus = { status -> Log.d(TAG, status) }
    )

    val brain = SeyfiBrain(activity.applicationContext)

    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val modelTaskStarted = AtomicBoolean(false)
    @Volatile private var initialized = false
    @Volatile private var modelStatus =
        "Seyfi Usta APK içindeki yerel yapay zekâ hazırlanıyor."

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true

        // Model hazırlığını konuşma servisinden bağımsız başlat.
        // Cihazda konuşma servisi yoksa da sohbet modeli yüklenebilsin.
        startModelLoading()

        try {
            // Sesli komutlar model indirilirken de kullanılabilir.
            voice.initialize(onReady)
        } catch (e: Exception) {
            Log.e(TAG, "Ses sistemi başlatılamadı", e)
            onReady()
        }
    }

    private fun startModelLoading() {
        if (!modelTaskStarted.compareAndSet(false, true)) return
        modelExecutor.execute {
            try {
                modelStatus = "APK içindeki yerel yapay zekâ modeli kontrol ediliyor."
                val modelFile = ensureModelFile()
                modelStatus = "Yerel yapay zekâ cihazda başlatılıyor."
                brain.initialize(modelFile) { success, message ->
                    modelStatus = if (success) {
                        "Yerel yapay zekâ hazır."
                    } else {
                        modelTaskStarted.set(false)
                        "Yerel yapay zekâ başlatılamadı: $message"
                    }
                    if (success) Log.i(TAG, message) else Log.e(TAG, modelStatus)
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                modelStatus = "Yerel yapay zekâ kullanılamıyor: ${e.message}"
                Log.e(TAG, modelStatus, e)
            }
        }
    }

    /** APK içine gömülü ve SHA-256 ile doğrulanan modeli yerel dosyaya hazırlar. */
    private fun ensureModelFile(): File {
        val target = File(activity.filesDir, MODEL_FILE_NAME)
        if (target.exists() && target.length() >= MIN_MODEL_BYTES &&
            sha256(target).equals(MODEL_SHA256, ignoreCase = true)
        ) return target

        if (target.exists()) target.delete()
        val partial = File(activity.filesDir, "$MODEL_FILE_NAME.part")
        if (partial.exists()) partial.delete()

        try {
            activity.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Seyfi Usta model kopyalama işlemi durduruldu.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("APK içindeki Seyfi Usta model dosyası eksik.")
            }
            if (!sha256(partial).equals(MODEL_SHA256, ignoreCase = true)) {
                throw IllegalStateException("APK içindeki Seyfi Usta modelinin SHA-256 doğrulaması başarısız.")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Seyfi Usta modeli cihazın yerel alanına kopyalanamadı.")
            }
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun getModelStatus(): String = modelStatus

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
        if (!brain.isReady() && !modelTaskStarted.get()) {
            // İlk açılışta internet yoksa model indirilememiş olabilir.
            // Sonraki sohbet isteğinde bağlantı yeniden denenir.
            startModelLoading()
        }
        if (brain.isReady()) {
            brain.chat(message) { answer ->
                onAnswer(answer)
                speak(answer)
            }
        } else {
            val fallback = when {
                modelStatus.contains("indiriliyor", ignoreCase = true) ||
                    modelStatus.contains("kontrol ediliyor", ignoreCase = true) ||
                    modelStatus.contains("yükleniyor", ignoreCase = true) ->
                    "APK içindeki yerel yapay zekâyı hazırlıyorum. İlk açılışta model cihazın uygulama alanına kopyalanıyor. Biraz sonra tekrar deneyin."
                else ->
                    "Yerel yapay zekâ şu anda hazır değil. Durum: $modelStatus. ECU tarama, arıza okuma ve canlı veri gibi tanımlı teşhis komutları kullanılabilir."
            }
            onAnswer(fallback)
            speak(fallback)
        }
    }

    fun chat(message: String): String {
        return if (brain.isReady()) {
            "Sorunuzu yerel yapay zekâya iletiyorum: $message"
        } else {
            modelStatus
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
        modelExecutor.shutdownNow()
        initialized = false
    }
}
