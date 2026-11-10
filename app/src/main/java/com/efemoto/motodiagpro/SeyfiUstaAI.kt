package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.os.StatFs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta'nın uygulamadaki tek asistan giriş noktası.
 * Türkçe Vosk dinleme modeli APK'da kalır; Gemma 4 E2B modeli ilk açılışta
 * uygulamanın özel depolamasına indirilir ve sonraki açılışlarda çevrimdışı kullanılır.
 */
class SeyfiUstaAI(
    private val activity: Activity,
    private val onCommandRecognized: (String) -> Unit,
    private val onVoiceStatusChanged: (String) -> Unit = {},
    private val onAppAction: (String) -> String = { "Uygulama işlem köprüsü hazır değil." }
) {
    companion object {
        private const val TAG = "SeyfiUstaAI"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val MIN_MODEL_BYTES = 2_500_000_000L
        private const val MAX_MODEL_BYTES = 2_800_000_000L
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
    // Sohbet istekleri tek tek işlenir; aynı Conversation üzerinde paralel üretim yoktur.
    private val chatInProgress = AtomicBoolean(false)
    private val pendingChats = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var initialized = false
    @Volatile private var voiceReady = false
    @Volatile private var initializationCallback: (() -> Unit)? = null
    @Volatile private var modelStatus = "Gemma 4 E2B modeli kontrol ediliyor."

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true
        initializationCallback = onReady
        // Mikrofonu, Gemma 4 gerçekten açılıp deneme çıkarımı tamamlanmadan
        // komut dinlemeye başlatma. İlk kurulumda model indirmesi beklenir.
        try {
            voice.initialize {
                voiceReady = true
                completeStartupIfReady()
            }
            startModelLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Asistan başlatılamadı", e)
            onReady()
        }
    }

    private fun completeStartupIfReady() {
        // Vosk + TTS, Gemma modelinden bağımsızdır. İlk model indirmesi sürerken
        // yerel uyandırma ve temel uygulama komutları da kullanılabilsin.
        if (!voiceReady) return
        val callback = initializationCallback ?: return
        initializationCallback = null
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) callback()
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
                publishModelStatus("Gemma 4 E2B yerel yapay zekâsı başlatılıyor.")
                brain.initialize(modelFile) { success, message ->
                    modelStatus = if (success) {
                        "Gemma 4 E2B yerel yapay zekâsı hazır."
                    } else {
                        modelTaskStarted.set(false)
                        "Gemma 4 E2B başlatılamadı: $message"
                    }
                    publishModelStatus(modelStatus)
                    if (success) {
                        completeStartupIfReady()
                        drainPendingChats()
                    } else {
                        failPendingChats(modelStatus)
                    }
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                val failure = "Gemma 4 E2B modeli hazırlanamadı: ${e.message}"
                publishModelStatus(failure)
                activity.runOnUiThread { failPendingChats(failure) }
            }
        }
    }

    /**
     * Gemma 4 E2B ilk kurulumda Hugging Face'ten uygulamanın özel alanına indirilir.
     * İndirme tamamlanmadan .part dosyası motor tarafından kullanılmaz.
     */
    private fun modelStorageDirectories(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += File(activity.filesDir, "models")
        activity.getExternalFilesDir("models")?.let { candidates += it }
        return candidates.distinctBy { it.absolutePath }
    }

    private fun ensureModelFile(): File {
        val directories = modelStorageDirectories()
        directories.forEach { dir ->
            val installed = File(dir, MODEL_FILE_NAME)
            if (installed.isFile && installed.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                publishModelStatus("Gemma 4 E2B telefonda kurulu; çevrimdışı başlatılıyor.")
                return installed
            }
        }

        val available = directories.mapNotNull { dir ->
            try {
                if (!dir.exists() && !dir.mkdirs()) return@mapNotNull null
                dir to StatFs(dir.absolutePath).availableBytes
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.second }

        val minimumFreeBytes = MIN_MODEL_BYTES + 150_000_000L
        if (available == null || available.second < minimumFreeBytes) {
            val freeGb = (available?.second ?: 0L) / 1_000_000_000.0
            val needGb = minimumFreeBytes / 1_000_000_000.0
            throw IllegalStateException(
                "Gemma 4 E2B yaklaşık 2,6 GB. Kurulum için en az %.1f GB boş alan gerekiyor. " +
                "Uygulamanın erişebildiği en geniş depolama alanında %.1f GB boş yer var. " +
                "En az 3 GB boşaltıp tekrar deneyin.".format(java.util.Locale.US, needGb, freeGb)
            )
        }

        val modelDirectory = available.first
        val target = File(modelDirectory, MODEL_FILE_NAME)
        val partial = File(modelDirectory, "$MODEL_FILE_NAME.part")
        if (target.exists()) target.delete()
        if (partial.exists()) partial.delete()

        publishModelStatus(
            "Gemma 4 E2B indiriliyor. Uygun depolama alanı: %.1f GB".format(
                java.util.Locale("tr", "TR"), available.second / 1_000_000_000.0
            )
        )
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MOTO-DIAG-PRO/1.0")
            }
            connection.connect()
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Model sunucusu HTTP $response yanıtı verdi.")
            }

            val expectedBytes = connection.contentLengthLong
            if (expectedBytes !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                throw IllegalStateException("Model dosyasının sunucu boyutu beklenenden farklı: $expectedBytes bayt.")
            }
            if (StatFs(modelDirectory.absolutePath).availableBytes < expectedBytes + 100_000_000L) {
                throw IllegalStateException("İndirme başladıktan önce depolama alanı yetersiz kaldı. En az 3 GB boş alan açın.")
            }

            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MODEL_BYTES) {
                            throw IllegalStateException("İndirilen model izin verilen boyutu aştı.")
                        }
                        output.write(buffer, 0, count)
                        val percent = (total * 100L / expectedBytes).toInt()
                        if (percent != lastPercent && percent % 5 == 0) {
                            lastPercent = percent
                            publishModelStatus("Gemma 4 E2B indiriliyor: %$percent")
                        }
                    }
                    output.fd.sync()
                }
            }

            if (total != expectedBytes || partial.length() != expectedBytes) {
                throw IllegalStateException("Model indirmesi eksik. Gelen: $total / Beklenen: $expectedBytes bayt.")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Gemma 4 modeli uygulama alanına taşınamadı.")
            }
            publishModelStatus("Gemma 4 E2B indirildi; yerel motor başlatılıyor.")
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Uyandırma sözü sonrasında kullanıcı sesini metne çevirmeden Gemma 4'e aktarır.
     */
    fun listenDirectAudio(onAnswer: (String) -> Unit = {}) {
        if (!brain.isReady()) {
            val message = if (modelStatus.contains("indiriliyor", ignoreCase = true)) {
                "Yerel yapay zekâ modeli henüz indiriliyor. İndirme tamamlandığında doğrudan sesli sohbet açılacak."
            } else {
                "Yerel yapay zekâ henüz hazır değil. Model durumunu kontrol edin."
            }
            activity.runOnUiThread {
                try { onAnswer(message) } finally { speak(message) }
            }
            return
        }
        voice.speakAndRecordDirectAudio { audio ->
            publishModelStatus("Gemma 4 konuşmayı doğrudan anlıyor...")
            brain.askAudio(audio) { answer ->
                activity.runOnUiThread {
                    try { onAnswer(answer) } finally { speak(answer) }
                }
            }
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
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return

        val pending = cleanMessage to onAnswer
        pendingChats.add(pending)

        if (brain.isReady()) {
            drainPendingChats()
            return
        }

        publishModelStatus("Gemma 4 hazırlanıyor; mesaj sıraya alındı.")
        // Model yükleme başarısız veya takılmışsa bekleyen isteği sonsuza kadar tutma.
        chatTimeoutExecutor.schedule({
            if (pendingChats.remove(pending)) {
                val failure = "Yerel yapay zekâ 30 saniye içinde hazır olmadı. Model yükleme durumunu kontrol edin."
                publishModelStatus("Model hazır olmadığı için bekleyen mesaj sonlandırıldı.")
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

    private fun answerWithBrain(
        message: String,
        onAnswer: (String) -> Unit,
        onFinished: () -> Unit
    ) {
        publishModelStatus("Seyfi Usta yanıt hazırlıyor.")
        Log.i(TAG, "Yerel model isteği başladı: ${message.take(240)}")

        val reported = AtomicBoolean(false)
        // Bu süre yalnızca kullanıcıya durum bildirir. Native üretimi iptal ettiği
        // iddia edilmez ve yeni istek başlatılmaz; gerçek callback gelince kuyruk açılır.
        val watchdog = chatTimeoutExecutor.schedule({
            if (reported.compareAndSet(false, true)) {
                val warning = "Usta, yerel modelden 90 saniyedir yanıt gelmedi. İşlem hâlâ cihazda sürüyor olabilir; aynı anda yeni soru göndermiyorum. Uygulama yanıt vermezse yeniden başlatın."
                publishModelStatus("Yerel model yanıtı gecikti; mevcut işlem güvenli biçimde bekleniyor.")
                activity.runOnUiThread {
                    try {
                        onAnswer(warning)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bekleme uyarısı ekrana aktarılamadı", e)
                    } finally {
                        speak(warning)
                    }
                }
            }
        }, 90, TimeUnit.SECONDS)

        try {
            brain.chat(message) { answer ->
                watchdog.cancel(false)
                if (reported.compareAndSet(false, true)) {
                    Log.i(TAG, "Yerel model yanıtı alındı: ${answer.take(600)}")
                    activity.runOnUiThread {
                        try {
                            onAnswer(answer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Yanıt ekrana aktarılamadı", e)
                        } finally {
                            speak(answer)
                        }
                    }
                } else {
                    Log.w(TAG, "Gecikme uyarısından sonra gerçek yanıt geldi.")
                }
                // Model callback'i gerçekten tamamlandı; sıradaki mesaja ancak şimdi geç.
                activity.runOnUiThread { onFinished() }
            }
        } catch (e: Exception) {
            watchdog.cancel(false)
            if (reported.compareAndSet(false, true)) {
                val failure = "Yerel yapay zekâ yanıt üretemedi: ${e.message}"
                activity.runOnUiThread {
                    try {
                        onAnswer(failure)
                    } finally {
                        speak(failure)
                        onFinished()
                    }
                }
            } else {
                activity.runOnUiThread { onFinished() }
            }
        }
    }

    private fun drainPendingChats() {
        if (!brain.isReady()) return
        if (!chatInProgress.compareAndSet(false, true)) return

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
            pending.second(answer)
            speak(answer)
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
        voiceReady = false
        initializationCallback = null
    }
}
