package com.efemoto.motodiagpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EfeMotoDiagnosticEngineTest {
    @Test fun missingSensorsAreNotFilledWithHealthyDefaults() {
        val result = EfeMotoDiagnosticEngine.analyze(EfeMotoSnapshot(mapOf("rpm" to 1300.0)))
        assertEquals(1, result.measuredCount)
        assertTrue(result.notes.any { it.contains("ölçülmeyen sensörler") })
        assertTrue(result.findings.isEmpty())
    }

    @Test fun noUniversalThresholdIsAppliedWithoutBikeProfile() {
        val result = EfeMotoDiagnosticEngine.analyze(
            EfeMotoSnapshot(mapOf("coolant" to 108.0, "voltage" to 12.2, "map" to 45.0))
        )
        assertTrue(result.findings.isEmpty())
        assertTrue(result.notes.any { it.contains("marka/model/yıl profili") })
    }

    @Test fun modelSpecificCoolantAndMapRangesAreEvaluated() {
        val profile = EfeMotoBikeProfile(
            "TEST-250",
            mapOf(
                "coolant" to EfeMotoReferenceRange(70.0, 100.0, "°C"),
                "map" to EfeMotoReferenceRange(25.0, 40.0, "kPa")
            )
        )
        val result = EfeMotoDiagnosticEngine.analyze(
            EfeMotoSnapshot(mapOf("coolant" to 105.0, "map" to 48.0)), profile
        )
        assertEquals(2, result.findings.count { it.level == EfeMotoFindingLevel.WARNING })
    }

    @Test fun impossibleTpsIsFlaggedAsDataQualityNotPartFailure() {
        val result = EfeMotoDiagnosticEngine.analyze(EfeMotoSnapshot(mapOf("tps" to 140.0)))
        assertEquals(EfeMotoFindingLevel.DATA_QUALITY, result.findings.single().level)
        assertTrue(result.findings.single().nextCheck.contains("ham yanıtı"))
    }

    @Test fun disconnectedEcuProducesNoLiveDiagnosis() {
        val result = EfeMotoDiagnosticEngine.analyze(
            EfeMotoSnapshot(mapOf("rpm" to 1300.0), ecuConnected = false)
        )
        assertEquals(0, result.measuredCount)
        assertTrue(result.notes.single().contains("ECU bağlantısı yok"))
    }
}
