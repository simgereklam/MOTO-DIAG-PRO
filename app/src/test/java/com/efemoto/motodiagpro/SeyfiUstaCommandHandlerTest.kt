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
        assertTrue(SeyfiDtcCodeParser.extract("P 0 3") == null)
    }

    @Test
    fun testAndScanAliasesAreNotSentToFreeChat() {
        val handler = SeyfiUstaCommandHandler()
        assertTrue(handler.handle("usta seyfi usta test test yap").action == SeyfiAction.SHOW_TESTS)
        assertTrue(handler.handle("tarama").action == SeyfiAction.START_SCAN)
        assertTrue(handler.handle("motorumu tara").action == SeyfiAction.START_SCAN)
    }
}
