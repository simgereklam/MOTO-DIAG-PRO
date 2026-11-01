
package com.efemoto.motodiagpro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class DiagnosticManager {

    private val connection = ViecarConnection()
    private val obd = ObdCommands(connection)

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val response: String = ""
    )

    data class LiveDataResult(
        val success: Boolean,
        val data: ObdCommands.LiveData?,
        val message: String
    )

    suspend fun connect(): OperationResult =
        withContext(Dispatchers.IO) {
            try {
                connection.connect()

                val initialization = obd.initialize()

                if (!initialization.success) {
                    connection.disconnect()

                    OperationResult(
                        false,
                        initialization.message,
                        initialization.response
                    )
                } else {
                    OperationResult(
                        true,
                        "Viecar adaptörüne TCP bağlantısı kuruldu.",
                        initialization.response
                    )
                }

            } catch (e: Exception) {
                connection.disconnect()

                OperationResult(
                    false,
                    e.message ?: "Bağlantı kurulamadı."
                )
            }
        }

    suspend fun disconnect(): OperationResult =
        withContext(Dispatchers.IO) {
            connection.disconnect()

            OperationResult(
                true,
                "Adaptör bağlantısı kapatıldı."
            )
        }

    suspend fun isConnected(): Boolean =
        withContext(Dispatchers.IO) {
            connection.isConnected()
        }

    suspend fun readFaultCodes(): OperationResult =
        withContext(Dispatchers.IO) {
            if (!connection.isConnected()) {
                return@withContext OperationResult(
                    false,
                    "Önce adaptöre bağlanın."
                )
            }

            val result = obd.readDiagnosticTroubleCodes()

            OperationResult(
                result.success,
                if (result.success) {
                    "ECU arıza kodu yanıtı alındı."
                } else {
                    result.message
                },
                result.response
            )
        }

    suspend fun clearFaultCodes(): OperationResult =
        withContext(Dispatchers.IO) {
            if (!connection.isConnected()) {
                return@withContext OperationResult(
                    false,
                    "Önce adaptöre bağlanın."
                )
            }

            val clearResult = obd.clearDiagnosticTroubleCodes()

            if (!clearResult.success) {
                return@withContext OperationResult(
                    false,
                    clearResult.message,
                    clearResult.response
                )
            }

            // Silme komutundan sonra ECU yanıtını kontrol et.
            val verifyResult = obd.readDiagnosticTroubleCodes()

            if (!verifyResult.success) {
                return@withContext OperationResult(
                    false,
                    "Silme komutu gönderildi ancak sonuç doğrulanamadı.",
                    verifyResult.response
                )
            }

            val response = verifyResult.response
                .uppercase()
                .replace(" ", "")
                .replace("\r", "")
                .replace("\n", "")

            val noFaults =
                response.contains("4300") ||
                response == "4300"

            if (noFaults) {
                OperationResult(
                    true,
                    "ECU yanıtında kayıtlı arıza kodu görünmüyor.",
                    verifyResult.response
                )
            } else {
                OperationResult(
                    false,
                    "Arıza kodları hâlâ görünüyor veya ECU yanıtı yorumlanamadı.",
                    verifyResult.response
                )
            }
        }

    suspend fun readLiveData(): LiveDataResult =
        withContext(Dispatchers.IO) {
            if (!connection.isConnected()) {
                return@withContext LiveDataResult(
                    false,
                    null,
                    "Önce adaptöre bağlanın."
                )
            }

            try {
                val data = obd.readLiveData()

                val hasData =
                    data.rpm != null ||
                    data.throttle != null ||
                    data.coolantTemperature != null ||
                    data.intakePressure != null ||
                    data.batteryVoltage != null

                LiveDataResult(
                    hasData,
                    data,
                    if (hasData) {
                        "Canlı veri yanıtları alındı."
                    } else {
                        "ECU desteklenen PID'lere yanıt vermedi."
                    }
                )

            } catch (e: Exception) {
                LiveDataResult(
                    false,
                    null,
                    e.message ?: "Canlı veri okunamadı."
                )
            }
        }

    fun close() {
        connection.disconnect()
    }
}
