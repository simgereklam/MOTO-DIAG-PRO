
package com.efemoto.motodiagpro

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SeyfiBrain(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    private val initializing = AtomicBoolean(false)

    private val systemPrompt = """
        Sen SEYFİ USTA'sın.
        Profesyonel bir motosiklet dijital tamir asistanısın.

        Türkçe konuş.
        Kullanıcıyla doğal, samimi ve karşılıklı sohbet et.
        Motosiklet mekanik ve elektronik sistemlerini açıkla.
        ECU arıza kodlarını teknik olarak değerlendir.
        Gerçek sensör verilerini yorumla.
        Eksik bilgi varsa kullanıcıya soru sor.
        Teşhisleri olasılık sırasına göre açıkla.
        Kontrol ve ölçüm adımlarını anlaşılır biçimde anlat.

        ÖNEMLİ GÜVENLİK KURALLARI:
        Okunmamış ECU verisini okunmuş gibi gösterme.
        Yapılmamış testi yapılmış gibi anlatma.
        Bilmediğin teknik bilgiyi uydurma.
        Tahmini teşhisi kesin arıza gibi sunma.
        ECU'ya komut gönderdiğini iddia etme.
        Arıza silme veya tehlikeli işlemleri kendiliğinden yapma.
        Kullanıcıya tehlikeli işlemlerden önce uyarı ver.

        Cevapların doğal Türkçe, anlaşılır ve ustaca olsun.
    """.trimIndent()

    fun initialize(
        modelFile: File,
        onResult: (Boolean, String) -> Unit
    ) {
        if (closed) {
            mainHandler.post {
                onResult(false, "SEYFİ USTA motoru kapatılmış.")
            }
            return
        }

        if (!initializing.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            try {
                ready = false

                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw IllegalStateException(
                        "Yapay zekâ model dosyası bulunamadı."
                    )
                }

                conversation?.close()
                conversation = null

                engine?.close()
                engine = null

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                newEngine.initialize()

                if (closed) {
                    newEngine.close()
                    return@execute
                }

                engine = newEngine

                val newConversation = newEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                conversation = newConversation
                ready = true

                mainHandler.post {
                    onResult(
                        true,
                        "SEYFİ USTA yapay zekâ motoru hazır."
                    )
                }

            } catch (e: Exception) {
                ready = false

                mainHandler.post {
                    onResult(
                        false,
                        "Yapay zekâ başlatılamadı: ${e.message}"
                    )
                }

            } finally {
                initializing.set(false)
            }
        }
    }

    fun ask(
        question: String,
        onResponse: (String) -> Unit
    ) {
        if (closed) {
            mainHandler.post {
                onResponse("SEYFİ USTA şu anda kapalı.")
            }
            return
        }

        if (question.isBlank()) {
            mainHandler.post {
                onResponse("Usta, sorunuzu anlayamadım. Tekrar söyler misiniz?")
            }
            return
        }

        executor.execute {
            try {
                val activeConversation = conversation

                if (!ready || activeConversation == null) {
                    throw IllegalStateException(
                        "Yapay zekâ henüz hazır değil."
                    )
                }

                val answer = activeConversation
                    .sendMessage(question)
                    .toString()
                    .trim()

                val finalAnswer = answer.ifBlank {
                    "Usta, şu anda cevap oluşturamadım. Tekrar sorar mısınız?"
                }

                mainHandler.post {
                    onResponse(finalAnswer)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onResponse(
                        "Usta şu anda cevap üretemedi: ${e.message}"
                    )
                }
            }
        }
    }

    fun isReady(): Boolean {
        return ready && !closed
    }

    fun close() {
        if (closed) return

        closed = true
        ready = false

        executor.execute {
            try {
                conversation?.close()
                conversation = null

                engine?.close()
                engine = null

            } catch (_: Exception) {
            } finally {
                executor.shutdown()
            }
        }
    }
}
