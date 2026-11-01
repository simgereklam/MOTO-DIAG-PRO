
package com.efemoto.motodiagpro

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView

    private val manager = DiagnosticManager()

    private var busy = false
    private var currentScreen = "home"
    private var lastFaults = emptyList<Any>()

    private val bg = Color.rgb(5, 10, 20)
    private val panel = Color.rgb(12, 23, 42)
    private val blue = Color.rgb(0, 145, 255)
    private val cyan = Color.rgb(0, 210, 255)
    private val green = Color.rgb(0, 220, 120)
    private val red = Color.rgb(255, 55, 65)
    private val white = Color.WHITE
    private val muted = Color.rgb(155, 180, 210)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        showHome()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeText(
        value: String,
        size: Float = 15f,
        color: Int = white,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun cardBackground(
        color: Int = panel,
        stroke: Int = blue
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun buttonBackground(color: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, Color.rgb(5, 35, 100))
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), cyan)
        }
    }

    private fun newColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun addSpace(target: LinearLayout, height: Int = 12) {
        target.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun addLabel(
        target: LinearLayout,
        value: String,
        size: Float = 16f,
        color: Int = white,
        bold: Boolean = false
    ) {
        target.addView(
            makeText(value, size, color, bold),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addButton(
        target: LinearLayout,
        label: String,
        color: Int = blue,
        action: () -> Unit
    ): TextView {
        val button = makeText(label, 16f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(15), dp(12), dp(15))
            background = buttonBackground(color)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!busy) action()
            }
        }

        target.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
        return button
    }

    private fun addPanel(
        target: LinearLayout,
        title: String
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground()
        }

        box.addView(makeText(title, 17f, cyan, true))
        addSpace(box, 10)

        target.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
        return box
    }

    private fun showBase(title: String) {
        currentScreen = title.lowercase(Locale.ROOT)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = cardBackground(bg, blue)
        }

        header.addView(
            makeText("✦  EFE MOTO", 24f, cyan, true)
        )
        header.addView(
            makeText("MOTOSİKLET ARIZA TEŞHİS SİSTEMİ", 11f, muted, true)
        )

        statusText = makeText("ADAPTÖR BEKLENİYOR", 12f, green, true)
        header.addView(statusText)

        root.addView(header)

        val scroll = ScrollView(this)
        content = newColumn()
        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(5), dp(3), dp(5))
            background = cardBackground(bg, blue)
        }

        addNav(nav, "⌂\nAna Sayfa") { showHome() }
        addNav(nav, "◉\nCanlı Veri") { showLiveData() }
        addNav(nav, "⚙\nTest") { showTests() }
        addNav(nav, "▤\nHata Dökümü") { showLogs() }

        root.addView(nav)
        setContentView(root)
    }

    private fun addNav(
        nav: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val item = makeText(label, 11f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(8), dp(3), dp(8))
            isClickable = true
            setOnClickListener { action() }
        }

        nav.addView(
            item,
            LinearLayout.LayoutParams(0, dp(55), 1f)
        )
    }

    // ANA SAYFA / AÇILIŞ

    private fun showHome() {
        showBase("home")

        addSpace(content, 24)

        val logo = addPanel(content, "EFE MOTO")
        addLabel(logo, "⚡", 46f, cyan, true)
        addLabel(logo, "ARIZA TEŞHİS", 27f, white, true)
        addLabel(logo, "Profesyonel Yamaha Diagnostik", 15f, muted)
        addSpace(logo, 15)
        addLabel(logo, "ECU • ARIZA • CANLI VERİ • SİSTEM TESTİ", 11f, cyan, true)

        addSpace(content, 24)

        val prompt = addPanel(content, "MOTOSİKLET TEŞHİSİ")
        addLabel(prompt, "Arıza teşhis başlatılsın mı?", 22f, white, true)
        addLabel(
            prompt,
            "Adaptör bağlantısı kurularak ECU taraması başlatılır.",
            14f,
            muted
        )

        addSpace(prompt, 20)

        addButton(prompt, "✓   EVET - TARAMAYI BAŞLAT", green) {
            startDiagnosis()
        }

        addButton(prompt, "✕   HAYIR - ŞİMDİLİK ÇIK", red) {
            statusText.text = "TEŞHİS BEKLEMEDE"
            prompt.removeAllViews()
            addLabel(prompt, "Teşhis başlatılmadı.", 18f, muted, true)
            addButton(prompt, "TEŞHİSE GERİ DÖN", blue) {
                showHome()
            }
        }

        addSpace(content, 12)

        addPanel(content, "SİSTEM ÖZELLİKLERİ").also {
            addLabel(it, "✓  ECU arıza kodu okuma", 14f)
            addLabel(it, "✓  Desteklenen arızaları silme", 14f)
            addLabel(it, "✓  Canlı motor verileri", 14f)
            addLabel(it, "✓  Devir saati ve sensör değerleri", 14f)
            addLabel(it, "✓  Hata geçmişi ve raporlama", 14f)
        }
    }

    // TARAMA

    private fun startDiagnosis() {
        if (busy) return

        showBase("scan")
        busy = true

        statusText.text = "VIECAR BAĞLANIYOR..."

        addSpace(content, 30)

        val panelView = addPanel(content, "ECU SİSTEM TARAMASI")

        addLabel(panelView, "MOTOSİKLET TEŞHİSİ BAŞLADI", 20f, cyan, true)
        addSpace(panelView, 20)

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        panelView.addView(progress)

        addSpace(panelView, 18)
        addLabel(panelView, "Viecar Wi-Fi adaptörüne bağlanılıyor...", 15f, white)

        lifecycleScope.launch {
            try {
                val connection = manager.connect()

                if (!connection.success) {
                    busy = false
                    statusText.text = "BAĞLANTI BAŞARISIZ"

                    panelView.removeAllViews()
                    addLabel(panelView, "ADAPTÖR BAĞLANAMADI", 21f, red, true)
                    addLabel(panelView, connection.message, 15f, white)
                    addButton(panelView, "TEKRAR DENE", blue) {
                        startDiagnosis()
                    }
                    return@launch
                }

                statusText.text = "ECU BAĞLANDI"
                panelView.removeAllViews()

                addLabel(panelView, "✓  VIECAR BAĞLANTISI KURULDU", 16f, green, true)
                addLabel(panelView, "ECU arıza kodları okunuyor...", 15f, cyan)

                val result = manager.readFaultCodes()

                busy = false
                panelView.removeAllViews()

                addLabel(panelView, "TARAMA TAMAMLANDI", 22f, green, true)
                addLabel(panelView, result.message, 15f, white)

                statusText.text = "TARAMA TAMAMLANDI"

                if (result.faults.isEmpty()) {
                    addSpace(panelView, 15)
                    addLabel(
                        panelView,
                        "AYRIŞTIRILABİLEN ARIZA KODU BULUNAMADI",
                        17f,
                        green,
                        true
                    )
                    addLabel(
                        panelView,
                        "Bu sonuç ECU'da kesinlikle arıza olmadığı anlamına gelmez.",
                        13f,
                        muted
                    )
                } else {
                    addLabel(
                        panelView,
                        "BULUNAN ARIZA SAYISI: ${result.faults.size}",
                        20f,
                        red,
                        true
                    )

                    result.faults.forEach { fault ->
                        val faultPanel = addPanel(panelView, "⚠  ${fault.code}")
                        addLabel(faultPanel, fault.title, 19f, red, true)
                        addLabel(faultPanel, fault.description, 15f, white)
                    }
                }

                addButton(panelView, "ARIZA DETAYLARINI GÖR", blue) {
                    showFaults(result.faults)
                }

                addButton(panelView, "CANLI MOTOR VERİLERİ", green) {
                    showLiveData()
                }

            } catch (e: Exception) {
                busy = false
                statusText.text = "TARAMA HATASI"
                panelView.removeAllViews()
                addLabel(panelView, "İŞLEM HATASI", 20f, red, true)
                addLabel(panelView, e.message ?: "Bilinmeyen hata", 14f)
            }
        }
    }

    // ARIZA DETAYLARI

    private fun showFaults(faults: List<*>) {
        showBase("faults")

        addSpace(content, 12)

        val heading = addPanel(content, "ARIZA RAPORU")
        addLabel(heading, "TESPİT EDİLEN ARIZALAR", 22f, red, true)
        addLabel(heading, "Toplam: ${faults.size}", 17f, white, true)

        if (faults.isEmpty()) {
            addLabel(heading, "Gösterilecek ayrıştırılmış arıza bulunamadı.", 14f, muted)
        }

        faults.forEach { item ->
            val fault = item as? FaultDisplay
            if (fault != null) {
                addFaultCard(
                    fault.code,
                    fault.title,
                    fault.description,
                    fault.possibleCauses,
                    fault.checks
                )
            } else {
                addLabel(content, item.toString(), 14f)
            }
        }

        val deletePanel = addPanel(content, "ARIZA SİLME")

        addLabel(
            deletePanel,
            "Tüm arızaları silmek için aşağıdaki butona uzun bas.",
            14f,
            muted
        )

        val deleteButton = addButton(
            deletePanel,
            "▣   TÜM ARIZALARI SİL\n(UZUN BAS)",
            red
        ) {
            statusText.text = "SİLME İÇİN UZUN BASIN"
        }

        deleteButton.setOnLongClickListener {
            if (!busy) clearAllFaults()
            true
        }
    }

    private fun addFaultCard(
        code: String,
        title: String,
        description: String,
        causes: List<String>,
        checks: List<String>
    ) {
        val box = addPanel(content, "⚠  $code")

        addLabel(box, title, 20f, red, true)
        addSpace(box, 8)
        addLabel(box, description, 16f, white)

        addSpace(box, 12)
        addLabel(box, "OLASI NEDENLER", 15f, cyan, true)

        causes.forEach {
            addLabel(box, "•  $it", 14f, white)
        }

        addSpace(box, 10)
        addLabel(box, "KONTROL ÖNERİLERİ", 15f, cyan, true)

        checks.forEach {
            addLabel(box, "✓  $it", 14f, white)
        }
    }

    private fun clearAllFaults() {
        busy = true
        statusText.text = "ARIZALAR SİLİNİYOR..."

        lifecycleScope.launch {
            try {
                val result = manager.clearFaultCodes()
                busy = false

                statusText.text =
                    if (result.success) "SİLME SONUCU BAŞARILI"
                    else "SONUÇ DOĞRULANAMADI"

                showBase("clear")
                val box = addPanel(content, "ARIZA SİLME SONUCU")

                addLabel(
                    box,
                    result.message,
                    18f,
                    if (result.success) green else red,
                    true
                )

                addLabel(box, "ECU YANITI:", 15f, cyan, true)
                addLabel(box, result.response, 14f, white)

                addLabel(
                    box,
                    "Sonucun kesinleşmesi için ECU'dan arıza kodları tekrar okunmalıdır.",
                    13f,
                    muted
                )

                addButton(box, "ARIZALARI TEKRAR OKU", blue) {
                    startDiagnosis()
                }

            } catch (e: Exception) {
                busy = false
                statusText.text = "SİLME HATASI"
            }
        }
    }

    // CANLI VERİ

    private fun showLiveData() {
        showBase("live")

        addSpace(content, 10)

        val gaugePanel = addPanel(content, "CANLI MOTOR VERİLERİ")

        addLabel(gaugePanel, "MOTOR DEVRİ", 15f, cyan, true)

        val gauge = RpmGaugeView(this)
        gaugePanel.addView(
            gauge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(225)
            )
        )

        addButton(gaugePanel, "CANLI VERİLERİ OKU", green) {
            readLiveData(gaugePanel, gauge)
        }

        val values = addPanel(content, "SENSÖR DEĞERLERİ")

        addLabel(values, "GAZ KELEBEĞİ: --", 17f)
        addLabel(values, "MOTOR SICAKLIĞI: --", 17f)
        addLabel(values, "EMME BASINCI: --", 17f)
        addLabel(values, "AKÜ VOLTAJI: --", 17f)

        addLabel(
            values,
            "Değerler ECU tarafından destekleniyorsa görüntülenir.",
            12f,
            muted
        )
    }

    private fun readLiveData(
        gaugePanel: LinearLayout,
        gauge: RpmGaugeView
    ) {
        if (busy) return

        busy = true
        statusText.text = "CANLI VERİ OKUNUYOR..."

        lifecycleScope.launch {
            try {
                val result = manager.readLiveData()
                busy = false

                val data = result.data
                gaugePanel.removeAllViews()

                addLabel(gaugePanel, "MOTOR DEVRİ", 15f, cyan, true)

                if (data == null) {
                    addLabel(gaugePanel, result.message, 15f, red)
                    return@launch
                }

                val rpm = data.rpm ?: 0
                val newGauge = RpmGaugeView(this@MainActivity).apply {
                    value = rpm.toFloat()
                }

                gaugePanel.addView(
                    newGauge,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(225)
                    )
                )

                addLabel(gaugePanel, "DEVİR: ${data.rpm?.let { "$it RPM" } ?: "Desteklenmiyor"}", 18f, white, true)

                addLabel(
                    gaugePanel,
                    "GAZ KELEBEĞİ: ${data.throttle?.let { String.format(Locale.US, "%.1f %%", it) } ?: "Desteklenmiyor"}",
                    16f
                )

                addLabel(
                    gaugePanel,
                    "MOTOR SICAKLIĞI: ${data.coolantTemperature?.let { "$it °C" } ?: "Desteklenmiyor"}",
                    16f
                )

                addLabel(
                    gaugePanel,
                    "EMME BASINCI: ${data.intakePressure?.let { "$it kPa" } ?: "Desteklenmiyor"}",
                    16f
                )

                addLabel(
                    gaugePanel,
                    "AKÜ VOLTAJI: ${data.batteryVoltage?.let { "$it V" } ?: "Desteklenmiyor"}",
                    16f
                )

                statusText.text = "CANLI VERİ OKUNDU"

            } catch (e: Exception) {
                busy = false
                statusText.text = "VERİ OKUMA HATASI"
            }
        }
    }

    // SİSTEM TESTLERİ

    private fun showTests() {
        showBase("tests")

        addSpace(content, 10)

        val box = addPanel(content, "SİSTEM TESTLERİ")

        addLabel(box, "MOTOSİKLET SİSTEM KONTROLLERİ", 19f, cyan, true)
        addSpace(box, 12)

        addLabel(box, "•  ECU iletişim kontrolü", 16f)
        addLabel(box, "•  Arıza kodu okuma", 16f)
        addLabel(box, "•  Canlı sensör verileri", 16f)
        addLabel(box, "•  Akü voltajı kontrolü", 16f)

        addSpace(box, 16)

        addLabel(
            box,
            "Enjektör, yakıt pompası, ABS ve aktüatör testleri ancak ilgili Yamaha ECU ve adaptör tarafından desteklendiği doğrulanırsa etkinleştirilecektir.",
            14f,
            muted
        )

        addButton(box, "ECU BAĞLANTISINI KONTROL ET", blue) {
            startDiagnosis()
        }

        addButton(box, "CANLI SENSÖR VERİLERİ", green) {
            showLiveData()
        }
    }

    // HATA DÖKÜMÜ

    private fun showLogs() {
        showBase("logs")

        addSpace(content, 10)

        val box = addPanel(content, "HATA DÖKÜMÜ")

        addLabel(box, "TEŞHİS VE İŞLEM KAYITLARI", 19f, cyan, true)
        addSpace(box, 12)

        addLabel(box, "Bu bölümde teşhis sonuçları, ECU yanıtları ve silme işlemleri listelenecek.", 15f, white)

        addSpace(box, 15)

        addButton(box, "SON ARIZA TARAMASINI AÇ", blue) {
            startDiagnosis()
        }

        addButton(box, "CANLI VERİ EKRANINI AÇ", green) {
            showLiveData()
        }
    }

    override fun onDestroy() {
        manager.close()
        super.onDestroy()
    }

    private class RpmGaugeView(
        context: android.content.Context
    ) : View(context) {

        var value: Float = 0f
            set(newValue) {
                field = newValue.coerceIn(0f, 12000f)
                invalidate()
            }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 15f
            strokeCap = Paint.Cap.ROUND
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height * 0.78f
            val radius = minOf(width * 0.38f, height * 0.66f)
            val rect = RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

            arcPaint.color = Color.rgb(30, 55, 90)
            canvas.drawArc(rect, 135f, 270f, false, arcPaint)

            arcPaint.color = Color.rgb(0, 170, 255)
            canvas.drawArc(
                rect,
                135f,
                270f * (value / 12000f),
                false,
                arcPaint
            )

            val angle = Math.toRadians(
                (135f + 270f * (value / 12000f)).toDouble()
            )

            val needleLength = radius * 0.78f
            val endX = cx + kotlin.math.cos(angle).toFloat() * needleLength
            val endY = cy + kotlin.math.sin(angle).toFloat() * needleLength

            val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
            }

            canvas.drawLine(cx, cy, endX, endY, needle)

            textPaint.textSize = 38f
            canvas.drawText(
                value.toInt().toString(),
                cx,
                cy - radius * 0.15f,
                textPaint
            )

            textPaint.textSize = 17f
            canvas.drawText("RPM", cx, cy + radius * 0.15f, textPaint)

            textPaint.textSize = 12f
            for (i in 0..6) {
                val angleTick = Math.toRadians((135 + i * 45).toDouble())
                val x = cx + kotlin.math.cos(angleTick).toFloat() * (radius + 22f)
                val y = cy + kotlin.math.sin(angleTick).toFloat() * (radius + 22f)
                canvas.drawText((i * 2).toString(), x, y, textPaint)
            }
        }
    }

    private interface FaultDisplay {
        val code: String
        val title: String
        val description: String
        val possibleCauses: List<String>
        val checks: List<String>
    }
}
