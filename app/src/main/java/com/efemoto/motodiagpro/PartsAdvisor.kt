package com.efemoto.motodiagpro

import android.content.Context
import org.json.JSONObject

data class VerifiedPart(
    val dtc: String,
    val model: String,
    val modelYearFrom: Int,
    val modelYearTo: Int,
    val partName: String,
    val partType: String,
    val manufacturer: String,
    val partNumber: String,
    val source: String
)

/**
 * OEM/muadil önerisi. Tam model ve yıl eşleşmesi olmadan parça önermez.
 * DTC tek başına parça değişim kararı değildir.
 */
object PartsAdvisor {
    fun find(context: Context, dtc: String, model: String, year: Int): List<VerifiedPart> {
        if (model.isBlank() || year <= 0) return emptyList()
        return try {
            val root = JSONObject(
                context.assets.open("database/yamaha_oem_parts.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            val records = root.optJSONArray("records") ?: return emptyList()
            buildList {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val itemModel = item.optString("model")
                    val from = item.optInt("modelYearFrom", -1)
                    val to = item.optInt("modelYearTo", -1)
                    if (!item.optString("dtc").equals(dtc, true)) continue
                    if (!itemModel.equals(model, true)) continue
                    if (from < 0 || to < from || year !in from..to) continue
                    val number = item.optString("partNumber").trim()
                    val source = item.optString("source").trim()
                    if (number.isBlank() || source.isBlank()) continue
                    add(
                        VerifiedPart(
                            dtc = dtc.uppercase(),
                            model = itemModel,
                            modelYearFrom = from,
                            modelYearTo = to,
                            partName = item.optString("partName"),
                            partType = item.optString("partType"),
                            manufacturer = item.optString("manufacturer"),
                            partNumber = number,
                            source = source
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun statusMessage(): String =
        "OEM ve muadil parça önerisi için tam model, üretim yılı ve doğrulanmış parça kataloğu kaydı gerekir. Arıza kodu tek başına parça değişimini doğrulamaz."
}
