
package com.efemoto.motodiagpro

import java.io.IOException

class ObdCommands(
    private val connection: ViecarConnection
) {

    data class CommandResult(
        val command: String,
        val response: String,
        val success: Boolean,
        val message: String
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
            "Adaptör komutları hazır."
        )
    }

    fun readRpm(): Double? {
        val response = execute("010C")
        if (!response.success) return null

        val bytes = parseObdResponse(response.response, "410C")
            ?: return null

        if (bytes.size < 2) return null

        val value = (bytes[0] * 256) + bytes[1]
        return value / 4.0
    }

    fun readThrottle(): Double? {
        val response = execute("0111")
        if (!response.success) return null

        val bytes = parseObdResponse(response.response, "4111")
            ?: return null

        if (bytes.isEmpty()) return null

        return bytes[0] * 100.0 / 255.0
    }

    fun readCoolantTemperature(): Double? {
        val response = execute("0105")
        if (!response.success) return null

        val bytes = parseObdResponse(response.response, "4105")
            ?: return null

        if (bytes.isEmpty()) return null

        return bytes[0] - 40.0
    }

    fun readIntakePressure(): Int? {
        val response = execute("010B")
        if (!response.success) return null

        val bytes = parseObdResponse(response.response, "410B")
            ?: return null

        return bytes.firstOrNull()
    }

    fun readBatteryVoltage(): Double? {
        val response = execute("ATRV")
        if (!response.success) return null

        val match = Regex(
            """([0-9]+(?:\.[0-9]+)?)\s*V""",
            RegexOption.IGNORE_CASE
        ).find(response.response) ?: return null

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
        return execute("03")
    }

    fun clearDiagnosticTroubleCodes(): CommandResult {
        val result = execute("04")

        if (!result.success) return result

        return CommandResult(
            "04",
            result.response,
            true,
            "Silme komutu gönderildi. Sonucu doğrulamak için arıza kodlarını yeniden okuyun."
        )
    }

    private fun execute(command: String): CommandResult {
        return try {
            val response = connection.sendCommand(command)

            val normalized = response.uppercase()

            val failed =
                normalized.contains("NO DATA") ||
                normalized.contains("ERROR") ||
                normalized.contains("UNABLE TO CONNECT") ||
                normalized.contains("STOPPED") ||
                normalized.contains("?")

            CommandResult(
                command,
                response,
                !failed,
                if (failed) "Adaptör yanıtı: $response"
                else "Yanıt alındı."
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

    private fun parseObdResponse(
        response: String,
        header: String
    ): List<Int>? {

        val cleaned = response
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")

        val index = cleaned.indexOf(header)

        if (index < 0) return null

        val dataStart = index + header.length

        if (dataStart >= cleaned.length) return null

        val data = cleaned.substring(dataStart)

        if (data.length % 2 != 0) return null

        return data.chunked(2).mapNotNull {
            it.toIntOrNull(16)
        }.takeIf { it.isNotEmpty() }
    }
}
