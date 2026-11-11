package com.efemoto.motodiagpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuPidCatalogTest {
    @Test fun decodesRpm() {
        val reading = EcuPidCatalog.decode(0x0C, listOf(0x1A, 0xF8))
        assertEquals(1726.0, reading.value!!, 0.001)
        assertEquals("RPM", reading.unit)
    }

    @Test fun decodesThrottlePercent() {
        val reading = EcuPidCatalog.decode(0x11, listOf(0x80))
        assertEquals(128.0 * 100.0 / 255.0, reading.value!!, 0.001)
    }

    @Test fun preservesUnknownPidRawBytes() {
        val reading = EcuPidCatalog.decode(0xD1, listOf(0x12, 0xAB, 0x00))
        assertEquals("12AB00", reading.rawHex)
        assertTrue(reading.displayValue.contains("Ham veri"))
        assertEquals(null, reading.value)
    }

    @Test fun identifiesSupportBitmapPids() {
        assertTrue(EcuPidCatalog.isSupportBitmap(0x20))
        assertTrue(!EcuPidCatalog.isSupportBitmap(0x21))
    }
}
