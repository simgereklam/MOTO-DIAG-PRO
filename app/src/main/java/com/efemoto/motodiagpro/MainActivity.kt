
package com.efemoto.motodiagpro

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var mainLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(15, 20, 28)
        window.navigationBarColor = Color.rgb(15, 20, 28)

        createScreen()
    }

    private fun createScreen() {

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 35, 24, 24)
            setBackgroundColor(Color.rgb(15, 20, 28))
        }

        val title = TextView(this).apply {
            text = "MOTO DIAG PRO"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "YAMAHA MOTORCYCLE DIAGNOSTICS"
            textSize = 13f
            setTextColor(Color.rgb(0, 200, 150))
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "ADAPTÖR BEKLENİYOR"
            textSize = 16f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 35, 10, 35)
        }

        mainLayout.addView(title)
        mainLayout.addView(subtitle)
        mainLayout.addView(statusText)

        addButton("VIECAR BAĞLANTISI") {
            statusText.text =
                "Bağlantı modülü hazırlanıyor..."
        }

        addButton("ARIZA KODLARINI OKU") {
            statusText.text =
                "Önce ECU bağlantısı gerekli."
        }

        addButton("ARIZA KODLARINI SİL") {
            statusText.text =
                "Önce ECU bağlantısı gerekli."
        }

        addButton("CANLI MOTOR VERİLERİ") {
            statusText.text =
                "Canlı veri ekranı hazırlanıyor."
        }

        addButton("YAMAHA ARIZA KODLARI") {
            statusText.text =
                "Yamaha kod kataloğu hazırlanıyor."
        }

        setContentView(mainLayout)
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener {
                action()
            }
        }

        mainLayout.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                65
            ).apply {
                bottomMargin = 12
            }
        )
    }
}
