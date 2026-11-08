
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

    @Volatile
    private var ecuContext = "Henüz ECU verisi alınmadı."

    private val initializing = AtomicBoolean(false)

    private val systemPrompt = """
        Sen SEYFİ USTA'sın.
        Profesyonel bir motosiklet dijital tamir asistanısın.

        Türkçe konuş.
        Kullanıcıyla doğal ve karşılıklı sohbet et.
        Motosiklet mekanik ve elektronik sistemlerini açıkla.
        ECU arıza kodlarını teknik olarak değerlendir.
        Gerçek sensör verilerini yorumla.
        Eksik bilgi varsa soru sor.
        Teşhisleri olasılık sırasına göre açıkla.
        Kontrol ve ölçüm adımlarını anlaşılır anlat.

        GÜVENLİK:
        Okunmamış ECU verisini okunmuş gibi gösterme.
        Yapılmamış testi yapılmış gibi anlatma.
        Bilmediğin bilgiyi uydurma.
        Tahmini teşhisi kesin arıza gibi sunma.
        ECU'ya komut gönderdiğini iddia etme.
        Arıza silme işlemini kendiliğinden yapma.
        Tehlikeli işlemlerden önce uyar.

        Cevapların doğal, anlaşılır ve teknik Türkçe olsun.
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

        if (ready) {
            mainHandler.post {
                onResult(true, "SEYFİ USTA hazır.")
            }
            return
        }

        if (!initializing.compareAndSet(false, true)) return

        executor.execute {
            try {
                ready = false

                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw IllegalStateException(
                        "Yapay zekâ model dosyası bulunamadı: ${modelFile.absolutePath}"
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
                    onResult(true, "SEYFİ USTA yerel yapay zekâ motoru hazır.")
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

    // MainActivity'den gelen gerçek ECU verilerini saklar.
    fun updateEcuData(
        rpm: Number,
        tps: Number,
        ect: Number,
        map: Number,
        voltage: Number,
        dtcs: List<String>,
        connected: Boolean
    ) {
        ecuContext = buildString {
            appendLine("GERÇEK ECU DURUMU:")
            appendLine("Viecar bağlantısı: ${if (connected) "Bağlı" else "Bağlı değil"}")
            appendLine("Motor devri: $rpm RPM")
            appendLine("Gaz konumu (TPS): $tps")
            appendLine("Motor sıcaklığı: $ect derece")
            appendLine("Manifold basıncı (MAP): $map")
            appendLine("Akü voltajı: $voltage volt")
            appendLine(
                "Okunan arıza kodları: ${
                    dtcs.joinToString().ifBlank { "Kayıtlı kod bildirilmedi" }
                }"
            )
            appendLine(
                "Not: Bu bilgiler uygulamanın ECU veri katmanından aktarılmıştır. " +
                    "Okunmamış değerler ayrıca doğrulanmalıdır."
            )
        }
    }

    // Ana ekrandaki sohbet isteği için asenkron cevap.
    fun chat(
        question: String,
        onResponse: (String) -> Unit
    ) {
        ask(question, onResponse)
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
                        "Yerel yapay zekâ henüz hazır değil. Model dosyasını kontrol edin."
                    )
                }

                val contextualQuestion = """
                    $ecuContext

                    KULLANICININ SORUSU:
                    $question

                    ECU verileri yetersizse bunu açıkça belirt.
                    Gerçek ölçüm ile tahmini birbirinden ayır.
                """.trimIndent()

                val answer = activeConversation
                    .sendMessage(contextualQuestion)
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
