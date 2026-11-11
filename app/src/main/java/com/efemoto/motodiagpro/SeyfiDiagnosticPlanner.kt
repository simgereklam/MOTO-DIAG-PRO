package com.efemoto.motodiagpro

/**
 * AI'nın seçebileceği yalnızca salt-okunur OBD sorguları.
 * Bu katalogda arıza silme (04), aktüatör testi veya ECU'ya yazma komutu bulunmaz.
 */
data class SeyfiReadOnlyTest(
    val id: String,
    val title: String,
    val description: String,
    val queries: List<Triple<String, String, String>>,
    val requiredPids: Set<Int>
)

object SeyfiDiagnosticTestCatalog {
    private fun test(
        id: String,
        title: String,
        description: String,
        vararg queries: Triple<String, String, String>
    ) = SeyfiReadOnlyTest(
        id = id,
        title = title,
        description = description,
        queries = queries.toList(),
        requiredPids = queries.mapNotNull { query ->
            query.second.takeIf { it.matches(Regex("01[0-9A-Fa-f]{2}")) }
                ?.takeLast(2)?.toIntOrNull(16)
        }.toSet()
    )

    private val tests = listOf(
        test(
            "basic_live_data",
            "TEMEL CANLI VERİ KONTROLÜ",
            "Gerçek ECU'dan temel motor sensörlerini salt-okunur sorgularla kontrol eder.",
            Triple("Motor devri (RPM)", "010C", "410C"),
            Triple("Gaz kelebeği (TPS)", "0111", "4111"),
            Triple("Soğutma suyu sıcaklığı", "0105", "4105"),
            Triple("Emme basıncı (MAP)", "010B", "410B"),
            Triple("ECU / kontrol modülü voltajı", "0142", "4142")
        ),
        test(
            "fuel_trim_review",
            "YAKIT KARIŞIMI VERİ İNCELEMESİ",
            "ECU destekliyorsa STFT, LTFT ve oksijen sensörü verilerini okur. Yakıt basıncı veya enjektör püskürtme testi yapmaz.",
            Triple("Kısa dönem yakıt düzeltmesi (STFT)", "0106", "4106"),
            Triple("Uzun dönem yakıt düzeltmesi (LTFT)", "0107", "4107"),
            Triple("Oksijen sensörü 1 verisi", "0114", "4114")
        ),
        test(
            "throttle_rpm_review",
            "TPS / RPM İLİŞKİ KONTROLÜ",
            "Gaz kelebeği konumu ile motor devrini okur. Gaz kelebeğini otomatik hareket ettirmez.",
            Triple("Motor devri (RPM)", "010C", "410C"),
            Triple("Gaz kelebeği (TPS)", "0111", "4111")
        ),
        test(
            "ignition_advance_review",
            "ATEŞLEME AVANSI VERİ KONTROLÜ",
            "ECU destekliyorsa ateşleme avansını okur. Bobini tetiklemez ve kıvılcım testi yapmaz.",
            Triple("Ateşleme avansı", "010E", "410E")
        ),
        test(
            "dtc_memory_review",
            "ARIZA HAFIZASI KONTROLÜ",
            "Kayıtlı, bekleyen ve kalıcı arıza hafızalarını okur. Hiçbir kodu silmez.",
            Triple("Kayıtlı arızalar", "03", "43"),
            Triple("Bekleyen arızalar", "07", "47"),
            Triple("Kalıcı arızalar", "0A", "4A")
        ),
        test(
            "ecu_identity_review",
            "ECU KİMLİK BİLGİSİ",
            "ECU destekliyorsa VIN ve kalibrasyon kimliğini salt-okunur sorgular.",
            Triple("Şasi numarası (VIN)", "0902", "4902"),
            Triple("Kalibrasyon kimliği", "0904", "4904")
        )
    )

    fun all(): List<SeyfiReadOnlyTest> = tests.toList()

    fun find(id: String): SeyfiReadOnlyTest? =
        tests.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    fun isReadOnly(test: SeyfiReadOnlyTest): Boolean =
        test.queries.all { (_, command, _) ->
            command.matches(Regex("(?i)(01[0-9A-F]{2}|03|07|0A|09[0-9A-F]{2}|ATI|ATRV)"))
        }
}

data class SeyfiDiagnosticPlan(
    val detectedCodes: List<String>,
    val recommendedTests: List<SeyfiReadOnlyTest>,
    val notes: List<String>,
    val observedValues: Map<String, Double>
) {
    fun toPromptContext(supportedPids: Set<Int>): String = buildString {
        appendLine("SEYFİ USTA TEŞHİS PLANI (yalnızca gerçek ECU kanıtı):")
        appendLine(
            "ECU'dan okunan arıza kodları: " +
                detectedCodes.joinToString().ifBlank { "Kod bildirilmedi." }
        )
        if (observedValues.isEmpty()) {
            appendLine("Ölçülen canlı değer: Henüz yok.")
        } else {
            val units = mapOf(
                "rpm" to "RPM", "tps" to "%", "coolant" to "°C", "map" to "kPa",
                "voltage" to "V", "load" to "%", "stft" to "%", "ltft" to "%",
                "speed" to "km/h", "timing" to "°", "iat" to "°C", "maf" to "g/s",
                "o2" to "V", "relativeThrottle" to "%", "pedal" to "%",
                "commandedThrottle" to "%", "oilTemp" to "°C"
            )
            appendLine("Gerçek ECU canlı ölçümleri:")
            observedValues.toSortedMap().forEach { (key, value) ->
                val unit = units[key].orEmpty()
                appendLine("- $key: ${"%.2f".format(java.util.Locale.US, value)} $unit")
            }
        }
        appendLine("Önerilen güvenli salt-okunur kontroller:")
        recommendedTests.forEach { test ->
            val support = when {
                supportedPids.isEmpty() -> "PID desteği henüz doğrulanmadı"
                test.requiredPids.isEmpty() -> "standart sorgu"
                test.requiredPids.all { it in supportedPids } -> "gerekli PID'ler destekleniyor"
                else -> "bazı PID'ler desteklenmiyor veya yanıt vermedi"
            }
            appendLine("- ${test.id}: ${test.title}; ${test.description}; durum: $support")
        }
        notes.forEach { appendLine("Not: $it") }
        append(
            "Güvenlik: Bu plan yalnızca okuma sorgularıdır. Desteklenmeyen PID arıza kanıtı değildir. " +
                "Aktüatör, fan, pompa, enjektör, bobin veya ECU yazma/silme komutu önerme. " +
                "Motor çalıştırma veya sürüş gerektiren ölçümde kullanıcı güvenli koşulları sağlamadan test başlatma."
        )
    }
}

object SeyfiDiagnosticPlanner {
    private val dtcRegex = Regex("\\bP[0-9A-F]{4}\\b", RegexOption.IGNORE_CASE)

    fun createPlan(
        rawCodes: List<String>,
        observedValues: Map<String, Double>,
        supportedPids: Set<Int>
    ): SeyfiDiagnosticPlan {
        val codes = rawCodes.flatMap { raw ->
            dtcRegex.findAll(raw).map { it.value.uppercase() }.toList()
        }.distinct()

        val selected = linkedSetOf<String>()
        if (codes.isEmpty()) selected += "basic_live_data"
        if (codes.any { it == "P0171" || it == "P0172" || it.startsWith("P013") }) {
            selected += "fuel_trim_review"
        }
        if (codes.any { it == "P0335" || it == "P0336" }) {
            selected += "basic_live_data"
        }
        if (codes.any { it == "P0300" || it == "P0301" || it == "P0302" }) {
            selected += "basic_live_data"
            selected += "ignition_advance_review"
        }
        if (codes.any { it == "P0511" || it.startsWith("P012") }) {
            selected += "throttle_rpm_review"
        }
        if (selected.isEmpty()) selected += "basic_live_data"

        val recommendations = selected.mapNotNull { SeyfiDiagnosticTestCatalog.find(it) }
        val notes = mutableListOf<String>()
        if (codes.any { it == "P0335" || it == "P0336" }) {
            notes += "Krank sensörü kodunda RPM verisi yalnızca güvenli ve kontrollü marş gözlemiyle değerlendirilebilir; sıfır RPM tek başına sensör arızasını kanıtlamaz."
        }
        if (codes.any { it == "P0300" || it == "P0301" || it == "P0302" }) {
            notes += "Standart OBD RPM ve ateşleme avansı tek başına tekleme silindirini doğrulamaz; modele özgü sayaç veya servis prosedürü olmadan kesin sonuç çıkarma."
        }
        if (supportedPids.isEmpty()) {
            notes += "ECU destek PID listesi alınmadı; hiçbir PID destekleniyor veya desteklenmiyor diye kesin işaretlenmedi."
        }
        if (observedValues.isEmpty()) {
            notes += "Canlı sensör verisi alınmadı; arıza kodundan tek başına parça değişimi önermeyin."
        }
        return SeyfiDiagnosticPlan(codes, recommendations, notes, observedValues.toMap())
    }
}
