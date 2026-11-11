package com.efemoto.motodiagpro

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONObject

/**
 * Qwen3 0.6B'in seçtiği uygulama işlemlerini güvenli bir izin listesiyle çalıştırır.
 * Teşhis testleri yalnızca önceden tanımlanmış salt-okunur OBD sorgularından seçilebilir.
 */
class SeyfiAppTools(
    private val executeAction: (String) -> String
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "moto_app_action",
          "description": "MOTO DIAG PRO uygulamasında gerçek işlemler yap. Kullanıcı ECU taraması, arıza okuma, canlı veri, ekran değiştirme, bağlantı durumu veya rapor isterse uygun action değerini seç. Teşhis sırasında güvenli bir veri kontrolü gerekiyorsa yalnızca run_safe_diagnostic_test eylemini ve izin verilen test_id değerlerinden birini seç. Bu testler yalnızca salt-okunur standart sorgulardır; aktüatör çalıştırmaz, ECU'ya yazmaz ve arıza silmez. Kullanıcı normal bilgi veya sohbet isterse bu aracı çağırma. Arıza silme yalnızca ekrandaki kullanıcı onayıyla yapılabilir. Bir işlemi tamamladığını ancak araç sonucu doğrularsa söyle.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": [
                  "start_ecu_scan",
                  "read_fault_codes",
                  "clear_fault_codes",
                  "show_live_data",
                  "get_live_data",
                  "connection_status",
                  "show_home",
                  "show_tests",
                  "run_safe_diagnostic_test",
                  "export_report"
                ],
                "description": "Gerçekleştirilecek uygulama işlemi"
              },
              "test_id": {
                "type": "string",
                "enum": [
                  "basic_live_data",
                  "fuel_trim_review",
                  "throttle_rpm_review",
                  "ignition_advance_review",
                  "dtc_memory_review",
                  "ecu_identity_review"
                ],
                "description": "Yalnızca run_safe_diagnostic_test için kullanılacak önceden tanımlı güvenli test"
              }
            },
            "required": ["action"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JSONObject(paramsJsonString)
            val action = params.optString("action").trim()
            val testId = params.optString("test_id").trim()
            if (action.isBlank()) {
                "İşlem adı gelmedi; hiçbir uygulama işlemi yapılmadı."
            } else {
                val request = if (action == "run_safe_diagnostic_test") {
                    "$action|$testId"
                } else {
                    action
                }
                JSONObject()
                    .put("result", executeAction(request))
                    .toString()
            }
        } catch (e: Exception) {
            JSONObject()
                .put("error", "Uygulama aracı parametre hatası: ${e.message}")
                .toString()
        }
    }
}
