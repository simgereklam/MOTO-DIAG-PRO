package com.efemoto.motodiagpro

/**
 * Yamaha ABS profile scope for the first diagnostic integration.
 *
 * The 2020+ XMAX manuals confirm a dedicated ABS diagnostic connector,
 * separate from the FI diagnostic connector. Exact five-pin wiring,
 * CAN bitrate and ECU addressing are not yet verified for every variant.
 * Therefore this profile intentionally permits adapter identification only.
 */
internal object YamahaAbsDiagnostics {
    const val FIRST_SUPPORTED_YEAR = 2020
    const val STATUS = "ADAPTER_IDENTIFICATION_ONLY"

    fun supportsModel(model: String, year: Int): Boolean {
        if (year < FIRST_SUPPORTED_YEAR) return false
        val normalized = model.uppercase()
        return normalized.contains("XMAX 250") || normalized.contains("XMAX250") ||
            normalized.contains("XMAX 300") || normalized.contains("XMAX300")
    }

    const val SAFETY_NOTE =
        "ABS ECU addressing and five-pin wiring must be verified before sending diagnostic frames."
}
