package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * Kısa günlük sohbet yanıtları. Bilinmeyen cümlelerde uzun, tekrarlayan
 * hazır metin üretmez; araç/arıza kararları SeyfiBrain içinde kalır.
 */
object SeyfiConversationRules {
    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("tr", "TR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ı", "i")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun reply(message: String): String? {
        val q = normalize(message)
        if (q.isBlank()) return null

        if (q in setOf("usta", "seyfi", "seyfi usta")) return "Dinliyorum."
        if (q.contains("nasilsin") || q.contains("ne haber") ||
            q.contains("keyfin nasil") || q.contains("iyi misin") ||
            q.contains("nasil gidiyor")
        ) return "İyiyim usta, teşekkür ederim."

        if (q == "iyi" || q == "iyiyim" || q.contains("iyiyim") ||
            q.contains("sukur") || q.contains("keyfim yerinde") ||
            q.contains("fena degilim") || q.contains("iyilik")
        ) return "Sevindim usta."

        if (q.contains("motosiklet tamiri") || q.contains("motor tamiri") ||
            q.contains("motosiklet tamir") || q.contains("motor tamir")
        ) return "Kolay gelsin usta. Hangi motosiklet?"

        if (q.contains("kotu") && (q.contains("hissediyorum") ||
                q.contains("gunum") || q.contains("keyfim"))
        ) return "Üzüldüm usta. İstersen anlat."

        if (q.contains("merhaba") || q == "selam" ||
            q.contains("gunaydin") || q.contains("iyi aksamlar") ||
            q.contains("iyi geceler")
        ) return "Merhaba usta."

        if (q.contains("tesekkur") || q == "sag ol" || q == "eyvallah")
            return "Rica ederim usta."

        if (q.contains("sen kimsin") || q == "kimsin" || q.contains("kendini tanit"))
            return "Ben Seyfi Usta. Motosiklet teşhisinde ve günlük sohbette yardımcı olurum."

        if (q.contains("ne yapiyorsun") || q.contains("napıyorsun"))
            return "Seninle konuşuyorum usta."

        if (q.contains("sohbet edelim") || q.contains("sohbet edebilir misin"))
            return "Tabii usta, dinliyorum."

        return null
    }
}
