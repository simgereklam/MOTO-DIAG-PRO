package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * Seyfi Usta'nın konuşarak verilen DTC kodlarını tek biçime dönüştürür.
 * Bu sınıf yalnızca kodu ayrıştırır; kodun anlamını veri tabanı doğrular.
 */
object SeyfiDtcCodeParser {
    private val spokenDigits = mapOf(
        "sifir" to "0", "bir" to "1", "iki" to "2", "uc" to "3",
        "dort" to "4", "bes" to "5", "alti" to "6", "yedi" to "7",
        "sekiz" to "8", "dokuz" to "9"
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("tr", "TR")), Normalizer.Form.NFD)
            .replace(Regex("\\\\p{Mn}+"), "")
            .replace("ı", "i")
            .replace("ş", "s").replace("ğ", "g")
            .replace("ü", "u").replace("ö", "o").replace("ç", "c")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\\\s+"), " ")
            .trim()

    fun isDtcPrompt(value: String): Boolean {
        val text = normalize(value)
        return text.contains("ariza kodu") &&
            Regex("\\\\b(sor|ver|soyle|oku)[a-z]*\\\\b").containsMatchIn(text)
    }

    fun extract(value: String): String? {
        var text = normalize(value)
            .replace(Regex("\\\\bpe\\\\b"), "p")
            .replace(Regex("\\\\bbe\\\\b"), "b")
            .replace(Regex("\\\\bce\\\\b"), "c")
            .replace(Regex("\\\\byu\\\\b"), "u")

        spokenDigits.forEach { (word, digit) ->
            text = text.replace(Regex("\\\\b$word\\\\b"), digit)
        }

        text = text.replace(
            Regex("\\\\b([pbcu])\\\\s+([0-9a-f](?:\\\\s+[0-9a-f]){3})\\\\b")
        ) { match ->
            match.groupValues[1] + match.groupValues[2].replace(Regex("\\\\s+"), "")
        }
        text = text.replace(Regex("\\\\b([pbcu])\\\\s+([0-9a-f]{4})\\\\b"), "$1$2")

        return Regex("\\\\b(?:[pbcu][0-9a-f]{4}|[0-9]{2}(?:_abs)?)\\\\b")
            .find(text)?.value?.uppercase(Locale.US)
    }
}
