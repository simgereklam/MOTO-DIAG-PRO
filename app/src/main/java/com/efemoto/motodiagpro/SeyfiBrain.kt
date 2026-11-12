package com.efemoto.motodiagpro

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seyfi Usta bağımsız, cihaz içi teşhis çekirdeği.
 * Hazır LLM veya üçüncü taraf model ağırlığı kullanmaz.
 * Bu sürüm deterministik teşhis mantığıdır; öğrenilmiş sinir ağı henüz eğitilmemiştir.
 */
class SeyfiBrain(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var ready = true
    @Volatile private var ecuConnected = false
    @Volatile private var ecuValues: Map<String, Double> = emptyMap()
    @Volatile private var ecuFaults: List<String> = emptyList()
    @Volatile private var diagnosticPlanContext = "Henüz teşhis planı oluşturulmadı."
    @Volatile private var latestDiagnosticTestResult = "Henüz salt-okunur test sonucu alınmadı."

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale("tr", "TR")), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace("ı", "i")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // Eski çağrı noktalarıyla uyumluluk; hiçbir model dosyası açılmaz.
    fun initialize(modelFile: java.io.File, onResult: (Boolean, String) -> Unit) {
        ready = true
        main.post { onResult(true, "Seyfi Usta bağımsız yerel teşhis çekirdeği hazır. Harici model yüklenmedi.") }
    }
    fun isReady(): Boolean = ready

    fun updateDiagnosticTestResult(result: String) {
        latestDiagnosticTestResult = result.trim().take(12000).ifBlank { "Son salt-okunur test sonucu boş." }
    }
    fun updateDiagnosticPlan(planContext: String) {
        diagnosticPlanContext = planContext.trim().ifBlank { "Henüz güvenli teşhis planı oluşturulmadı." }
    }

    fun updateEcuData(rpm: Int, tps: Int, coolant: Int, mapKpa: Int, voltage: Double, faults: List<String>, connected: Boolean) {
        val values = linkedMapOf<String, Double>()
        if (rpm >= 0) values["Motor devri (RPM)"] = rpm.toDouble()
        if (tps >= 0) values["Gaz kelebeği (%)"] = tps.toDouble()
        if (coolant > -100) values["Soğutma suyu sıcaklığı (°C)"] = coolant.toDouble()
        if (mapKpa >= 0) values["Manifold basıncı (kPa)"] = mapKpa.toDouble()
        if (voltage >= 0) values["Akü/ECU voltajı (V)"] = voltage
        updateEcuDataSnapshot(values, faults, connected)
    }
    fun updateEcuDataSnapshot(values: Map<String, Double>, faults: List<String>, connected: Boolean) {
        ecuConnected = connected
        ecuValues = values.filterValues { it.isFinite() }.toMap()
        ecuFaults = faults.toList()
    }

    fun analyzeCurrentEngineData(onResponse: (String) -> Unit) {
        if (!ecuConnected) {
            main.post { onResponse("ECU bağlantısı yok. Önce Viecar bağlantısını kurup gerçek canlı verileri almalıyız.") }
        } else chat("Güncel ECU verilerini analiz et; ölçümleri, olası riskleri ve güvenli kontrol sırasını açıkla.", onResponse)
    }
    fun chat(question: String, onResponse: (String) -> Unit) = ask(question, onResponse)

    fun ask(question: String, onResponse: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            main.post { onResponse("Önce mevcut değerlendirmeyi tamamlayayım, usta.") }
            return
        }
        Thread({
            val answer = try { reason(question) } catch (e: Exception) {
                "Değerlendirme sırasında hata oluştu: ${e.message ?: "bilinmeyen hata"}"
            }
            busy.set(false)
            main.post { onResponse(answer) }
        }, "Seyfi-Local-Reasoner").start()
    }

    private fun reason(question: String): String {
        val q = normalize(question)
        val codePattern = Regex("\\bP[0-9A-F]{4}\\b", RegexOption.IGNORE_CASE)
        val codes = (codePattern.findAll(question).map { it.value.uppercase(Locale.US) } + ecuFaults).distinct().take(12).toList()
        if (q.contains("merhaba") || q == "selam" || q.contains("nasilsin"))
            return "İyiyim usta, hazırım. ECU bağlantısı varsa canlı verileri birlikte değerlendirebiliriz."
        if (q.contains("ne yapabilirsin") || q == "yardim" || q.contains("neler yapabilirsin"))
            return "Gerçek ECU arıza kodlarını, desteklenen canlı sensör verilerini ve test sonuçlarını değerlendirebilirim. Ölçülmeyen değeri uydurmam; doğrulanmamış parçayı kesin arızalı ilan etmem."
        if (q.contains("baglanti") && (q.contains("durum") || q.contains("bagli mi")))
            return if (ecuConnected) "ECU bağlantısı aktif. Son alınan ${ecuValues.size} ölçüm değeri mevcut." else "ECU bağlantısı yok. Viecar VP002 WiFi bağlantısını ve motosiklet adaptör kablosunu kontrol edelim."
        if (codes.isNotEmpty()) return explainCodes(codes)
        if (listOf("canli", "sensor", "veri", "motor durumu", "analiz", "yorumla", "degerlendir").any { q.contains(it) })
            return analyzeValues()
        val matches = YamahaKnowledgeBase.search(appContext, question, limit = 3)
        if (matches.isNotEmpty()) return buildString {
            append("Usta, yerel servis bilgi bankasında şu referansları buldum: ")
            matches.forEach { record ->
                append("${record.title}. ${record.description} ")
                if (record.possibleCauses.isNotEmpty()) append("Olası nedenler: ${record.possibleCauses.take(3).joinToString("; ")}. ")
                if (record.checks.isNotEmpty()) append("İlk kontroller: ${record.checks.take(3).joinToString("; ")}. ")
                append("Doğrulama seviyesi: ${record.verification}. ")
            }
            append("Bu kayıtlar belirti eşleşmesidir; kesin teşhis değildir.")
        }
        if (!ecuConnected)
            return "Seni anladım usta. Doğru değerlendirme için motosikletin marka, model ve yılını; belirtinin ne zaman oluştuğunu bilmem gerekiyor. ECU bağlı değilken canlı ölçüm varmış gibi konuşmayacağım."
        return "Sorunu biraz daha tarif et usta. Elimde ${ecuValues.size} gerçek ECU ölçümü var. ${diagnosticPlanContext.take(250)}"
    }

    private fun explainCodes(codes: List<String>): String = buildString {
        append("Arıza kodlarını inceliyorum. ")
        codes.forEach { code ->
            val record = YamahaKnowledgeBase.find(appContext, code)
            if (record != null) {
                append("$code: ${record.title}. ${record.description} ")
                if (record.possibleCauses.isNotEmpty()) append("Olası nedenler: ${record.possibleCauses.take(4).joinToString("; ")}. ")
                if (record.checks.isNotEmpty()) append("Kontrol sırası: ${record.checks.take(4).joinToString("; ")}. ")
                append("Doğrulama: ${record.verification}. ")
            } else {
                val title = DtcDatabase.title(code)
                append("$code: $title. ")
                if (title.startsWith("Üreticiye özel")) append("Bu kod için doğrulanmış açıklamam yok; model/yıl ve Yamaha servis dokümanı gerekli. ")
                else append("${DtcDatabase.description(code)} Olası nedenler: ${DtcDatabase.causes(code).take(4).joinToString("; ")}. Genel OBD açıklamasıdır; Yamaha'ya özel teşhis sayılmaz. ")
            }
        }
        append("Kod tek başına parçanın bozuk olduğunu kanıtlamaz. Önce soket, kablo, besleme ve şaseyi; sonra sensör sinyalini servis ölçümüyle doğrulayalım.")
    }

    private fun analyzeValues(): String = buildString {
        if (!ecuConnected) { append("ECU bağlantısı yok; canlı veri analizi yapamam."); return@buildString }
        if (ecuValues.isEmpty()) { append("ECU bağlı görünüyor ancak geçerli canlı ölçüm alınmamış."); return@buildString }

        append("Son ECU örneğinde ${ecuValues.size} gerçek ölçüm var. ")
        ecuValues.toSortedMap().forEach { (key, value) ->
            append("$key: ${"%.2f".format(Locale("tr", "TR"), value)}. ")
        }
        if (ecuFaults.isNotEmpty()) append("Kayıtlı kodlar: ${ecuFaults.joinToString()}. ")
        else append("Son bildirilen arıza kodu listesi boş. ")

        fun valueFor(vararg names: String): Double? =
            ecuValues.entries.firstOrNull { entry ->
                names.any { name -> entry.key.contains(name, ignoreCase = true) }
            }?.value

        val rpm = valueFor("rpm", "devir")
        val tps = valueFor("tps", "kelebek")
        val coolant = valueFor("coolant", "soğutma", "sicaklik", "sıcaklık")
        val map = valueFor("map", "manifold")
        val voltage = valueFor("volt", "akü", "aku")

        // Fiziksel olarak beklenmeyen değerler arıza diye etiketlenmez;
        // PID yanıtının/ölçeklemenin yeniden doğrulanması istenir.
        if (rpm != null && rpm !in 0.0..16000.0)
            append("RPM ölçümü beklenen genel OBD aralığının dışında; PID yanıtını tekrar doğrula. ")
        if (tps != null && tps !in 0.0..100.0)
            append("TPS yüzdesi 0–100 aralığının dışında; ham PID çözümlemesini kontrol et. ")
        if (coolant != null && coolant !in -40.0..215.0)
            append("Soğutma suyu sıcaklığı PID aralığının dışında; sensör verisini tekrar oku. ")
        if (map != null && map !in 0.0..255.0)
            append("MAP değeri standart PID aralığının dışında; ölçüm çözümlemesini doğrula. ")

        if (voltage != null && voltage < 11.8)
            append("Voltaj düşük görünüyor; aküyü uygun cihazla ve üretici prosedürüne göre kontrol ettir. ")
        if (voltage != null && voltage > 15.2)
            append("Voltaj yüksek görünüyor; şarj sistemi ölçümünü servis prosedürüne göre doğrulat. ")

        if (latestDiagnosticTestResult != "Henüz salt-okunur test sonucu alınmadı.")
            append("Son sistem testi: ${latestDiagnosticTestResult.take(700)}. ")
        if (diagnosticPlanContext != "Henüz teşhis planı oluşturulmadı.")
            append("Mevcut kontrol planı: ${diagnosticPlanContext.take(500)}. ")

        append("Aralık dışı değer tek başına parça arızasını kanıtlamaz; PID yanıtı ve ölçüm koşulları tekrar doğrulanmalıdır. Tek anlık örnek kesin teşhis değildir.")
    }

    fun close() { ready = false }
}
