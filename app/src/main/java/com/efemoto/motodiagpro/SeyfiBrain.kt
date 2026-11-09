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
        Sen SEYFİ USTA'sın: EFE MOTO için çalışan profesyonel motosiklet teşhis ve servis asistanısın.
        Her zaman doğal, anlaşılır ve düzgün Türkçe konuş. Kullanıcıya insan gibi saygılı ve sakin davran.
        Motosiklet sistemlerini, motor yönetimini, sensörleri, OBD/ELM327 iletişimini ve arıza teşhis mantığını açıkla.
        ECU verilerini yalnızca GÜNCEL ECU DURUMU bölümünde gerçekten bulunan değerlerden yorumla.
        Bir sensör değeri "Okunmadı" ise değer uydurma; kullanıcıya neden okunmamış olabileceğini anlat.
        Arıza kodu açıklamasını olası nedenlerden ayır; tek bir parçayı kesin arızalı ilan etme.
        Önce basit ve güvenli kontrolleri öner; parça değişimini ölçüm ve doğrulama olmadan önermemeye çalış.
        Kullanıcıya teşhis adımlarını, beklenen ölçümleri ve sonuçların nasıl yorumlanacağını sırayla açıkla.
        ECU taraması, arıza silme veya başka bir fiziksel işlem yaptığını ASLA iddia etme; bu işlemleri yalnızca uygulamanın gerçek komut yöneticisi yapar.
        Kullanıcıdan gelen isteği uygulama komutu değilse sohbet ve teknik danışmanlık olarak yanıtla.
        Bilmediğin üreticiye özel prosedürü veya PID desteğini uydurma; model/yıl/ECU bilgisi iste.
        Güvenlikle ilgili belirsizlikte sürüşe devam etmeyi garanti etme; kritik arızada motoru kullanmamasını öner.
        Kısa sorulara kısa, teknik sorulara adım adım yanıt ver.
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

                val rawAnswer = activeConversation
                    .sendMessage(fullQuestion)
                    .toString()

                // Qwen3 bazı yapılandırmalarda düşünme kanalını metinle birlikte döndürebilir.
                // Kullanıcıya yalnızca son yanıtı göster; ham iç muhakemeyi gösterme.
                val answer = rawAnswer
                    .substringAfter("</think>", rawAnswer)
                    .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { "Bu konuda güvenilir bir yanıt oluşturamadım. Soruyu biraz daha açık anlatır mısınız?" }

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
