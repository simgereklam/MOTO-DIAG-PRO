
package com.efemoto.motodiagpro

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import java.io.File
import java.util.concurrent.Executors

class SeyfiBrain(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val systemPrompt = """
        Sen SEYFİ USTA'sın.
        Profesyonel bir motosiklet dijital tamir asistanısın.

        Türkçe konuş.
        Kullanıcıyla doğal ve karşılıklı sohbet et.
        Motosiklet arızalarını mantıklı şekilde değerlendir.
        Eksik bilgi varsa soru sor.
        ECU arıza kodlarını açıkla.
        Gerçek sensör verilerini yorumla.
        Test sonuçlarını açıkla.
        Bilmediğin bilgiyi uydurma.
        Okunmamış ECU verisini okunmuş gibi gösterme.
        Tehlikeli işlemler için kullanıcıdan onay al.
        Cevaplarını anlaşılır ve ustaca ver.
    """.trimIndent()

    fun initialize(
        modelFile: File,
        onResult: (Boolean, String) -> Unit
    ) {
        executor.execute {
            try {
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw IllegalStateException(
                        "Yapay zekâ model dosyası bulunamadı."
                    )
                }

                conversation?.close()
                conversation = null

                engine?.close()
                engine = null

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                newEngine.initialize()
                engine = newEngine

                conversation = newEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                mainHandler.post {
                    onResult(true, "Seyfi Usta yapay zekâ motoru hazır.")
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onResult(
                        false,
                        "Yapay zekâ başlatılamadı: ${e.message}"
                    )
                }
            }
        }
    }

    fun ask(
        question: String,
        onResponse: (String) -> Unit
    ) {
        executor.execute {
            try {
                val activeConversation = conversation
                    ?: throw IllegalStateException(
                        "Yapay zekâ henüz başlatılmadı."
                    )

               val answer = activeConversation
    .sendMessage(question)
    .toString()

                mainHandler.post {
                    onResponse(answer)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onResponse(
                        "Usta şu anda cevap üretemedi: ${e.message}"
                    )
                }
            }
        }
    }

    fun close() {
        executor.execute {
            try {
                conversation?.close()
                conversation = null

                engine?.close()
                engine = null

            } catch (_: Exception) {
            } finally {
                executor.shutdown()
            }
        }
    }
}
