
package com.efemoto.motodiagpro

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : Activity() {

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

            setBusy(false, if (result.success) {
                "ADAPTÖR BAĞLANDI"
            } else {
                "BAĞLANTI BAŞARISIZ"
            })

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

            resultText.text =
                result.message + "\n\nECU YANITI:\n" +
                (result.response.ifBlank { "Yanıt alınamadı." })
        }
    }

    private fun clearFaultCodes() {
        setBusy(true, "ARIZA SİLME İŞLEMİ...")

        lifecycleScope.launch {
            val result = diagnosticManager.clearFaultCodes()

            setBusy(false, if (result.success) {
                "KONTROL TAMAMLANDI"
            } else {
                "SONUÇ DOĞRULANAMADI"
            })

            resultText.text =
                result.message + "\n\nECU YANITI:\n" +
                (result.response.ifBlank { "Yanıt alınamadı." })
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

                DEVİR: ${data.rpm?.let { "$it RPM" } ?: "Desteklenmiyor"}

                GAZ KELEBEĞİ: ${data.throttle?.let { "%.1f %%".format(it) } ?: "Desteklenmiyor"}

                MOTOR SICAKLIĞI: ${data.coolantTemperature?.let { "$it °C" } ?: "Desteklenmiyor"}

                EMME BASINCI: ${data.intakePressure?.let { "$it kPa" } ?: "Desteklenmiyor"}

                AKÜ VOLTAJI: ${data.batteryVoltage?.let { "$it V" } ?: "Desteklenmiyor"}
                """.trimIndent()
            }
        }
    }

    override fun onDestroy() {
        diagnosticManager.close()
        super.onDestroy()
    }
}
