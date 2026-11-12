package com.efemoto.motodiagpro

/**
 * Efe Moto'nun cihaz içi, açıklanabilir teşhis çekirdeği.
 * Gerçek ECU ölçümlerini kullanır; profil yoksa modele özel eşik uydurmaz.
 * Yalnızca analiz yapar: ECU'ya komut göndermez veya parça değişimini kesinleştirmez.
 */
data class EfeMotoSnapshot(
    val values: Map<String, Double>,
    val dtcs: List<String> = emptyList(),
    val ecuConnected: Boolean = true
)

data class EfeMotoReferenceRange(val minimum: Double, val maximum: Double, val unit: String) {
    init { require(minimum <= maximum) { "Referans aralığı geçersiz." } }
}

data class EfeMotoBikeProfile(
    val profileId: String,
    val referenceRanges: Map<String, EfeMotoReferenceRange> = emptyMap(),
    val idleRpmRange: ClosedFloatingPointRange<Double>? = null
)

enum class EfeMotoFindingLevel { INFO, WARNING, CRITICAL, DATA_QUALITY }

data class EfeMotoFinding(
    val sensor: String,
    val title: String,
    val detail: String,
    val nextCheck: String,
    val level: EfeMotoFindingLevel
)

data class EfeMotoAnalysis(
    val findings: List<EfeMotoFinding>,
    val notes: List<String>,
    val measuredCount: Int
)

object EfeMotoDiagnosticEngine {
    private data class PhysicalLimit(val min: Double, val max: Double, val unit: String)

    // Bunlar arıza eşikleri değil, anlamsız PID çözümlemelerini yakalayan geniş sınırlardır.
    private val physicalLimits = mapOf(
        "rpm" to PhysicalLimit(0.0, 20000.0, "RPM"),
        "tps" to PhysicalLimit(0.0, 100.0, "%"),
        "coolant" to PhysicalLimit(-40.0, 215.0, "°C"),
        "iat" to PhysicalLimit(-40.0, 215.0, "°C"),
        "oilTemp" to PhysicalLimit(-40.0, 250.0, "°C"),
        "map" to PhysicalLimit(0.0, 255.0, "kPa"),
        "voltage" to PhysicalLimit(0.0, 25.0, "V"),
        "stft" to PhysicalLimit(-100.0, 100.0, "%"),
        "ltft" to PhysicalLimit(-100.0, 100.0, "%")
    )

    private val requiredCoreSensors = listOf("rpm", "tps", "coolant", "map", "voltage")

    fun analyze(
        snapshot: EfeMotoSnapshot,
        profile: EfeMotoBikeProfile? = null,
        engineRunning: Boolean? = null,
        vehicleSpeedKph: Double? = null
    ): EfeMotoAnalysis {
        if (!snapshot.ecuConnected) {
            return EfeMotoAnalysis(emptyList(), listOf("ECU bağlantısı yok; canlı teşhis üretilmedi."), 0)
        }

        val values = snapshot.values.filterValues { it.isFinite() }
        val findings = mutableListOf<EfeMotoFinding>()
        val notes = mutableListOf<String>()

        snapshot.values.filterValues { !it.isFinite() }.keys.forEach { key ->
            findings += EfeMotoFinding(
                key, "Geçersiz sensör örneği",
                "$key için sayısal olmayan veya sonsuz bir değer alındı.",
                "PID yanıtını ve ham ECU baytlarını yeniden doğrulayın.",
                EfeMotoFindingLevel.DATA_QUALITY
            )
        }

        physicalLimits.forEach { (key, limit) ->
            val value = values[key] ?: return@forEach
            if (value !in limit.min..limit.max) {
                findings += EfeMotoFinding(
                    key, "Ölçüm aralık dışı",
                    "$key = $value ${limit.unit}; PID çözümlemesinin geniş geçerlilik aralığı dışında.",
                    "Sensör parçasını değiştirmeden önce PID numarasını, ham yanıtı ve ölçeklemeyi kontrol edin.",
                    EfeMotoFindingLevel.DATA_QUALITY
                )
            }
        }

        if (profile == null) {
            notes += "Motosikletin marka/model/yıl profili seçilmedi. Sıcaklık, MAP, şarj ve rölanti için modele özgü eşiklerle kesin yorum yapılmadı."
        } else {
            profile.referenceRanges.forEach { (key, range) ->
                val value = values[key] ?: return@forEach
                if (value < range.minimum || value > range.maximum) {
                    findings += EfeMotoFinding(
                        key, "Model referans aralığı dışında",
                        "$key = $value ${range.unit}; ${profile.profileId} profili için tanımlı ${range.minimum}–${range.maximum} ${range.unit} aralığının dışında.",
                        "Ölçüm koşullarını ve ilgili sensör devresini üreticinin servis prosedürüyle doğrulayın.",
                        EfeMotoFindingLevel.WARNING
                    )
                }
            }

            val idle = profile.idleRpmRange
            val rpm = values["rpm"]
            if (engineRunning == true && vehicleSpeedKph == 0.0 && idle != null && rpm != null && rpm !in idle) {
                findings += EfeMotoFinding(
                    "rpm", "Rölanti devri profil dışında",
                    "Durağan ve çalışan motorda ölçülen $rpm RPM, ${profile.profileId} rölanti referansı ${idle.start}–${idle.endInclusive} RPM dışında.",
                    "Motor sıcaklığı, rölanti koşulu, gaz kelebeği ve üretici rölanti prosedürünü doğrulayın.",
                    EfeMotoFindingLevel.WARNING
                )
            }
        }

        val missing = requiredCoreSensors.filter { it !in values }
        if (missing.isNotEmpty()) {
            notes += "Bu örnekte ölçülmeyen sensörler: ${missing.joinToString()}. Eksik değerler normal kabul edilmedi ve varsayılan değer atanmadı."
        }
        if (snapshot.dtcs.isNotEmpty()) {
            notes += "ECU arıza kayıtları mevcut: ${snapshot.dtcs.joinToString()}. DTC tek başına arızalı parçayı kanıtlamaz; sensör ve devre kontrolleriyle eşleştirilmelidir."
        }
        if (values.isEmpty()) notes += "ECU bağlı olsa da geçerli sayısal canlı ölçüm bulunamadı."
        return EfeMotoAnalysis(findings, notes, values.size)
    }
}
