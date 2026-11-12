package com.efemoto.motodiagpro

import org.junit.Assert.assertTrue
import org.junit.Test

class SeyfiUstaCommandHandlerTest {
    @Test
    fun allBuiltInVoiceCommandExamplesResolveCorrectly() {
        val failures = SeyfiUstaCommandHandler().selfTest()
        if (failures.isNotEmpty()) throw AssertionError("SEYFI_COMMAND_SELF_TEST_FAILURES=" + failures.joinToString(" || "))
    }

    @Test
    fun spokenDtcPromptsAndCodesAreNormalizedExactly() {
        assertTrue(SeyfiDtcCodeParser.isDtcPrompt("arıza kodu sorcam"))
        assertTrue(SeyfiDtcCodeParser.isDtcPrompt("arıza kodu soracağım"))
        assertTrue(SeyfiDtcCodeParser.isDtcPrompt("arıza kodu vereceğim"))
        assertTrue(SeyfiDtcCodeParser.extract("P sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P 0 3 3 5") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P0335") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("pe sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("pe harfi sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("pi sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P harfi sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P sıfır otuz üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P sıfır üç otuz beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("B harfi sıfır bir iki üç") == "B0123")
        assertTrue(SeyfiDtcCodeParser.extract("P 0 3") == null)
        assertTrue(SeyfiDtcCodeParser.extract("P 0 33 5") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("pe sıfır otuz üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("P 0335") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("arıza kodu P sıfır üç üç beş") == "P0335")
        assertTrue(SeyfiDtcCodeParser.extract("B sıfır bir iki üç") == "B0123")
        // İki haneli bir ASR parçası P-kodunun yerine geçip immobilizer/ABS
        // teşhisi üretmemeli; yalnızca açık DTC sorgusunda ve tam ifade ise adaydır.
        assertTrue(SeyfiDtcCodeParser.extract("51") == null)
        assertTrue(SeyfiDtcCodeParser.extract("51", allowShortCode = true) == "51")
        assertTrue(SeyfiDtcCodeParser.extract("ariza kodu 51", allowShortCode = true) == null)
        assertTrue(SeyfiDtcCodeParser.extract("11_ABS", allowShortCode = true) == "11_ABS")
    }

    @Test
    fun shortConversationRepliesStayNaturalAndDoNotRepeatPrompts() {
        assertTrue(SeyfiConversationRules.reply("Usta") == "Dinliyorum.")
        assertTrue(SeyfiConversationRules.reply("Seyfi Usta") == "Dinliyorum.")
        assertTrue(SeyfiConversationRules.reply("Nasılsın?") == "İyiyim usta, teşekkür ederim.")
        assertTrue(SeyfiConversationRules.reply("İyiyim çok şükür") == "Sevindim usta.")
        assertTrue(SeyfiConversationRules.reply("İyi") == "Sevindim usta.")
        assertTrue(SeyfiConversationRules.reply("Motosiklet tamiri") == "Kolay gelsin usta. Hangi motosiklet?")
    }

    @Test
    fun testAndScanAliasesAreNotSentToFreeChat() {
        val handler = SeyfiUstaCommandHandler()
        assertTrue(handler.handle("usta seyfi usta test test yap").action == SeyfiAction.SHOW_TESTS)
        assertTrue(handler.handle("tarama").action == SeyfiAction.START_SCAN)
        assertTrue(handler.handle("motorumu tara").action == SeyfiAction.START_SCAN)
        assertTrue(handler.handle("teste başla").action == SeyfiAction.SHOW_TESTS)
        assertTrue(handler.handle("test ekranını aç").action == SeyfiAction.SHOW_TESTS)
        assertTrue(handler.handle("canlı veriyi aç").action == SeyfiAction.SHOW_LIVE_DATA)
        assertTrue(handler.handle("canlı sensör verileri").action == SeyfiAction.SHOW_LIVE_DATA)
        assertTrue(handler.handle("arıza taramasını başlat").action == SeyfiAction.START_SCAN)
    }
}
