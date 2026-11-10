package com.efemoto.motodiagpro

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta'nın tek giriş noktası.
 * Kullanıcı sesi Vosk/ASR üzerinden metne çevrilmez; 16 kHz WAV doğrudan
 * Gemma 4 E2B'nin yerel ses girişine verilir. Model ilk kurulumda Wi-Fi
 * üzerinden indirilir, sonraki kullanımlarda tamamen cihaz üzerinde çalışır.
 */
class SeyfiUstaAI(
    private val activity: Activity,
    @Suppress("unused") private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {},
    private val onAppAction: (String) -> String = { "Uygulama işlem köprüsü hazır değil." },
    private val onVoiceResponse: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SeyfiUstaAI"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
        private const val MIN_MODEL_BYTES = 2_400_000_000L
        private const val MAX_MODEL_BYTES = 2_800_000_000L
    }

    private val voice = DirectAudioAssistant(
        activity = activity,
        onAudioCaptured = { wav -> handleVoiceAudio(wav) },
        onStatus = { status ->
            Log.d(TAG, status)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onVoiceStatusChanged(status)
            }
        }
    )

    val brain = SeyfiBrain(
        activity.applicationContext,
        com.google.ai.edge.litertlm.tool(SeyfiAppTools(onAppAction))
    )
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val chatTimeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val modelTaskStarted = AtomicBoolean(false)
    private val chatInProgress = AtomicBoolean(false)
    private val readyNotified = AtomicBoolean(false)
    private val pendingChats = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()

    @Volatile private var initialized = false
    @Volatile private var voiceReady = false
    @Volatile private var modelReady = false
    @Volatile private var modelStatus = "Gemma 4 E2B yerel modeli hazırlanıyor."
    @Volatile private var readyCallback: (() -> Unit)? = null

    fun initialize(onReady: () -> Unit) {
        readyCallback = onReady
        if (readyNotified.get()) {
            activity.runOnUiThread { onReady() }
            return
        }
        if (!initialized) {
            initialized = true
            voice.initialize {
                voiceReady = true
                maybeNotifyReady()
            }
            startModelLoading()
        }
    }

    private fun maybeNotifyReady() {
        if (voiceReady && modelReady && readyNotified.compareAndSet(false, true)) {
            publishModelStatus("Seyfi Usta doğrudan ses anlayan Gemma 4 yapay zekâsı hazır.")
            activity.runOnUiThread { readyCallback?.invoke() }
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
                publishModelStatus("Gemma 4 E2B modeli açılıyor; telefonun belleği hazırlanıyor.")
                brain.initialize(modelFile) { success, message ->
                    if (success) {
                        modelReady = true
                        modelStatus = "Gemma 4 E2B hazır."
                        publishModelStatus("Gemma 4 E2B yerel yapay zekâsı hazır.")
                        maybeNotifyReady()
                        drainPendingChats()
                    } else {
                        modelTaskStarted.set(false)
                        publishModelStatus("Gemma 4 E2B başlatılamadı: $message")
                        failPendingChats(modelStatus)
                    }
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                publishModelStatus("Gemma 4 E2B hazırlanamadı: ${e.message}")
                activity.runOnUiThread { failPendingChats(modelStatus) }
            }
        }
    }

    private fun ensureModelFile(): File {
        val target = File(activity.filesDir, MODEL_FILE_NAME)
        if (target.isFile && target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            publishModelStatus("Gemma 4 E2B modeli telefonda mevcut.")
            return target
        }
        if (target.exists()) target.delete()
        val partial = File(activity.filesDir, "$MODEL_FILE_NAME.part")
        if (partial.length() > MAX_MODEL_BYTES) partial.delete()

        if (partial.length() < MIN_MODEL_BYTES) {
            val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                throw IllegalStateException(
                    "Gemma 4 E2B modeli yaklaşık 2,58 GB. Mobil veri kullanmamak için Wi-Fi ağına bağlanıp uygulamayı yeniden açın."
                )
            }
            downloadModel(partial)
        }

        if (partial.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            throw IllegalStateException("Model dosyası eksik veya geçersiz: ${partial.length()} bayt.")
        }
        if (!partial.renameTo(target)) {
            throw IllegalStateException("Gemma modeli uygulama alanına taşınamadı.")
        }
        publishModelStatus("Gemma 4 E2B modeli indirildi ve doğrulandı.")
        return target
    }

    private fun downloadModel(partial: File) {
        var offset = partial.length()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                partial.delete()
                offset = 0L
            }
            if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IllegalStateException("Model indirme sunucusu HTTP $code döndürdü.")
            }
            val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            val initialBytes = if (append) offset else 0L
            val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val totalBytes = if (declaredLength > 0L) initialBytes + declaredLength else -1L
            var lastReport = -1
            FileOutputStream(partial, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = initialBytes
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_MODEL_BYTES) {
                            throw IllegalStateException("İndirilen model beklenen boyutu aşıyor.")
                        }
                        output.write(buffer, 0, count)
                        val percent = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else -1
                        if (percent >= 0 && percent / 20 > lastReport / 20) {
                            lastReport = percent
                            publishModelStatus("Gemma 4 E2B modeli indiriliyor: %$percent")
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun getModelStatus(): String = modelStatus
    fun getVoiceStatus(): String = if (voice.isListening()) "Doğrudan ses dinleniyor." else "Ses beklemede."
    fun speak(text: String, onDone: (() -> Unit)? = null) = voice.speak(text, onDone)
    fun startListening() = voice.startListening()
    fun pauseForProcessing() = voice.pauseForProcessing()
    fun stopListening() = voice.stopListening()
    fun isListening(): Boolean = voice.isListening()

    private fun handleVoiceAudio(wav: ByteArray) {
        voice.pauseForProcessing()
        publishModelStatus("Konuşma doğrudan Gemma 4 tarafından dinleniyor ve yorumlanıyor.")
        if (!brain.isReady()) {
            val failure = "Gemma 4 henüz hazır değil. Model durumunu kontrol edin."
            onVoiceResponse(failure)
            voice.speak(failure)
            return
        }
        brain.askAudio(wav) { answer ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                onVoiceResponse(answer)
                speak(answer)
            }
        }
    }

    fun chat(message: String, onAnswer: (String) -> Unit) {
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return
        val pending = cleanMessage to onAnswer
        pendingChats.add(pending)
        if (brain.isReady()) {
            drainPendingChats()
            return
        }
        publishModelStatus("Gemma 4 hazırlanıyor; yazılı mesaj sıraya alındı.")
        chatTimeoutExecutor.schedule({
            if (pendingChats.remove(pending)) {
                val failure = "Gemma 4 henüz hazır değil. Model indirme/yükleme durumunu kontrol edin."
                activity.runOnUiThread { onAnswer(failure) }
            }
        }, 180, TimeUnit.SECONDS)
        if (!modelTaskStarted.get()) startModelLoading()
    }

    private fun answerWithBrain(
        message: String,
        onAnswer: (String) -> Unit,
        onFinished: () -> Unit
    ) {
        publishModelStatus("Seyfi Usta yanıt hazırlıyor.")
        val watchdog = chatTimeoutExecutor.schedule({
            publishModelStatus("Yapay zekâ yanıtı gecikiyor; cihaz üzerindeki işlem sürüyor.")
        }, 120, TimeUnit.SECONDS)

        try {
            brain.chat(message) { answer ->
                watchdog.cancel(false)
                activity.runOnUiThread {
                    try {
                        onAnswer(answer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Yanıt ekrana aktarılamadı", e)
                    } finally {
                        onFinished()
                    }
                }
            }
        } catch (e: Exception) {
            watchdog.cancel(false)
            activity.runOnUiThread {
                onAnswer("Yerel yapay zekâ yanıt üretemedi: ${e.message}")
                onFinished()
            }
        }
    }

    private fun drainPendingChats() {
        if (!brain.isReady() || !chatInProgress.compareAndSet(false, true)) return
        val pending = pendingChats.poll()
        if (pending == null) {
            chatInProgress.set(false)
            return
        }
        answerWithBrain(pending.first, pending.second) {
            chatInProgress.set(false)
            drainPendingChats()
        }
    }

    private fun failPendingChats(reason: String) {
        while (true) {
            val pending = pendingChats.poll() ?: break
            val answer = "Yerel yapay zekâ başlatılamadı. $reason"
            activity.runOnUiThread { pending.second(answer) }
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

    fun processVoiceCommand(spokenText: String) = onCommandRecognized(spokenText)

    fun release() {
        voice.release()
        brain.close()
        modelExecutor.shutdownNow()
        chatTimeoutExecutor.shutdownNow()
        initialized = false
    }
}
