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
    @Volatile private var activeVehicleProfile: String = ""
    @Volatile private var diagnosticPlanContext = "Henüz teşhis planı oluşturulmadı."
    @Volatile private var latestDiagnosticTestResult = "Henüz salt-okunur test sonucu alınmadı."
    private val conversationPrefs = appContext.getSharedPreferences("seyfi_conversation", Context.MODE_PRIVATE)
    @Volatile private var pendingSymptom = conversationPrefs.getString("pending_symptom", "").orEmpty()
    init { activeVehicleProfile = conversationPrefs.getString("vehicle_profile", "").orEmpty() }
    private fun saveVehicleProfile(value: String) { activeVehicleProfile = value.trim().take(100); conversationPrefs.edit().putString("vehicle_profile", activeVehicleProfile).apply() }
    private fun rememberPendingSymptom(value: String) { pendingSymptom = value.take(250); conversationPrefs.edit().putString("pending_symptom", pendingSymptom).apply() }
    private fun detectVehicleMention(q: String): String? {
        val models = listOf("mt 07" to "Yamaha MT-07", "mt07" to "Yamaha MT-07", "mt 09" to "Yamaha MT-09", "mt09" to "Yamaha MT-09", "mt 25" to "Yamaha MT-25", "mt25" to "Yamaha MT-25", "mt 03" to "Yamaha MT-03", "mt03" to "Yamaha MT-03", "nmax" to "Yamaha NMAX", "xmax" to "Yamaha XMAX", "tmax" to "Yamaha TMAX", "r25" to "Yamaha R25", "tracer 7" to "Yamaha Tracer 7", "tracer 9" to "Yamaha Tracer 9", "nc750" to "Honda NC750", "nc 750" to "Honda NC750", "pcx" to "Honda PCX", "z650" to "Kawasaki Z650", "sv650" to "Suzuki SV650")
        val found = models.firstOrNull { (key, _) -> Regex("(^| )" + Regex.escape(key) + "($| )").containsMatchIn(q) }?.second ?: return null
        val year = Regex("\\b(19[89]\\d|20[0-3]\\d)\\b").find(q)?.value
        return if (year != null) "$found $year" else found
    }

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

    fun updateVehicleProfile(modelYear: String) {
        if (modelYear.isNotBlank()) saveVehicleProfile(modelYear)
    }

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
        val codePattern = Regex("\\b(?:P[0-9A-F]{4}|[0-9]{2}(?:_ABS)?)\\b", RegexOption.IGNORE_CASE)
        // ECU strings may contain display suffixes such as "P0335 • KAYITLI".
        // Extract only the actual DTC before looking it up.
        val codes = (
            codePattern.findAll(question).map { it.value.uppercase(Locale.US) } +
                ecuFaults.flatMap { raw -> codePattern.findAll(raw).map { it.value.uppercase(Locale.US) }.toList() }
        ).distinct().take(12).toList()
        // Günlük sohbet, ECU bağlantısından bağımsız ele alınır.
        // Önce kullanıcının kendi hâlini bildirdiğini yakala: "iyiyim, sen nasılsın"
        // cümlesindeki nasılsın kelimesi Seyfi'nin aynı soruyu tekrar ettirmemeli.
        if (q.contains("iyiyim") || q.contains("fena degilim") ||
            q.contains("iyi sayilirim") || q.contains("sukur iyiyim") ||
            q.contains("gayet iyiyim") || q.contains("keyfim yerinde")
        ) return "Oh, iyi olmana sevindim usta. Bugün ne konuşalım? Aklında bir konu varsa anlat, seni dinliyorum."

        if (q.contains("kotu") && (q.contains("hissediyorum") || q.contains("gunum") || q.contains("keyfim")))
            return "Üzüldüm usta. İstersen anlat, seni dinliyorum. Belki birlikte biraz olsun çözüm bulabiliriz."

        if (q.contains("sohbet edebiliyor musun") || q.contains("sohbet edebilir misin") ||
            q.contains("sohbet edebiliyor") || q.contains("sohbet edebilir") ||
            q.contains("konusabiliyor musun") || q.contains("seninle sohbet") ||
            q.contains("sohbet edelim") || q.contains("biraz konusalim")
        ) return "Elbette usta, seninle sohbet edebilirim. ECU'ya bağlı olmamız gerekmiyor. İstersen birlikte adım adım ilerleyelim. Neler konuşmak istersin?"

        if (q.contains("nasilsin") || q.contains("ne haber") || q.contains("keyfin nasil") ||
            q.contains("iyi misin") || q.contains("nasil gidiyor")
        ) return "İyiyim usta, teşekkür ederim. Bugün buradayım, sana eşlik ediyorum. Senin günün nasıl geçiyor?"

        if (q.contains("merhaba") || q == "selam" || q.contains("gunaydin") ||
            q.contains("iyi aksamlar") || q.contains("iyi geceler")
        ) return "Merhaba usta, hoş geldin. Ben buradayım, seni dinliyorum. Sen nasılsın?"

        if (q.contains("tesekkur") || q == "sag ol" || q == "eyvallah")
            return "Rica ederim usta. Seninle konuşmak güzel. Başka ne konuşalım?"

        if (q.contains("sen kimsin") || q.contains("kimsin") || q.contains("kendini tanit"))
            return "Ben Seyfi Usta. Motosiklet teşhisinde yardımcı olurum ama yalnızca bununla sınırlı değilim; seninle günlük konularda da sohbet edebilirim."

        if (q.contains("ne yapiyorsun") || q.contains("napıyorsun") || q.contains("ne yapiyorsun"))
            return "Seninle konuşuyorum usta, söylediklerini anlamaya çalışıyorum. Sen neler yapıyorsun?"

        if (q.contains("beni anliyor musun") || q.contains("sesim geliyor mu"))
            return "Sesin bana metin olarak ulaştı usta. Bir cümle daha söyle; doğru algılayıp algılamadığımı birlikte kontrol edelim."

        if (q.contains("biraz daha anlat") || q.contains("detaylandir") ||
            q.contains("bunu acikla") || q == "devam et"
        ) return "Tabii usta. Önceki konuyu biraz daha açmam için hangi bölümünü merak ettiğini söyle; adım adım anlatayım."
        if (q.contains("ne yapabilirsin") || q == "yardim" || q.contains("neler yapabilirsin"))
            return "Gerçek ECU arıza kodlarını, desteklenen canlı sensör verilerini ve test sonuçlarını değerlendirebilirim. Ölçülmeyen değeri uydurmam; doğrulanmamış parçayı kesin arızalı ilan etmem."
        if (q.contains("baglanti") && (q.contains("durum") || q.contains("bagli mi")))
            return if (ecuConnected) "ECU bağlantısı aktif. Son alınan ${ecuValues.size} ölçüm değeri mevcut." else "ECU bağlantısı yok. Viecar VP002 WiFi bağlantısını ve motosiklet adaptör kablosunu kontrol edelim."
        val mentionedVehicle = detectVehicleMention(q)
        if (mentionedVehicle != null) {
            saveVehicleProfile(mentionedVehicle)
            if (pendingSymptom.isNotBlank()) {
                rememberPendingSymptom("")
                return "Tamam usta, motosikletini $activeVehicleProfile olarak kaydettim. Önceki şikâyetini bu modele göre değerlendireceğim. Tekleme soğuk motorda mı, ısınınca mı, yoksa gaz verince mi oluyor?"
            }
            return "Tamam usta, motosikletini $activeVehicleProfile olarak kaydettim. Bundan sonraki arıza sorularını bu model üzerinden değerlendireceğim."
        }
        val modelHint = activeVehicleProfile.takeIf { it.isNotBlank() }
        if (codes.isNotEmpty()) return explainCodes(codes, modelHint)
        if (listOf("canli", "sensor", "veri", "motor durumu", "analiz", "yorumla", "degerlendir").any { q.contains(it) }) return analyzeValues()
        val symptomTerms = listOf("tekleme", "tekliyor", "calismiyor", "stop ediyor", "stop etme", "cekis dusuk", "gaz yemiyor", "gec calisiyor", "zor calisiyor", "hararet", "isinma", "titresim", "duman atiyor", "yakit tuketimi", "rolanti")
        if (symptomTerms.any { q.contains(it) } && modelHint == null) {
            rememberPendingSymptom(question)
            return "Önce motosikletini netleştirelim usta. Marka ve model nedir? Mümkünse model yılını da söyle. Modeli öğrenince bu şikâyeti ona göre değerlendireceğim."
        }
        if (symptomTerms.any { q.contains(it) } && modelHint != null) {
            val scoped = YamahaKnowledgeBase.search(appContext, question, limit = 3, model = modelHint)
            if (scoped.isEmpty()) return "$modelHint için bu belirtiye ait doğrulanmış model özelinde kaydım yok. Başka modelin arızasını buna uyarlamayacağım. Tekleme hangi koşulda oluyor?"
        }
        val matches = YamahaKnowledgeBase.search(appContext, question, limit = 3, model = modelHint)
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
            return "Anladım usta. Şu an ECU'ya bağlı değilim; bu yüzden gerçek ölçüm yapamam. Ama sohbet edebilir, genel bilgi verebilir ve bir arıza anlatırsan önce motosikletinin marka-modelini öğrenip adım adım ilerleyebilirim. Seni dinliyorum."
        return "Sorunu biraz daha tarif et usta. Elimde ${ecuValues.size} gerçek ECU ölçümü var. ${diagnosticPlanContext.take(250)}"
    }

    private fun explainCodes(codes: List<String>, model: String? = null): String = buildString {
        append("Arıza kodlarını inceliyorum. ")
        codes.forEach { code ->
            val record = YamahaKnowledgeBase.find(appContext, code, model)
            if (record != null) {
                append("$code: ${record.title}. ${record.description} ")
                if (record.possibleCauses.isNotEmpty()) append("Olası nedenler: ${record.possibleCauses.take(4).joinToString("; ")}. ")
                if (record.checks.isNotEmpty()) append("Kontrol sırası: ${record.checks.take(4).joinToString("; ")}. ")
                if (record.models.isNotEmpty()) {
                    append("Bu açıklama ${record.models.joinToString()} ${record.years.joinToString()} için servis kılavuzuyla doğrulanmıştır; diğer modellere genellenemez. ")
                }
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

        // Efe Moto bağımsız çekirdeği: yalnızca gerçek ECU snapshot'ını analiz eder.
        val efeMotoResult = EfeMotoDiagnosticEngine.analyze(
            EfeMotoSnapshot(values = ecuValues, dtcs = ecuFaults, ecuConnected = ecuConnected)
        )
        efeMotoResult.findings.forEach { finding ->
            append("${finding.title}: ${finding.detail} Önerilen kontrol: ${finding.nextCheck} ")
        }
        efeMotoResult.notes.forEach { note -> append("$note ") }

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
