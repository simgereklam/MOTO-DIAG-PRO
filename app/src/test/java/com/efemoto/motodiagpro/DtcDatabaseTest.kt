package com.efemoto.motodiagpro

import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDatabaseTest {
    @Test
    fun recognizesCommonPowertrainCodes() {
        assertTrue(DtcDatabase.title("P0335").contains("Krank"))
        assertTrue(DtcDatabase.title("P0562").contains("gerilimi düşük"))
        assertTrue(DtcDatabase.title("P0171").contains("fakir"))
        assertTrue(DtcDatabase.title("P2135").contains("korelasyon"))
    }

    @Test
    fun classifiesBodyChassisAndNetworkCodes() {
        assertTrue(DtcDatabase.title("B1000").contains("Gövde"))
        assertTrue(DtcDatabase.title("C0035").contains("tekerlek hız"))
        assertTrue(DtcDatabase.title("U0100").contains("iletişim kaybı"))
        assertTrue(DtcDatabase.description("C0035").contains("ABS"))
    }

    @Test
    fun doesNotPretendUnknownManufacturerCodesAreUniversal() {
        assertTrue(DtcDatabase.title("P1234").contains("Üreticiye özel"))
        assertTrue(DtcDatabase.title("12").contains("Yamaha"))
        assertTrue(DtcDatabase.description("12").contains("model"))
    }
}
