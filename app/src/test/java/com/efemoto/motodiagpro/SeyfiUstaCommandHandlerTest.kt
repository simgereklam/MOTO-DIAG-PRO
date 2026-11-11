package com.efemoto.motodiagpro

import org.junit.Assert.assertEquals
import org.junit.Test

class SeyfiUstaCommandHandlerTest {
    private val handler = SeyfiUstaCommandHandler()

    @Test fun singleWordTaramaStartsScan() {
        assertEquals(SeyfiAction.START_SCAN, handler.handle("tarama").action)
    }

    @Test fun singleWordTaraStartsScan() {
        assertEquals(SeyfiAction.START_SCAN, handler.handle("tara").action)
    }

    @Test fun wakeWordAndTaramaAreRecognizedTogether() {
        assertEquals(SeyfiAction.START_SCAN, handler.handle("Seyfi Usta tarama").action)
    }

    @Test fun accentedScanPhraseIsNormalized() {
        assertEquals(SeyfiAction.START_SCAN, handler.handle("Taramayı başlat").action)
    }

    @Test fun wakeWordAloneRequestsListening() {
        assertEquals(SeyfiAction.WAKE_WORD, handler.handle("Seyfi Usta").action)
    }
}
