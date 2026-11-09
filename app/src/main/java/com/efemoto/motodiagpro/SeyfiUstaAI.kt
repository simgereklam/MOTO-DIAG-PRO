package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
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
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true"
        private const val MODEL_SHA256 =
            "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
        private const val MODEL_BYTES = 344686592L
        private const val MODEL_CHUNK_BYTES = 16L * 1024L * 1024L
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
                    if (success) Log.i(TAG, message) else Log.e(TAG, modelStatus)
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                modelStatus = "Yerel yapay zekâ kullanılamıyor: ${e.message}"
                Log.e(TAG, modelStatus, e)
            }
        }
    }

    /**
     * Modeli ilk kullanımda Hugging Face üzerinden parça parça indirir.
     * Her aralık Content-Range ile doğrulanır; dosya yalnızca tam boyut ve
     * SHA-256 kontrolünden geçerse etkin model dosyasına taşınır.
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
        val partial = File(activity.filesDir, "$MODEL_FILE_NAME.part")
        if (partial.exists()) partial.delete()

        try {
            downloadModelInChunks(partial)

            if (partial.length() != MODEL_BYTES) {
                throw IllegalStateException(
                    "Model dosyası eksik: " + partial.length() + " / " + MODEL_BYTES + " bayt."
                )
            }

            val actualHash = sha256(partial)
            if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                throw IllegalStateException(
                    "Model SHA-256 doğrulaması başarısız: " + actualHash
                )
            }

            if (!partial.renameTo(target)) {
                throw IllegalStateException(
                    "Doğrulanan model cihazın uygulama alanına taşınamadı."
                )
            }

            modelStatus = "Model indirildi ve doğrulandı."
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private fun downloadModelInChunks(target: File) {
        target.parentFile?.mkdirs()
        var start = 0L

        while (start < MODEL_BYTES) {
            val end = minOf(start + MODEL_CHUNK_BYTES - 1L, MODEL_BYTES - 1L)
            val expectedChunkBytes = end - start + 1L
            var connection: HttpURLConnection? = null

            try {
                modelStatus = "Seyfi Usta modeli indiriliyor: " +
                    (start * 100L / MODEL_BYTES).toInt() + "%"

                val active = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 120000
                    instanceFollowRedirects = true
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MOTO-DIAG-PRO-Android/1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=$start-$end")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                connection = active

                val status = active.responseCode
                if (status != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException(
                        "Model sunucusu aralıklı indirmeyi kabul etmedi (HTTP $status)."
                    )
                }

                val expectedRange = "bytes $start-$end/$MODEL_BYTES"
                val receivedRange = active.getHeaderField("Content-Range")?.trim()
                if (receivedRange != expectedRange) {
                    throw IllegalStateException(
                        "Model parça aralığı doğrulanamadı. Gelen: " +
                            receivedRange + "; beklenen: " + expectedRange
                    )
                }

                var received = 0L
                active.inputStream.use { input ->
                    FileOutputStream(target, true).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (received < expectedChunkBytes) {
                            val wanted = minOf(
                                buffer.size.toLong(),
                                expectedChunkBytes - received
                            ).toInt()
                            val count = input.read(buffer, 0, wanted)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                        }
                        output.fd.sync()
                    }
                }

                if (received != expectedChunkBytes) {
                    throw IllegalStateException(
                        "Model parçası eksik indirildi: " +
                            received + " / " + expectedChunkBytes + " bayt."
                    )
                }
            } finally {
                connection?.disconnect()
            }

            start = end + 1L
        }

        modelStatus = "Model indirildi; bütünlük kontrolü yapılıyor."
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
                    "Yerel yapay zekâ modelini hazırlıyorum. İlk kullanımda model internet üzerinden cihaza indiriliyor. İndirme tamamlanınca çevrimdışı çalışacak."
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
