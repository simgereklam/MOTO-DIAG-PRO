
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
        val responses = StringBuilder()

        val reset = execute("ATZ")

        responses.append("ATZ: ")
            .append(reset.response)
            .append("\n")

        if (!reset.success) {
            return CommandResult(
                "INITIALIZE",
                responses.toString(),
                false,
                "Adaptör sıfırlama komutu başarısız."
            )
        }

        // ELM327/Viecar yeniden başlasın.
        try {
            Thread.sleep(1200)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()

            return CommandResult(
                "INITIALIZE",
                responses.toString(),
                false,
                "Adaptör başlatma işlemi kesildi."
            )
        }

        val commands = listOf(
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATSP0"
        )

        for (command in commands) {
            val result = execute(command)

            responses.append(command)
                .append(": ")
                .append(result.response)
                .append("\n")

            if (!result.success) {
                return CommandResult(
                    "INITIALIZE",
                    responses.toString(),
                    false,
                    "$command komutu başarısız: ${result.message}"
                )
            }
        }

        return CommandResult(
            "INITIALIZE",
            responses.toString(),
            true,
            "Adaptör temel komutları tamamlandı. ECU protokolü henüz doğrulanmadı."
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
                "ECU yanıtı alındı ancak ayrıştırılabilir arıza kodu bulunamadı."
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

            val cleaned = ObdResponseParser.cleanResponse(response)

            val failed =
                ObdResponseParser.isErrorResponse(cleaned) ||
                cleaned.contains("?")

            CommandResult(
                command = command,
                response = cleaned,
                success = !failed,
                message = if (failed) {
                    "Adaptör yanıtı: $cleaned"
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
