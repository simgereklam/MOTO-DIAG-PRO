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
 * Android konuşma tanıma + sistem TTS + APK içine gömülü Qwen3 yerel metin modeli.
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
    @Volatile private var modelInitializationFinished = false
    @Volatile private var initializationCallback: (() -> Unit)? = null
    @Volatile private var modelStatus = "Qwen3 0.6B modeli kontrol ediliyor."

    /**
     * Açılış selamı için yalnızca Android ses katmanını hazırlar.
     * Qwen3 modeli arka planda yüklenir; model gecikmesi TTS selamını engellemez.
     */
    fun initializeVoiceOnly(onReady: () -> Unit) {
        if (voiceReady) {
            activity.runOnUiThread { onReady() }
            if (!modelTaskStarted.get()) startModelLoading()
            return
        }
        try {
            voice.initialize {
                voiceReady = true
                onReady()
            }
            if (!modelTaskStarted.get()) startModelLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Ses katmanı başlatılamadı", e)
            onReady()
        }
    }

    fun isTtsReady(): Boolean = voice.isTtsReady()
    fun isRecognitionAvailable(): Boolean = voice.isRecognitionAvailable()

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            if (voiceReady && modelInitializationFinished) {
                activity.runOnUiThread { onReady() }
            } else {
                val previous = initializationCallback
                initializationCallback = if (previous == null) {
                    onReady
                } else {
                    {
                        previous()
                        onReady()
                    }
                }
            }
            return
        }
        initialized = true
        initializationCallback = onReady
        // Sesli komutları başlatmadan önce ses katmanı ve modelin deneme yanıtı tamamlanmalı.
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
        if (!voiceReady || !modelInitializationFinished) return
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
                        "Qwen3 0.6B yerel yapay zekâsı hazır; deneme sorusu yanıtlandı."
                    } else {
                        modelTaskStarted.set(false)
                        "Qwen3 0.6B başlatılamadı: $message"
                    }
                    modelInitializationFinished = true
                    publishModelStatus(modelStatus)
                    completeStartupIfReady()
                    if (success) drainPendingChats() else failPendingChats(modelStatus)
                }
            } catch (e: Exception) {
                modelTaskStarted.set(false)
                val failure = "Qwen3 0.6B modeli hazırlanamadı: ${e.message}"
                modelInitializationFinished = true
                publishModelStatus(failure)
                activity.runOnUiThread {
                    completeStartupIfReady()
                    failPendingChats(failure)
                }
            }
        }
    }

    /**
     * Model APK assets içinden kurulur. Ağdan indirme yedeği bulunmaz.
     */
    private fun installBundledModel(): File {
        val modelDirectory = File(activity.filesDir, "models")
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("Yerel model klasörü oluşturulamadı.")
        }
        val target = File(modelDirectory, MODEL_FILE_NAME)
        if (target.isFile && target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return target
        val partial = File(modelDirectory, "$MODEL_FILE_NAME.part")
        partial.delete()
        try {
            activity.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw IllegalStateException("APK içinde yerel yapay zekâ modeli bulunamadı.", e)
        }
        if (partial.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            val actual = partial.length()
            partial.delete()
            throw IllegalStateException("APK içindeki model boyutu geçersiz: $actual bayt.")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IllegalStateException("Model uygulamanın özel alanına kopyalanamadı.")
        }
        publishModelStatus("Yerel yapay zekâ APK içinden çevrimdışı kuruluyor.")
        return target
    }

    private fun ensureModelFile(): File = installBundledModel()
    /**
     * Qwen3 metin tabanlıdır. Android konuşma tanıma katmanından gelen metni işler.
     */
    fun listenDirectAudio(onAnswer: (String) -> Unit = {}) {
        val message = "Android Türkçe konuşma tanıma hattı kullanılıyor."
        activity.runOnUiThread {
            onAnswer(message)
            speak("Dinliyorum.")
        }
    }

    fun getModelStatus(): String = modelStatus
    fun getVoiceStatus(): String = voice.getStatus()

    /**
     * Donanım/servis düzeyinde açılış öz testi. Gerçek mikrofon anlaşılırlığı,
     * ancak kullanıcı bir cümle söylediğinde doğrulanabilir.
     */
    fun runCompatibilitySelfTest(): List<String> {
        val failures = mutableListOf<String>()
        if (!brain.isReady()) failures += "Yerel yapay zekâ modeli deneme yanıtını tamamlamadı."
        if (!voice.isRecognitionAvailable()) failures += "Android konuşma tanıma servisi bulunamadı."
        if (!voice.isTtsReady()) failures += "Türkçe metin okuma motoru hazır değil."
        return failures
    }
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
                val failure = "Yerel yapay zekâ 3 dakika içinde hazır olmadı. Model dosyası veya cihaz belleği kontrol edilmeli."
                publishModelStatus("Model hazır olmadığı için bekleyen mesaj sonlandırıldı.")
                activity.runOnUiThread {
                    try {
                        onAnswer(failure)
                    } finally {
                        speak(failure)
                    }
                }
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

    fun updateDiagnosticTestResult(result: String) {
        brain.updateDiagnosticTestResult(result)
    }

    fun updateDiagnosticPlan(planContext: String) {
        brain.updateDiagnosticPlan(planContext)
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
        modelInitializationFinished = false
        initializationCallback = null
    }
}
