
package com.efemoto.motodiagpro

import java.io.IOException

class ObdCommands(
    private val connection: ViecarConnection
) {

    data class CommandResult(
        val command: String,
        val response: String,
        val success: Boolean,
        val message: String,
        val dtcCodes: List<String> = emptyList()
    )

    data class LiveData(
        val rpm: Double? = null,
        val throttle: Double? = null,
        val coolantTemperature: Double? = null,
        val intakePressure: Int? = null,
        val batteryVoltage: Double? = null
    )

    fun initialize(): CommandResult {
        val commands = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATSP0"
        )

        val responses = StringBuilder()

        for (command in commands) {
            val result = execute(command)

            if (!result.success) {
                return CommandResult(
                    command,
                    responses.toString(),
                    false,
                    "$command komutu başarısız: ${result.message}"
                )
            }

            responses.append(result.response).append("\n")
        }

        return CommandResult(
            "INITIALIZE",
            responses.toString(),
            true,
            "Adaptör başlangıç komutları tamamlandı."
        )
    }

    fun readRpm(): Double? {
        val result = execute("010C")
        if (!result.success) return null

        val bytes = ObdResponseParser.parsePidBytes(
            result.response, "410C"
        ) ?: return null

        if (bytes.size < 2) return null

        return ((bytes[0] * 256) + bytes[1]) / 4.0
    }

    fun readThrottle(): Double? {
        val result = execute("0111")
        if (!result.success) return null

        val bytes = ObdResponseParser.parsePidBytes(
            result.response, "4111"
        ) ?: return null

        return bytes.firstOrNull()?.let {
            it * 100.0 / 255.0
        }
    }

    fun readCoolantTemperature(): Double? {
        val result = execute("0105")
        if (!result.success) return null

        val bytes = ObdResponseParser.parsePidBytes(
            result.response, "4105"
        ) ?: return null

        return bytes.firstOrNull()?.let {
            it - 40.0
        }
    }

    fun readIntakePressure(): Int? {
        val result = execute("010B")
        if (!result.success) return null

        val bytes = ObdResponseParser.parsePidBytes(
            result.response, "410B"
        ) ?: return null

        return bytes.firstOrNull()
    }

    fun readBatteryVoltage(): Double? {
        val result = execute("ATRV")
        if (!result.success) return null

        val match = Regex(
            """([0-9]+(?:\.[0-9]+)?)\s*V""",
            RegexOption.IGNORE_CASE
        ).find(result.response) ?: return null

        return match.groupValues[1].toDoubleOrNull()
    }

    fun readLiveData(): LiveData {
        return LiveData(
            rpm = readRpm(),
            throttle = readThrottle(),
            coolantTemperature = readCoolantTemperature(),
            intakePressure = readIntakePressure(),
            batteryVoltage = readBatteryVoltage()
        )
    }

    fun readDiagnosticTroubleCodes(): CommandResult {
        val result = execute("03")

        if (!result.success) return result

        val codes = ObdResponseParser.parseDtcCodes(
            result.response
        )

        return result.copy(
            message = if (codes.isEmpty()) {
                "ECU yanıtı alındı ancak arıza kodu ayrıştırılamadı veya kod bulunamadı."
            } else {
                "${codes.size} arıza kodu ayrıştırıldı."
            },
            dtcCodes = codes
        )
    }

    fun clearDiagnosticTroubleCodes(): CommandResult {
        return execute("04")
    }

    private fun execute(command: String): CommandResult {
        return try {
            val response = connection.sendCommand(command)

            val failed =
                ObdResponseParser.isErrorResponse(response) ||
                response.contains("?")

            CommandResult(
                command = command,
                response = ObdResponseParser.cleanResponse(response),
                success = !failed,
                message = if (failed) {
                    "Adaptör yanıtı: $response"
                } else {
                    "Yanıt alındı."
                }
            )

        } catch (e: IOException) {
            CommandResult(
                command,
                "",
                false,
                e.message ?: "Bağlantı hatası."
            )
        }
    }
}
