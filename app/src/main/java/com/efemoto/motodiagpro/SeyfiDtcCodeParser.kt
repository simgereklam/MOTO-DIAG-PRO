package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * Toleranslı Türkçe DTC konuşma ayrıştırıcısı.
 * Eksik kodu tamamlamaz veya tahmin etmez.
 */
object SeyfiDtcCodeParser {
    private val spokenDigits = mapOf(
        "sifir" to "0", "zero" to "0", "bir" to "1", "one" to "1",
        "iki" to "2", "two" to "2", "uc" to "3", "three" to "3",
        "dort" to "4", "four" to "4", "bes" to "5", "five" to "5",
        "alti" to "6", "six" to "6", "yedi" to "7", "seven" to "7",
        "sekiz" to "8", "eight" to "8", "dokuz" to "9", "nine" to "9"
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

    fun extract(value: String): String? {
        var text = normalize(value)
            .replace(Regex("\\b(pe|pi|pı)\\b"), "p")
            .replace(Regex("\\b(be|bi|bı)\\b"), "b")
            .replace(Regex("\\b(ce|ci)\\b"), "c")
            .replace(Regex("\\b(yu)\\b"), "u")

        tens.forEach { (word, digit) ->
            text = text.replace(Regex("\\b$word\\s+(bir|iki|uc|dort|bes|alti|yedi|sekiz|dokuz)\\b")) { m ->
                digit + spokenDigits.getValue(m.groupValues[1])
            }
        }
        spokenDigits.forEach { (word, digit) ->
            text = text.replace(Regex("\\b$word\\b"), digit)
        }

        Regex("(?<![a-z0-9])([pbcu][0-9a-f]{4})(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1].uppercase(Locale.US) }

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in tokens.indices) {
            val prefix = tokens[i]
            if (prefix !in setOf("p", "b", "c", "u")) continue
            val digits = StringBuilder()
            var j = i + 1
            while (j < tokens.size && tokens[j].matches(Regex("[0-9a-f]+")) && digits.length < 5) {
                digits.append(tokens[j])
                j++
            }
            if (digits.length == 4) return (prefix + digits).uppercase(Locale.US)
        }

        Regex("(?<![a-z0-9])([0-9]{2}_abs)(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1].uppercase(Locale.US) }
        Regex("(?<![a-z0-9])([0-9]{4})(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1] }
        Regex("(?<![a-z0-9])([0-9]{2})(?![a-z0-9])")
            .find(text)?.let { return it.groupValues[1] }
        return null
    }
}
