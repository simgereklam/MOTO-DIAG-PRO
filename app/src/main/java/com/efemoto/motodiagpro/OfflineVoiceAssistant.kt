package com.efemoto.motodiagpro

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(0)
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningEnabled = false
    @Volatile private var processing = false
    @Volatile private var speaking = false
    @Volatile private var released = false
    @Volatile private var currentStatus = "Android konuşma sistemi hazırlanıyor."
    @Volatile private var recognitionAvailable = false
    private var initCallback: (() -> Unit)? = null
    private var ttsInitialized = false
    private var recognizerInitialized = false
    private var restartCount = 0

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
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            recognitionAvailable = false
            recognizerInitialized = false
            initCallback = onReady
            initializeRecognizer()
        }
    }

    private fun initializeRecognizer() {
        try {
            // Bulut/karma tanıma yedeği kesinlikle kullanılmaz.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                recognitionAvailable = false
                status("Bu Android sürümünde güvenilir cihaz içi konuşma tanıma API'si yok. İnternet üzerinden tanımaya geçilmeyecek.")
                recognizerInitialized = true
                finishInitializationIfReady()
                return
            }
            recognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            if (!recognitionAvailable) {
                status("Cihaz içi konuşma servisi bulunamadı. Türkçe çevrimdışı dil paketini yükleyin.")
                recognizerInitialized = true
                finishInitializationIfReady()
                return
            }
            recognizer?.destroy()
            val speechEngine = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            recognizer = speechEngine.also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        restartCount = 0
                        status("Seyfi Usta mikrofonu dinliyor (Türkçe).")
                    }
                    override fun onBeginningOfSpeech() { status("Seyfi Usta konuşmanızı dinliyor.") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { status("Konuşma alındı, metne çevriliyor.") }
                    override fun onError(error: Int) {
                        if (released || processing || speaking) return
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Konuşma servisi çevrimdışı tanımayı tamamlayamadı. Telefonda Türkçe çevrimdışı dil paketi gerekli olabilir."
                            SpeechRecognizer.ERROR_NO_MATCH -> "Söylenen ifade anlaşılamadı; tekrar dinliyorum."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı; tekrar dinliyorum."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni verilmedi."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                "Telefonda Türkçe cihaz içi konuşma modeli yüklü değil veya bu servis Türkçeyi desteklemiyor."
                            else -> "Konuşma tanıma hatası: $error. Yeniden deniyorum."
                        }
                        status(message)
                        scheduleRestart()
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        if (released || processing || speaking) return
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotBlank()) {
                            status("Seyfi Usta duydu: $text")
                            try { onRecognized(text) } catch (e: Exception) {
                                Log.e("SeyfiVoice", "Tanınan metin aktarılamadı", e)
                            }
                        } else status("Konuşma metne çevrilemedi.")
                        scheduleRestart(250)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (partial.isNotBlank()) status("Seyfi Usta duyuyor: $partial")
                    }
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            recognizerInitialized = true
            status(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    "Android cihaz içi Türkçe konuşma tanıma servisi hazır."
                else
                    "Android konuşma servisi hazır; çevrimdışı tercih etkin, bu Android sürümünde cihaz içi çalışma garanti edilemiyor."
            )
        } catch (e: Exception) {
            recognitionAvailable = false
            recognizerInitialized = true
            status("Konuşma tanıma başlatılamadı: ${e.message}")
        }
        finishInitializationIfReady()
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(activity.applicationContext) { result ->
                main.post {
                    val engine = tts
                    if (released) return@post
                    if (result == TextToSpeech.SUCCESS && engine != null) {
                        val language = engine.setLanguage(Locale("tr", "TR"))
                        ttsReady = language != TextToSpeech.LANG_MISSING_DATA &&
                            language != TextToSpeech.LANG_NOT_SUPPORTED
                        // Varsa kurulu Türkçe erkek sesini tercih et. Android her ses
                        // için cinsiyet alanı sunmadığından yalnızca ses adı belirtiyorsa seçilir.
                        val maleVoice = engine.voices
                            ?.filter { it.locale.language.equals("tr", ignoreCase = true) }
                            ?.filter {
                                val n = it.name.lowercase(Locale.ROOT)
                                n.contains("male") || n.contains("masculine") || n.contains("erkek")
                            }
                            ?.maxByOrNull { it.quality }
                        if (maleVoice != null) {
                            engine.voice = maleVoice
                            status("Erkek Türkçe ses seçildi: ${maleVoice.name}")
                        } else if (ttsReady) {
                            status("Türkçe ses hazır; erkek Türkçe ses paketi bulunamadı. Telefonun TTS ayarlarından erkek Türkçe ses yüklenmeli.")
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
                        status(
                            if (ttsReady) "Türkçe TTS hazır; telefonun sistemde seçili Türkçe sesi kullanılacak."
                            else "Türkçe TTS dili telefonda yüklü değil."
                        )
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
            if (released || !listeningEnabled || processing || speaking) return@postDelayed
            val speech = recognizer
            if (speech == null || !recognitionAvailable) {
                status("Mikrofon dinlemesi kullanılamıyor; Android konuşma servisini kontrol edin.")
                return@postDelayed
            }
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
                }
                speech.startListening(intent)
            } catch (e: Exception) {
                status("Mikrofon dinlemesi başlatılamadı: ${e.message}")
                scheduleRestart()
            }
        }, delayMs)
    }

    private fun scheduleRestart(delayMs: Long = 700) {
        if (!listeningEnabled || processing || speaking || released) return
        restartCount = (restartCount + 1).coerceAtMost(8)
        val backoff = delayMs + (restartCount * 250L)
        startListeningInternal(backoff)
    }

    fun pauseForProcessing() {
        processing = true
        main.post { try { recognizer?.cancel() } catch (_: Exception) {} }
    }

    fun stopListening() {
        listeningEnabled = false
        processing = false
        main.post { try { recognizer?.cancel() } catch (_: Exception) {} }
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
            val utteranceId = "seyfi-${ids.incrementAndGet()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    main.post {
                        if (id == utteranceId) {
                            speaking = false
                            processing = false
                            status("Seyfi Usta yanıtını tamamladı.")
                            val hasCompletionCallback = onDone != null
                            try { onDone?.invoke() } catch (e: Exception) {
                                Log.e("SeyfiVoice", "Konuşma sonrası işlem başarısız", e)
                            } finally {
                                if (!hasCompletionCallback && listeningEnabled && !processing) {
                                    startListeningInternal(350)
                                }
                            }
                        }
                    }
                }
                override fun onError(id: String?) {
                    main.post {
                        if (id == utteranceId) {
                            speaking = false
                            processing = false
                            status("Metin seslendirilemedi.")
                            val hasCompletionCallback = onDone != null
                            try { onDone?.invoke() } catch (e: Exception) {
                                Log.e("SeyfiVoice", "Konuşma sonrası işlem başarısız", e)
                            } finally {
                                if (!hasCompletionCallback && listeningEnabled && !processing) {
                                    startListeningInternal(350)
                                }
                            }
                        }
                    }
                }
                override fun onStop(id: String?, interrupted: Boolean) {
                    main.post {
                        if (id == utteranceId) {
                            speaking = false
                            processing = false
                            if (listeningEnabled) startListeningInternal(350)
                        }
                    }
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                speaking = false
                processing = false
                status("Telefonun ses motoru konuşmayı başlatamadı.")
                onDone?.invoke()
            }
        }
    }

    fun release() {
        released = true
        listeningEnabled = false
        processing = true
        main.post {
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
            tts = null
        }
    }
}
