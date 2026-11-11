package com.efemoto.motodiagpro

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
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
 * Türkçe Vosk dinleme modeli APK'da kalır; Qwen3 0.6B modeli ilk açılışta
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
        private const val MODEL_FILE_NAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private const val MIN_MODEL_BYTES = 340_000_000L
        private const val MAX_MODEL_BYTES = 360_000_000L
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
    @Volatile private var modelStatus = "Qwen3 0.6B modeli kontrol ediliyor."

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        initialized = true
        initializationCallback = onReady
        // Mikrofonu, Qwen3 0.6B gerçekten açılıp deneme çıkarımı tamamlanmadan
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
                publishModelStatus("Qwen3 0.6B yerel yapay zekâsı başlatılıyor.")
                brain.initialize(modelFile) { success, message ->
                    modelStatus = if (success) {
                        "Qwen3 0.6B yerel yapay zekâsı hazır."
                    } else {
                        modelTaskStarted.set(false)
                        "Qwen3 0.6B başlatılamadı: $message"
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
                val failure = "Qwen3 0.6B modeli hazırlanamadı: ${e.message}"
                publishModelStatus(failure)
                activity.runOnUiThread { failPendingChats(failure) }
            }
        }
    }

    /**
     * Qwen3 0.6B ilk kurulumda Hugging Face'ten uygulamanın özel alanına indirilir.
     * İndirme tamamlanmadan .part dosyası motor tarafından kullanılmaz.
     */
    private fun modelStorageDirectories(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += File(activity.filesDir, "models")
        activity.getExternalFilesDir("models")?.let { candidates += it }
        return candidates.distinctBy { it.absolutePath }
    }

    /**
     * Qwen3 modeli derleme sırasında APK assets içine gömülür.
     * İlk açılışta yalnızca uygulamanın özel alanına kopyalanır; internet gerekmez.
     */
    private fun installBundledModel(): File? {
        val modelDirectory = File(activity.filesDir, "models")
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("Yerel model klasörü oluşturulamadı.")
        }
        val target = File(modelDirectory, MODEL_FILE_NAME)
        if (target.isFile && target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return target

        val partial = File(modelDirectory, "$MODEL_FILE_NAME.part")
        try {
            activity.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
        } catch (_: FileNotFoundException) {
            partial.delete()
            return null
        }

        if (partial.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            val actual = partial.length()
            partial.delete()
            throw IllegalStateException("APK içindeki Qwen3 model boyutu geçersiz: $actual bayt.")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IllegalStateException("APK içindeki Qwen3 modeli uygulama alanına kopyalanamadı.")
        }
        publishModelStatus("Qwen3 0.6B APK içinde bulundu; çevrimdışı kuruluyor.")
        return target
    }

    private fun ensureModelFile(): File {
        installBundledModel()?.let { return it }
        val directories = modelStorageDirectories()
        directories.forEach { dir ->
            val installed = File(dir, MODEL_FILE_NAME)
            if (installed.isFile && installed.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                publishModelStatus("Qwen3 0.6B telefonda kurulu; çevrimdışı başlatılıyor.")
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

        val minimumFreeBytes = MIN_MODEL_BYTES + 100_000_000L
        if (available == null || available.second < minimumFreeBytes) {
            val freeGb = (available?.second ?: 0L) / 1_000_000_000.0
            val needGb = minimumFreeBytes / 1_000_000_000.0
            throw IllegalStateException(
                "Qwen3 0.6B yaklaşık 330 MB. Kurulum için en az %.1f GB boş alan gerekiyor. " +
                "Uygulamanın erişebildiği en geniş depolama alanında %.1f GB boş yer var. " +
                "En az 500 MB boşaltıp tekrar deneyin.".format(java.util.Locale.US, needGb, freeGb)
            )
        }

        val modelDirectory = available.first
        val target = File(modelDirectory, MODEL_FILE_NAME)
        val partial = File(modelDirectory, "$MODEL_FILE_NAME.part")
        if (target.exists()) target.delete()
        if (partial.exists()) partial.delete()

        publishModelStatus(
            "Qwen3 0.6B indiriliyor. Uygun depolama alanı: %.1f GB".format(
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
            if (StatFs(modelDirectory.absolutePath).availableBytes < expectedBytes + 80_000_000L) {
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
                            publishModelStatus("Qwen3 0.6B indiriliyor: %$percent")
                        }
                    }
                    output.fd.sync()
                }
            }

            if (total != expectedBytes || partial.length() != expectedBytes) {
                throw IllegalStateException("Model indirmesi eksik. Gelen: $total / Beklenen: $expectedBytes bayt.")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Qwen3 0.6B modeli uygulama alanına taşınamadı.")
            }
            publishModelStatus("Qwen3 0.6B indirildi; yerel motor başlatılıyor.")
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Qwen3 metin tabanlıdır. Uyandırma ve konuşma tanıma Vosk ile çevrimdışı yapılır.
     * Tanınan Türkçe metin daha sonra chat() üzerinden yerel modele gönderilir.
     */
    fun listenDirectAudio(onAnswer: (String) -> Unit = {}) {
        val message = "Sesli sohbet için Vosk metin tanıma hattı kullanılıyor."
        activity.runOnUiThread {
            onAnswer(message)
            speak("Dinliyorum.")
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

        publishModelStatus("Qwen3 0.6B hazırlanıyor; mesaj sıraya alındı.")
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
