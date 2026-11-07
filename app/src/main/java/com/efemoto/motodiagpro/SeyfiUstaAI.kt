package com.efemoto.motodiagpro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SEYFİ USTA voice interface.
 *
 * This class handles Turkish speech recognition and Android's installed TTS voice.
 * It does not pretend to contain an LLM: diagnostic decisions remain in MainActivity
 * and are based on real ECU values / DTC responses.
 */
class SeyfiUstaAI(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicInteger(0)
    private val afterSpeech = ConcurrentHashMap<String, (() -> Unit)>()

    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile private var initialized = false
    @Volatile private var ttsReady = false
    @Volatile private var isListening = false
    @Volatile private var isSpeaking = false
    @Volatile private var listenRequested = false
    @Volatile private var released = false

    private val restartRunnable = Runnable {
        isListening = false
        listenInternal()
    }

    fun initialize(onReady: (() -> Unit)? = null) {
        if (initialized || released) return
        initialized = true

        textToSpeech = TextToSpeech(context) { status ->
            handler.post {
                if (released) return@post
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale("tr", "TR"))
                    textToSpeech?.setSpeechRate(0.96f)

                    // Prefer a Turkish voice explicitly marked male if the device exposes one.
                    val turkishVoices = textToSpeech?.voices.orEmpty()
                        .filter { it.locale.language.equals("tr", ignoreCase = true) }
                    val maleVoice = turkishVoices.firstOrNull {
                        val name = it.name.lowercase(Locale.ROOT)
                        name.contains("male") || name.contains("erkek")
                    }
                    if (maleVoice != null) {
                        textToSpeech?.voice = maleVoice
                    }

                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            handler.post {
                                isSpeaking = true
                                stopRecognitionOnly()
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            handler.post {
                                isSpeaking = false
                                utteranceId?.let { afterSpeech.remove(it)?.invoke() }
                                if (listenRequested && !released) {
                                    handler.removeCallbacks(restartRunnable)
                                    handler.postDelayed(restartRunnable, 350)
                                }
                            }
                        }

                        @Deprecated("Deprecated by Android")
                        override fun onError(utteranceId: String?) {
                            handler.post { finishUtterance(utteranceId) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handler.post { finishUtterance(utteranceId) }
                        }
                    })
                } else {
                    ttsReady = false
                }
                onReady?.invoke()
            }
        }

        createRecognizer()
        if (hasMicrophonePermission()) startListening()
    }

    private fun createRecognizer() {
        if (released || !hasMicrophonePermission() ||
            !SpeechRecognizer.isRecognitionAvailable(context)
        ) return

        try {
            speechRecognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                // Fallback recognizer may use the installed network-backed service.
                // EXTRA_PREFER_OFFLINE below is a preference, not a guarantee.
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (released || !listenRequested) return
                    // Permission / client errors need user action; transient no-match and
                    // timeout errors are retried with a short delay.
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    ) return
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, 900)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val recognized = matches?.firstOrNull()?.trim().orEmpty()
                    if (recognized.isNotBlank() && !released) {
                        onCommandRecognized(recognized)
                    }
                    if (listenRequested && !released) {
                        handler.removeCallbacks(restartRunnable)
                        handler.postDelayed(restartRunnable, 450)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (_: Exception) {
            speechRecognizer = null
            isListening = false
        }
    }

    fun startListening() {
        if (released) return
        listenRequested = true
        handler.removeCallbacks(restartRunnable)
        listenInternal()
    }

    fun stopListening() {
        listenRequested = false
        handler.removeCallbacks(restartRunnable)
        stopRecognitionOnly()
    }

    private fun listenInternal() {
        if (released || !listenRequested || isSpeaking || isListening) return
        if (!hasMicrophonePermission()) return
        if (speechRecognizer == null) createRecognizer()
        val recognizer = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            isListening = true
            recognizer.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            handler.removeCallbacks(restartRunnable)
            handler.postDelayed(restartRunnable, 1200)
        }
    }

    fun speak(text: String, after: (() -> Unit)? = null) {
        if (released || text.isBlank()) return
        val tts = textToSpeech
        if (tts == null || !ttsReady) {
            // Do not silently claim speech succeeded if the device has no Turkish TTS data.
            after?.invoke()
            return
        }

        handler.removeCallbacks(restartRunnable)
        stopRecognitionOnly()
        isSpeaking = true
        val id = "SEYFI_${utteranceCounter.incrementAndGet()}"
        if (after != null) afterSpeech[id] = after

        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            isSpeaking = false
            afterSpeech.remove(id)?.invoke()
            if (listenRequested) handler.postDelayed(restartRunnable, 350)
        }
    }

    private fun finishUtterance(id: String?) {
        isSpeaking = false
        id?.let { afterSpeech.remove(it)?.invoke() }
        if (listenRequested && !released) {
            handler.removeCallbacks(restartRunnable)
            handler.postDelayed(restartRunnable, 350)
        }
    }

    private fun stopRecognitionOnly() {
        try {
            if (isListening) speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        isListening = false
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun release() {
        if (released) return
        released = true
        listenRequested = false
        handler.removeCallbacksAndMessages(null)
        afterSpeech.clear()

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
        isListening = false
        isSpeaking = false
        ttsReady = false
    }
}
