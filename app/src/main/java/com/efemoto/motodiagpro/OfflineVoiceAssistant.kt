package com.efemoto.motodiagpro

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.json.JSONObject
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android yerel konuşma/TTS katmanı.
 * Vosk kullanılmaz. SpeechRecognizer'a tr-TR ve çevrimdışı tercih bilgisi verilir.
 * Android sağlayıcısı çevrimdışı Türkçe tanımayı desteklemiyorsa durum açıkça bildirilir.
 */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "OfflineVoiceAssistant"
    }

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(0)
    @Volatile private var voskModel: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningEnabled = false
    @Volatile private var processing = false
    @Volatile private var speaking = false
    // TTS sırasında yankıyı önlemek için mikrofon kapalı tutulur.
    @Volatile private var bargeInListening = false
    @Volatile private var released = false
    @Volatile private var currentStatus = "Android konuşma sistemi hazırlanıyor."
    @Volatile private var recognitionAvailable = false
    private var initCallback: (() -> Unit)? = null
    private var ttsInitialized = false
    private var recognizerInitialized = false
    private var restartCount = 0
    private var recognitionSessionActive = false
    private var restartRunnable: Runnable? = null
    @Volatile private var lastDeliveredText: String = ""
    @Volatile private var lastDeliveredAtMs: Long = 0L

    private fun status(value: String) {
        currentStatus = value
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!released) onStatus(value)
        } else main.post { if (!released) onStatus(value) }
    }

    fun getStatus(): String = currentStatus
    fun isRecognitionAvailable(): Boolean = recognitionAvailable
    fun isTtsReady(): Boolean = ttsReady

    fun initialize(onInitialized: (() -> Unit)? = null) {
        if (released) return
        initCallback = onInitialized
        main.post {
            initializeRecognizer()
            initializeTts()
        }
    }

    private fun finishInitializationIfReady() {
        if (!recognizerInitialized || !ttsInitialized) return
        val callback = initCallback
        initCallback = null
        callback?.invoke()
    }

    /**
     * Mikrofon izni sonradan verildiğinde eski, izinsiz oluşturulmuş
     * SpeechRecognizer örneğini atıp izinli olarak yeniden kurar.
     */
    fun refreshRecognition(onReady: (() -> Unit)? = null) {
        if (released) return
        main.post {
            initCallback = onReady
            recognitionAvailable = voskModel != null
            recognizerInitialized = recognitionAvailable
            finishInitializationIfReady()
        }
    }

    private fun initializeRecognizer() {
        status("Seyfi Usta'nın Türkçe çevrimdışı modeli yükleniyor...")
        try {
            StorageService.unpack(activity, "model-tr", "model-tr",
                { loaded ->
                    if (released) { loaded.close(); return@unpack }
                    voskModel?.close()
                    voskModel = loaded
                    recognitionAvailable = true
                    recognizerInitialized = true
                    status("Türkçe çevrimdışı ses modeli hazır. İnternet veya telefon konuşma servisi kullanılmıyor.")
                    finishInitializationIfReady()
                },
                { error ->
                    recognitionAvailable = false
                    recognizerInitialized = true
                    status("Türkçe çevrimdışı model yüklenemedi: ${error.message}")
                    finishInitializationIfReady()
                })
        } catch (e: Exception) {
            recognitionAvailable = false
            recognizerInitialized = true
            status("Türkçe çevrimdışı model başlatılamadı: ${e.message}")
            finishInitializationIfReady()
        }
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(activity.applicationContext) { result ->
                main.post {
                    val engine = tts
                    if (released) return@post
                    if (result == TextToSpeech.SUCCESS && engine != null) {
                        val turkishVoices = engine.voices
                            ?.filter {
                                it.locale.language.equals("tr", ignoreCase = true) &&
                                    !it.isNetworkConnectionRequired
                            }
                            .orEmpty()

                        // İnternet kullanmamak için yalnızca cihaza kurulu Türkçe sesleri seç.
                        // Android tüm TTS sağlayıcılarında standart cinsiyet bilgisi sunmaz;
                        // varsa açık erkek etiketi/ismi önceliklidir.
                        val maleVoice = turkishVoices
                            .filter { voice ->
                                val n = voice.name.lowercase(Locale.ROOT)
                                n.contains("male") || n.contains("masculine") ||
                                    n.contains("erkek") ||
                                    voice.features.any { feature ->
                                        feature.contains("gender=male", ignoreCase = true)
                                    }
                            }
                            .maxByOrNull { it.quality }
                        val selectedVoice = maleVoice ?: turkishVoices.maxByOrNull { it.quality }

                        ttsReady = if (selectedVoice != null) {
                            val voiceResult = engine.setVoice(selectedVoice)
                            voiceResult != TextToSpeech.ERROR
                        } else {
                            val language = engine.setLanguage(Locale("tr", "TR"))
                            language != TextToSpeech.LANG_MISSING_DATA &&
                                language != TextToSpeech.LANG_NOT_SUPPORTED
                        }

                        if (maleVoice != null && ttsReady) {
                            status("Çevrimdışı erkek Türkçe ses seçildi: ${maleVoice.name}")
                        } else if (selectedVoice != null && ttsReady) {
                            status("Çevrimdışı Türkçe ses hazır: ${selectedVoice.name}. Telefonda erkek olduğu doğrulanabilen ses bulunamadı; erkek ses paketi yüklenirse otomatik seçilecek.")
                        } else if (ttsReady) {
                            status("Türkçe TTS dili hazır ancak çevrimdışı Türkçe ses bulunamadı. İnternet sesine geçilmeyecek.")
                        }
                        engine.setSpeechRate(0.94f)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                main.post { if (!released) status("Seyfi Usta konuşuyor.") }
                            }
                            override fun onDone(utteranceId: String?) {
                                main.post {
                                    speaking = false
                                    status("Seyfi Usta yanıtını tamamladı.")
                                    if (listeningEnabled && !processing) startListeningInternal(350)
                                }
                            }
                            override fun onError(utteranceId: String?) {
                                main.post {
                                    speaking = false
                                    status("Telefonun metin okuma motoru ses üretemedi.")
                                    if (listeningEnabled && !processing) startListeningInternal(350)
                                }
                            }
                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                main.post {
                                    speaking = false
                                    if (listeningEnabled && !processing) startListeningInternal(350)
                                }
                            }
                        })
                        if (!ttsReady) {
                            status("Çevrimdışı Türkçe ses bu telefonda hazır değil. İnternet üzerinden ses kullanılmayacak; Android metin okuma ayarlarından Türkçe ses paketi yükleyin.")
                        }
                    } else {
                        ttsReady = false
                        status("Android metin okuma motoru başlatılamadı.")
                    }
                    ttsInitialized = true
                    finishInitializationIfReady()
                }
            }
        } catch (e: Exception) {
            ttsReady = false
            ttsInitialized = true
            status("Metin okuma motoru başlatılamadı: ${e.message}")
            finishInitializationIfReady()
        }
    }

    fun startListening() {
        if (released) return
        listeningEnabled = true
        processing = false
        startListeningInternal()
    }

    private fun startListeningInternal(delayMs: Long = 0) {
        main.postDelayed({
            if (released || !listeningEnabled ||
                (processing && !bargeInListening) || (speaking && !bargeInListening)
            ) return@postDelayed
            val model = voskModel
            if (model == null) {
                status("Türkçe çevrimdışı ses modeli henüz hazır değil.")
                return@postDelayed
            }
            try {
                speechService?.let {
                    it.setPause(false)
                    recognitionSessionActive = true
                    status("Seyfi Usta çevrimdışı Türkçe dinliyor.")
                    return@postDelayed
                }
                val rec = Recognizer(model, 16000.0f)
                val service = SpeechService(rec, 16000.0f)
                speechService = service
                recognitionSessionActive = true
                service.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onResult(hypothesis: String?) { deliverVoskResult(hypothesis) }
                    override fun onPartialResult(hypothesis: String?) {
                        val partial = parseVoskText(hypothesis)
                        if (partial.isNotBlank()) status("Seyfi Usta duyuyor: $partial")
                    }
                    override fun onFinalResult(hypothesis: String?) { deliverVoskResult(hypothesis) }
                    override fun onError(exception: Exception?) {
                        recognitionSessionActive = false
                        status("Çevrimdışı mikrofon hatası: ${exception?.message ?: "bilinmeyen hata"}")
                        scheduleRestart(900)
                    }
                    override fun onTimeout() { recognitionSessionActive = false; scheduleRestart(300) }
                })
                status("Seyfi Usta çevrimdışı Türkçe dinliyor.")
            } catch (e: Exception) {
                recognitionSessionActive = false
                status("Çevrimdışı mikrofon başlatılamadı: ${e.message}")
                scheduleRestart(1200)
            }
        }, delayMs)
    }

    private fun parseVoskText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }
    }

    private fun deliverVoskResult(json: String?) {
        if (released) return
        val heard = parseVoskText(json)
        if (heard.isNotBlank() && speaking && bargeInListening) {
            val wakeText = java.text.Normalizer.normalize(
                heard.lowercase(Locale("tr", "TR")),
                java.text.Normalizer.Form.NFD
            ).replace(Regex("\\p{Mn}+"), "")
                .replace("ı", "i")
                .replace(Regex("[^a-z ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            // TTS cümlelerini komut olarak işleme; yalnızca tek başına söylenen
            // uyandırma kelimesi konuşmayı kesebilir.
            if (wakeText == "usta" || wakeText == "seyfi" || wakeText == "seyfi usta") {
                bargeInListening = false
                speaking = false
                processing = false
                try { tts?.stop() } catch (_: Exception) {}
                try { speechService?.setPause(true) } catch (_: Exception) {}
                try { onRecognized(heard) } catch (e: Exception) {
                    Log.e("SeyfiVoice", "Kesme sözcüğü aktarılamadı", e)
                }
            }
            return
        }
        if (processing || speaking) return
        if (heard.isNotBlank()) {
            // Vosk aynı cümleyi onResult ve onFinalResult üzerinden iki kez
            // bildirebilir. Kısa aralıkta aynı metni tekrar komut olarak işleme.
            val now = SystemClock.elapsedRealtime()
            if (heard.equals(lastDeliveredText, ignoreCase = true) &&
                now - lastDeliveredAtMs < 1800L
            ) return
            lastDeliveredText = heard
            lastDeliveredAtMs = now
            status("Seyfi Usta duydu: $heard")
            try { onRecognized(heard) } catch (e: Exception) {
                Log.e("SeyfiVoice", "Tanınan metin aktarılamadı", e)
            }
        }
    }

    /** ERROR_RECOGNIZER_BUSY sonrası aynı SpeechRecognizer örneğini zorlamayız. */
    private fun resetRecognizerAfterBusy() {
        if (released) return
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
        recognitionSessionActive = false
        try { speechService?.setPause(true) } catch (_: Exception) {}
        restartCount = (restartCount + 1).coerceAtMost(8)
        val retryDelay = 1200L + restartCount * 500L
        status("Mikrofon oturumu yenileniyor; ${retryDelay / 1000.0} saniye sonra tekrar dinleyeceğim.")
        scheduleRestart(retryDelay)
    }

    private fun scheduleRestart(delayMs: Long = 700) {
        if (!listeningEnabled || (processing && !bargeInListening) ||
            (speaking && !bargeInListening) || released
        ) return
        restartCount = (restartCount + 1).coerceAtMost(8)
        val backoff = delayMs + (restartCount * 250L)
        restartRunnable?.let { main.removeCallbacks(it) }
        val task = Runnable {
            restartRunnable = null
            startListeningInternal()
        }
        restartRunnable = task
        main.postDelayed(task, backoff)
    }

    fun pauseForProcessing() {
        processing = true
        bargeInListening = false
        main.post {
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            try { speechService?.setPause(true) } catch (_: Exception) {}
        }
    }

    fun stopListening() {
        listeningEnabled = false
        processing = false
        bargeInListening = false
        main.post {
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            try { speechService?.setPause(true) } catch (_: Exception) {}
            recognitionSessionActive = false
        }
    }

    fun isListening(): Boolean = listeningEnabled && !processing && !speaking

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (released || text.isBlank()) {
            onDone?.invoke()
            return
        }
        // Durumu main kuyruğuna girmeden kilitle: tanıma callback'i konuşma
        // başlarken yeni bir mikrofon oturumu açamasın.
        speaking = true
        pauseForProcessing()
        main.post {
            val engine = tts
            if (!ttsReady || engine == null) {
                speaking = false
                processing = false
                status("Sesli yanıt verilemiyor: Türkçe TTS hazır değil.")
                try { onDone?.invoke() } finally {
                    if (listeningEnabled) startListeningInternal(350)
                }
                return@post
            }
            // Yankı önleme: Seyfi konuşurken mikrofon kesin olarak duraklatılır.
            // Hoparlörden çıkan TTS sesi Vosk tarafından kullanıcı komutu sanılmasın.
            // Kullanıcı konuşmayı bitirdikten sonra mikrofon yeniden açılır.
            bargeInListening = false
            try { speechService?.setPause(true) } catch (e: Exception) {
                status("Konuşma sırasında mikrofon duraklatılamadı: ${e.message}")
            }
            val utteranceId = "seyfi-${ids.incrementAndGet()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    main.post {
                        if (id == utteranceId) {
                            bargeInListening = false
                            try { speechService?.setPause(true) } catch (_: Exception) {}
                            speaking = false
                            processing = false
                            status("Seyfi Usta yanıtını tamamladı.")
                            try { onDone?.invoke() } catch (e: Exception) {
                                Log.e("SeyfiVoice", "Konuşma sonrası işlem başarısız", e)
                            } finally {
                                // Callback bir ekran açsa veya tarama başlatsa da,
                                // yeni bir TTS başlamadıysa mikrofon oturumunu geri getir.
                                if (listeningEnabled && !processing && !speaking) {
                                    startListeningInternal(450)
                                }
                            }
                        }
                    }
                }
                override fun onError(id: String?) {
                    main.post {
                        if (id == utteranceId) {
                            bargeInListening = false
                            try { speechService?.setPause(true) } catch (_: Exception) {}
                            speaking = false
                            processing = false
                            status("Metin seslendirilemedi.")
                            try { onDone?.invoke() } catch (e: Exception) {
                                Log.e("SeyfiVoice", "Konuşma sonrası işlem başarısız", e)
                            } finally {
                                // Callback bir ekran açsa veya tarama başlatsa da,
                                // yeni bir TTS başlamadıysa mikrofon oturumunu geri getir.
                                if (listeningEnabled && !processing && !speaking) {
                                    startListeningInternal(450)
                                }
                            }
                        }
                    }
                }
                override fun onStop(id: String?, interrupted: Boolean) {
                    main.post {
                        if (id == utteranceId) {
                            bargeInListening = false
                            try { speechService?.setPause(true) } catch (_: Exception) {}
                            speaking = false
                            processing = false
                            if (listeningEnabled) startListeningInternal(350)
                        }
                    }
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                bargeInListening = false
                speaking = false
                processing = false
                status("Telefonun ses motoru konuşmayı başlatamadı.")
                try { onDone?.invoke() } finally {
                    if (listeningEnabled && !processing && !speaking) startListeningInternal(450)
                }
            }
        }
    }

    fun release() {
        released = true
        listeningEnabled = false
        processing = true
        main.post {
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            try { speechService?.stop() } catch (_: Exception) {}
            try { speechService?.shutdown() } catch (_: Exception) {}
            speechService = null
            try { voskModel?.close() } catch (_: Exception) {}
            voskModel = null
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
            tts = null
        }
    }
}
