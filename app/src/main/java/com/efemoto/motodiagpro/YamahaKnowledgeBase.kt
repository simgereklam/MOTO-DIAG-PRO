package com.efemoto.motodiagpro

import android.content.Context
import org.json.JSONObject

data class YamahaKnowledgeRecord(
    val code: String,
    val title: String,
    val description: String,
    val system: String,
    val possibleCauses: List<String>,
    val checks: List<String>,
    val codeStandard: String,
    val source: String,
    val verification: String
)

/**
 * Yamaha bilgi bankası okuyucusu.
 * Kayıtların doğrulama seviyesi korunur; genel SAE kodları Yamaha'ya özel kod gibi sunulmaz.
 */
object YamahaKnowledgeBase {
    private const val FAULT_FILE = "database/yamaha_fault_database.json"

    fun find(context: Context, code: String): YamahaKnowledgeRecord? {
        val normalized = code.trim().uppercase()
        if (normalized.isBlank()) return null

        return try {
            val root = JSONObject(context.assets.open(FAULT_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() })
            val records = root.optJSONArray("records") ?: return null
            for (index in 0 until records.length()) {
                val item = records.optJSONObject(index) ?: continue
                if (!item.optString("code").equals(normalized, ignoreCase = true)) continue
                return YamahaKnowledgeRecord(
                    code = item.optString("code"),
                    title = item.optString("title"),
                    description = item.optString("description"),
                    system = item.optString("system"),
                    possibleCauses = item.stringList("possibleCauses"),
                    checks = item.stringList("checks"),
                    codeStandard = item.optString("codeStandard"),
                    source = item.optString("source"),
                    verification = item.optString("verification")
                )
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
