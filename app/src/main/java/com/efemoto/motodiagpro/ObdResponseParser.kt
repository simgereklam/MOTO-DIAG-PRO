
package com.efemoto.motodiagpro

object ObdResponseParser {

    /**
     * ELM327 yanıtından belirtilen servis ve PID
     * sonrasındaki veri baytlarını çıkarır.
     *
     * Örnek:
     * 410C1AF8 -> [0x1A, 0xF8]
     */
    fun parsePidBytes(
        response: String,
        servicePid: String
    ): List<Int>? {

        val header = servicePid
            .uppercase()
            .replace(" ", "")

        if (header.isEmpty()) return null

        val cleaned = response
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")

        val index = cleaned.indexOf(header)

        if (index < 0) return null

        val start = index + header.length

        if (start >= cleaned.length) return null

        val data = cleaned.substring(start)

        if (data.length % 2 != 0) return null

        return data.chunked(2).mapNotNull {
            it.toIntOrNull(16)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Standart OBD-II Mode 03 yanıtını DTC kodlarına çevirir.
     *
     * Örnek:
     * 430133 -> P0133
     * 4300   -> Arıza kodu yok
     */
    fun parseDtcCodes(response: String): List<String> {

        val cleaned = response
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")

        val index = cleaned.indexOf("43")

        if (index < 0) return emptyList()

        val data = cleaned.substring(index + 2)

        if (data.length < 4 || data.length % 2 != 0) {
            return emptyList()
        }

        val codes = mutableListOf<String>()

        for (i in data.indices step 4) {

            val first = data.substring(i, i + 2).toIntOrNull(16)
                ?: continue

            val second = data.substring(i + 2, i + 4).toIntOrNull(16)
                ?: continue

            if (first == 0 && second == 0) continue

            val family = when ((first shr 6) and 0x03) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }

            val digit1 = (first shr 4) and 0x03
            val digit2 = first and 0x0F
            val digit3 = (second shr 4) and 0x0F
            val digit4 = second and 0x0F

            val code = family +
                    digit1.toString() +
                    digit2.toString(16).uppercase() +
                    digit3.toString(16).uppercase() +
                    digit4.toString(16).uppercase()

            codes.add(code)
        }

        return codes.distinct()
    }

    /**
     * ELM327 hata yanıtlarını kontrol eder.
     */
    fun isErrorResponse(response: String): Boolean {

        val value = response.uppercase()

        return value.contains("NO DATA") ||
                value.contains("ERROR") ||
                value.contains("UNABLE TO CONNECT") ||
                value.contains("STOPPED") ||
                value.contains("BUS INIT") ||
                value.contains("CAN ERROR")
    }

    /**
     * Adaptör yanıtındaki boşlukları ve satır
     * sonlarını temizler.
     */
    fun cleanResponse(response: String): String {
        return response
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
