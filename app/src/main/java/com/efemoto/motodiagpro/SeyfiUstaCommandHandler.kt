
package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * SEYFİ USTA
 * Türkçe sesli komut çözümleyicisi.
 *
 * İnternet bağlantısı kullanmaz.
 * Ses tanıma işlemini OfflineVoiceAssistant sağlar.
 * Bu sınıf tanınan cümleyi uygulama komutuna dönüştürür.
 */
enum class SeyfiAction {
    WAKE_WORD,
    START_SCAN,
    READ_FAULTS,
    CLEAR_FAULTS,
    SHOW_LIVE_DATA,
    SHOW_RPM,
    SHOW_TEMPERATURE,
    SHOW_VOLTAGE,
    SHOW_TPS,
    SHOW_HELP,
    STOP,
    UNKNOWN
}

data class SeyfiCommand(
    val action: SeyfiAction,
    val originalText: String,
    val response: String
)

class SeyfiUstaCommandHandler {

    private fun normalize(text: String): String {
        val lower = text.lowercase(Locale("tr", "TR"))

        val normalized = Normalizer.normalize(
            lower,
            Normalizer.Form.NFD
        )

        return normalized
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("ı", "i")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsAny(
        text: String,
        vararg phrases: String
    ): Boolean {
        return phrases.any { text.contains(it) }
    }

    fun handle(recognizedText: String): SeyfiCommand {
        val original = recognizedText.trim()
        val text = normalize(original)

        if (text.isBlank()) {
            return command(
                SeyfiAction.UNKNOWN,
                original,
                "Sizi anlayamadım. Tekrar söyler misiniz?"
            )
        }

        // Uyandırma kelimesi
        val wakeWords = listOf(
            "seyfi usta",
            "seyfi",
            "usta"
        )

        val wakeDetected = wakeWords.any {
            text.contains(normalize(it))
        }

        val commandText = text
            .replace("seyfi usta", "")
            .replace("seyfi", "")
            .replace("usta", "")
            .trim()

        // Sadece uyandırma kelimesi söylendiyse
        if (commandText.isBlank() && wakeDetected) {
            return command(
                SeyfiAction.WAKE_WORD,
                original,
                "Dinliyorum."
            )
        }

        // Uyandırma kelimesi olmadan da komutları tanıyabilir.
        if (containsAny(
                commandText,
                "taramaya basla",
                "ariza tara",
                "ariza taramasi yap",
                "motoru tara",
                "ecu tara",
                "diagnostik baslat",
                "diagnostik tara"
            )
        ) {
            return command(
                SeyfiAction.START_SCAN,
                original,
                "Arıza taraması başlatılıyor."
            )
        }

        if (containsAny(
                commandText,
                "arizalari sil",
                "ariza sil",
                "hatalari sil",
                "hata kodlarini sil",
                "dtc sil",
                "kodlari temizle"
            )
        ) {
            return command(
                SeyfiAction.CLEAR_FAULTS,
                original,
                "Arıza silme işlemi başlatılıyor. Sonucu ECU üzerinden kontrol edeceğim."
            )
        }

        if (containsAny(
                commandText,
                "arizalari oku",
                "ariza kodlarini oku",
                "hata kodlarini oku",
                "arizalar neler",
                "hangi arizalar var",
                "ariza listesi"
            )
        ) {
            return command(
                SeyfiAction.READ_FAULTS,
                original,
                "Kayıtlı arıza kodlarını okuyorum."
            )
        }

        if (containsAny(
                commandText,
                "canli verileri goster",
                "canli verileri oku",
                "sensorleri goster",
                "motor verileri",
                "canli veri"
            )
        ) {
            return command(
                SeyfiAction.SHOW_LIVE_DATA,
                original,
                "Canlı motor verilerini kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "devir kac",
                "motor devri",
                "rpm kac",
                "motor kac devir"
            )
        ) {
            return command(
                SeyfiAction.SHOW_RPM,
                original,
                "Motor devrini kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "motor sicakligi",
                "hararet kac",
                "su sicakligi",
                "sicaklik kac"
            )
        ) {
            return command(
                SeyfiAction.SHOW_TEMPERATURE,
                original,
                "Motor sıcaklığını kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "aku voltaji",
                "sarj voltaji",
                "voltaj kac",
                "akunun durumu"
            )
        ) {
            return command(
                SeyfiAction.SHOW_VOLTAGE,
                original,
                "Akü voltajını kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "gaz konumu",
                "gaz kelebeginin durumu",
                "tps degeri",
                "gaz yuzdesi"
            )
        ) {
            return command(
                SeyfiAction.SHOW_TPS,
                original,
                "Gaz kelebeği konumunu kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "yardim",
                "neler yapabilirsin",
                "komutlar neler",
                "ne yapabiliyorsun"
            )
        ) {
            return command(
                SeyfiAction.SHOW_HELP,
                original,
                "Arıza taraması, arıza okuma, arıza silme ve canlı veri kontrolü yapabilirim."
            )
        }

        if (containsAny(
                commandText,
                "dinlemeyi durdur",
                "sus",
                "bekle",
                "dinlemeyi kapat"
            )) {
            return command(
                SeyfiAction.STOP,
                original,
                "Tamam, bekliyorum."
            )
        }

        return command(
            SeyfiAction.UNKNOWN,
            original,
            "Bu komutu henüz tanımıyorum. Yardım diyerek komutları öğrenebilirsiniz."
        )
    }

    private fun command(
        action: SeyfiAction,
        original: String,
        response: String
    ) = SeyfiCommand(
        action = action,
        originalText = original,
        response = response
    )
}
