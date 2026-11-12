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
    companion object {
        private const val TAG = "OfflineVoiceAssistant"
    }

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
    private var recognitionSessionActive = false
    private var restartRunnable: Runnable? = null

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
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            recognitionSessionActive = false
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
            // Önce gerçek cihaz içi tanıyıcıyı kullan. MIUI/Google sağlayıcısı
            // bu API'yi sunmuyorsa standart Android tanıyıcıya düş; istekte
            // EXTRA_PREFER_OFFLINE=true gönderilir ve çevrimdışı model tercih edilir.
            // Eski kod yalnızca özel API'yi kabul ettiği için MIUI cihazlarda
            // servis görünmediğinde mikrofonu tamamen kapatıyordu.
            val hasOnDeviceRecognizer =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            val hasSystemRecognizer = SpeechRecognizer.isRecognitionAvailable(activity)
            if (!hasOnDeviceRecognizer && !hasSystemRecognizer) {
                recognitionAvailable = false
                status("Telefonda kullanılabilir konuşma tanıma servisi bulunamadı.")
                recognizerInitialized = true
                finishInitializationIfReady()
                return
            }
            recognizer?.destroy()
            val speechEngine = if (hasOnDeviceRecognizer) {
                recognitionAvailable = true
                status("Cihaz içi Türkçe konuşma tanıma servisi seçildi.")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                recognitionAvailable = true
                status("Android konuşma servisi seçildi. Çevrimdışı tanıma tercihi etkin; Türkçe çevrimdışı model gerekir.")
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
            recognizer = speechEngine.also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        recognitionSessionActive = true
                        restartCount = 0
                        status("Seyfi Usta mikrofonu dinliyor (Türkçe).")
                    }
                    override fun onBeginningOfSpeech() { status("Seyfi Usta konuşmanızı dinliyor.") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { status("Konuşma alındı, metne çevriliyor.") }
                    override fun onError(error: Int) {
                        recognitionSessionActive = false
                        if (released || processing || speaking) return
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            status("Konuşma tanıyıcı meşgul kaldı. Oturum yenileniyor...")
                            resetRecognizerAfterBusy()
                            return
                        }
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
                        recognitionSessionActive = false
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
            status("Seyfi Usta konuşma servisi hazır. Çevrimdışı tanıma tercihi etkin.")
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
            if (released || !listeningEnabled || processing || speaking) return@postDelayed
            val speech = recognizer
            if (speech == null || !recognitionAvailable) {
                status("Mikrofon dinlemesi kullanılamıyor; Android konuşma servisini kontrol edin.")
                return@postDelayed
            }
            if (recognitionSessionActive) return@postDelayed
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
                recognitionSessionActive = true
                speech.startListening(intent)
            } catch (e: Exception) {
                recognitionSessionActive = false
                status("Mikrofon dinlemesi başlatılamadı: ${e.message}")
                scheduleRestart()
            }
        }, delayMs)
    }

    /** ERROR_RECOGNIZER_BUSY sonrası aynı SpeechRecognizer örneğini zorlamayız. */
    private fun resetRecognizerAfterBusy() {
        if (released) return
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
        recognitionSessionActive = false
        // ERROR_RECOGNIZER_BUSY sonrasında sağlayıcının eski oturumu bırakması için
        // önce iptal et, sonra yok et ve yeni tanıyıcıyı gecikmeli oluştur.
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        recognitionAvailable = false
        recognizerInitialized = false
        restartCount = (restartCount + 1).coerceAtMost(8)
        val retryDelay = 1800L + restartCount * 700L
        status("Konuşma servisi meşgul. Mikrofon oturumu sıfırlanıyor; ${retryDelay / 1000.0} saniye sonra yeniden denenecek.")
        main.postDelayed({
            if (released || !listeningEnabled || processing || speaking) return@postDelayed
            initializeRecognizer()
            if (recognitionAvailable) startListeningInternal(500L)
            else status("Konuşma servisi yeniden kurulamadı. Mikrofonu kapatıp açmayı deneyin.")
        }, retryDelay)
    }

    private fun scheduleRestart(delayMs: Long = 700) {
        if (!listeningEnabled || processing || speaking || released) return
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
        main.post {
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            recognitionSessionActive = false
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
    }

    fun stopListening() {
        listeningEnabled = false
        processing = false
        main.post {
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            recognitionSessionActive = false
            try { recognizer?.cancel() } catch (_: Exception) {}
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
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            recognitionSessionActive = false
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
            tts = null
        }
    }
}
