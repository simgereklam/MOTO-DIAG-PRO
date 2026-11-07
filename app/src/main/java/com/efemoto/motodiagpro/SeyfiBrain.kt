package com.efemoto.motodiagpro

class SeyfiBrain {

    private val systemPrompt = """
        Sen SEYFİ USTA'sın.
        Motosikletler konusunda uzman bir dijital tamir asistanısın.

        Görevlerin:
        - Kullanıcıyla doğal Türkçe konuş.
        - Motosiklet arızalarını anlamaya çalış.
        - Eksik bilgi varsa soru sor.
        - ECU arıza kodlarını açıkla.
        - Gerçek sensör verilerini değerlendir.
        - Test sonuçlarını yorumla.
        - Tehlikeli işlemleri onaysız başlatma.
        - Bilmediğin arızayı uydurma.
        - Gerçek ECU verisi olmadan ölçüm sonucu verme.

        Cevapların anlaşılır, kısa ve usta ağzıyla olsun.
    """.trimIndent()

    fun getSystemPrompt(): String {
        return systemPrompt
    }

    fun prepareQuestion(question: String): String {
        return """
            $systemPrompt

            Kullanıcının söylediği:
            $question

            Türkçe cevap ver.
        """.trimIndent()
    }
}
