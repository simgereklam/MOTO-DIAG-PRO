package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * Toleranslı Türkçe DTC konuşma ayrıştırıcısı.
 * Eksik kodu tamamlamaz veya tahmin etmez.
 */
object SeyfiDtcCodeParser {
    // Vosk'un Türkçe konuşmada üretebildiği yaygın yazım/söyleyiş biçimleri.
    private val spokenDigits = mapOf(
        "sifir" to "0", "sıfır" to "0", "sifiri" to "0", "zero" to "0", "zira" to "0",
        "bir" to "1", "biri" to "1", "birinci" to "1", "one" to "1",
        "iki" to "2", "ikiye" to "2", "ikiyi" to "2", "two" to "2",
        "uc" to "3", "üç" to "3", "uçu" to "3", "üçe" to "3", "üçü" to "3", "three" to "3",
        "dort" to "4", "dört" to "4", "dörde" to "4", "dördü" to "4", "four" to "4",
        "bes" to "5", "beş" to "5", "bese" to "5", "besi" to "5", "beşe" to "5", "beşi" to "5", "five" to "5",
        "alti" to "6", "altı" to "6", "altıya" to "6", "altıyı" to "6", "six" to "6",
        "yedi" to "7", "yediyi" to "7", "seven" to "7",
        "sekiz" to "8", "sekizi" to "8", "eight" to "8",
        "dokuz" to "9", "dokuzu" to "9", "nine" to "9"
    )
    private val tens = mapOf(
        "on" to "1", "yirmi" to "2", "otuz" to "3", "kirk" to "4",
        "elli" to "5", "altmis" to "6", "yetmis" to "7",
        "seksen" to "8", "doksan" to "9"
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("tr", "TR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
            .replace("ü", "u").replace("ö", "o").replace("ç", "c")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    fun isDtcPrompt(value: String): Boolean {
        val text = normalize(value)
        return text.contains("ariza kodu") &&
            Regex("\\b(sor|ver|soyle|oku)[a-z]*\\b").containsMatchIn(text)
    }

    fun extract(value: String, allowShortCode: Boolean = false): String? {
        var text = normalize(value)
            .replace(Regex("\\b(pe|pi|pı|p harfi|pe harfi|p harfı|pe harfı|p kodu|pe kodu)\\b"), "p")
            .replace(Regex("\\b(be|bi|bı|b harfi|be harfi|b kodu|be kodu)\\b"), "b")
            .replace(Regex("\\b(ce|ci|c harfi|ce harfi|c kodu|ce kodu)\\b"), "c")
            .replace(Regex("\\b(yu|u harfi|u harfı|u kodu)\\b"), "u")
            .replace(Regex("\\b(harfini|harfi|harf|kodunu|kodu)\\b"), " ")

        tens.forEach { (word, digit) ->
            text = text.replace(Regex("\\b$word\\s+(bir|iki|uc|dort|bes|alti|yedi|sekiz|dokuz)\\b")) { m ->
                digit + spokenDigits.getValue(m.groupValues[1])
            }
        }
        spokenDigits.forEach { (word, digit) ->
            text = text.replace(Regex("\\b$word\\b"), digit)
        }

        // Harf ve kod rakamları tek parça veya ayrı ayrı söylenebilir.
        // Harf karışıklığına (P/B gibi) otomatik tahmin uygulamayız.
        Regex("(?<![a-z0-9])([pbcu][0-9a-f]{4})(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1].uppercase(Locale.US) }

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in tokens.indices) {
            val prefix = tokens[i]
            if (prefix !in setOf("p", "b", "c", "u")) continue
            val digits = StringBuilder()
            var j = i + 1
            // "Pe harfi sıfır üç..." gibi konuşmalarda harf kelimesini atla.
            while (j < tokens.size && tokens[j] in setOf("harf", "harfi", "harfini", "kodu", "kodunu")) j++
            while (j < tokens.size && tokens[j].matches(Regex("[0-9a-f]+")) && digits.length < 5) {
                digits.append(tokens[j])
                j++
            }
            if (digits.length == 4) return (prefix + digits).uppercase(Locale.US)
        }

        // Normalizer alt çizgiyi boşluğa çevirir; ABS eki bu nedenle ayrı okunur.
        Regex("^([0-9]{2})\\s+abs$")
            .find(text)?.let { return it.groupValues[1] + "_ABS" }
        Regex("(?<![a-z0-9])([0-9]{4})(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1] }
        if (allowShortCode) {
            // İki haneli Yamaha kodunu yalnızca tüm ifade bu kodsa kabul et.
            // Serbest konuşmadaki yıl/sayı parçaları arıza koduna dönüşmesin.
            Regex("^(?:kod\\s+)?([0-9]{2})(?:\\s+kod(?:u)?)?$")
                .find(text)?.let { return it.groupValues[1] }
        }
        return null
    }
}
