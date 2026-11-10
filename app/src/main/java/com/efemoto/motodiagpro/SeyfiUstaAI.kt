package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta'nın uygulamadaki tek asistan giriş noktası.
 * Vosk ve Gemma 3 1B modelleri derleme sırasında APK assets içine paketlenir.
 * Telefonda ilk açılışta yalnızca uygulama alanına kopyalanır; internet gerekmez.
 */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {},
    private val onAppAction: (String) -> String = { "Uygulama işlem köprüsü hazır değil." }
) {
    companion object {
        private const val TAG = "SeyfiUstaAI"
        private const val MODEL_FILE_NAME = "seyfi_model.litertlm"
        private const val MODEL_ASSET_NAME = "seyfi_model.litertlm"
        // Gemma 3 1B IT int4 QAT: 584,417,280 bayt (LiteRT Community model card).
        private const val MIN_MODEL_BYTES = 570_000_000L
        private const val MAX_MODEL_BYTES = 600_000_000L
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

    val brain = SeyfiBrain(
        activity.applicationContext,
        com.google.ai.edge.litertlm.tool(SeyfiAppTools(onAppAction))
    )
    private val modelExecutor = Executors.newSingleThreadExecutor()
    // Yerel model yanıt üretirken takılırsa sesli asistan sonsuza kadar mikrofonu kapalı tutmasın.
    private val chatTimeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val modelTaskStarted = AtomicBoolean(false)
    private val pendingChats = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var initialized = false
    @Volatile private var modelStatus = "APK içindeki Seyfi Usta yapay zekâsı hazırlanıyor."

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true
        // Ses tanıma başlatılırken APK'ya gömülü yapay zekâ da arka planda
        // hazırlanır. İlk sohbet için internetten model indirme beklenmez.
        try {
            voice.initialize(onReady)
            startModelLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Asistan başlatılamadı", e)
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
                val failure = "APK içindeki Seyfi Usta modeli hazırlanamadı: ${e.message}"
                publishModelStatus(failure)
                activity.runOnUiThread { failPendingChats(failure) }
            }
        }
    }

    /** Model APK assets içinden kopyalanır; çalışma anında internet bağlantısı kullanılmaz. */
    private fun ensureModelFile(): File {
        val target = File(activity.filesDir, MODEL_FILE_NAME)
        if (target.isFile && target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            publishModelStatus("Seyfi Usta yapay zekâ modeli uygulamada hazır.")
            return target
        }
        if (target.exists()) target.delete()

        val partial = File(activity.filesDir, "$MODEL_FILE_NAME.part")
        if (partial.exists()) partial.delete()

        publishModelStatus("APK içindeki yapay zekâ modeli telefona hazırlanıyor.")
        try {
            activity.assets.open(MODEL_ASSET_NAME).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MODEL_BYTES) {
                            throw IllegalStateException("APK içindeki yapay zekâ modeli beklenen boyutu aşıyor.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            if (partial.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                throw IllegalStateException(
                    "APK içindeki yapay zekâ modeli eksik veya geçersiz (${partial.length()} bayt)."
                )
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Yapay zekâ modeli uygulama alanına kopyalanamadı.")
            }
            publishModelStatus("APK içindeki yapay zekâ modeli hazır; yerel motor başlatılıyor.")
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
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

        val pending = message to onAnswer
        pendingChats.add(pending)
        publishModelStatus("Yerel model henüz hazır değil; mesaj kısa süreliğine bekletiliyor.")

        // Model başlatma takılırsa kullanıcıya sonsuza kadar "gönderildi" denmesin.
        // Kuyrukta hâlâ duran istek 30 saniye içinde açık bir hata alır.
        chatTimeoutExecutor.schedule({
            if (pendingChats.remove(pending)) {
                val failure = "Yerel yapay zekâ 30 saniye içinde hazır olmadı. Model yükleme durumunu kontrol edin; tekrar deneyebilirsiniz."
                publishModelStatus("Model yüklemesi tamamlanmadı; bekleyen mesaj sonlandırıldı.")
                activity.runOnUiThread {
                    try {
                        onAnswer(failure)
                    } finally {
                        speak(failure)
                    }
                }
            }
        }, 30, TimeUnit.SECONDS)

        if (!modelTaskStarted.get()) startModelLoading()
    }

    private fun answerWithBrain(message: String, onAnswer: (String) -> Unit) {
        publishModelStatus("Seyfi Usta düşünüyor ve yanıt hazırlıyor.")
        Log.i(TAG, "Sohbet isteği Qwen'e gönderildi: ${message.take(240)}")

        val completed = AtomicBoolean(false)
        val timeout = chatTimeoutExecutor.schedule({
            if (completed.compareAndSet(false, true)) {
                val fallback = "Usta şu anda yerel yapay zekâdan zamanında yanıt alamadı. Mikrofonu yeniden açıyorum; biraz daha kısa bir soru sorabilirsiniz."
                Log.e(TAG, "Yerel yapay zekâ 25 saniyede yanıt üretemedi.")
                publishModelStatus("Yerel yapay zekâ 25 saniyede yanıt üretemedi; sesli dinleme yeniden başlatılıyor.")
                activity.runOnUiThread {
                    try {
                        onAnswer(fallback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Zaman aşımı yanıtı ekrana aktarılamadı", e)
                    } finally {
                        speak(fallback)
                    }
                }
            }
        }, 25, TimeUnit.SECONDS)

        try {
            brain.chat(message) { answer ->
                if (completed.compareAndSet(false, true)) {
                    timeout.cancel(false)
                    Log.i(TAG, "Qwen yanıtı alındı: ${answer.take(600)}")
                    try {
                        onAnswer(answer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Yanıt ekrana aktarılamadı", e)
                    } finally {
                        speak(answer)
                    }
                } else {
                    Log.w(TAG, "Zaman aşımından sonra gelen geç yanıt yok sayıldı.")
                }
            }
        } catch (e: Exception) {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false)
                val failure = "Yerel yapay zekâ yanıt üretemedi: ${e.message}"
                activity.runOnUiThread {
                    try { onAnswer(failure) } finally { speak(failure) }
                }
            }
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
        chatTimeoutExecutor.shutdownNow()
        initialized = false
    }
}
