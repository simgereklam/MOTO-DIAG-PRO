package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
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
        private const val MODEL_SHA256 =
            "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
        private const val MODEL_BYTES = 344686592L
    }

    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text -> onCommandRecognized(text) },
        onStatus = { status -> Log.d(TAG, status) }
    )

    val brain = SeyfiBrain(activity.applicationContext)

    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val modelTaskStarted = AtomicBoolean(false)
    private val pendingChats = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var initialized = false
    @Volatile private var modelStatus =
        "Seyfi Usta yerel yapay zekâsı hazırlanıyor."

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
                modelStatus = "Yerel yapay zekâ modeli kontrol ediliyor."
                val modelFile = ensureModelFile()
                modelStatus = "Yerel yapay zekâ cihazda başlatılıyor."
                brain.initialize(modelFile) { success, message ->
                    modelStatus = if (success) {
                        "Yerel yapay zekâ hazır."
                    } else {
                        modelTaskStarted.set(false)
                        "Yerel yapay zekâ başlatılamadı: $message"
                    }
                    if (success) {
                        Log.i(TAG, message)
                        drainPendingChats()
                    } else {
                        Log.e(TAG, modelStatus)
                        failPendingChats(modelStatus)
                    }
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                modelStatus = "Yerel yapay zekâ kullanılamıyor: ${e.message}"
                Log.e(TAG, modelStatus, e)
                activity.runOnUiThread { failPendingChats(modelStatus) }
            }
        }
    }

    /**
     * Model derleme sırasında APK assets içine eklenir.
     * İlk açılışta yalnızca uygulamanın kendi assets alanından kopyalanır;
     * telefonda model indirmek için internet bağlantısı gerekmez.
     */
    private fun ensureModelFile(): File {
        val target = File(activity.filesDir, MODEL_FILE_NAME)
        if (target.isFile && target.length() == MODEL_BYTES &&
            sha256(target).equals(MODEL_SHA256, ignoreCase = true)
        ) {
            modelStatus = "Yerel yapay zekâ modeli cihazda hazır."
            return target
        }

        if (target.exists()) target.delete()
        val temporary = File(activity.filesDir, "$MODEL_FILE_NAME.tmp")
        if (temporary.exists()) temporary.delete()

        try {
            activity.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }

            if (temporary.length() != MODEL_BYTES) {
                throw IllegalStateException(
                    "APK içindeki model eksik: ${temporary.length()} / $MODEL_BYTES bayt."
                )
            }

            val actualHash = sha256(temporary)
            if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                throw IllegalStateException(
                    "APK içindeki model SHA-256 doğrulaması başarısız: $actualHash"
                )
            }

            if (!temporary.renameTo(target)) {
                throw IllegalStateException(
                    "Doğrulanan model uygulama alanına kopyalanamadı."
                )
            }

            modelStatus = "APK içindeki model kopyalandı ve doğrulandı."
            return target
        } catch (e: Exception) {
            temporary.delete()
            throw IllegalStateException(
                "APK içinde doğrulanmış Seyfi Usta modeli bulunamadı. ${e.message}", e
            )
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

    fun pauseForProcessing() {
        voice.pauseForProcessing()
    }

    fun stopListening() {
        voice.stopListening()
    }

    fun isListening(): Boolean = voice.isListening()

    fun chat(message: String, onAnswer: (String) -> Unit) {
        if (brain.isReady()) {
            answerWithBrain(message, onAnswer)
            return
        }

        // Model açılırken gelen ilk soruyu kaybetme. Mikrofon beklemede kalır;
        // model hazır olunca gerçek yanıt üretilip seslendirilir.
        pendingChats.add(message to onAnswer)
        if (!modelTaskStarted.get()) startModelLoading()
    }

    private fun answerWithBrain(message: String, onAnswer: (String) -> Unit) {
        brain.chat(message) { answer ->
            onAnswer(answer)
            speak(answer)
        }
    }

    private fun drainPendingChats() {
        while (true) {
            val pending = pendingChats.poll() ?: break
            answerWithBrain(pending.first, pending.second)
        }
    }

    private fun failPendingChats(reason: String) {
        while (true) {
            val pending = pendingChats.poll() ?: break
            val answer = "Yerel yapay zekâ başlatılamadı. $reason"
            pending.second(answer)
            speak(answer)
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
