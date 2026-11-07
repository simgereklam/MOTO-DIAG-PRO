package com.efemoto.motodiagpro

import android.app.AlertDialog
import android.graphics.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    @Volatile private var currentScreen = "HOME"
    private var faults: MutableList<String> = mutableListOf()
    private var selectedFault = ""
    private var hasCompletedScan = false
    private var showingLibraryLookup = false
    private val logs = mutableListOf<String>()
    private var smartInsights: List<String> = emptyList()
    private var currentRiskScore: Int = 0
    private var serviceCustomer: String = ""
    private var servicePlate: String = ""
    private var serviceComplaint: String = ""
    private var rpm = -1
    private var tps = -1.0
    private var coolant = -999
    private var mapKpa = -1
    private var voltage = -1.0
    @Volatile private var liveLoop = false
    private var liveValues: MutableMap<String, Double> = mutableMapOf()
    private var supportedPidSet: MutableSet<Int> = mutableSetOf()
    private var liveGauge: RpmGaugeView? = null
    private val liveMetricViews = mutableMapOf<String, TextView>()
    private val liveMetricCards = mutableMapOf<String, LinearLayout>()
    private val liveSectionGrids = mutableMapOf<String, GridLayout>()
    private val liveSectionHeaders = mutableMapOf<String, TextView>()
    private var liveEmptyState: TextView? = null
    private var liveView: LinearLayout? = null
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var scanPercent = 0

    // SEYFİ USTA SESLİ ASİSTAN MODÜLÜ
    private var seyfiAssistant: SeyfiUstaAI? = null
    private var hasWelcomed = false

    private val homeScreenBitmap: Bitmap by lazy {
        val bytes = Base64.decode(HOME_IMAGE_PARTS.joinToString(""), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("EFE MOTO açılış görseli yüklenemedi")
    }

    private val HOME_IMAGE_PARTS = listOf(
        "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"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        applyImmersiveMode()
        loadPersistentState()

        // SEYFİ USTA SESLİ ASİSTAN BAŞLATMA
        seyfiAssistant = SeyfiUstaAI(this) { phrase ->
            mainHandler.post { handleVoiceCommand(phrase) }
        }.also { it.initialize() }

        val restoredScreen = savedInstanceState?.getString("efe_screen") ?: "HOME"
        when (restoredScreen) {
            "LIVE" -> showLiveData()
            "TESTS" -> showTests()
            "LOGS" -> showLogs()
            "FAULTS" -> showFaults()
            else -> showHome()
        }

        // GARAJ KARŞILAMASI (SADECE BİR KEZ ÇALIŞIR)
        mainHandler.postDelayed({
            if (!hasWelcomed) {
                hasWelcomed = true
                seyfiAssistant?.speak("Merhaba Usta, Seyfi Usta devrede. OBD kablosunu takıp kontağı açabilirsin.")
            }
        }, 1200)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("efe_screen", currentScreen)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
        when (currentScreen) {
            "HOME" -> showHome()
            "LIVE" -> showLiveData()
            "TESTS" -> showTests()
            "LOGS" -> showLogs()
            "FAULTS" -> showFaults()
            "SCAN" -> showScanProgressShell()
            else -> showHome()
        }
    }

    private fun showScanProgressShell() {
        val c = baseScreen("SCAN", true)
        c.addView(header("ECU AKILLI TARAMA"))
        val card = neonCard("TARAMA DEVAM EDİYOR", cyan)
        val progress = CircularProgressView(this).apply { this.progress = scanPercent }
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(220)))
        progressLine = text("ECU taraması sürüyor…  •  %$scanPercent", 18, white, true).apply { gravity = Gravity.CENTER }
        card.addView(progressLine)
        addGap(card, 10)
        card.addView(text("Bağlantı korunuyor.", 13, muted))
        c.addView(card)
        c.addView(actionButton("TARAMAYI İPTAL ET", red) { closeAdapter(); showHome() })
    }

    override fun onDestroy() {
        liveLoop = false
        seyfiAssistant?.release()
        seyfiAssistant = null
        closeAdapter()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun handleVoiceCommand(rawCommand: String) {
        val cmd = rawCommand.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .trim()

        val reply = when {
            cmd.contains("seyfi usta") || cmd.contains("merhaba") -> "Aleykümselam Usta, garajdayız. Ne yapıyoruz?"
            cmd.contains("tara") || cmd.contains("ariza bul") -> {
                startDiagnosis()
                "Hemen taramayı başlatıyorum Usta."
            }
            else -> "Söylediğini duydum Usta."
        }
        seyfiAssistant?.speak(reply)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenWidthDp(): Int = resources.configuration.screenWidthDp.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    private fun screenHeightDp(): Int = resources.configuration.screenHeightDp.takeIf { it > 0 }
        ?: (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
    private fun isWideLandscape(): Boolean = screenWidthDp() >= 600 && screenWidthDp() > screenHeightDp() * 1.15

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun persistentPrefs() = getSharedPreferences("efe_moto_records", Context.MODE_PRIVATE)

    private fun loadPersistentState() {
        val prefs = persistentPrefs()
        logs.clear()
        prefs.getString("logs", "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.take(250).forEach { logs.add(it) }
    }

    private fun persistLogs() {
        persistentPrefs().edit().putString("logs", logs.take(250).joinToString("\n")).apply()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("tr", "TR")).format(Date())
        logs.add(0, "$time  $message")
        if (logs.size > 250) logs.removeAt(logs.lastIndex)
        persistLogs()
    }

    private fun baseScreen(title: String, showNav: Boolean = true): LinearLayout {
        applyImmersiveMode()
        val keepCurrentLiveLoop = title == "LIVE" && currentScreen == "LIVE" && liveLoop
        currentScreen = title
        if (!keepCurrentLiveLoop) liveLoop = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val body = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (showNav) {
            val navHeight = if (screenHeightDp() < 480) 66 else 88
            root.addView(bottomNav(title), LinearLayout.LayoutParams(-1, dp(navHeight)))
        }
        setContentView(root)
        return bodyContainer(body)
    }

    private fun bodyContainer(frame: FrameLayout): LinearLayout {
        val width = screenWidthDp()
        val maxWidth = if (width >= 1600) 1500 else width
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(20))
            layoutParams = FrameLayout.LayoutParams(if (width > maxWidth) dp(maxWidth) else -1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
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
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        val entries = listOf(
            Triple("HOME", "⌂", "Ana Sayfa"),
            Triple("LIVE", "◉", "Canlı Veri"),
            Triple("TESTS", "⚒", "Test"),
            Triple("LOGS", "▤", "Kayıtlar")
        )
        entries.forEach { (key, symbol, label) ->
            val selected = active == key || (key == "HOME" && active in listOf("FAULTS", "SCAN"))
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(4), dp(5), dp(4))
                background = rounded(if (selected) Color.rgb(0, 40, 100) else Color.rgb(3, 16, 37), if (selected) cyan else Color.rgb(20, 75, 135), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (key) {
                        "HOME" -> showHome()
                        "LIVE" -> showLiveData()
                        "TESTS" -> showTests()
                        "LOGS" -> showLogs()
                    }
                }
            }
            item.addView(text(symbol, 24, if (selected) cyan else muted, true).apply { gravity = Gravity.CENTER })
            item.addView(text(label, 11, if (selected) cyan else white, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
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
        line.addView(text(" EFE MOTO • SEYFİ USTA", 22, white, true))
        box.addView(line)
        if (!subtitle.isNullOrBlank()) box.addView(text(subtitle, 12, cyan, true).apply { gravity = Gravity.CENTER })
        val statusText = if (connected) "● VIECAR / ECU BAĞLI • SEYFİ USTA AKTİF" else "● ÇEVRİMDIŞI MOD"
        statusLine = text(statusText, 11, if (connected) green else muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }
        box.addView(statusLine)
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

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView = text(label, 18, white, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(64)
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
        liveLoop = false
        currentScreen = "HOME"
        applyImmersiveMode()

        val bitmap = homeScreenBitmap
        val wide = isWideLandscape()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val artworkFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }

        val artwork = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "EFE MOTO açılış ekranı"
        }
        artworkFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        artworkFrame.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val vw = view.width.toFloat().coerceAtLeast(1f)
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val scale = min(vw / 403f, vh / 728f)
                val drawW = 403f * scale
                val drawH = 728f * scale
                val offX = (vw - drawW) / 2f
                val offY = (vh - drawH) / 2f
                val x = (event.x - offX) / drawW
                val y = (event.y - offY) / drawH
                if (x in 0f..1f && y in 0f..1f) {
                    when {
                        y in 0.905f..0.995f && x in 0.035f..0.495f -> startDiagnosis()
                        y in 0.905f..0.995f && x in 0.515f..0.975f -> showExitMessage()
                        y in 0.795f..0.905f && x in 0.05f..0.95f -> startDiagnosis()
                    }
                }
            }
            true
        }

        if (wide) {
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(Color.rgb(2, 10, 27), cyan, 18)
            }
            side.addView(text("EFE MOTO", 28, white, true).apply { gravity = Gravity.CENTER })
            side.addView(text("SEYFİ USTA", 11, cyan, true).apply { gravity = Gravity.CENTER })
            addGap(side, 8)
            side.addView(actionButton("✓  ARIZA TESPİTİ", green) { startDiagnosis() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("▤  ARIZALAR", cyan) { showFaults() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("◉  CANLI VERİ", blue) { showLiveData() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })

            content.addView(artworkFrame, LinearLayout.LayoutParams(0, -1, 1.05f))
            content.addView(side, LinearLayout.LayoutParams(0, -1, 0.95f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        } else {
            content.addView(artworkFrame, LinearLayout.LayoutParams(-1, -1))
        }

        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val navHeight = if (screenHeightDp() < 480) 70 else 88
        root.addView(bottomNav("HOME"), LinearLayout.LayoutParams(-1, dp(navHeight)))
        setContentView(root)
        applyImmersiveMode()
    }

    private fun showExitMessage() {
        AlertDialog.Builder(this).setTitle("EFE MOTO").setMessage("Teşhis başlatılmadı Usta.")
            .setPositiveButton("TAMAM", null).show()
    }

    private fun startDiagnosis() {
        seyfiAssistant?.speak("Wi-Fi adaptör taranıyor Usta.")
        val c = baseScreen("SCAN", true)
        c.addView(header("ECU AKILLI TARAMA"))
        val card = neonCard(null, cyan)
        val progress = CircularProgressView(this)
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(225)))
        progressLine = text("Wi-Fi adaptör aranıyor…", 18, white, true).apply { gravity = Gravity.CENTER }
        card.addView(progressLine)
        addGap(card, 10)
        c.addView(card)
        c.addView(actionButton("İPTAL ET", red) { closeAdapter(); showHome() })

        io.execute {
            try {
                updateScan(20, "Bağlantı kuruluyor...")
                ensureConnected()
                updateScan(50, "ECU taranıyor…")
                val support = elm("0100", 5000)
                if (!support.contains("4100")) throw IllegalStateException("ECU yanıt vermedi.")
                updateScan(80, "Arızalar okunuyor…")
                val found = mutableListOf<String>()
                listOf("03", "07", "0A").forEach { mode ->
                    try {
                        val resp = elm(mode, 3000)
                        decodeDtcs(resp, if (mode == "03") "43" else if (mode == "07") "47" else "4A").forEach {
                            if (it !in found) found.add(it)
                        }
                    } catch (_: Exception) {}
                }
                updateScan(100, "Tarama tamamlandı")
                mainHandler.post {
                    faults = found.toMutableList()
                    if (faults.isEmpty()) {
                        seyfiAssistant?.speak("Tarama bitti Usta, arıza kodu yok.")
                    } else {
                        seyfiAssistant?.speak("Tarama bitti Usta, arızalar bulundu.")
                    }
                    if (currentScreen == "SCAN") showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    seyfiAssistant?.speak("Bağlantı kurulamadı Usta.")
                    if (currentScreen == "SCAN") {
                        progressLine?.text = "BAĞLANTI BAŞARISIZ"
                        statusLine?.text = "● BAĞLANTI YOK"
                    }
                }
            }
        }
    }

    private fun updateScan(percent: Int, message: String) {
        scanPercent = percent
        mainHandler.post {
            progressLine?.text = "$message  •  %$percent"
            (progressLine?.parent as? LinearLayout)?.let { parent ->
                (parent.getChildAt(0) as? CircularProgressView)?.let {
                    it.progress = percent
                    it.invalidate()
                }
            }
        }
    }

    private fun showFaults() {
        val c = baseScreen("FAULTS")
        c.addView(header("ARIZA LİSTESİ (${faults.size})"))
        if (faults.isEmpty()) {
            c.addView(neonCard("✓  KAYITLI ARIZA YOK - MOTOR SAAT GİBİ", green))
        } else {
            faults.forEach { code ->
                val card = neonCard("$code • ARIZA TESPİT EDİLDİ", red)
                c.addView(card)
            }
        }
        addGap(c, 10)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showLiveData() {
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ"))
        val card = neonCard("ANLIK DEĞERLER", cyan)
        val voltStr = if (voltage >= 0) "$voltage V" else "Ölçülüyor..."
        card.addView(text("Akü Voltajı: $voltStr", 15, green, true))
        c.addView(card)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showTests() {
        val c = baseScreen("TESTS")
        c.addView(header("SİSTEM TESTLERİ"))
        c.addView(neonCard("Yakıt Pompası Testi", cyan))
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showLogs() {
        val c = baseScreen("LOGS")
        c.addView(header("İŞLEM KAYITLARI"))
        val card = neonCard(null, cyan)
        if (logs.isEmpty()) {
            card.addView(text("Kayıt bulunmuyor.", 13, muted))
        } else {
            logs.take(30).forEach { card.addView(text(it, 11, white)) }
        }
        c.addView(card)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun createWifiBoundSocket(): Socket {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val candidates = (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()).distinct()
            .filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        
        if (candidates.isEmpty()) {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("192.168.0.10", 35000), 5000)
            s.soTimeout = 3500
            return s
        }

        for (network in candidates) {
            try {
                val s = network.socketFactory.createSocket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("192.168.0.10", 35000), 7000)
                s.soTimeout = 3500
                return s
            } catch (_: Exception) {}
        }
        throw IllegalStateException("Viecar adaptörüne ulaşılamadı.")
    }

    private fun ensureConnected() {
        if (connected && socket?.isConnected == true && socket?.isClosed == false) return
        closeAdapter()
        socket = createWifiBoundSocket()
        connected = true
    }

    @Synchronized
    private fun elm(command: String, timeoutMs: Int = 3000): String {
        val s = socket ?: throw IllegalStateException("Bağlantı yok")
        val out = s.getOutputStream()
        val input = s.getInputStream()
        s.soTimeout = timeoutMs
        try { while (input.available() > 0) input.read() } catch (_: Exception) {}
        val res = ByteArrayOutputStream()
        out.write((command.trim() + "\r").toByteArray(Charset.forName("US-ASCII")))
        out.flush()
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val b = input.read()
            if (b < 0) break
            if (b == '>'.code) break
            res.write(b)
        }
        return res.toString("US-ASCII").replace("\u0000", "").trim().uppercase(Locale.US)
    }

    private fun decodeDtcs(raw: String, positiveResponse: String): List<String> {
        val compact = raw.replace(Regex("[^0-9A-F]"), "")
        val index = compact.indexOf(positiveResponse)
        if (index < 0) return emptyList()
        val bytes = compact.substring(index + 2).chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        val found = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]; val b = bytes[i + 1]
            if (a == 0 && b == 0) { i += 2; continue }
            val first = when ((a and 0xC0) shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
            val d2 = (a and 0x30) shr 4
            val d3 = a and 0x0F
            val d4 = (b and 0xF0) shr 4
            val d5 = b and 0x0F
            found.add("$first$d2${d3.toString(16).uppercase(Locale.US)}${d4.toString(16).uppercase(Locale.US)}${d5.toString(16).uppercase(Locale.US)}")
            i += 2
        }
        return found.distinct()
    }

    private fun closeAdapter() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        mainHandler.post { statusLine?.apply { text = "● ÇEVRİMDIŞI MOD"; setTextColor(muted) } }
    }
}

private class NeonBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(2, 7, 18))
        paint.shader = RadialGradient(width * .5f, height * .25f, width * .8f, intArrayOf(Color.rgb(0, 30, 85), Color.rgb(2, 7, 18)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private class CircularProgressView(context: Context) : View(context) {
    var progress: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 22f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(238, 246, 255)
        textSize = 46f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(w, h) / 2f - 35f
        val cx = w / 2f
        val cy = h / 2f

        paint.color = Color.rgb(6, 30, 70)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.rgb(0, 220, 255)
        val sweepAngle = (progress.coerceIn(0, 100) / 100f) * 360f
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, sweepAngle, false, paint)

        val fontMetrics = textPaint.fontMetrics
        val textY = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("%$progress", cx, textY, textPaint)
    }
}

private class RpmGaugeView(context: Context) : View(context) {
    var rpm: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(cx, cy) - 20f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16f
        paint.color = Color.rgb(10, 35, 75)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.rgb(0, 220, 255)
        val sweep = (rpm.coerceIn(0, 10000) / 10000f) * 270f
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, sweep, false, paint)
    }
}
