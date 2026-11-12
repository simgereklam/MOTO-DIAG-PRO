package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/** Yamaha öncelikli Türkçe model/yıl sözlüğü. Bilgi tahmin etmez. */
object YamahaVehicleCatalog {
    data class Match(val model: String, val year: String?)

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("tr", "TR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace("ı", "i")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val models = listOf(
        "MT-125" to listOf("mt 125", "mt125", "em ti 125"),
        "MT-15" to listOf("mt 15", "mt15", "em ti 15", "em ti on bes"),
        "MT-25" to listOf("mt 25", "mt25", "mt yirmi bes", "em ti 25", "em ti yirmi bes"),
        "MT-03" to listOf("mt 03", "mt03", "mt uc", "em ti 03", "em ti uc"),
        "MT-07" to listOf("mt 07", "mt07", "mt yedi", "em ti 07", "em ti yedi"),
        "MT-09" to listOf("mt 09", "mt09", "mt dokuz", "em ti 09", "em ti dokuz"),
        "MT-10" to listOf("mt 10", "mt10", "em ti 10", "em ti on"),
        "R1" to listOf("yzf r1", "yzfr1", "r1", "r bir"),
        "R6" to listOf("yzf r6", "yzfr6", "r6", "r alti"),
        "R7" to listOf("yzf r7", "yzfr7", "r7", "r yedi"),
        "R3" to listOf("yzf r3", "yzfr3", "r3", "r uc"),
        "R25" to listOf("yzf r25", "yzfr25", "r 25", "r25", "r yirmi bes"),
        "R15" to listOf("yzf r15", "yzfr15", "r 15", "r15"),
        "R125" to listOf("yzf r125", "yzfr125", "r 125", "r125"),
        "NMAX 155" to listOf("nmax 155", "nmax yuz elli bes", "en max 155"),
        "NMAX 125" to listOf("nmax 125", "nmax yuz yirmi bes", "en max 125"),
        "NMAX" to listOf("nmax", "n max", "en max"),
        "XMAX 250" to listOf("xmax 250", "eks max 250"),
        "XMAX 300" to listOf("xmax 300", "eks max 300"),
        "XMAX 400" to listOf("xmax 400", "eks max 400"),
        "XMAX" to listOf("xmax", "x max", "eks max"),
        "TMAX 530" to listOf("tmax 530", "ti max 530"),
        "TMAX 560" to listOf("tmax 560", "ti max 560"),
        "TMAX" to listOf("tmax", "t max", "ti max"),
        "TRACER 7" to listOf("tracer 7", "tracer 700", "tracer yedi", "treyser 7"),
        "TRACER 9" to listOf("tracer 9", "tracer 900", "tracer dokuz", "treyser 9"),
        "TENERE 700" to listOf("tenere 700", "tenere yedi yuz"),
        "XSR 700" to listOf("xsr 700", "xsr yedi yuz"),
        "XSR 900" to listOf("xsr 900", "xsr dokuz yuz"),
        "FZ25" to listOf("fz25", "fz 25", "fz yirmi bes"),
        "FZ6" to listOf("fz6", "fz 6", "fazer 6"),
        "FZ8" to listOf("fz8", "fz 8", "fazer 8"),
        "XJ6" to listOf("xj6", "xj 6", "xj six"),
        "FAZER 8" to listOf("fazer 8", "fazer sekiz"),
        "YBR 125" to listOf("ybr 125", "ybr125"),
        "YS 125" to listOf("ys 125", "ys125"),
        "CRYPTON" to listOf("crypton", "kripton"),
        "AEROX" to listOf("aerox"),
        "DELIGHT" to listOf("delight", "d light"),
        "MAJESTY" to listOf("majesty"),
        "WR250" to listOf("wr 250", "wr250"),
        "XT660" to listOf("xt 660", "xt660"),
        "XT1200" to listOf("xt 1200", "xt1200", "super tenere"),
        "FJR1300" to listOf("fjr 1300", "fjr1300"),
        "BOLT" to listOf("bolt"),
        "DRAG STAR" to listOf("drag star", "dragstar")
    )

    private val words = mapOf(
        1 to "bir", 2 to "iki", 3 to "uc", 4 to "dort", 5 to "bes",
        6 to "alti", 7 to "yedi", 8 to "sekiz", 9 to "dokuz",
        10 to "on", 20 to "yirmi", 30 to "otuz"
    )

    fun detectBrand(text: String): Boolean =
        Regex("(^| )yamaha($| )").containsMatchIn(normalize(text))

    fun extractYear(text: String): String? {
        val value = normalize(text)
        Regex("\\b(19[89][0-9]|20[0-3][0-9])\\b").find(value)?.let { return it.value }
        for (year in 2039 downTo 2000) {
            val n = year - 2000
            val phrase = when {
                n == 0 -> "iki bin"
                n < 10 -> "iki bin " + words[n]
                n % 10 == 0 -> "iki bin " + words[n]
                else -> "iki bin " + words[(n / 10) * 10] + " " + words[n % 10]
            }
            if (Regex("(^| )" + Regex.escape(phrase) + "($| )").containsMatchIn(value))
                return year.toString()
        }
        return null
    }

    fun detectModel(text: String): String? {
        val value = normalize(text)
        return models.sortedByDescending { it.second.maxOf { alias -> alias.length } }
            .firstOrNull { (_, aliases) ->
                aliases.any { alias ->
                    Regex("(^| )" + Regex.escape(normalize(alias)) + "($| )").containsMatchIn(value)
                }
            }?.first
    }

    fun parse(text: String): Match? {
        val model = detectModel(text) ?: return null
        return Match(model, extractYear(text))
    }

    fun selfTest(): List<String> {
        val cases = listOf(
            "Yamaha MT-25 2022 model" to Match("MT-25", "2022"),
            "Yamaha em ti yirmi beş iki bin yirmi iki" to Match("MT-25", "2022"),
            "2021 model Yamaha NMAX" to Match("NMAX", "2021"),
            "Yamaha YZF R25 2020" to Match("R25", "2020"),
            "MT07" to Match("MT-07", null),
            "Yamaha Tenere 700 2023" to Match("TENERE 700", "2023")
        )
        return cases.mapNotNull { (input, expected) ->
            val actual = parse(input)
            if (actual == expected) null else "$input -> beklenen $expected, bulunan $actual"
        }
    }
}
