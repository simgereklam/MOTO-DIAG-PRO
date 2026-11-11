
package com.efemoto.motodiagpro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticManager(
    private val connection: ViecarConnection = ViecarConnection()
) {
    private val obd = ObdCommands(connection)

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val response: String = ""
    )

    data class FaultDetail(
        val code: String,
        val title: String,
        val description: String,
        val possibleCauses: List<String>,
        val checks: List<String>
    )

    data class FaultReadResult(
        val success: Boolean,
        val faults: List<FaultDetail>,
        val rawResponse: String,
        val message: String
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
                        "Viecar adaptörüne bağlantı kuruldu.",
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

    suspend fun readFaultCodes(): FaultReadResult =
        withContext(Dispatchers.IO) {

            if (!connection.isConnected()) {
                return@withContext FaultReadResult(
                    false,
                    emptyList(),
                    "",
                    "Önce adaptöre bağlanın."
                )
            }

            val result = obd.readDiagnosticTroubleCodes()

            if (!result.success) {
                return@withContext FaultReadResult(
                    false,
                    emptyList(),
                    result.response,
                    result.message
                )
            }

            val details = result.dtcCodes.map { code ->

                val catalog = YamahaFaultCodes.find(code)
                val genericTitle = DtcDatabase.title(code)
                val isKnownGeneric = !genericTitle.startsWith("Üreticiye özel")

                FaultDetail(
                    code = code,
                    title = catalog?.title
                        ?: if (isKnownGeneric) genericTitle else "Tanımlanmamış arıza kodu",
                    description = catalog?.description
                        ?: if (isKnownGeneric) {
                            DtcDatabase.description(code)
                        } else {
                            "Bu kod için doğrulanmış açıklama bulunamadı. ECU kimliği ve motosikletin model/yıl servis tablosu kontrol edilmeli."
                        },
                    possibleCauses = catalog?.possibleCauses
                        ?: if (isKnownGeneric) DtcDatabase.causes(code)
                        else listOf("Model ve ECU servis dokümanı kontrol edilmeli."),
                    checks = catalog?.checks
                        ?: if (isKnownGeneric) DtcDatabase.checks(code)
                        else listOf("Üretici teşhis prosedürü uygulanmalı.")
                )
            }

            FaultReadResult(
                true,
                details,
                result.response,
                if (details.isEmpty()) {
                    "ECU yanıtı alındı. Ayrıştırılabilir arıza kodu bulunamadı. Bu sonuç tek başına arıza olmadığı anlamına gelmez."
                } else {
                    "${details.size} arıza kodu bulundu."
                }
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

            val verifyResult = obd.readDiagnosticTroubleCodes()

            if (!verifyResult.success) {
                return@withContext OperationResult(
                    false,
                    "Silme komutu gönderildi ancak sonuç doğrulanamadı.",
                    verifyResult.response
                )
            }

            val codes = verifyResult.dtcCodes

            if (codes.isEmpty()) {
                OperationResult(
                    true,
                    "ECU yanıtında ayrıştırılabilir arıza kodu bulunamadı. Silme sonucu kesin doğrulanamadı.",
                    verifyResult.response
                )
            } else {
                OperationResult(
                    false,
                    "Arıza kodları hâlâ mevcut: ${codes.joinToString(", ")}",
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
                    "ECU canlı veri yanıtları alındı."
                } else {
                    "ECU desteklenen PID'lere yanıt vermedi."
                }
            )
        }

    fun close() {
        connection.disconnect()
    }
}
