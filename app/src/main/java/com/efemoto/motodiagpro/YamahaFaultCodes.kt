
package com.efemoto.motodiagpro

data class YamahaFaultCode(
    val code: String,
    val title: String,
    val description: String,
    val possibleCauses: List<String>,
    val checks: List<String>
)

object YamahaFaultCodes {

    private val codes = listOf(

        YamahaFaultCode(
            "P0030",
            "Oksijen sensörü ısıtıcı devresi",
            "Oksijen sensörünün ısıtıcı kontrol devresinde arıza algılandı.",
            listOf(
                "Oksijen sensörü arızası",
                "Kablo veya soket hasarı",
                "Isıtıcı devresi besleme problemi"
            ),
            listOf(
                "Sensör soketini kontrol edin.",
                "Besleme ve kablo tesisatını ölçün.",
                "Sensör ısıtıcı devresini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0107",
            "MAP sensörü düşük giriş",
            "Emme manifoldu basınç sensörü sinyali beklenen seviyenin altında.",
            listOf(
                "MAP sensörü arızası",
                "Kısa devre",
                "Kablo veya soket sorunu"
            ),
            listOf(
                "MAP sensörü bağlantısını kontrol edin.",
                "Sensör beslemesini ölçün.",
                "Sinyal kablosunu kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0108",
            "MAP sensörü yüksek giriş",
            "Emme manifoldu basınç sensörü sinyali beklenen seviyenin üzerinde.",
            listOf(
                "MAP sensörü arızası",
                "Sinyal kablosunda kısa devre",
                "Soket temassızlığı"
            ),
            listOf(
                "Sensör soketini kontrol edin.",
                "Sinyal voltajını ölçün.",
                "Kablo tesisatını inceleyin."
            )
        ),

        YamahaFaultCode(
            "P0112",
            "Emme hava sıcaklığı düşük giriş",
            "Emme havası sıcaklık sensörü devresinden düşük sinyal algılandı.",
            listOf(
                "Sıcaklık sensörü arızası",
                "Kablo kısa devresi",
                "Soket problemi"
            ),
            listOf(
                "Sensör bağlantısını kontrol edin.",
                "Sensör direncini ölçün.",
                "Kablo tesisatını inceleyin."
            )
        ),

        YamahaFaultCode(
            "P0113",
            "Emme hava sıcaklığı yüksek giriş",
            "Emme havası sıcaklık sensörü devresinden yüksek sinyal algılandı.",
            listOf(
                "Sensör bağlantısının kopması",
                "Sensör arızası",
                "Kablo tesisatı problemi"
            ),
            listOf(
                "Sensör soketini kontrol edin.",
                "Kablo sürekliliğini ölçün.",
                "Sensör direncini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0117",
            "Motor sıcaklık sensörü düşük giriş",
            "Motor sıcaklık sensörü devresinden düşük sinyal algılandı.",
            listOf(
                "Sıcaklık sensörü arızası",
                "Sinyal hattında kısa devre",
                "Kablo hasarı"
            ),
            listOf(
                "Sensör soketini kontrol edin.",
                "Sinyal hattını ölçün.",
                "Sensör direncini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0118",
            "Motor sıcaklık sensörü yüksek giriş",
            "Motor sıcaklık sensörü devresinden yüksek sinyal algılandı.",
            listOf(
                "Sensör bağlantısının kopması",
                "Sensör arızası",
                "Soket veya kablo problemi"
            ),
            listOf(
                "Sensör bağlantısını kontrol edin.",
                "Kablo sürekliliğini ölçün.",
                "Sensör direncini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0122",
            "Gaz kelebeği sensörü düşük giriş",
            "Gaz kelebeği konum sensörü sinyali düşük seviyede.",
            listOf(
                "TPS sensörü arızası",
                "Sensör besleme problemi",
                "Sinyal kablosunda kısa devre"
            ),
            listOf(
                "TPS soketini kontrol edin.",
                "Sensör beslemesini ölçün.",
                "Gaz açıldıkça sinyal değişimini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0123",
            "Gaz kelebeği sensörü yüksek giriş",
            "Gaz kelebeği konum sensörü sinyali yüksek seviyede.",
            listOf(
                "TPS sensörü arızası",
                "Sinyal kablosu kısa devresi",
                "Soket temassızlığı"
            ),
            listOf(
                "TPS bağlantısını kontrol edin.",
                "Sinyal voltajını ölçün.",
                "Kablo tesisatını inceleyin."
            )
        ),

        YamahaFaultCode(
            "P0335",
            "Krank mili konum sensörü",
            "ECU, krank mili konum sensörü devresinde beklenen sinyali algılayamıyor.",
            listOf(
                "Krank sensörü arızası",
                "Sensör kablosu hasarı",
                "Sensör soketi problemi",
                "Sensör ile tetik dişlisi arasında sorun"
            ),
            listOf(
                "Krank sensörü soketini kontrol edin.",
                "Sensör kablosunu inceleyin.",
                "Sensör direncini model servis değerine göre ölçün.",
                "Marş sırasında sensör sinyalini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0340",
            "Eksantrik mili konum sensörü",
            "Eksantrik mili konum sensörü devresinde sinyal problemi algılandı.",
            listOf(
                "Eksantrik sensörü arızası",
                "Kablo veya soket sorunu",
                "Sensör sinyal problemi"
            ),
            listOf(
                "Sensör soketini kontrol edin.",
                "Kablo tesisatını inceleyin.",
                "Sensör sinyalini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0351",
            "Ateşleme bobini A devresi",
            "Birinci ateşleme bobini kontrol devresinde arıza algılandı.",
            listOf(
                "Ateşleme bobini arızası",
                "Bobin soketi problemi",
                "ECU kontrol kablosu hasarı"
            ),
            listOf(
                "Bobin bağlantısını kontrol edin.",
                "Bobin direncini servis verisine göre ölçün.",
                "Kablo tesisatını kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0352",
            "Ateşleme bobini B devresi",
            "İkinci ateşleme bobini kontrol devresinde arıza algılandı.",
            listOf(
                "Ateşleme bobini arızası",
                "Bobin soketi problemi",
                "ECU kontrol kablosu hasarı"
            ),
            listOf(
                "Bobin bağlantısını kontrol edin.",
                "Bobin direncini servis verisine göre ölçün.",
                "Kablo tesisatını kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0500",
            "Araç hız sensörü",
            "ECU, araç hız sensöründen beklenen sinyali alamıyor.",
            listOf(
                "Hız sensörü arızası",
                "Sensör kablosu hasarı",
                "Soket temassızlığı"
            ),
            listOf(
                "Hız sensörünü kontrol edin.",
                "Soket ve kablo bağlantılarını inceleyin.",
                "Sensör sinyalini kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P0560",
            "Sistem voltajı",
            "ECU besleme voltajında beklenmeyen durum algılandı.",
            listOf(
                "Zayıf akü",
                "Şarj sistemi problemi",
                "Gevşek akü bağlantısı",
                "Şase bağlantısı sorunu"
            ),
            listOf(
                "Akü voltajını ölçün.",
                "Şarj voltajını kontrol edin.",
                "Akü kutup başlarını inceleyin.",
                "Şase bağlantılarını kontrol edin."
            )
        ),

        YamahaFaultCode(
            "P2135",
            "Gaz kelebeği sensör uyumsuzluğu",
            "Gaz kelebeği konum sensörlerinden gelen sinyaller arasında uyumsuzluk algılandı.",
            listOf(
                "TPS sensörlerinden birinin arızası",
                "Sensör kablo problemi",
                "Soket temassızlığı",
                "Gaz kelebeği mekanizması problemi"
            ),
            listOf(
                "TPS sensör bağlantılarını kontrol edin.",
                "Sensör sinyallerini karşılaştırın.",
                "Gaz kelebeği mekanizmasını inceleyin."
            )
        )
    )

    fun find(code: String): YamahaFaultCode? {
        return codes.firstOrNull {
            it.code.equals(code.trim(), ignoreCase = true)
        }
    }

    fun all(): List<YamahaFaultCode> {
        return codes
    }

    fun search(query: String): List<YamahaFaultCode> {
        val term = query.trim()

        if (term.isEmpty()) return codes

        return codes.filter {
            it.code.contains(term, ignoreCase = true) ||
            it.title.contains(term, ignoreCase = true) ||
            it.description.contains(term, ignoreCase = true)
        }
    }
}
