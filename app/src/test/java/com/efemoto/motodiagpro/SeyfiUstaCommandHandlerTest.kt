package com.efemoto.motodiagpro

import org.junit.Assert.assertTrue
import org.junit.Test

class SeyfiUstaCommandHandlerTest {
    @Test
    fun allBuiltInVoiceCommandExamplesResolveCorrectly() {
        val failures = SeyfiUstaCommandHandler().selfTest()
        assertTrue("Komut sözlüğü öz testi başarısız:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun testAndScanAliasesAreNotSentToFreeChat() {
        val handler = SeyfiUstaCommandHandler()
        assertTrue(handler.handle("usta seyfi usta test test yap").action == SeyfiAction.SHOW_TESTS)
        assertTrue(handler.handle("tarama").action == SeyfiAction.START_SCAN)
        assertTrue(handler.handle("motorumu tara").action == SeyfiAction.START_SCAN)
    }
}
