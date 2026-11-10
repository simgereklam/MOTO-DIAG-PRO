package com.efemoto.motodiagpro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seyfi Usta ses katmanı:
 * - Vosk: sürekli, yerel Türkçe konuşma tanıma.
 * - Android TTS: Türkçe sesli yanıt.
 * - Tanınan metin onRecognized üzerinden SeyfiUstaAI / Qwen'e ve
 *   MainActivity komut yönlendiricisine aktarılır.
 *
 * SpeechRecognizer kullanılmaz; böylece telefonun bulut/üretici konuşma
 * servisine ve her cümleden sonra tek-atımlık dinleme döngüsüne bağlı kalmaz.
 */
class OfflineVoiceAssistant(
    private val activity: Activity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var utteranceWatchdog: Runnable? = null
    private val turkish = Locale("tr", "TR")
    private val utteranceIds = AtomicInteger(0)
    private val pendingSpeechCallbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile private var voskModel: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listeningEnabled = false
    @Volatile private var processingRequested = false
    @Volatile private var speakingRequested = false
    @Volatile private var released = false
    @Volatile private var currentStatus = "Türkçe çevrimdışı ses modeli hazırlanıyor."

    private var modelInitialized = false
    private var ttsInitialized = false
    private var initializedCallback: (() -> Unit)? = null
    private var activeUtteranceId: String? = null
    private var restartAttempts = 0

    private fun reportStatus(message: String) {
        if (currentStatus == message) return
        currentStatus = message
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!released) onStatus(message)
        } else {
            mainHandler.post { if (!released) onStatus(message) }
        }
    }

    fun getStatus(): String = currentStatus

    fun initialize(onInitialized: (() -> Unit)? = null) {
        if (released) return
        initializedCallback = onInitialized
        reportStatus("Türkçe çevrimdışı konuşma modeli yükleniyor...")

        try {
            StorageService.unpack(
                activity,
                "model-tr",
                "seyfi-voice",
                { model ->
                    if (released) {
                        try { model.close() } catch (_: Exception) {}
                        return@unpack
                    }
                    voskModel = model
                    modelInitialized = true
                    reportStatus("Türkçe Vosk modeli hazır.")
                    completeInitializationIfReady()
                    if (listeningEnabled && !processingRequested && !speakingRequested) {
                        startSpeechService()
                    }
                },
                { error ->
                    if (released) return@unpack
                    modelInitialized = true
                    reportStatus("Türkçe ses modeli açılamadı: ${error.message}")
                    completeInitializationIfReady()
                }
            )
        } catch (e: Exception) {
            modelInitialized = true
            reportStatus("Türkçe ses modeli başlatılamadı: ${e.message}")
            completeInitializationIfReady()
        }

        initializeTts()
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(activity) { status ->
                if (released) return@TextToSpeech
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    try {
                        // Önce dili ayarla. Android setLanguage() çağrısı varsayılan sesi
                        // yeniden seçtiği için özel sesi bundan SONRA seçmek gerekir.
                        val languageResult = engine.setLanguage(turkish)
                        val languageSupported =
                            languageResult != TextToSpeech.LANG_MISSING_DATA &&
                                languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                        val turkishVoices = engine.voices.orEmpty().filter {
                            it.locale.language.equals("tr", ignoreCase = true)
                        }
                        val offlineTurkish = turkishVoices.filter {
                            !it.isNetworkConnectionRequired
                        }
                        fun isMaleVoice(voice: android.speech.tts.Voice): Boolean {
                            val details = (voice.name + " " +
                                voice.features.orEmpty().joinToString(" "))
                                .lowercase(Locale.ROOT)
                            return details.contains("male") || details.contains("erkek")
                        }

                        // Önce çevrimdışı Türkçe erkek sesi; yoksa çevrimdışı herhangi
                        // bir Türkçe ses; o da yoksa telefondaki Türkçe ağ sesini seç.
                        // Önceki kod çevrimdışı Türkçe ses yokken varsayılan motor sesini
                        // bırakıyordu; bu ses Türkçe olmayabilir veya kullanılamayabilirdi.
                        val selected = offlineTurkish.firstOrNull(::isMaleVoice)
                            ?: offlineTurkish.firstOrNull()
                            ?: turkishVoices.firstOrNull(::isMaleVoice)
                            ?: turkishVoices.firstOrNull()

                        var selectedVoiceName = "Android varsayılan Türkçe sesi"
                        if (selected != null) {
                            val voiceResult = engine.setVoice(selected)
                            if (voiceResult == TextToSpeech.ERROR) {
                                reportStatus("Türkçe ses seçilemedi: ${selected.name}; varsayılan ses deneniyor.")
                            } else {
                                selectedVoiceName = selected.name
                            }
                        }

                        // Medya ses seviyesini kullan; telefonun medya sesi kapalıysa
                        // kullanıcı bunu telefonun ses tuşlarıyla kontrol edebilir.
                        val audioResult = engine.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )

                        // TTS motoru başarıyla açıldıysa telefonun mevcut sesi kullanılabilir.
                        // Türkçe dil paketi bulunamaması motoru tümden devre dışı bırakmasın:
                        // Türkçe ses varsa onu seç, yoksa Android'in varsayılan sesine düş.
                        ttsReady = true
                        engine.setSpeechRate(0.92f)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                mainHandler.post {
                                    if (!released) reportStatus("SEYFİ TTS: konuşma başladı.")
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                finishUtterance(utteranceId, "SEYFİ TTS: konuşma tamamlandı.")
                            }

                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                finishUtterance(
                                    utteranceId,
                                    if (interrupted) "SEYFİ TTS: konuşma kesildi."
                                    else "SEYFİ TTS: konuşma durdu."
                                )
                            }

                            @Deprecated("Deprecated by Android")
                            override fun onError(utteranceId: String?) {
                                finishUtterance(utteranceId, "SEYFİ TTS HATASI: ses üretilemedi.")
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                finishUtterance(
                                    utteranceId,
                                    "SEYFİ TTS HATASI: kod $errorCode."
                                )
                            }
                        })

                        reportStatus(
                            when {
                                !languageSupported && turkishVoices.isEmpty() ->
                                    "Telefonun TTS motoru hazır; Türkçe ses paketi bulunamadı, varsayılan telefon sesi kullanılacak."
                                selected != null && selected.isNetworkConnectionRequired ->
                                    "SEYFİ TTS HAZIR: $selectedVoiceName (internet gerektirir); medya ses çıkışı ${if (audioResult == TextToSpeech.SUCCESS) "hazır" else "kontrol edilmeli"}."
                                selected != null ->
                                    "SEYFİ TTS HAZIR: $selectedVoiceName (çevrimdışı); medya ses çıkışı ${if (audioResult == TextToSpeech.SUCCESS) "hazır" else "kontrol edilmeli"}."
                                turkishVoices.isEmpty() ->
                                    "Türkçe TTS sesi bulunamadı. Telefona Türkçe ses paketi yüklenmeli."
                                else ->
                                    "Türkçe TTS hazır; varsayılan ses kullanılacak."
                            }
                        )
                    } catch (e: Exception) {
                        ttsReady = false
                        reportStatus("Türkçe ses motoru hazırlanamadı: ${e.message}")
                    }
                } else {
                    ttsReady = false
                    reportStatus("Telefonun metin okuma motoru başlatılamadı.")
                }
                ttsInitialized = true
                completeInitializationIfReady()
            }
        } catch (e: Exception) {
            ttsReady = false
            ttsInitialized = true
            reportStatus("Metin okuma motoru açılamadı: ${e.message}")
            completeInitializationIfReady()
        }
    }

    private fun completeInitializationIfReady() {
        if (!modelInitialized || !ttsInitialized || released) return
        val callback = initializedCallback
        initializedCallback = null
        callback?.invoke()
    }

    fun startListening() {
        if (released) return
        listeningEnabled = true
        processingRequested = false
        restartAttempts = 0
        startSpeechService()
    }

    /** Uyandırma sözü veya komut işlenirken mikrofondan gelen sesi askıya alır. */
    fun pauseForProcessing() {
        processingRequested = true
        mainHandler.removeCallbacks(restartRecognition)
        speechService?.setPause(true)
        reportStatus("Seyfi Usta komutu işliyor...")
    }

    private fun startSpeechService() {
        if (released || !listeningEnabled || processingRequested || speakingRequested) return
        if (speechService != null) return

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            reportStatus("Mikrofon izni gerekli.")
            return
        }

        val model = voskModel
        if (model == null) {
            reportStatus("Türkçe ses modeli hazır değil; yükleme tekrar kontrol ediliyor.")
            // Model yüklemesi gecikmiş veya ilk açılışta depolama işlemi aksatılmış
            // olabilir. Dinlemeyi sessizce kalıcı olarak kapatmak yerine yeniden dene.
            scheduleRestart()
            return
        }

        try {
            val newRecognizer = Recognizer(model, SAMPLE_RATE)
            recognizer = newRecognizer
            val newService = SpeechService(newRecognizer, SAMPLE_RATE)
            speechService = newService

            val started = newService.startListening(voskListener)
            if (!started) {
                speechService = null
                try { newService.cancel() } catch (_: Exception) {}
                try { newService.shutdown() } catch (_: Exception) {}
                try { newRecognizer.close() } catch (_: Exception) {}
                recognizer = null
                reportStatus("Mikrofon dinleme oturumu başlatılamadı; yeniden denenecek.")
                scheduleRestart()
                return
            }

            restartAttempts = 0
            reportStatus("Seyfi Usta çevrimdışı dinliyor...")
        } catch (e: Exception) {
            releaseSpeechService()
            reportStatus("Mikrofon açılamadı: ${e.message}")
            scheduleRestart()
        }
    }

    private val restartRecognition = Runnable {
        if (listeningEnabled && !processingRequested && !speakingRequested && !released) {
            releaseSpeechService()
            startSpeechService()
        }
    }

    private fun scheduleRestart() {
        if (!listeningEnabled || processingRequested || speakingRequested || released) return
        mainHandler.removeCallbacks(restartRecognition)
        restartAttempts = (restartAttempts + 1).coerceAtMost(6)
        val delay = (500L shl (restartAttempts - 1)).coerceAtMost(8000L)
        mainHandler.postDelayed(restartRecognition, delay)
    }

    private fun extractText(json: String, field: String): String {
        return try {
            JSONObject(json).optString(field).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Kısmi sonuçları komut olarak çalıştırma; ancak dinleme hattının
            // canlı olduğunu durum bilgisinde belirt.
            if (!released && listeningEnabled && !processingRequested && !speakingRequested) {
                val partial = hypothesis?.let { extractText(it, "partial") }.orEmpty()
                if (partial.isNotBlank()) {
                    mainHandler.post {
                        if (!released && listeningEnabled && !processingRequested && !speakingRequested) {
                            reportStatus("Seyfi Usta duyuyor: $partial")
                            // Kısmi metin yalnızca ekranda gösterilir.
                            // Burada komut çalıştırmak mikrofonu erken durdurur:
                            // "seyfi" algılanınca "usta" ve komutun geri kalanı kesilir.
                            // Uyandırma sözü ve komut, Vosk onResult kesin metni verdiğinde
                            // MainActivity'ye tek seferde aktarılır.
                        }
                    }
                }
            }
        }

        override fun onResult(hypothesis: String?) {
            if (released || !listeningEnabled || processingRequested || speakingRequested) return
            val recognized = hypothesis?.let { extractText(it, "text") }.orEmpty()
            if (recognized.isBlank()) return

            // SpeechService her tamamlanan ses parçası için onResult çağırır.
            // onFinalResult yalnızca servis durdurulurken geldiğinden ikinci kez
            // komut çalıştırmamak için burada yalnızca onResult kullanılır.
            mainHandler.post {
                if (!released && listeningEnabled && !processingRequested && !speakingRequested) {
                    try {
                        onRecognized(recognized)
                    } catch (e: Exception) {
                        reportStatus("Tanınan komut aktarılamadı: ${e.message}")
                    }
                }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            // Stop/cancel sonrasında gelen final paketini tekrar komut sayma.
        }

        override fun onError(exception: Exception?) {
            mainHandler.post {
                if (released || !listeningEnabled || processingRequested || speakingRequested) return@post
                reportStatus("Mikrofon/ses tanıma hatası: ${exception?.message ?: "bilinmeyen hata"}")
                releaseSpeechService()
                scheduleRestart()
            }
        }

        override fun onTimeout() {
            mainHandler.post {
                if (listeningEnabled && !processingRequested && !speakingRequested && !released) {
                    reportStatus("Dinleme oturumu sona erdi; mikrofon yeniden açılıyor.")
                    releaseSpeechService()
                    scheduleRestart()
                }
            }
        }
    }

    // TTS oynarken Vosk tamamen kapalıdır; ses bitince yeni tanıyıcı açılır.
    private fun requestSpeechAudioFocus() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .setWillPauseWhenDucked(false)
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Exception) { }
    }

    private fun abandonSpeechAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) { }
    }

    private fun finishUtterance(utteranceId: String?, status: String) {
        mainHandler.post {
            if (released) return@post
            utteranceWatchdog?.let { mainHandler.removeCallbacks(it) }
            utteranceWatchdog = null
            val callback = utteranceId?.let { pendingSpeechCallbacks.remove(it) }
            if (utteranceId == activeUtteranceId) {
                activeUtteranceId = null
                speakingRequested = false
                abandonSpeechAudioFocus()
                reportStatus(status)
                callback?.invoke()
                resumeRecognition()
            } else {
                callback?.invoke()
            }
        }
    }

    private fun resumeRecognition() {
        if (!listeningEnabled || released) return

        // TTS sırasında Vosk SpeechService pause durumuna alınmıştı.
        // Bazı Android cihazlarda aynı Recognizer'ı reset()+setPause(false)
        // ile sürdürmek sonraki konuşma parçasını teslim etmeyebiliyor.
        // Her TTS/komut sonrasında eski oturumu tamamen kapatıp yeni
        // Recognizer + SpeechService oluşturmak daha güvenilir bir döngüdür.
        processingRequested = false
        mainHandler.removeCallbacks(restartRecognition)
        releaseSpeechService()
        reportStatus("Seyfi Usta mikrofonu yeniden açıyor...")
        mainHandler.postDelayed({
            if (listeningEnabled && !processingRequested && !speakingRequested && !released) {
                startSpeechService()
            }
        }, 450L)
    }

    fun stopListening() {
        listeningEnabled = false
        processingRequested = true
        mainHandler.removeCallbacks(restartRecognition)
        releaseSpeechService()
        reportStatus("Seyfi Usta dinlemeyi durdurdu.")
    }

    fun speak(message: String, onDone: (() -> Unit)? = null) {
        if (released) return
        if (message.isBlank()) {
            onDone?.invoke()
            resumeRecognition()
            return
        }

        val engine = tts
        if (!ttsReady || engine == null) {
            reportStatus("Türkçe telefon sesi kullanılamıyor.")
            onDone?.invoke()
            resumeRecognition()
            return
        }

        // Mikrofonu kapat: Seyfi kendi hoparlör sesini komut sanmasın.
        processingRequested = true
        mainHandler.removeCallbacks(restartRecognition)
        releaseSpeechService()
        speakingRequested = true
        try { engine.stop() } catch (_: Exception) {}
        requestSpeechAudioFocus()

        val id = "SEYFI_${utteranceIds.incrementAndGet()}"
        activeUtteranceId = id
        if (onDone != null) pendingSpeechCallbacks[id] = onDone

        reportStatus("SEYFİ TTS: ses isteği gönderiliyor.")
        val watchdog = Runnable {
            if (activeUtteranceId == id && !released) {
                reportStatus("SEYFİ TTS yanıt vermedi; mikrofon yeniden başlatılıyor.")
                try { engine.stop() } catch (_: Exception) {}
                finishUtterance(id, "SEYFİ TTS zaman aşımı.")
            }
        }
        utteranceWatchdog = watchdog
        mainHandler.postDelayed(watchdog, (10_000L + message.length * 110L).coerceAtMost(90_000L))
        val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            pendingSpeechCallbacks.remove(id)
            activeUtteranceId = null
            speakingRequested = false
            utteranceWatchdog?.let { mainHandler.removeCallbacks(it) }
            utteranceWatchdog = null
            abandonSpeechAudioFocus()
            reportStatus("SEYFİ TTS HATASI: speak() isteği reddedildi.")
            onDone?.invoke()
            resumeRecognition()
        } else {
            reportStatus("SEYFİ TTS: ses isteği kabul edildi.")
        }
    }

    fun isListening(): Boolean =
        listeningEnabled && !processingRequested && !speakingRequested && speechService != null

    fun isSpeechReady(): Boolean = ttsReady

    fun isRecognitionReady(): Boolean = voskModel != null

    private fun releaseSpeechService() {
        val service = speechService
        speechService = null
        try { service?.cancel() } catch (_: Exception) {}
        try { service?.shutdown() } catch (_: Exception) {}
        val currentRecognizer = recognizer
        recognizer = null
        try { currentRecognizer?.close() } catch (_: Exception) {}
    }

    fun release() {
        released = true
        listeningEnabled = false
        processingRequested = true
        mainHandler.removeCallbacksAndMessages(null)
        releaseSpeechService()
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
        utteranceWatchdog?.let { mainHandler.removeCallbacks(it) }
        utteranceWatchdog = null
        abandonSpeechAudioFocus()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        speakingRequested = false
        activeUtteranceId = null
        pendingSpeechCallbacks.clear()
        initializedCallback = null
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
    }
}
