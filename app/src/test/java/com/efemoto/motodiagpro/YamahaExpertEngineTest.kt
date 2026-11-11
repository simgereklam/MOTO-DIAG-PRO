package com.efemoto.motodiagpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YamahaExpertEngineTest {

    @Test
    fun nmax2022UsesItsVerifiedP0335ProcedureAndSensorGap() {
        val report = YamahaExpertEngine.analyze(
            rawCodes = listOf("P0335 • KAYITLI"),
            modelAndYear = "Yamaha NMAX 155 2022",
            liveValues = mapOf("rpm" to 1450.0, "voltage" to 14.1)
        )

        assertTrue(report.usedModelSpecificProcedure)
        assertTrue(report.displayText.contains("1,15 mm"))
        assertTrue(report.displayText.contains("SGCU"))
        assertTrue(report.displayText.contains("Yamaha NMAX 155 GPD155-A (2022)"))
        assertTrue(report.displayText.contains("1450.00 RPM"))
    }

    @Test
    fun nmaxSensorGapIsNeverAppliedToAnUnverifiedYearOrModel() {
        val wrongYear = YamahaExpertEngine.analyze(
            rawCodes = listOf("P0335"),
            modelAndYear = "Yamaha NMAX 155 2021"
        )
        val unknownModel = YamahaExpertEngine.analyze(
            rawCodes = listOf("P0335"),
            modelAndYear = "Yamaha XMAX 250 2022"
        )

        assertFalse(wrongYear.usedModelSpecificProcedure)
        assertFalse(unknownModel.usedModelSpecificProcedure)
        assertFalse(wrongYear.displayText.contains("1,15 mm"))
        assertFalse(unknownModel.displayText.contains("1,15 mm"))
        assertTrue(unknownModel.displayText.contains("servis prosedürü doğrulanmadı"))
    }

    @Test
    fun mt1252023GetsItsOwnP0335Procedure() {
        val report = YamahaExpertEngine.analyze(
            rawCodes = listOf("P0335"),
            modelAndYear = "MT-125 2023"
        )

        assertTrue(report.usedModelSpecificProcedure)
        assertTrue(report.displayText.contains("Yamaha MT-125 (2023)"))
        assertTrue(report.displayText.contains("ECU soketinin"))
        assertFalse(report.displayText.contains("1,15 mm"))
    }

    @Test
    fun unknownCodeIsNotInvented() {
        val report = YamahaExpertEngine.analyze(
            rawCodes = listOf("P0999"),
            modelAndYear = "Yamaha R25 2024"
        )

        assertFalse(report.usedModelSpecificProcedure)
        assertTrue(report.displayText.contains("yerel ve doğrulanmış açıklama bulunamadı"))
    }

    @Test
    fun emptyCodeListDoesNotClaimThatTheBikeHasNoFaults() {
        val report = YamahaExpertEngine.analyze(
            rawCodes = emptyList(),
            modelAndYear = "Yamaha NMAX 155 2022",
            scanCompleted = false
        )

        assertFalse(report.usedModelSpecificProcedure)
        assertTrue(report.displayText.contains("gerçek ECU taramasını tamamlayın"))
    }
}
