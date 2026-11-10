package com.efemoto.motodiagpro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TechnicalResearchSource(val title: String, val url: String)

data class TechnicalResearchResult(
    val answer: String,
    val sources: List<TechnicalResearchSource>,
    val searchSuggestionsHtml: String = "",
    val error: String? = null
)

/** Gemini + Google Search tabanlı, yerel Seyfi beyninden bağımsız araştırma uzmanı. */
object TechnicalResearchAssistant {
    private const val PREFS = "efe_moto_research"
    private const val KEY_VALUE = "gemini_key_encrypted"
    private const val KEY_ALIAS = "efe_moto_gemini_key_v1"
    private const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasApiKey(context: Context): Boolean = !readApiKey(context).isNullOrBlank()

    fun saveApiKey(context: Context, apiKey: String) {
        val clean = apiKey.trim()
        require(clean.isNotEmpty()) { "API anahtarı boş olamaz." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun clearApiKey(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_VALUE).apply()
    }

    private fun readApiKey(context: Context): String? {
        val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VALUE, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12))
            )
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun research(
        context: Context,
        question: String,
        ecuFaults: List<String>,
        liveValues: Map<String, Double>,
        onResult: (TechnicalResearchResult) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val apiKey = readApiKey(appContext)
                    ?: throw IllegalStateException("Gemini API anahtarı ayarlanmamış.")
                val technicalContext = buildString {
                    appendLine("Kullanıcının teknik araştırma isteği: " + question)
                    appendLine("Gerçek ECU taramasında bulunan kodlar: " +
                        ecuFaults.joinToString(", ").ifBlank { "Bu oturumda doğrulanmış arıza kodu yok." })
                    appendLine("ECU'dan son okunan canlı veriler:")
                    if (liveValues.isEmpty()) appendLine("Bu oturumda canlı veri alınmadı.")
                    else liveValues.forEach { (name, value) ->
                        if (value.isFinite()) appendLine(name + " = " + "%.2f".format(Locale.US, value))
                    }
                }
                val systemInstruction = """
                    Sen MOTO DIAG PRO uygulamasının internet üzerinden çalışan teknik araştırma uzmanısın.
                    Motosiklet arıza kodlarını, üretici servis dokümanlarını ve OEM yedek parça numaralarını araştır.
                    Google Search aracını gerçekten kullan; kaynakları ve URL'leri yanıtla birlikte döndür.
                    Öncelik üreticinin resmi parça katalogları, servis kılavuzları ve teknik servis bültenleridir.

                    KRİTİK KURALLAR:
                    - Marka, model, model yılı ve motor varyantı belli değilse OEM parça numarası tahmin etme;
                      hangi araç bilgisinin eksik olduğunu sor.
                    - Arıza kodunu tek başına arızalı parçanın kesin kanıtı sayma.
                    - OEM numarasını yalnızca kaynakta açıkça doğrulanıyorsa doğrulanmış olarak ver.
                    - Alternatif/yan sanayi numaralarını OEM numarasından ayrı göster.
                    - Kaynak bulamazsan "doğrulanmış parça numarası bulunamadı" de.
                    - Her parça satırında parça adı, OEM numarası, uyumlu model/yıl, kaynak ve doğrulama durumunu belirt.
                    - Kaynaksız numara üretme; forum iddialarını üretici bilgisi gibi sunma.
                    - Teşhis önerilerini ECU verisi, olası neden ve doğrulama ölçümü olarak ayır.
                    - Türkçe ve anlaşılır cevap ver.
                """.trimIndent()

                val request = JSONObject()
                    .put("systemInstruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", systemInstruction))
                    ))
                    .put("contents", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", technicalContext)))
                    ))
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 3000))

                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                try {
                    connection.outputStream.use {
                        it.write(request.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        val message = runCatching {
                            JSONObject(responseText).optJSONObject("error")?.optString("message")
                        }.getOrNull().orEmpty()
                        throw IllegalStateException(
                            "Gemini HTTP " + status + ": " +
                                message.ifBlank { responseText.take(500) }
                        )
                    }

                    val response = JSONObject(responseText)
                    val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                        ?: throw IllegalStateException("Gemini geçerli bir yanıt adayı döndürmedi.")
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    val answer = buildString {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val partText = part.optString("text")
                            if (partText.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(partText)
                            }
                        }
                    }.trim().ifBlank { "Araştırma yanıtı boş döndü. Soruyu model ve yıl bilgisiyle tekrar deneyin." }

                    val metadata = candidate.optJSONObject("groundingMetadata")
                    val chunks = metadata?.optJSONArray("groundingChunks") ?: JSONArray()
                    val sources = mutableListOf<TechnicalResearchSource>()
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val uri = web.optString("uri")
                        if (uri.startsWith("https://") || uri.startsWith("http://")) {
                            if (sources.none { it.url == uri }) {
                                sources.add(TechnicalResearchSource(web.optString("title").ifBlank { uri }, uri))
                            }
                        }
                    }
                    val suggestions = metadata?.optJSONObject("searchEntryPoint")
                        ?.optString("renderedContent").orEmpty()
                    TechnicalResearchResult(answer, sources, suggestions)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                TechnicalResearchResult(
                    answer = "İnternet teknik araştırması tamamlanamadı: " +
                        (e.message ?: "Bilinmeyen bağlantı hatası"),
                    sources = emptyList(),
                    error = e.message ?: "Araştırma hatası"
                )
            }
            mainHandler.post { onResult(result) }
        }
    }
}
