package com.efemoto.motodiagpro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores real live ECU samples as JSON Lines for later supervised training.
 * Sessions are unlabelled by default; a DTC is not treated as confirmed root cause.
 */
object EcuSessionRecorder {
    fun append(
        context: Context,
        sessionId: String,
        values: Map<String, Double>,
        vehicle: String,
        dtcs: List<String>
    ) {
        if (values.isEmpty()) return
        runCatching {
            val folder = File(context.filesDir, "seyfi_training").apply { mkdirs() }
            val file = File(folder, "ecu_live_samples.jsonl")
            val row = JSONObject()
                .put("session_id", sessionId)
                .put("timestamp", System.currentTimeMillis())
                .put("fault_label", "unlabelled")
                .put("vehicle", vehicle)
                .put("dtcs", JSONArray(dtcs))
            val sensorValues = JSONObject()
            values.filterValues { it.isFinite() }.forEach { (key, value) ->
                sensorValues.put(key, value)
            }
            row.put("values", sensorValues)
            file.appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }

    fun filePath(context: Context): String =
        File(context.filesDir, "seyfi_training/ecu_live_samples.jsonl").absolutePath
}
