package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta'nın uygulamadaki tek asistan giriş noktası.
 * Vosk modeli APK içindedir; büyük Qwen modeli ilk kullanımda ayrıca indirilir.
 * Kısmi indirme korunur ve sunucu Range destekliyorsa kaldığı yerden sürer.
 */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SeyfiUstaAI"
        private const val MODEL_FILE_NAME = "seyfi_model.litertlm"
        private const val MIN_MODEL_BYTES = 300_000_000L
        private const val MAX_MODEL_BYTES = 400_000_000L
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e6bb3e4a1eb3f663153f0c1c12b735d64be657a7/Qwen3-0.6B_dynamic_wi4b32_afp32_gpu_composites.litertlm?download=true"
    }

    private val voice = OfflineVoiceAssistant(
        activity = activity,
        onRecognized = { text -> onCommandRecognized(text) },
        onStatus = { status ->
            Log.d(TAG, status)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onVoiceStatusChanged(status)
            }
        }
    )

    val brain = SeyfiBrain(activity.applicationContext)
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val modelTaskStarted = AtomicBoolean(false)
    private val pendingChats = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var initialized = false
    @Volatile private var modelStatus = "Seyfi Usta yapay zekâ modeli henüz indirilmedi."

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true
        // Büyük Qwen modeli açılışta indirilmez/yüklenmez.
        // Mikrofon ve uyandırma sözü hazır olduktan sonra, ilk sohbet sorusunda yüklenir.
        try {
            voice.initialize(onReady)
        } catch (e: Exception) {
            Log.e(TAG, "Ses sistemi başlatılamadı", e)
            onReady()
        }
    }

    private fun publishModelStatus(message: String) {
        modelStatus = message
        Log.i(TAG, message)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) onVoiceStatusChanged(message)
        }
    }

    private fun startModelLoading() {
        if (!modelTaskStarted.compareAndSet(false, true)) return
        modelExecutor.execute {
            try {
                val modelFile = ensureModelFile()
                publishModelStatus("Seyfi Usta yerel yapay zekâsı başlatılıyor.")
                brain.initialize(modelFile) { success, message ->
                    modelStatus = if (success) {
                        "Seyfi Usta yerel yapay zekâsı hazır."
                    } else {
                        modelTaskStarted.set(false)
                        "Seyfi Usta yapay zekâ başlatılamadı: $message"
                    }
                    publishModelStatus(modelStatus)
                    if (success) drainPendingChats() else failPendingChats(modelStatus)
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                val failure = "Seyfi Usta modeli indirilemedi: ${e.message}"
                publishModelStatus(failure)
                activity.runOnUiThread { failPendingChats(failure) }
            }
        }
    }

    /** İlk açılışta indirir; .part dosyası sayesinde kesilen indirme korunur. */
    private fun ensureModelFile(): File {
        val target = File(activity.filesDir, MODEL_FILE_NAME)
        if (target.isFile && target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            publishModelStatus("Seyfi Usta yapay zekâ modeli cihazda hazır.")
            return target
        }
        if (target.exists()) target.delete()

        val partial = File(activity.filesDir, "$MODEL_FILE_NAME.part")
        var downloaded = if (partial.isFile) partial.length() else 0L
        if (downloaded > MAX_MODEL_BYTES) {
            partial.delete()
            downloaded = 0L
        }

        var connection: HttpURLConnection? = null
        try {
            publishModelStatus(
                if (downloaded > 0L) "Seyfi Usta yapay zekâ indirmesine kaldığı yerden devam ediyor."
                else "Seyfi Usta yapay zekâ modeli indiriliyor: 0%"
            )

            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 25_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MOTO-DIAG-PRO-Android/1.0")
                setRequestProperty("Accept-Encoding", "identity")
                if (downloaded > 0L) setRequestProperty("Range", "bytes=$downloaded-")
            }

            val response = connection.responseCode
            val append = when {
                response == HttpURLConnection.HTTP_PARTIAL && downloaded > 0L -> true
                response == HttpURLConnection.HTTP_OK -> {
                    // Sunucu Range desteklemiyorsa bozuk birleştirme yapma; baştan başla.
                    downloaded = 0L
                    false
                }
                else -> throw IllegalStateException("Model sunucusu HTTP $response yanıtı verdi.")
            }

            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val totalBytes = if (responseLength > 0L) downloaded + responseLength else -1L
            if (totalBytes > MAX_MODEL_BYTES) {
                throw IllegalStateException("Model dosyası beklenen boyut sınırını aşıyor.")
            }

            var lastReportedStep = -1
            var lastReportedMb = -1L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var currentBytes = downloaded
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        currentBytes += count
                        if (currentBytes > MAX_MODEL_BYTES) {
                            throw IllegalStateException("İndirilen model dosyası boyut sınırını aştı.")
                        }

                        if (totalBytes > 0L) {
                            val percent = ((currentBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            val step = percent / 10
                            if (step > lastReportedStep) {
                                lastReportedStep = step
                                publishModelStatus("Seyfi Usta yapay zekâ modeli indiriliyor: ${step * 10}%")
                            }
                        } else {
                            val mb = currentBytes / (1024L * 1024L)
                            if (mb >= lastReportedMb + 20L) {
                                lastReportedMb = mb
                                publishModelStatus("Seyfi Usta yapay zekâ modeli indiriliyor: ${mb} MB")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (partial.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                throw IllegalStateException(
                    "İndirilen model eksik veya geçersiz (${partial.length()} bayt). Bağlantıyı kontrol edip yeniden deneyin."
                )
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("İndirilen model dosyası kaydedilemedi.")
            }
            publishModelStatus("Seyfi Usta yapay zekâ modeli indirildi ve doğrulandı.")
            return target
        } finally {
            connection?.disconnect()
        }
    }

    fun getModelStatus(): String = modelStatus
    fun getVoiceStatus(): String = voice.getStatus()
    fun speak(text: String, onDone: (() -> Unit)? = null) = voice.speak(text, onDone)
    fun startListening() = voice.startListening()
    fun pauseForProcessing() = voice.pauseForProcessing()
    fun stopListening() = voice.stopListening()
    fun isListening(): Boolean = voice.isListening()

    fun chat(message: String, onAnswer: (String) -> Unit) {
        if (brain.isReady()) {
            answerWithBrain(message, onAnswer)
            return
        }
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

    fun chat(message: String): String =
        if (brain.isReady()) "Sorunuzu yerel yapay zekâya iletiyorum: $message" else modelStatus

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

    fun processVoiceCommand(spokenText: String) = onCommandRecognized(spokenText)

    fun release() {
        voice.release()
        brain.close()
        modelExecutor.shutdownNow()
        initialized = false
    }
}
