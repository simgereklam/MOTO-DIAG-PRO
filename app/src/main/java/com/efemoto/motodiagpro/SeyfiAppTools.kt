package com.efemoto.motodiagpro

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONObject

/**
 * Gemma 43'in doğal dilde verdiği karara göre gerçek uygulama işlemlerini çağıran araç.
 * Yalnızca MainActivity tarafından izin verilen eylemler çalıştırılır.
 */
class SeyfiAppTools(
    private val executeAction: (String) -> String
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "moto_app_action",
          "description": "MOTO DIAG PRO uygulamasında gerçek işlemler yap. Kullanıcı ECU taraması, arıza okuma, canlı veri, ekran değiştirme, bağlantı durumu veya rapor isterse uygun action değerini seç. Kullanıcı normal bilgi veya sohbet isterse bu aracı çağırma. Arıza silme yalnızca ekrandaki kullanıcı onayıyla tamamlanabilir; bu araç sadece onay penceresini açar. Bir işlemi yaptığını ancak araç sonucu doğrularsa söyle.",
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
                  "export_report"
                ],
                "description": "Gerçekleştirilecek uygulama işlemi"
              }
            },
            "required": ["action"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val action = JSONObject(paramsJsonString).optString("action")
            if (action.isBlank()) {
                "İşlem adı gelmedi; hiçbir uygulama işlemi yapılmadı."
            } else {
                JSONObject()
                    .put("result", executeAction(action))
                    .toString()
            }
        } catch (e: Exception) {
            JSONObject()
                .put("error", "Uygulama aracı parametre hatası: ${e.message}")
                .toString()
        }
    }
}
