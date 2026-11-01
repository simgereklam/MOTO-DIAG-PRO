
package com.efemoto.motodiagpro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class LiveDataManager(
    private val diagnosticManager: DiagnosticManager
) {

    data class LiveSnapshot(
        val timestamp: Long,
        val rpm: Double?,
        val throttle: Double?,
        val coolantTemperature: Double?,
        val intakePressure: Int?,
        val batteryVoltage: Double?
    )

    suspend fun readOnce(): LiveSnapshot? =
        withContext(Dispatchers.IO) {

            val result = diagnosticManager.readLiveData()

            if (!result.success || result.data == null) {
                return@withContext null
            }

            val data = result.data

            LiveSnapshot(
                timestamp = System.currentTimeMillis(),
                rpm = data.rpm,
                throttle = data.throttle,
                coolantTemperature = data.coolantTemperature,
                intakePressure = data.intakePressure,
                batteryVoltage = data.batteryVoltage
            )
        }

    suspend fun readContinuous(
        intervalMs: Long = 1000,
        onData: (LiveSnapshot) -> Unit,
        onError: (String) -> Unit
    ) {
        while (true) {
            val snapshot = try {
                readOnce()
            } catch (e: Exception) {
                null
            }

            if (snapshot != null) {
                onData(snapshot)
            } else {
                onError("ECU canlı veri yanıtı alınamadı.")
            }

            delay(intervalMs.coerceAtLeast(500))
        }
    }
}
