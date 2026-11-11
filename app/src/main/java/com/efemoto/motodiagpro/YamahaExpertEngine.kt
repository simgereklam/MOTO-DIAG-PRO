package com.efemoto.motodiagpro

import java.util.Locale

data class YamahaExpertReport(
    val displayText: String,
    val speechText: String,
    val usedModelSpecificProcedure: Boolean
)

/**
 * Deterministic, offline Yamaha diagnosis layer.
 * Model-specific details are returned only for an exact model/year match.
 * Otherwise the result is explicitly labelled as generic OBD-II information.
 */
object YamahaExpertEngine {
    private data class Procedure(
        val year: Int,
        val aliases: Set<String>,
        val modelLabel: String,
        val symptom: String,
        val failSafe: String,
        val checks: List<String>,
        val sourceLabel: String,
        val sourceUrl: String
    )

    private val procedures = listOf(
        Procedure(
            2022, setOf("NMAX 155", "GPD155-A", "GPD155A"),
            "Yamaha NMAX 155 GPD155-A (2022)",
            "Krank mili konum sensöründen normal sinyal alınmıyor.",
            "Servis dokümanında motorun çalıştırılamaması ve aracın kullanılamaması belirtiliyor.",
            listOf(
                "Krank sensörü soketinin kilidini, pinlerini ve gevşek/hasarlı terminal olup olmadığını kontrol edin.",
                "SGCU soketini ve pinlerini kontrol edin.",
                "Sensör ile SGCU arasındaki tesisatta kopukluk ve kısa devre kontrollerini, ilgili soketleri ayırarak yapın.",
                "Sensörün gevşek veya sıkışmış takılmadığını kontrol edin; sensör ile jeneratör rotoru arasındaki hava aralığını 1,15 mm olarak doğrulayın.",
                "Sensörü servis manuelindeki ölçüm prosedürüyle kontrol edin; önceki devre kontrolleri normalse sensör/SGCU değerlendirmesine geçin.",
                "Onarım sonrası YDT ile kodun 'Recovered' durumuna geçtiğini doğrulayın; bundan sonra kod silme işlemini uygulayın."
            ),
            "Yamaha NMAX GPD155-A 2022 Servis Manueli, bölüm 9-93–9-96",
            "https://www.scribd.com/document/740047365/2022%EB%85%84-N-MAX-155-BBD-F8197-EK-%EC%9E%A0%EA%B8%88-%ED%95%B4%EC%A0%9C%EB%90%A8-o-1g0gaip5i1m91vck17da1j0p1dsg8-1"
        ),
        Procedure(
            2023, setOf("MT 125", "MT125"),
            "Yamaha MT-125 (2023)",
            "Krank mili konum sensöründen normal sinyal alınmıyor.",
            "Servis dokümanında motorun çalıştırılamaması ve aracın kullanılamaması belirtiliyor.",
            listOf(
                "Krank sensörü soketinin kilidini ve pinlerini kontrol edin.",
                "ECU soketinin kilidini ve pinlerini kontrol edin.",
                "Sensör ile ECU arasındaki kablo tesisatında süreklilik ve kısa devre kontrollerini servis prosedürüne göre yapın.",
                "Soket ve tesisat kontrolleri normalse krank sensörünü ilgili modelin servis ölçüm prosedürüyle kontrol edin.",
                "Onarım sonrası Yamaha Diagnostic Tool ile arızanın 'Recovered' durumuna geçtiğini doğrulayın."
            ),
            "Yamaha MT-125 2023 Servis Manueli, P0335 teşhis bölümü",
            "https://www.manualslib.com/manual/4649537/Yamaha-Mt125-2023.html?page=431"
        ),
        Procedure(
            2021, setOf("MT 09", "MT09", "MT 09 SP", "MT09SP", "MTN890", "MTN890D"),
            "Yamaha MT-09 / MT-09 SP (2021)",
            "Krank mili konum sensöründen normal sinyal alınmıyor.",
            "Servis dokümanında motorun çalıştırılamaması ve aracın kullanılamaması belirtiliyor.",
            listOf(
                "Krank sensörü soketinin kilidini ve pinlerini kontrol edin.",
                "ECU soketini ve ara bağlantı soketini kontrol edin.",
                "Sensör–ara soket–ECU arasındaki kablo sürekliliğini ve kısa devreleri servis şemasına göre ölçün.",
                "Kablo kontrolleri normalse krank sensörünü Yamaha servis manuelindeki kontrol prosedürüyle test edin.",
                "Sensör ve tesisat doğrulanmadan ECU'yu arızalı kabul etmeyin; onarım sonrası YDT ile kodun geri dönmediğini doğrulayın."
            ),
            "Yamaha MT-09 / MT-09 SP 2021 Servis Manueli, bölüm 9-151–9-153",
            "https://www.moto4.ru/downloads/Yamaha-MT-09-Service-manual-2021-B7N-28197-E0.pdf"
        )
    )

    private val codeRegex = Regex("\\bP[0-9A-F]{4}\\b", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("\\b(20\\d{2})\\b")

    fun analyze(
        rawCodes: List<String>,
        modelAndYear: String,
        liveValues: Map<String, Double> = emptyMap()
    ): YamahaExpertReport {
        val codes = rawCodes.flatMap { raw ->
            codeRegex.findAll(raw).map { it.value.uppercase(Locale.US) }.toList()
        }.distinct().take(5)

        if (codes.isEmpty()) {
            val message = "Bu rapora doğrulanmış bir ECU arıza kodu aktarılmadı. Önce gerçek ECU taramasını tamamlayın; yalnızca canlı verilerden arıza kodu varmış gibi sonuç çıkarmıyorum."
            return YamahaExpertReport(
                message,
                "Önce gerçek ECU taramasını tamamlayalım. Henüz doğrulanmış arıza kodu yok.",
                false
            )
        }

        val procedure = resolveProcedure(modelAndYear)
        val modelSpecificUsed = codes.any { it == "P0335" && procedure != null }
        val output = buildString {
            appendLine("SEYFİ USTA • YAMAHA ÇEVRİMDIŞI TEŞHİS")
            appendLine("Araç bilgisi: ${modelAndYear.ifBlank { "Belirtilmedi" }}")
            appendLine("Okunan ECU kodları: ${codes.joinToString()}")
            appendLine()

            codes.forEach { code ->
                appendLine(code)
                if (code == "P0335" && procedure != null) {
                    appendLine("Model/yıl eşleşmesi: ${procedure.modelLabel}")
                    appendLine("Doğrulama: Bu kod için model ve yıl bazında servis prosedürü bulundu.")
                    appendLine("Anlamı: ${procedure.symptom}")
                    appendLine("Fail-safe: ${procedure.failSafe}")
                    appendLine("Kontrol sırası:")
                    procedure.checks.forEachIndexed { index, check ->
                        appendLine("${index + 1}. $check")
                    }
                    appendLine("Kaynak: ${procedure.sourceLabel}")
                    appendLine("Kaynak bağlantısı: ${procedure.sourceUrl}")
                } else {
                    val generic = YamahaFaultCodes.find(code)
                    if (generic != null) {
                        appendLine("Açıklama: ${generic.description}")
                        appendLine("Olası nedenler: ${generic.possibleCauses.joinToString("; ")}")
                        appendLine("İlk kontroller: ${generic.checks.joinToString("; ")}")
                        appendLine("Doğrulama sınırı: Bu kayıt genel OBD-II referansıdır. Girilen Yamaha model/yılı için üretici servis prosedürü doğrulanmadı.")
                    } else {
                        appendLine("Bu kod için yerel ve doğrulanmış açıklama bulunamadı. Parça veya ölçüm değeri tahmin edilmeyecek.")
                    }
                }
                appendLine()
            }

            if (procedure == null && modelAndYear.isNotBlank()) {
                appendLine("MODEL UYARISI: Girilen model/yıl için P0335 kodunda doğrulanmış Yamaha servis prosedürü bulunamadı. Başka modelin kablo rengi, sensör aralığı veya direnç değeri kullanılmamalıdır.")
            } else if (modelAndYear.isBlank()) {
                appendLine("MODEL UYARISI: Model ve üretim yılı seçilmediği için yalnızca genel kod bilgisi verilebilir.")
            }

            if (liveValues.isNotEmpty()) {
                appendLine("GERÇEK ECU CANLI VERİLERİ:")
                val units = mapOf(
                    "rpm" to "RPM", "tps" to "%", "coolant" to "°C",
                    "map" to "kPa", "voltage" to "V", "stft" to "%",
                    "ltft" to "%", "speed" to "km/h"
                )
                liveValues.toSortedMap().forEach { (key, value) ->
                    appendLine("- $key: ${String.format(Locale.US, "%.2f", value)} ${units[key].orEmpty()}")
                }
                appendLine("Canlı değerler tek başına sensör veya ECU arızasını kesinleştirmez; model servis ölçümleriyle birlikte değerlendirilmelidir.")
            }

            appendLine("GÜVENLİK: Bu rapor yalnızca teşhis yönlendirmesidir. Aktüatör sürme, ECU'ya yazma veya otomatik arıza silme yapmaz. Elektrik direnci/süreklilik ölçümünden önce ilgili devre ve soketler servis prosedürüne göre enerjisiz bırakılmalıdır.")
        }

        val speech = if (modelSpecificUsed && procedure != null) {
            "${codeSummary(codes)}. ${procedure.modelLabel} için doğrulanmış servis kontrol sırasını ekrana yazdım. Önce soket ve kablo tesisatını kontrol edin; sensörü veya kontrol ünitesini ölçüm yapmadan değiştirmeyin."
        } else {
            "${codeSummary(codes)}. Bu kodlar için genel OBD bilgisi var; seçilen Yamaha model ve yılına özel servis prosedürü henüz doğrulanmadı. Ekrandaki genel kontrolleri model servis verisiyle teyit edin."
        }

        return YamahaExpertReport(output.trim(), speech, modelSpecificUsed)
    }

    private fun codeSummary(codes: List<String>): String =
        if (codes.size == 1) "ECU'dan ${codes.first()} kodu okunmuş" else "ECU'dan ${codes.size} arıza kodu okunmuş"

    private fun resolveProcedure(modelAndYear: String): Procedure? {
        if (modelAndYear.isBlank()) return null
        val year = yearRegex.find(modelAndYear)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val normalizedInput = normalize(modelAndYear)
        return procedures.firstOrNull { procedure ->
            procedure.year == year && procedure.aliases.any { alias ->
                normalizedInput.contains(normalize(alias))
            }
        }
    }

    private fun normalize(value: String): String =
        value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
}
