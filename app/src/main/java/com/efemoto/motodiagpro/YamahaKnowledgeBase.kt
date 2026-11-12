package com.efemoto.motodiagpro

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class YamahaKnowledgeRecord(
    val code: String,
    val title: String,
    val description: String,
    val system: String,
    val possibleCauses: List<String>,
    val checks: List<String>,
    val codeStandard: String,
    val source: String,
    val verification: String,
    val models: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val namespace: String = "POWERTRAIN"
)

/**
 * Yerel Yamaha bilgi bankası okuyucusu.
 * DTC eşleşmesinin yanında belirti/şikâyet metni için küçük, çevrimdışı RAG araması sunar.
 * Kayıtların doğrulama seviyesi korunur; genel SAE kodları Yamaha'ya özel kod gibi sunulmaz.
 */
object YamahaKnowledgeBase {
    private const val FAULT_FILE = "database/yamaha_fault_database.json"
    private val stopWords = setOf(
        "bir", "ve", "veya", "ile", "icin", "gibi", "daha", "cok", "nasil",
        "neden", "nedir", "olur", "olan", "benim", "motor", "motorum", "ariza",
        "arizasi", "sorun", "problem", "var", "yok", "mi", "mu", "mı", "mü"
    )

    fun find(context: Context, code: String, model: String? = null): YamahaKnowledgeRecord? {
        val normalized = code.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return null
        val matches = readRecords(context).filter {
            it.code.equals(normalized, ignoreCase = true)
        }
        if (model.isNullOrBlank()) {
            // Preserve the generic lookup behavior when no vehicle profile is selected.
            return matches.firstOrNull { it.models.isEmpty() } ?: matches.firstOrNull()
        }
        val wanted = normalizeModel(model)
        return matches.firstOrNull { record ->
            record.models.any { candidate ->
                val available = normalizeModel(candidate)
                available == wanted || available.contains(wanted) || wanted.contains(available)
            }
        } ?: matches.firstOrNull { it.models.isEmpty() }
    }

    /**
     * Kullanıcının belirti/şikâyet metnini yerel Yamaha DTC kayıtlarıyla eşleştirir.
     * Eşleşme bir teşhis değildir; yalnızca ilgili referans kayıtlarını getirir.
     */
    fun search(
        context: Context,
        query: String,
        limit: Int = 4,
        model: String? = null
    ): List<YamahaKnowledgeRecord> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty() || limit <= 0) return emptyList()

        return readRecords(context)
            .filter { record ->
                model.isNullOrBlank() || record.models.isEmpty() ||
                    record.models.any { candidate ->
                        val wanted = normalizeModel(model)
                        val available = normalizeModel(candidate)
                        available == wanted || available.contains(wanted) || wanted.contains(available)
                    }
            }
            .mapNotNull { record ->
                val titleScore = overlap(queryTokens, tokens(record.title)) * 4
                val systemScore = overlap(queryTokens, tokens(record.system)) * 3
                val descriptionScore = overlap(queryTokens, tokens(record.description)) * 2
                val causeScore = overlap(
                    queryTokens,
                    tokens(record.possibleCauses.joinToString(" "))
                ) * 2
                val checkScore = overlap(
                    queryTokens,
                    tokens(record.checks.joinToString(" "))
                )
                val score = titleScore + systemScore + descriptionScore + causeScore + checkScore
                if (score < 2) null else score to record
            }
            .sortedWith(
                compareByDescending<Pair<Int, YamahaKnowledgeRecord>> { it.first }
                    .thenBy { it.second.code }
            )
            .take(limit)
            .map { it.second }
    }

    private fun overlap(query: Set<String>, field: Set<String>): Int =
        query.count { it in field }

    private fun tokens(value: String): Set<String> {
        val normalized = Normalizer.normalize(
            value.lowercase(Locale("tr", "TR")),
            Normalizer.Form.NFD
        )
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ı", "i")
        return normalized
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    private fun readRecords(context: Context): List<YamahaKnowledgeRecord> {
        return try {
            val root = JSONObject(
                context.assets.open(FAULT_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            val records = root.optJSONArray("records") ?: return emptyList()
            buildList {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val code = item.optString("code").trim().uppercase(Locale.US)
                    if (code.isBlank()) continue
                    add(
                        YamahaKnowledgeRecord(
                            code = code,
                            title = item.optString("title"),
                            description = item.optString("description"),
                            system = item.optString("system"),
                            possibleCauses = item.stringList("possibleCauses"),
                            checks = item.stringList("checks"),
                            codeStandard = item.optString("codeStandard"),
                            source = item.optString("source"),
                            verification = item.optString("verification"),
                            models = item.stringList("models"),
                            years = item.intList("years"),
                            namespace = item.optString("namespace", "POWERTRAIN")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeModel(value: String): String =
        value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")

    private fun JSONObject.intList(key: String): List<Int> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optInt(index, Int.MIN_VALUE)
                if (value != Int.MIN_VALUE) add(value)
            }
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
