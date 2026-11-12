package com.efemoto.motodiagpro

/**
 * Yamaha YZF-R9 (2026) passive CAN broadcast decoder.
 *
 * Source: DeziderMesko/motocan, analysis/r9-signals.md and analysis/r9.dbc.
 * The upstream project describes measured signals, not Yamaha factory diagnostics.
 *
 * IMPORTANT:
 * - This decoder only interprets frames already received from a compatible CAN adapter.
 * - It does not open sockets, transmit CAN frames, clear DTCs, or control the motorcycle.
 * - It is NOT compatible with the current Viecar ELM/Wi-Fi transport by itself.
 * - Signal meanings and scales must be validated on the exact model/year before service use.
 *
 * Upstream decoder source is MIT licensed; retain attribution when redistributing
 * adapted code. The upstream DBC/documentation is CC BY 4.0.
 */
object YamahaR9CanProfile {

    const val MODEL = "Yamaha YZF-R9"
    const val MODEL_YEAR = 2026
    const val BUS_SPEED_KBPS = 500

    data class Signal(
        val name: String,
        val value: Double,
        val unit: String = "",
        val confidence: Confidence = Confidence.MEASURED,
        val note: String = ""
    )

    enum class Confidence {
        MEASURED,
        CANDIDATE,
        PROVISIONAL
    }

    val supportedCanIds: Set<Int> = setOf(
        0x27C, 0x383, 0x512, 0x751, 0x756,
        0x758, 0x759, 0x765, 0x76D, 0x76E
    )

    /**
     * Decode one standard CAN frame payload.
     * Returns an empty list for unknown IDs or payloads that are too short.
     */
    fun decodeFrame(canId: Int, data: ByteArray): List<Signal> {
        fun b(index: Int): Int = data[index].toInt() and 0xFF
        fun has(size: Int): Boolean = data.size >= size

        return when (canId) {
            // 0x27C: 100 Hz engine/gear/rider setting broadcast (7 bytes).
            0x27C -> {
                if (!has(7)) return emptyList()
                val rpmRaw = (b(0) shl 8) or b(1)
                val gear = b(4) and 0x07
                val ecuGear = (b(3) ushr 5) and 0x07
                val tcs = (b(3) ushr 1) and 0x0F
                val pwr = ((b(4) ushr 6) and 0x03) + 1
                buildList {
                    add(Signal("Motor devri", rpmRaw * 100.0 / 256.0, "rpm"))
                    add(Signal("Vites", gear.toDouble(), displayGear(gear)))
                    add(Signal(
                        "ECU vitesi", ecuGear.toDouble(), displayGear(ecuGear),
                        Confidence.MEASURED,
                        "Vites geçişleri arasında son vitesi tutabilir; motor kapalıyken 0 görülebilir."
                    ))
                    add(Signal("TCS seviyesi", tcs.toDouble()))
                    add(Signal("PWR ayarı", pwr.toDouble()))
                    add(Signal("Arka teker darbe sayısı", b(2).toDouble(), "darbe/10 ms"))
                    add(Signal("Marş aktif", (b(3) and 0x01).toDouble(), "0=hayır, 1=evet"))
                    add(Signal(
                        "İlk çalıştırma sonrası bayrak", (b(6) ushr 7).toDouble(),
                        "0/1", Confidence.CANDIDATE,
                        "Anlamı tam açıklanmamış; arıza olarak yorumlanmamalı."
                    ))
                }
            }

            // 0x383: brake switch broadcast (8 bytes).
            0x383 -> {
                if (!has(5)) return emptyList()
                listOf(
                    Signal(
                        "Fren anahtarı", ((b(4) ushr 3) and 0x01).toDouble(),
                        "0=kapalı, 1=ön veya arka fren aktif"
                    )
                )
            }

            // 0x512: coolant and intake-air temperatures (4 bytes in DBC).
            0x512 -> {
                if (!has(2)) return emptyList()
                listOf(
                    Signal("Soğutma suyu sıcaklığı", b(0) * 0.625 - 30.0, "°C"),
                    Signal("Emme havası sıcaklığı", b(1) * 0.625 - 30.0, "°C")
                )
            }

            // 0x751: neutral-related byte; only useful with engine stopped.
            0x751 -> {
                if (!has(7)) return emptyList()
                val neutralByte = b(6)
                val neutral = neutralByte == 0x60 || neutralByte == 0x70
                listOf(
                    Signal(
                        "Boş vites adayı", if (neutral) 1.0 else 0.0,
                        "0/1", Confidence.CANDIDATE,
                        "Kaynakta yalnızca motor kapalıyken güvenilir olduğu belirtiliyor. Motor çalışırken vites için 0x27C kullanılmalı."
                    )
                )
            }

            // 0x756: rear wheel speed, raw / 100 km/h.
            0x756 -> {
                if (!has(4)) return emptyList()
                val raw = ((b(3) ushr 4) shl 12) or
                    ((b(1) and 0x0F) shl 8) or
                    (b(0) and 0xF0) or
                    (b(2) ushr 4)
                listOf(
                    Signal(
                        "Arka tekerlek hızı", raw / 100.0, "km/h",
                        Confidence.MEASURED,
                        "Kaynakta 59 km/h değerine kadar ölçümle karşılaştırılmıştır."
                    )
                )
            }

            // 0x758: grip (APS) and measured throttle valve (TPS).
            0x758 -> {
                if (!has(8)) return emptyList()
                val gripRaw = ((b(7) and 0x0F) shl 12) or
                    ((b(6) and 0x0F) shl 8) or b(5)
                val valveRaw = ((b(0) ushr 4) shl 8) or b(2)
                listOf(
                    Signal("Gaz kolu konumu (APS)", gripRaw * 100.0 / 31672.0, "%"),
                    Signal(
                        "Gerçek gaz kelebeği (TPS)", valveRaw * 100.0 / 2310.4, "%",
                        Confidence.PROVISIONAL,
                        "Sıfır kalibrasyonu kaynakta geçici olarak belirtilmiş; sıcak rölantide yaklaşık %8 görülmüş."
                    )
                )
            }

            // 0x759: commanded throttle valve position.
            0x759 -> {
                if (!has(3)) return emptyList()
                val commandedRaw = ((b(0) ushr 4) shl 4) or (b(2) ushr 4)
                listOf(
                    Signal(
                        "Komutlanan gaz kelebeği", commandedRaw * 100.0 / 144.4, "%",
                        Confidence.MEASURED,
                        "Komutlanan değer gerçek TPS değerinden yaklaşık bir örnek önde olabilir."
                    )
                )
            }

            // 0x765: front wheel speed and ECU gear.
            0x765 -> {
                if (!has(7)) return emptyList()
                val raw = ((b(0) ushr 4) shl 12) or
                    ((b(2) ushr 4) shl 8) or
                    ((b(6) ushr 4) shl 4) or
                    (b(3) and 0x0F)
                val gear = b(1) and 0x07
                listOf(
                    Signal(
                        "Ön tekerlek hızı", raw / 100.0, "km/h",
                        Confidence.CANDIDATE,
                        "Kaynakta bazı koşullarda arka teker hızını kopyaladığı belirtilmiş; ABS teşhisi için tek başına kullanılmamalı."
                    ),
                    Signal("Ön CAN mesajındaki ECU vitesi", gear.toDouble(), displayGear(gear))
                )
            }

            // 0x76D: cruise-control buttons.
            0x76D -> {
                if (!has(6)) return emptyList()
                listOf(
                    Signal("Cruise RES+", (b(3) and 0x01).toDouble(), "0/1"),
                    Signal("Cruise SET-", ((b(5) ushr 4) and 0x01).toDouble(), "0/1")
                )
            }

            // 0x76E: PWR and dash display mode.
            0x76E -> {
                if (!has(3)) return emptyList()
                val display = b(2)
                buildList {
                    add(Signal("PWR ayarı", (b(1) + 1).toDouble()))
                    if (display == 0x10 || display == 0x20) {
                        add(Signal("Gösterge modu", if (display == 0x10) 1.0 else 0.0,
                            if (display == 0x10) "Track" else "Normal"))
                    }
                }
            }

            else -> emptyList()
        }
    }

    private fun displayGear(gear: Int): String = when (gear) {
        0 -> "N / boş veya motor kapalı"
        7 -> "Vites geçişi"
        else -> "$gear. vites"
    }
}
