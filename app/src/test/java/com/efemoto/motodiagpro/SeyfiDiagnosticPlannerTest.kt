package com.efemoto.motodiagpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeyfiDiagnosticPlannerTest {
    @Test
    fun everyAiSelectableTestContainsOnlyReadOnlyQueries() {
        val tests = SeyfiDiagnosticTestCatalog.all()
        assertTrue(tests.isNotEmpty())
        tests.forEach { test ->
            assertTrue("Unsafe query in ${test.id}", SeyfiDiagnosticTestCatalog.isReadOnly(test))
            assertFalse(test.queries.any { it.second == "04" })
        }
    }

    @Test
    fun crankSensorCodeSuggestsObservationWithoutClaimingFailure() {
        val plan = SeyfiDiagnosticPlanner.createPlan(
            rawCodes = listOf("P0335 • KAYITLI"),
            observedValues = emptyMap(),
            supportedPids = setOf(0x0C, 0x11, 0x05)
        )
        assertEquals(listOf("P0335"), plan.detectedCodes)
        assertNotNull(plan.recommendedTests.firstOrNull { it.id == "basic_live_data" })
        assertTrue(plan.notes.any { it.contains("sıfır RPM tek başına") })
    }

    @Test
    fun leanMixtureCodeSuggestsFuelTrimReadOnlyTest() {
        val plan = SeyfiDiagnosticPlanner.createPlan(
            rawCodes = listOf("P0171 • BEKLEYEN"),
            observedValues = mapOf("stft" to 4.0),
            supportedPids = setOf(0x06, 0x07, 0x14)
        )
        assertTrue(plan.recommendedTests.any { it.id == "fuel_trim_review" })
    }

    @Test
    fun noFaultsStillCreatesBaselinePlanAndDoesNotInventData() {
        val plan = SeyfiDiagnosticPlanner.createPlan(
            rawCodes = emptyList(),
            observedValues = emptyMap(),
            supportedPids = emptySet()
        )
        assertTrue(plan.detectedCodes.isEmpty())
        assertEquals(listOf("basic_live_data"), plan.recommendedTests.map { it.id })
        assertTrue(plan.notes.any { it.contains("Canlı sensör verisi alınmadı") })
    }

    @Test
    fun unknownFaultCodeDoesNotCreateManufacturerSpecificProcedure() {
        val plan = SeyfiDiagnosticPlanner.createPlan(
            rawCodes = listOf("P0999 • KAYITLI"),
            observedValues = emptyMap(),
            supportedPids = emptySet()
        )
        assertEquals(listOf("basic_live_data"), plan.recommendedTests.map { it.id })
    }
}
