package com.efemoto.motodiagpro

import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(2, 7, 18)
    private val panel = Color.rgb(4, 15, 34)
    private val panel2 = Color.rgb(5, 23, 51)
    private val blue = Color.rgb(0, 113, 255)
    private val cyan = Color.rgb(0, 220, 255)
    private val green = Color.rgb(0, 235, 95)
    private val red = Color.rgb(255, 35, 55)
    private val yellow = Color.rgb(255, 220, 0)
    private val white = Color.rgb(238, 246, 255)
    private val muted = Color.rgb(145, 170, 205)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var connected = false
    @Volatile private var lastRaw = ""
    private var currentScreen = "HOME"
    private var faults: MutableList<String> = mutableListOf()
    private var selectedFault = ""
    private val logs = mutableListOf<String>()
    private var rpm = -1
    private var tps = -1.0
    private var coolant = -999
    private var mapKpa = -1
    private var voltage = -1.0
    private var liveLoop = false
    private var liveView: LinearLayout? = null
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var scanPercent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        showHome()
    }

    override fun onDestroy() {
        liveLoop = false
        closeAdapter()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")).format(Date())
        logs.add(0, "$time  $message")
        if (logs.size > 250) logs.removeAt(logs.lastIndex)
    }

    private fun baseScreen(title: String, showNav: Boolean = true): LinearLayout {
        currentScreen = title
        liveLoop = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val body = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        root.addView(scroll)
        if (showNav) root.addView(bottomNav(title), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
        return bodyContainer(body)
    }

    // Returns a vertical content column mounted inside the supplied frame.
    private fun bodyContainer(frame: FrameLayout): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
            layoutParams = FrameLayout.LayoutParams(-1, -2)
        }
        frame.addView(NeonBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        frame.addView(column)
        return column
    }

    private fun bottomNav(active: String): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0, 8, 22))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val entries = listOf(
            Triple("HOME", "⌂", "Ana Sayfa"),
            Triple("LIVE", "⌁", "Canlı Veri"),
            Triple("TESTS", "⚒", "Test"),
            Triple("LOGS", "▤", "Hata Dökümü")
        )
        entries.forEach { (key, symbol, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(4), dp(3), dp(3))
                background = rounded(if (active == key) Color.rgb(0, 36, 88) else Color.TRANSPARENT,
                    if (active == key) cyan else Color.rgb(18, 52, 94), 10)
                setOnClickListener {
                    when (key) {
                        "HOME" -> showHome()
                        "LIVE" -> showLiveData()
                        "TESTS" -> showTests()
                        "LOGS" -> showLogs()
                    }
                }
            }
            val icon = text(symbol, 25, if (active == key) cyan else muted, true).apply { gravity = Gravity.CENTER }
            val caption = text(label, 10, if (active == key) cyan else muted, true).apply { gravity = Gravity.CENTER }
            item.addView(icon)
            item.addView(caption)
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        return bar
    }

    private fun header(subtitle: String? = null): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        line.addView(text("✺", 27, cyan, true))
        line.addView(text(" EFE MOTO", 25, white, true))
        box.addView(line)
        if (!subtitle.isNullOrBlank()) box.addView(text(subtitle, 12, cyan, true).apply { gravity = Gravity.CENTER })
        val status = text(if (connected) "● VIECAR BAĞLI" else "● ADAPTÖR BEKLENİYOR", 11,
            if (connected) green else muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }
        statusLine = status
        box.addView(status)
        return box
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun neonCard(title: String? = null, accent: Int = cyan): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(13))
            background = rounded(panel, accent, 15)
            elevation = dp(5).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(9)) }
        }
        if (!title.isNullOrBlank()) {
            card.addView(text(title, 15, accent, true).apply { setPadding(0, 0, 0, dp(8)) })
        }
        return card
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView = text(label, 17, white, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(57)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.rgb(0, 28, 78), color, 14)
        elevation = dp(5).toFloat()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun addGap(parent: LinearLayout, h: Int = 8) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    private fun showHome() {
        val c = baseScreen("HOME", false)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(header("PROFESYONEL MOTOSİKLET ARIZA TEŞHİSİ"))
        val bikeCard = neonCard(null, blue).apply { setPadding(dp(6), dp(7), dp(6), dp(7)) }
        bikeCard.addView(MotorcycleArtView(this), LinearLayout.LayoutParams(-1, dp(300)))
        c.addView(bikeCard)
        addGap(c, 7)
        c.addView(text("ARIZA TEŞHİS BAŞLATILSIN MI?", 20, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(12), dp(5), dp(13))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(actionButton("✓  EVET", green) { startDiagnosis() }, LinearLayout.LayoutParams(0, dp(66), 1f).apply { setMargins(0, 0, dp(6), 0) })
        buttons.addView(actionButton("✕  HAYIR", red) { showExitMessage() }, LinearLayout.LayoutParams(0, dp(66), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        c.addView(buttons)
        addGap(c, 12)
        c.addView(text("VIECAR Wi‑Fi  •  192.168.0.10:35000", 11, muted).apply { gravity = Gravity.CENTER })
        c.addView(text("Gerçek ECU yanıtı alınmadan değer gösterilmez.", 10, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun showExitMessage() {
        AlertDialog.Builder(this).setTitle("EFE MOTO").setMessage("Teşhis başlatılmadı.")
            .setPositiveButton("TAMAM", null).show()
    }

    private fun startDiagnosis() {
        val c = baseScreen("SCAN", false)
        c.addView(header("ECU AKILLI TARAMA"))
        val card = neonCard(null, cyan)
        val progress = CircularProgressView(this)
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(225)))
        progressLine = text("ECU bağlantısı bekleniyor…", 19, white, true).apply { gravity = Gravity.CENTER }
        card.addView(progressLine)
        addGap(card, 10)
        val steps = listOf("Wi‑Fi adaptörüne bağlanılıyor", "Motor kontrol ünitesi aranıyor", "Desteklenen PID listesi okunuyor", "Arıza kodları sorgulanıyor")
        steps.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(text("◌", 22, cyan, true))
            row.addView(text("  $s", 14, white))
            card.addView(row)
        }
        c.addView(card)
        c.addView(actionButton("TARAMAYI İPTAL ET", red) { closeAdapter(); showHome() })
        io.execute {
            try {
                updateScan(8, "Viecar adaptörüne bağlanılıyor…")
                ensureConnected()
                updateScan(35, "ECU yanıtı kontrol ediliyor…")
                val support = elm("0100")
                if (support.contains("NO DATA") || support.contains("UNABLE") || support.contains("ERROR") || support.contains("?")) {
                    throw IllegalStateException("ECU yanıt vermedi: $support")
                }
                updateScan(68, "Arıza kodları okunuyor…")
                val raw = elm("03")
                val found = decodeDtcs(raw)
                updateScan(100, "Tarama tamamlandı")
                mainHandler.post {
                    faults = found.toMutableList()
                    log("ECU taraması tamamlandı • ${faults.size} arıza")
                    showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    AlertDialog.Builder(this).setTitle("BAĞLANTI / TARAMA BAŞARISIZ")
                        .setMessage(e.message ?: "Bilinmeyen bağlantı hatası")
                        .setPositiveButton("TEKRAR DENE") { _, _ -> startDiagnosis() }
                        .setNegativeButton("ANA SAYFA") { _, _ -> showHome() }.show()
                }
            }
        }
    }

    private fun updateScan(percent: Int, message: String) {
        scanPercent = percent
        mainHandler.post {
            progressLine?.text = "$message  •  %$percent"
            (progressLine?.parent as? LinearLayout)?.let { parent ->
                val p = parent.getChildAt(0) as? CircularProgressView
                p?.progress = percent
                p?.invalidate()
            }
        }
    }

    private fun showFaults() {
        val c = baseScreen("FAULTS")
        c.addView(header("TÜM ARIZALAR  (${faults.size})"))
        if (faults.isEmpty()) {
            val ok = neonCard("✓  ARIZA BULUNAMADI", green)
            ok.addView(text("ECU, standart OBD arıza sorgusuna kayıtlı kod döndürmedi.", 15, white))
            c.addView(ok)
        } else {
            faults.forEach { code ->
                val card = neonCard(null, red)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text("⊗", 28, red, true))
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                labels.addView(text(code, 20, white, true))
                labels.addView(text(faultTitle(code), 13, muted))
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 34, cyan, true))
                card.addView(row)
                card.setOnClickListener { selectedFault = code; showFaultDetail() }
                c.addView(card)
            }
        }
        addGap(c, 10)
        c.addView(actionButton("▤  TÜMÜNÜ SİL  (UZUN BASIŞ)", red) {
            confirmClearFaults()
        })
        c.getChildAt(c.childCount - 1).setOnLongClickListener {
            confirmClearFaults(); true
        }
        addGap(c, 5)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showFaultDetail() {
        val c = baseScreen("DETAIL")
        c.addView(header("ARIZA TEŞHİS RAPORU"))
        val card = neonCard(null, red)
        card.addView(text("⚠  ARIZA BULUNDU", 22, red, true).apply { gravity = Gravity.CENTER })
        addGap(card, 8)
        card.addView(text(selectedFault, 31, white, true).apply { gravity = Gravity.CENTER })
        card.addView(text(faultTitle(selectedFault), 18, cyan, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(7)) })
        card.addView(text(faultDescription(selectedFault), 14, white))
        c.addView(card)
        val causes = neonCard("OLASI NEDENLER", yellow)
        faultCauses(selectedFault).forEach { causes.addView(text("➜  $it", 14, white).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        c.addView(causes)
        val checks = neonCard("KONTROL ÖNERİLERİ", cyan)
        faultChecks(selectedFault).forEach { checks.addView(text("⌕  $it", 14, white).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        c.addView(checks)
        c.addView(actionButton("☷  TÜM ARIZALARI GÖRÜNTÜLE", blue) { showFaults() })
        addGap(c, 7)
        c.addView(actionButton("⌂  KAPAT", blue) { showHome() })
    }

    private fun faultTitle(code: String): String = when (code) {
        "P0335" -> "Krank Mili Pozisyon Sensörü Arızası"
        "P0115" -> "Motor Soğutma Suyu Sıcaklık Devresi"
        "P0120" -> "Gaz Kelebeği Konum Sensörü Devresi"
        "P0105" -> "Manifold Mutlak Basınç Sensörü Devresi"
        "P0130" -> "Oksijen Sensörü Devresi"
        "P0171" -> "Yakıt Sistemi Çok Fakir"
        "P0505" -> "Rölanti Kontrol Sistemi"
        else -> "Standart OBD arıza kodu"
    }

    private fun faultDescription(code: String): String = when (code) {
        "P0335" -> "ECU, krank mili konum sensöründen beklenen sinyali alamıyor veya sinyalde kesinti algılıyor."
        "P0115" -> "Motor sıcaklık sensörü devresinde elektriksel aralık veya bağlantı sorunu algılandı."
        "P0120" -> "Gaz kelebeği konum sensörü devresinde beklenmeyen sinyal algılandı."
        "P0105" -> "Manifold basınç sensörü devresinde sinyal sorunu algılandı."
        "P0130" -> "Oksijen sensörü sinyal devresinde arıza algılandı."
        "P0171" -> "ECU, karışım düzeltmesinin fakir karışım yönünde sınırı aştığını algıladı."
        "P0505" -> "Rölanti hava kontrol sisteminde beklenen kontrol davranışı alınamadı."
        else -> "Kod, ECU tarafından standart OBD formatında döndürüldü. Kesin teşhis için motosikletin model/yıl servis dokümanı ve kablo ölçümü gerekir."
    }

    private fun faultCauses(code: String): List<String> = when (code) {
        "P0335" -> listOf("Krank konum sensörü arızası", "Sensör kablosunda kopukluk veya kısa devre", "Sokette oksitlenme / gevşek bağlantı", "Sensör ile tetik çarkı aralığı")
        "P0115" -> listOf("Sıcaklık sensörü arızası", "Soket veya kablo temassızlığı", "Şase / besleme sorunu")
        "P0120" -> listOf("TPS sensörü veya gaz kelebeği gövdesi", "Soket pinlerinde temas sorunu", "Referans voltajı veya şase sorunu")
        else -> listOf("Sensör veya ilgili devrede arıza", "Kablo / soket bağlantısı", "Besleme, şase veya mekanik sorun")
    }

    private fun faultChecks(code: String): List<String> = when (code) {
        "P0335" -> listOf("Krank sensörü soketini ve kablo hattını inceleyin", "Sensör direncini servis değerleriyle karşılaştırın", "Tetik çarkı ve sensör aralığını kontrol edin", "Onarımdan sonra kodu tekrar okuyun")
        else -> listOf("İlgili sensör soketini ve kabloları kontrol edin", "Besleme ve şaseyi ölçün", "Sensör sinyalini servis dokümanına göre doğrulayın", "Onarımdan sonra arıza kodunu tekrar okuyun")
    }

    private fun confirmClearFaults() {
        AlertDialog.Builder(this).setTitle("ARIZALARI SİL")
            .setMessage("Kontak açık, motor kapalı olmalı. ECU destekliyorsa standart OBD silme komutu gönderilecek ve ardından kodlar yeniden okunarak doğrulanacak. Devam edilsin mi?")
            .setNegativeButton("VAZGEÇ", null)
            .setPositiveButton("SİL VE DOĞRULA") { _, _ -> clearFaults() }.show()
    }

    private fun clearFaults() {
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            try {
                ensureConnected()
                val response = elm("04")
                if (!response.contains("44")) throw IllegalStateException("ECU silme komutunu onaylamadı: $response")
                Thread.sleep(800)
                val verify = elm("03")
                val remaining = decodeDtcs(verify)
                mainHandler.post {
                    if (remaining.isEmpty()) {
                        faults.clear()
                        log("Arıza silme komutu onaylandı ve tekrar okumada kod bulunmadı")
                        AlertDialog.Builder(this).setTitle("✓  İŞLEM DOĞRULANDI")
                            .setMessage("ECU arıza silme komutunu onayladı. Yeniden okumada kayıtlı standart OBD arıza kodu bulunmadı.")
                            .setPositiveButton("TAMAM") { _, _ -> showFaults() }.show()
                    } else {
                        faults = remaining.toMutableList()
                        log("Silme sonrası kalan arızalar: ${remaining.joinToString()}")
                        AlertDialog.Builder(this).setTitle("BAZI ARIZALAR KALDI")
                            .setMessage("ECU tekrar okundu. Kalan kodlar: ${remaining.joinToString()}")
                            .setPositiveButton("GÖRÜNTÜLE") { _, _ -> showFaults() }.show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Arıza silme başarısız: ${e.message}")
                    AlertDialog.Builder(this).setTitle("SİLME BAŞARISIZ")
                        .setMessage(e.message ?: "Bilinmeyen hata")
                        .setPositiveButton("TAMAM", null).show()
                }
            } finally { busy.set(false) }
        }
    }

    private fun showLiveData() {
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ"))
        val gaugeCard = neonCard(null, cyan)
        val gauge = RpmGaugeView(this).apply { value = rpm.coerceAtLeast(0) }
        gaugeCard.addView(gauge, LinearLayout.LayoutParams(-1, dp(245)))
        liveView = gaugeCard
        c.addView(gaugeCard)
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        fun metric(label: String, value: String, color: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = rounded(panel2, color, 12)
            addView(text(label, 12, muted, true))
            addView(text(value, 20, white, true).apply { tag = label })
        }
        grid.addView(metric("GAZ KELEBEĞİ", if (tps >= 0) "%.1f %%".format(Locale.US, tps) else "—", green), GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(0, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        grid.addView(metric("MOTOR SICAKLIĞI", if (coolant > -100) "$coolant °C" else "—", red), GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(1, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        grid.addView(metric("EMME BASINCI", if (mapKpa >= 0) "$mapKpa kPa" else "—", cyan), GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(0, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        grid.addView(metric("AKÜ VOLTAJI", if (voltage >= 0) "%.2f V".format(Locale.US, voltage) else "—", green), GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(1, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        c.addView(grid)
        addGap(c, 9)
        c.addView(actionButton("↻  CANLI VERİYİ BAŞLAT / YENİLE", blue) { startLiveRead() })
        addGap(c, 6)
        c.addView(text("Yalnızca ECU'dan yanıt alınan PID değerleri gösterilir. Desteklenmeyen veriler — olarak kalır.", 11, muted))
        startLiveRead()
    }

    private fun startLiveRead() {
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            try {
                ensureConnected()
                val values = readLivePids()
                mainHandler.post {
                    rpm = values["rpm"]?.toInt() ?: -1
                    tps = values["tps"] ?: -1.0
                    coolant = values["coolant"]?.toInt() ?: -999
                    mapKpa = values["map"]?.toInt() ?: -1
                    voltage = values["voltage"] ?: -1.0
                    log("Canlı ECU verileri yenilendi")
                    if (currentScreen == "LIVE") showLiveDataNoRestart()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Canlı veri okunamadı: ${e.message}")
                    Toast.makeText(this, "Canlı veri okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally { busy.set(false) }
        }
    }

    private fun showLiveDataNoRestart() {
        // Redraw values without starting a second polling request.
        val wasLive = liveLoop
        showLiveDataContentsOnly()
        liveLoop = wasLive
    }

    private fun showLiveDataContentsOnly() {
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ"))
        val gaugeCard = neonCard(null, cyan)
        gaugeCard.addView(RpmGaugeView(this).apply { value = rpm.coerceAtLeast(0) }, LinearLayout.LayoutParams(-1, dp(245)))
        c.addView(gaugeCard)
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        val items = listOf(
            Triple("GAZ KELEBEĞİ", if (tps >= 0) "%.1f %%".format(Locale.US, tps) else "—", green),
            Triple("MOTOR SICAKLIĞI", if (coolant > -100) "$coolant °C" else "—", red),
            Triple("EMME BASINCI", if (mapKpa >= 0) "$mapKpa kPa" else "—", cyan),
            Triple("AKÜ VOLTAJI", if (voltage >= 0) "%.2f V".format(Locale.US, voltage) else "—", green)
        )
        items.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(11), dp(10), dp(11)); background = rounded(panel2, item.third, 12)
                addView(text(item.first, 12, muted, true)); addView(text(item.second, 20, white, true))
            }
            grid.addView(card, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(index % 2, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        c.addView(grid)
        addGap(c, 8)
        c.addView(actionButton("↻  CANLI VERİYİ YENİLE", blue) { startLiveRead() })
        c.addView(text("ECU yanıtı olmayan değerler gösterilmez.", 11, muted))
    }

    private fun readLivePids(): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        pidBytes("0C")?.let { if (it.size >= 2) out["rpm"] = ((it[0] * 256 + it[1]) / 4.0) }
        pidBytes("11")?.firstOrNull()?.let { out["tps"] = it * 100.0 / 255.0 }
        pidBytes("05")?.firstOrNull()?.let { out["coolant"] = (it - 40).toDouble() }
        pidBytes("0B")?.firstOrNull()?.let { out["map"] = it.toDouble() }
        pidBytes("42")?.takeIf { it.size >= 2 }?.let { out["voltage"] = (it[0] * 256 + it[1]) / 1000.0 }
        return out
    }

    private fun showTests() {
        val c = baseScreen("TESTS")
        c.addView(header("SİSTEM TESTLERİ"))
        val intro = neonCard("ECU / ADAPTÖR KONTROLÜ", cyan)
        intro.addView(text("Bu ekrandaki kontroller standart OBD yanıtlarıyla sınırlıdır. Enjektör, yakıt pompası veya aktüatör tetikleme testleri motosikletin özel servis protokolü desteklenmeden çalıştırılmaz.", 13, white))
        c.addView(intro)
        val tests = listOf(
            "Viecar adaptör bağlantısı" to "Wi‑Fi soket bağlantısı ve ELM yanıtı",
            "ECU iletişim testi" to "0100 desteklenen PID sorgusu",
            "Sensör yanıt testi" to "RPM / TPS / sıcaklık / MAP / voltaj",
            "Arıza hafızası testi" to "DTC okuma ve silme komutu yanıtı"
        )
        tests.forEach { (name, desc) ->
            val card = neonCard(null, blue)
            card.addView(text("◎  $name", 16, white, true))
            card.addView(text(desc, 12, muted))
            c.addView(card)
        }
        c.addView(actionButton("▶  BAĞLANTI VE ECU TESTİNİ ÇALIŞTIR", green) { runSystemCheck() })
        addGap(c, 8)
        c.addView(actionButton("ARIZA KODLARINI OKU", blue) { startDiagnosis() })
    }

    private fun runSystemCheck() {
        if (!busy.compareAndSet(false, true)) return
        Toast.makeText(this, "ECU kontrolü başlatıldı…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                ensureConnected()
                val response = elm("0100")
                val pids = readLivePids()
                mainHandler.post {
                    log("ECU testi: adaptör bağlantısı ve 0100 yanıtı alındı")
                    AlertDialog.Builder(this).setTitle("✓  ECU YANIT VERDİ")
                        .setMessage("Adaptör: Bağlı\n0100 yanıtı: $response\n\nAlınabilen canlı PID: ${pids.keys.joinToString().ifBlank { "Henüz yanıt yok" }}\n\nBu test enjektör / pompa gibi özel aktüatör testlerini doğrulamaz.")
                        .setPositiveButton("TAMAM", null).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("ECU testi başarısız: ${e.message}")
                    AlertDialog.Builder(this).setTitle("TEST BAŞARISIZ").setMessage(e.message)
                        .setPositiveButton("TAMAM", null).show()
                }
            } finally { busy.set(false) }
        }
    }

    private fun showLogs() {
        val c = baseScreen("LOGS")
        c.addView(header("HATA DÖKÜMÜ / İŞLEM KAYITLARI"))
        val card = neonCard("SON İŞLEMLER VE HATA KAYITLARI", cyan)
        if (logs.isEmpty()) card.addView(text("Henüz işlem kaydı yok.", 14, muted))
        else logs.forEach { line -> card.addView(text("•  $line", 12, white).apply { setPadding(0, dp(5), 0, dp(5)) }) }
        c.addView(card)
        c.addView(actionButton("↻  KAYITLARI YENİLE", blue) { showLogs() })
        addGap(c, 7)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    // ---------------- Viecar / ELM327 Wi-Fi ----------------

    private fun ensureConnected() {
        if (connected && socket?.isConnected == true && socket?.isClosed == false) return
        closeAdapter()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress("192.168.0.10", 35000), 5000)
        s.soTimeout = 1300
        socket = s
        // Reset and configure the ELM327. No ECU actuator or programming commands are sent.
        elm("ATZ", 3500)
        elm("ATE0")
        elm("ATL0")
        elm("ATS0")
        elm("ATH0")
        elm("ATSP0")
        val test = elm("0100")
        if (test.contains("UNABLE") || test.contains("ERROR") || test.contains("?")) {
            closeAdapter()
            throw IllegalStateException("Viecar adaptörü yanıt verdi ancak ECU protokolü kurulamadı: $test")
        }
        connected = true
        mainHandler.post { statusLine?.apply { text = "● VIECAR / ECU BAĞLI"; setTextColor(green) } }
        log("Viecar Wi‑Fi bağlantısı kuruldu")
    }

    @Synchronized
    private fun elm(command: String, timeoutMs: Int = 1800): String {
        val s = socket ?: throw IllegalStateException("Adaptör bağlı değil")
        if (s.isClosed || !s.isConnected) throw IllegalStateException("Adaptör bağlantısı kapalı")
        val out = s.getOutputStream()
        val input = s.getInputStream()
        s.soTimeout = timeoutMs
        out.write((command.trim() + "\r").toByteArray(Charset.forName("US-ASCII")))
        out.flush()
        val result = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - started < timeoutMs) {
                val b = input.read()
                if (b < 0) break
                if (b == '>'.code) break
                result.write(b)
            }
        } catch (_: SocketTimeoutException) { }
        val response = result.toString("US-ASCII").replace("\u0000", "").trim().uppercase(Locale.US)
        lastRaw = response
        return response
    }

    private fun pidBytes(pid: String): List<Int>? {
        val response = elm("01$pid")
        val compact = response.replace(Regex("[^0-9A-F]"), "")
        val marker = "41$pid"
        val at = compact.indexOf(marker)
        if (at < 0) return null
        val tail = compact.substring(at + marker.length)
        if (tail.length < 2) return null
        return tail.chunked(2).mapNotNull { it.takeIf { part -> part.length == 2 }?.toIntOrNull(16) }
    }

    private fun decodeDtcs(raw: String): List<String> {
        val compact = raw.replace(Regex("[^0-9A-F]"), "")
        val index = compact.indexOf("43")
        if (index < 0) return emptyList()
        val bytes = compact.substring(index + 2).chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        val found = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]
            val b = bytes[i + 1]
            if (a == 0 && b == 0) { i += 2; continue }
            val first = when ((a and 0xC0) shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
            val digit2 = (a and 0x30) shr 4
            val digit3 = a and 0x0F
            val digit4 = (b and 0xF0) shr 4
            val digit5 = b and 0x0F
            found.add("$first$digit2${digit3.toString(16).uppercase(Locale.US)}${digit4.toString(16).uppercase(Locale.US)}${digit5.toString(16).uppercase(Locale.US)}")
            i += 2
        }
        return found.distinct()
    }

    private fun closeAdapter() {
        connected = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        mainHandler.post { statusLine?.apply { text = "● ADAPTÖR BAĞLI DEĞİL"; setTextColor(muted) } }
    }

    // ---------------- Custom graphics ----------------

    private inner class NeonBackdrop(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(bg)
            paint.shader = RadialGradient(width * .5f, height * .25f, width * .8f,
                intArrayOf(Color.rgb(0, 30, 85), bg), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.color = Color.argb(22, 0, 135, 255)
            paint.strokeWidth = dp(1).toFloat()
            var x = 0
            while (x < width) { canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint); x += dp(38) }
            var y = 0
            while (y < height) { canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint); y += dp(38) }
        }
    }

    private inner class MotorcycleArtView(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = RadialGradient(w * .52f, h * .55f, w * .58f,
                intArrayOf(Color.rgb(0, 80, 210), Color.rgb(0, 20, 65), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p); p.shader = null
            // Electric rays behind the motorcycle
            p.color = Color.argb(95, 0, 160, 255); p.strokeWidth = dp(1).toFloat()
            for (i in 0..23) {
                val a = i * (Math.PI * 2 / 24)
                canvas.drawLine(w * .5f, h * .56f, w * .5f + cos(a).toFloat() * w * .47f,
                    h * .56f + sin(a).toFloat() * h * .42f, p)
            }
            val wheelY = h * .75f
            val leftX = w * .22f
            val rightX = w * .79f
            val radius = min(w, h) * .145f
            // Wheels
            for (x in listOf(leftX, rightX)) {
                p.color = Color.BLACK; p.style = Paint.Style.FILL; canvas.drawCircle(x, wheelY, radius, p)
                p.color = cyan; p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.setShadowLayer(dp(13).toFloat(), 0f, 0f, blue)
                canvas.drawCircle(x, wheelY, radius, p); p.clearShadowLayer()
                p.color = Color.rgb(30, 75, 130); p.strokeWidth = dp(2).toFloat(); canvas.drawCircle(x, wheelY, radius * .68f, p)
                for (j in 0..7) {
                    val a = j * Math.PI / 4
                    canvas.drawLine(x, wheelY, x + cos(a).toFloat() * radius * .66f, wheelY + sin(a).toFloat() * radius * .66f, p)
                }
                p.style = Paint.Style.FILL
            }
            // Motorcycle body silhouette / frame
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = dp(12).toFloat(); p.color = Color.rgb(8, 28, 65)
            val frame = Path().apply {
                moveTo(leftX, wheelY); lineTo(w * .42f, h * .53f); lineTo(w * .66f, h * .52f); lineTo(rightX, wheelY)
                moveTo(w * .42f, h * .53f); lineTo(w * .5f, h * .73f); lineTo(w * .66f, h * .52f)
            }
            canvas.drawPath(frame, p)
            p.color = cyan; p.strokeWidth = dp(3).toFloat(); p.setShadowLayer(dp(9).toFloat(), 0f, 0f, blue); canvas.drawPath(frame, p); p.clearShadowLayer()
            // Tank and fairing
            p.style = Paint.Style.FILL
            val fairing = Path().apply {
                moveTo(w * .26f, h * .59f); lineTo(w * .36f, h * .43f); lineTo(w * .55f, h * .37f)
                lineTo(w * .69f, h * .42f); lineTo(w * .79f, h * .56f); lineTo(w * .69f, h * .62f)
                lineTo(w * .54f, h * .59f); lineTo(w * .42f, h * .66f); close()
            }
            p.color = Color.rgb(0, 35, 105); p.setShadowLayer(dp(12).toFloat(), 0f, 0f, blue); canvas.drawPath(fairing, p); p.clearShadowLayer()
            p.color = cyan; p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); canvas.drawPath(fairing, p)
            p.color = Color.rgb(5, 20, 48); p.style = Paint.Style.FILL
            canvas.drawOval(w * .43f, h * .44f, w * .65f, h * .56f, p)
            p.color = Color.rgb(0, 115, 255); p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat()
            canvas.drawOval(w * .43f, h * .44f, w * .65f, h * .56f, p)
            // Windscreen / handlebar / lights
            p.strokeWidth = dp(6).toFloat(); p.color = Color.rgb(90, 180, 255)
            canvas.drawLine(w * .52f, h * .38f, w * .57f, h * .27f, p)
            canvas.drawLine(w * .57f, h * .27f, w * .71f, h * .29f, p)
            canvas.drawLine(w * .69f, h * .31f, w * .82f, h * .34f, p)
            p.color = cyan; p.strokeWidth = dp(4).toFloat(); p.setShadowLayer(dp(11).toFloat(), 0f, 0f, cyan)
            canvas.drawLine(w * .70f, h * .34f, w * .79f, h * .39f, p); p.clearShadowLayer()
            p.style = Paint.Style.FILL
            p.color = Color.WHITE; canvas.drawCircle(w * .76f, h * .47f, dp(5).toFloat(), p)
            p.color = cyan; p.setShadowLayer(dp(12).toFloat(), 0f, 0f, cyan); canvas.drawCircle(w * .76f, h * .47f, dp(7).toFloat(), p); p.clearShadowLayer()
            p.color = muted; p.textSize = dp(13).toFloat(); p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            canvas.drawText("EFE MOTO  •  ECU DIAGNOSTIC", w * .5f - p.measureText("EFE MOTO  •  ECU DIAGNOSTIC") / 2, h * .96f, p)
        }
    }

    private inner class CircularProgressView(context: android.content.Context) : View(context) {
        var progress = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f; val r = min(width, height) * .36f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(15).toFloat(); p.strokeCap = Paint.Cap.ROUND
            p.color = Color.rgb(10, 38, 76); canvas.drawArc(cx-r, cy-r, cx+r, cy+r, -90f, 360f, false, p)
            p.color = cyan; p.setShadowLayer(dp(13).toFloat(), 0f, 0f, blue)
            canvas.drawArc(cx-r, cy-r, cx+r, cy+r, -90f, 360f * progress / 100f, false, p); p.clearShadowLayer()
            p.style = Paint.Style.FILL; p.color = white; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = dp(39).toFloat()
            canvas.drawText("%$progress", cx, cy + dp(13), p)
        }
    }

    private inner class RpmGaugeView(context: android.content.Context) : View(context) {
        var value = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height * .69f; val r = min(width * .43f, height * .62f)
            val rect = RectF(cx-r, cy-r, cx+r, cy+r)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(14).toFloat(); p.strokeCap = Paint.Cap.ROUND
            p.color = Color.rgb(10, 35, 70); canvas.drawArc(rect, 135f, 270f, false, p)
            p.color = cyan; p.setShadowLayer(dp(10).toFloat(), 0f, 0f, blue); canvas.drawArc(rect, 135f, 270f * (value.coerceIn(0, 12000) / 12000f), false, p); p.clearShadowLayer()
            p.strokeWidth = dp(2).toFloat(); p.color = white
            for (i in 0..24) {
                val angle = Math.toRadians((135 + i * 11.25).toDouble())
                val inner = r * if (i % 3 == 0) .80f else .86f
                val outer = r * .95f
                canvas.drawLine(cx + cos(angle).toFloat()*inner, cy + sin(angle).toFloat()*inner,
                    cx + cos(angle).toFloat()*outer, cy + sin(angle).toFloat()*outer, p)
            }
            p.style = Paint.Style.FILL; p.color = white; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            p.textSize = dp(36).toFloat(); canvas.drawText(if (value >= 0) value.toString() else "—", cx, cy + dp(8), p)
            p.textSize = dp(16).toFloat(); canvas.drawText("RPM", cx, cy + dp(32), p)
            p.textSize = dp(10).toFloat(); p.color = muted
            canvas.drawText("0                 4                 8                 12", cx, height - dp(10).toFloat(), p)
        }
    }
}
