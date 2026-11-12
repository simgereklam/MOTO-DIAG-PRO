package com.efemoto.motodiagpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YamahaVehicleCatalogTest {
    @Test
    fun understandsWrittenModelAndYearInOneSentence() {
        assertEquals(
            YamahaVehicleCatalog.Match("MT-25", "2022"),
            YamahaVehicleCatalog.parse("Yamaha MT-25 2022 model")
        )
    }

    @Test
    fun understandsTurkishSpokenModelAndYear() {
        assertEquals(
            YamahaVehicleCatalog.Match("MT-25", "2022"),
            YamahaVehicleCatalog.parse("Yamaha em ti yirmi beş iki bin yirmi iki")
        )
    }

    @Test
    fun keepsNmaxEngineVariant() {
        assertEquals(
            YamahaVehicleCatalog.Match("NMAX 155", "2023"),
            YamahaVehicleCatalog.parse("2023 model Yamaha NMAX 155")
        )
    }

    @Test
    fun understandsR25AndTenere() {
        assertEquals(
            YamahaVehicleCatalog.Match("R25", "2020"),
            YamahaVehicleCatalog.parse("Yamaha YZF R25 2020")
        )
        assertEquals(
            YamahaVehicleCatalog.Match("TENERE 700", "2023"),
            YamahaVehicleCatalog.parse("Yamaha Tenere 700 2023")
        )
    }

    @Test
    fun asksForYearWhenOnlyModelWasSpoken() {
        assertEquals(YamahaVehicleCatalog.Match("MT-07", null), YamahaVehicleCatalog.parse("MT07"))
        assertNull(YamahaVehicleCatalog.parse("Yamaha"))
    }

    @Test
    fun allCatalogSelfTestExamplesPass() {
        val failures = YamahaVehicleCatalog.selfTest()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
