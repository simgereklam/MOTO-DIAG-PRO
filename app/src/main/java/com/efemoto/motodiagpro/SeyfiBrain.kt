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
import java.util.Locale
import java.util.concurrent.Executors

class SeyfiBrain(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var ready = false
    @Volatile private var ecuContext = "Henüz ECU verisi alınmadı."

    private val systemPrompt = """
        Sen SEYFİ USTA'sın.
        Profesyonel bir motosiklet arıza teşhis asistanısın.
        Her zaman Türkçe konuş.
        Kullanıcıyla doğal ve karşılıklı sohbet et.
        Motosiklet arızalarını teknik olarak değerlendir.
        ECU arıza kodlarını açıkla.
        Gerçek sensör verilerini yorumla.
        Eksik bilgi varsa soru sor.
        Bilmediğin bilgiyi uydurma.
        Okunmamış ECU verisini okunmuş gibi gösterme.
        ECU verisi yoksa bunu açıkça belirt.
        Tehlikeli işlemler için kullanıcıdan onay iste.
        Kesin olmayan arıza nedenlerini olasılık olarak açıkla.
    """.trimIndent()

    fun initialize(
        modelFile: File,
        onResult: (Boolean, String) -> Unit
    ) {
        executor.execute {
            try {
                ready = false

                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw IllegalStateException(
                        "Seyfi Usta model dosyası bulunamadı: ${modelFile.absolutePath}"
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

                val newConversation = newEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                engine = newEngine
                conversation = newConversation
                ready = true

                mainHandler.post {
                    onResult(true, "Seyfi Usta yapay zekâ motoru hazır.")
                }

            } catch (e: Exception) {
                ready = false

                mainHandler.post {
                    onResult(
                        false,
                        "Seyfi Usta başlatılamadı: ${e.message}"
                    )
                }
            }
        }
    }

    fun isReady(): Boolean = ready

    // MainActivity'nin beklediği chat metodu
    fun chat(
        question: String,
        onResponse: (String) -> Unit
    ) {
        ask(question, onResponse)
    }

    // ECU'dan gelen gerçek verileri hafızada tutar.
    // MainActivity'nin 7 parametreli çağrısıyla uyumludur.
    fun updateEcuData(
        rpm: Int,
        tps: Int,
        coolant: Int,
        mapKpa: Int,
        voltage: Double,
        faults: List<String>,
        connected: Boolean
    ) {
        ecuContext = buildString {
            appendLine("ECU bağlantısı: ${if (connected) "Bağlı" else "Bağlı değil"}")
            appendLine("Motor devri: ${if (rpm >= 0) "$rpm RPM" else "Okunmadı"}")
            appendLine("Gaz kelebeği: ${if (tps >= 0) "%${tps}" else "Okunmadı"}")
            appendLine("Motor sıcaklığı: ${if (coolant > -100) "$coolant °C" else "Okunmadı"}")
            appendLine("Manifold basıncı: ${if (mapKpa >= 0) "$mapKpa kPa" else "Okunmadı"}")
            appendLine(
                "Akü voltajı: ${
                    if (voltage >= 0.0) {
                        "%.2f V".format(Locale.US, voltage)
                    } else {
                        "Okunmadı"
                    }
                }"
            )
            appendLine(
                "ECU arıza kodları: ${
                    if (faults.isEmpty()) "Kayıtlı kod yok veya henüz tarama yapılmadı."
                    else faults.joinToString(", ")
                }"
            )
        }
    }

    fun ask(
        question: String,
        onResponse: (String) -> Unit
    ) {
        executor.execute {
            try {
                val activeConversation = conversation
                    ?: throw IllegalStateException(
                        "Yapay zekâ henüz başlatılmadı. Model dosyasını kontrol edin."
                    )

                val fullQuestion = """
                    GÜNCEL ECU DURUMU:
                    $ecuContext

                    KULLANICININ SORUSU:
                    $question

                    Yalnızca yukarıdaki gerçek ECU verilerine dayan.
                    Eksik verileri tahmin etme.
                """.trimIndent()

                val answer = activeConversation
                    .sendMessage(fullQuestion)
                    .toString()

                mainHandler.post {
                    onResponse(answer)
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

    fun close() {
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
