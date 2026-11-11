package com.efemoto.motodiagpro

import java.util.Locale

data class EcuPidReading(
    val pid: Int,
    val label: String,
    val unit: String,
    val rawHex: String,
    val value: Double?,
    val displayValue: String
)

/**
 * SAE J1979 Mode 01 PID catalog.
 * Unknown/vehicle-specific supported PIDs are never discarded: their raw bytes
 * are retained so a later model-specific decoder can be added safely.
 */
object EcuPidCatalog {
    private data class Spec(val label: String, val unit: String = "", val decode: (List<Int>) -> Double?)

    private fun u(b: List<Int>, i: Int = 0) = b.getOrNull(i)?.toDouble()
    private fun word(b: List<Int>, i: Int = 0): Double? =
        if (b.size > i + 1) (b[i] * 256 + b[i + 1]).toDouble() else null
    private fun pct(b: List<Int>, i: Int = 0) = u(b, i)?.times(100.0 / 255.0)
    private fun trim(b: List<Int>, i: Int = 0) = u(b, i)?.let { (it - 128.0) * 100.0 / 128.0 }
    private fun temp(b: List<Int>, i: Int = 0) = u(b, i)?.minus(40.0)

    private val specs: Map<Int, Spec> = buildMap {
        fun add(pid: Int, label: String, unit: String = "", decode: (List<Int>) -> Double?) {
            put(pid, Spec(label, unit, decode))
        }
        add(0x04, "Hesaplanan motor yükü", "%") { pct(it) }
        add(0x05, "Motor soğutma suyu sıcaklığı", "°C") { temp(it) }
        add(0x06, "Kısa dönem yakıt düzeltmesi B1", "%") { trim(it) }
        add(0x07, "Uzun dönem yakıt düzeltmesi B1", "%") { trim(it) }
        add(0x08, "Kısa dönem yakıt düzeltmesi B2", "%") { trim(it) }
        add(0x09, "Uzun dönem yakıt düzeltmesi B2", "%") { trim(it) }
        add(0x0A, "Yakıt basıncı", "kPa") { u(it)?.times(3.0) }
        add(0x0B, "Emme manifoldu mutlak basıncı", "kPa") { u(it) }
        add(0x0C, "Motor devri", "RPM") { word(it)?.div(4.0) }
        add(0x0D, "Araç hızı", "km/h") { u(it) }
        add(0x0E, "Ateşleme avansı", "°") { u(it)?.div(2.0)?.minus(64.0) }
        add(0x0F, "Emme havası sıcaklığı", "°C") { temp(it) }
        add(0x10, "Kütlesel hava akışı", "g/s") { word(it)?.div(100.0) }
        add(0x11, "Gaz kelebeği konumu", "%") { pct(it) }
        for (pid in 0x14..0x1B) {
            add(pid, "Oksijen sensörü ${pid - 0x13} voltajı", "V") { b ->
                if (b.size >= 2) b[0] * 0.005 else null
            }
        }
        add(0x1F, "Motor çalışma süresi", "s") { word(it) }
        add(0x21, "MIL yanarken katedilen mesafe", "km") { word(it) }
        add(0x22, "Yakıt rayı basıncı (vakum referanslı)", "kPa") { word(it)?.times(0.079) }
        add(0x23, "Yakıt rayı basıncı (dizel/doğrudan enjeksiyon)", "kPa") { word(it)?.times(10.0) }
        add(0x2C, "Komutlanan EGR", "%") { pct(it) }
        add(0x2D, "EGR hata değeri", "%") { trim(it) }
        add(0x2E, "Komutlanan buhar tahliyesi", "%") { pct(it) }
        add(0x2F, "Yakıt seviyesi", "%") { pct(it) }
        add(0x30, "Isınma döngüsü sayısı") { u(it) }
        add(0x31, "DTC silindikten sonraki mesafe", "km") { word(it) }
        add(0x32, "EVAP buhar basıncı", "Pa") { word(it)?.let { v -> if (v >= 32768) (v - 65536) * 0.25 else v * 0.25 } }
        add(0x33, "Barometrik basınç", "kPa") { u(it) }
        for (pid in 0x34..0x3B) {
            add(pid, "Geniş bant oksijen / lambda sensörü ${pid - 0x33}", "lambda") { b ->
                if (b.size >= 4) ((b[0] * 256 + b[1]) / 32768.0) else null
            }
        }
        for (pid in 0x3C..0x3F) {
            add(pid, "Katalizör sıcaklığı ${pid - 0x3B}", "°C") { b ->
                word(b)?.div(10.0)?.minus(40.0)
            }
        }
        add(0x42, "Kontrol modülü voltajı", "V") { word(it)?.div(1000.0) }
        add(0x43, "Mutlak hesaplanan yük", "%") { word(it)?.times(100.0 / 255.0) }
        add(0x44, "Komutlanan lambda / eşdeğer oran", "lambda") { word(it)?.div(32768.0) }
        add(0x45, "Göreceli gaz kelebeği", "%") { pct(it) }
        add(0x46, "Ortam hava sıcaklığı", "°C") { temp(it) }
        for (pid in 0x47..0x4B) add(pid, "Gaz kelebeği sensör kanalı ${pid - 0x46}", "%") { pct(it) }
        add(0x4C, "Komutlanan gaz kelebeği aktüatörü", "%") { pct(it) }
        add(0x4D, "MIL açıkken geçen süre", "min") { word(it) }
        add(0x4E, "DTC silindikten sonra geçen süre", "min") { word(it) }
        add(0x52, "Etanol yakıt oranı", "%") { u(it)?.times(100.0 / 255.0) }
        add(0x53, "EVAP mutlak basıncı", "kPa") { word(it)?.div(200.0) }
        add(0x54, "EVAP sistem buhar basıncı", "Pa") { word(it)?.let { v -> if (v >= 32768) (v - 65536) * 0.25 else v * 0.25 } }
        for (pid in 0x55..0x58) add(pid, "İkincil yakıt düzeltmesi PID ${pid.toString(16).uppercase(Locale.US)}", "%") { b ->
            if (b.size >= 2) (b[0] - 128.0) * 100.0 / 128.0 else null
        }
        add(0x59, "Mutlak yakıt rayı basıncı", "kPa") { word(it)?.times(10.0) }
        add(0x5A, "Göreceli gaz pedalı konumu", "%") { pct(it) }
        add(0x5B, "Hibrit batarya şarj seviyesi", "%") { pct(it) }
        add(0x5C, "Motor yağı sıcaklığı", "°C") { temp(it) }
        add(0x5D, "Yakıt enjeksiyon zamanlaması", "°") { word(it)?.div(128.0)?.minus(210.0) }
        add(0x5E, "Motor yakıt debisi", "L/h") { word(it)?.div(20.0) }
        add(0x61, "Sürücü talep edilen motor torku", "%") { u(it)?.minus(125.0) }
        add(0x62, "Gerçek motor torku", "%") { u(it)?.minus(125.0) }
        add(0x63, "Motor referans torku", "Nm") { word(it) }
        add(0x64, "Motor torku yüzdeleri") { u(it) }
        add(0xA6, "Odometre", "km") { if (it.size >= 4) (it[0] * 16777216.0 + it[1] * 65536.0 + it[2] * 256.0 + it[3]) / 10.0 else null }
    }

    fun decode(pid: Int, bytes: List<Int>): EcuPidReading {
        val spec = specs[pid]
        val raw = bytes.joinToString("") { "%02X".format(Locale.US, it) }
        val label = spec?.label ?: "Üreticiye özel / tanımı eklenmemiş PID"
        val unit = spec?.unit.orEmpty()
        val value = runCatching { spec?.decode?.invoke(bytes) }.getOrNull()
            ?.takeIf { it.isFinite() }
        val display = if (value != null) {
            val formatted = if (unit == "V" || unit == "lambda") "%.3f" else "%.2f"
            String.format(Locale.US, formatted, value) + if (unit.isBlank()) "" else " $unit"
        } else {
            "Ham veri: ${raw.ifBlank { "yanıt yok" }}"
        }
        return EcuPidReading(pid, label, unit, raw, value, display)
    }

    fun isSupportBitmap(pid: Int): Boolean = pid in setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
}
