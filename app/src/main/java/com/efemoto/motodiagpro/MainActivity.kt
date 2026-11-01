
package com.efemoto.motodiagpro

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var mainLayout: LinearLayout

    private val diagnosticManager = DiagnosticManager()

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(15, 20, 28)
        window.navigationBarColor = Color.rgb(15, 20, 28)

        createScreen()
    }

    private fun createScreen() {

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(Color.rgb(15, 20, 28))
        }

        val title = TextView(this).apply {
            text = "MOTO DIAG PRO"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 5)
        }

        val subtitle = TextView(this).apply {
            text = "YAMAHA MOTORCYCLE DIAGNOSTICS"
            textSize = 13f
            setTextColor(Color.rgb(0, 200, 150))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 25)
        }

        statusText = TextView(this).apply {
            text = "ADAPTÖR BEKLENİYOR"
            textSize = 16f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        mainLayout.addView(title)
        mainLayout.addView(subtitle)
        mainLayout.addView(statusText)

        addButton("VIECAR WI-FI BAĞLANTISI") {
            connectAdapter()
        }

        addButton("BAĞLANTIYI KES") {
            disconnectAdapter()
        }

        addButton("ARIZA KODLARINI OKU") {
            readFaultCodes()
        }

        addButton("ARIZA KODLARINI SİL") {
            clearFaultCodes()
        }

        addButton("CANLI MOTOR VERİLERİ") {
            readLiveData()
        }

        addButton("YAMAHA ARIZA KOD KATALOĞU") {
            showFaultCatalog()
        }

        resultText = TextView(this).apply {
            text = "İşlem sonuçları burada görüntülenecek."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(12, 25, 12, 25)
        }

        mainLayout.addView(resultText)

        val scrollView = ScrollView(this).apply {
            addView(mainLayout)
        }

        setContentView(scrollView)
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false

            setOnClickListener {
                if (!busy) action()
            }
        }

        mainLayout.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                62
            ).apply {
                bottomMargin = 10
            }
        )
    }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        statusText.text = message
    }

    private fun connectAdapter() {
        setBusy(true, "VIECAR BAĞLANIYOR...")
        resultText.text = "192.168.0.10:35000 adresi deneniyor."

        lifecycleScope.launch {
            val result = diagnosticManager.connect()

            setBusy(
                false,
                if (result.success) "ADAPTÖR BAĞLANDI"
                else "BAĞLANTI BAŞARISIZ"
            )

            resultText.text =
                result.message + "\n\n" + result.response
        }
    }

    private fun disconnectAdapter() {
        setBusy(true, "BAĞLANTI KAPATILIYOR...")

        lifecycleScope.launch {
            val result = diagnosticManager.disconnect()

            setBusy(false, "ADAPTÖR BEKLENİYOR")
            resultText.text = result.message
        }
    }

    private fun readFaultCodes() {
        setBusy(true, "ARIZA KODLARI OKUNUYOR...")

        lifecycleScope.launch {
            val result = diagnosticManager.readFaultCodes()

            setBusy(false, "ARIZA OKUMA TAMAMLANDI")

            val output = StringBuilder()
            output.appendLine(result.message)
            output.appendLine()

            if (result.faults.isEmpty()) {
                output.appendLine("Ayrıştırılmış arıza kodu bulunamadı.")
            }

            result.faults.forEach { fault ->
                output.appendLine("KOD: ${fault.code}")
                output.appendLine("ARIZA: ${fault.title}")
                output.appendLine(fault.description)
                output.appendLine()
                output.appendLine("OLASI NEDENLER:")

                fault.possibleCauses.forEach {
                    output.appendLine("• $it")
                }

                output.appendLine()
                output.appendLine("KONTROL ÖNERİLERİ:")

                fault.checks.forEach {
                    output.appendLine("• $it")
                }

                output.appendLine()
                output.appendLine("--------------------------")
                output.appendLine()
            }

            output.appendLine("HAM ECU YANITI:")
            output.appendLine(result.rawResponse)

            resultText.text = output.toString()
        }
    }

    private fun clearFaultCodes() {
        setBusy(true, "ARIZA SİLME İŞLEMİ...")

        lifecycleScope.launch {
            val result = diagnosticManager.clearFaultCodes()

            setBusy(
                false,
                if (result.success) "İŞLEM TAMAMLANDI"
                else "SONUÇ DOĞRULANAMADI"
            )

            resultText.text =
                result.message + "\n\nECU YANITI:\n" +
                result.response
        }
    }

    private fun readLiveData() {
        setBusy(true, "MOTOR VERİLERİ OKUNUYOR...")

        lifecycleScope.launch {
            val result = diagnosticManager.readLiveData()

            setBusy(false, "CANLI VERİ OKUMA TAMAMLANDI")

            val data = result.data

            resultText.text = if (data == null) {
                result.message
            } else {
                """
                ${result.message}

                DEVİR:
                ${data.rpm?.let { "$it RPM" } ?: "Desteklenmiyor"}

                GAZ KELEBEĞİ:
                ${data.throttle?.let { String.format(Locale.US, "%.1f %%", it) } ?: "Desteklenmiyor"}

                MOTOR SICAKLIĞI:
                ${data.coolantTemperature?.let { "$it °C" } ?: "Desteklenmiyor"}

                EMME BASINCI:
                ${data.intakePressure?.let { "$it kPa" } ?: "Desteklenmiyor"}

                AKÜ VOLTAJI:
                ${data.batteryVoltage?.let { "$it V" } ?: "Desteklenmiyor"}
                """.trimIndent()
            }
        }
    }

    private fun showFaultCatalog() {
        val output = StringBuilder()

        output.appendLine("YAMAHA ARIZA KOD KATALOĞU")
        output.appendLine()

        YamahaFaultCodes.all().forEach { fault ->
            output.appendLine("${fault.code} - ${fault.title}")
            output.appendLine(fault.description)
            output.appendLine()
        }

        resultText.text = output.toString()
        statusText.text = "ARIZA KATALOĞU"
    }

    override fun onDestroy() {
        diagnosticManager.close()
        super.onDestroy()
    }
}
