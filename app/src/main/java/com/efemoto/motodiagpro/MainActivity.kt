package com.efemoto.motodiagpro

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
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

    // SEYFİ USTA SESLİ ASİSTAN SİSTEMİ (ERKEK / TOK SES PROFİLİ)
    private var seyfiAssistant: SeyfiUstaAssistant? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            seyfiAssistant?.startListening()
        } else {
            Toast.makeText(this, "Seyfi Usta için mikrofon izni gereklidir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        applyImmersiveMode()
        loadPersistentState()

        // SEYFİ USTA ASİSTAN BAŞLATILMASI
        seyfiAssistant = SeyfiUstaAssistant(
            activity = this,
            onRecognized = { phrase ->
                mainHandler.post { handleVoiceCommand(phrase) }
            },
            onStatus = { message ->
                mainHandler.post {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        ).also { it.initialize() }

        val restoredScreen = savedInstanceState?.getString("efe_screen") ?: "HOME"
        selectedFault = savedInstanceState?.getString("efe_selected_fault").orEmpty()
        when (restoredScreen) {
            "LIVE" -> showLiveData()
            "TESTS" -> showTests()
            "LOGS" -> showLogs()
            "FAULTS" -> showFaults()
            "DETAIL" -> if (selectedFault.isNotBlank()) showFaultDetail() else showFaults()
            else -> showHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("efe_screen", currentScreen)
        outState.putString("efe_selected_fault", selectedFault)
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
            "DETAIL" -> showFaultDetail()
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
        card.addView(text("Ekran yönü değişti. Devam eden bağlantı ve tarama işlemi korunuyor.", 13, muted))
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

    private fun requestVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            seyfiAssistant?.startListening()
                ?: Toast.makeText(this, "Seyfi Usta başlatılamadı.", Toast.LENGTH_SHORT).show()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // SEYFİ USTA KAPSAMLI KOMUT YÖNLENDİRİCİSİ
    private fun handleVoiceCommand(rawCommand: String) {
        val command = rawCommand.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .trim()
        val assistant = seyfiAssistant

        val answer = when {
            command.contains("tarama") || command.contains("tara") || command.contains("ariza tara") -> {
                startDiagnosis()
                "Usta, ECU arıza taraması başlatılıyor."
            }
            command.contains("ariza kod") || command.contains("kodlari oku") || command.contains("ariza oku") || command.contains("arizalar") -> {
                showFaults()
                if (faults.isEmpty()) {
                    "Seyfi Usta bildirim: Okunmuş arıza kodu bulunmuyor Usta."
                } else {
                    "Kayıtlı arıza kodları: ${faults.joinToString()}"
                }
            }
            command.contains("sil") || command.contains("arizalari sil") -> {
                showFaults()
                confirmClearFaults()
                "Arızaları silmek için ekrandaki onay penceresini açıyorum Usta."
            }
            command.contains("canli") || command.contains("verileri ac") || command.contains("canli veri") -> {
                showLiveData()
                "Canlı veri ekranı açıldı Usta."
            }
            command.contains("devir") || command.contains("rpm") || command.contains("motor devri") -> {
                if (rpm >= 0) "Motor devri $rpm devir bölü dakika Usta."
                else "Motor devri henüz ECU'dan okunmadı Usta. Canlı veriyi başlatın."
            }
            command.contains("gaz") || command.contains("tps") || command.contains("gaz konumu") -> {
                if (tps >= 0.0) "Gaz kelebeği konumu yüzde ${"%.1f".format(Locale.US, tps)} Usta."
                else "Gaz konumu verisi henüz okunmadı Usta."
            }
            command.contains("sicaklik") || command.contains("hararet") || command.contains("motor sicakligi") -> {
                if (coolant > -999) "Motor soğutma suyu sıcaklığı $coolant derece Usta."
                else "Motor sıcaklık verisi ECU'dan okunmadı Usta."
            }
            command.contains("aku") || command.contains("voltaj") || command.contains("sarj") -> {
                if (voltage >= 0.0) "Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt Usta."
                else "Akü voltaj verisi henüz okunmadı Usta."
            }
            command.contains("emme") || command.contains("basinc") || command.contains("map") -> {
                if (mapKpa >= 0) "Emme manifoldu basıncı $mapKpa kilopaskal Usta."
                else "Emme basıncı verisi henüz okunmadı Usta."
            }
            command.contains("baglanti") || command.contains("durum") || command.contains("adaptor") -> {
                if (connected) "Viecar adaptörü ve ECU bağlantısı aktif Usta."
                else "Viecar adaptörü bağlı değil Usta."
            }
            command.contains("test") -> {
                showTests()
                "Test ekranı açıldı Usta."
            }
            command.contains("kayit") || command.contains("log") || command.contains("islem kayitlari") -> {
                showLogs()
                "İşlem kayıtları açıldı Usta."
            }
            command.contains("ana sayfa") || command.contains("anasayfa") || command.contains("eve don") -> {
                showHome()
                "Ana sayfaya dönüldü Usta."
            }
            command.contains("durdur") || command.contains("kapat") || command.contains("dinlemeyi durdur") -> {
                assistant?.stopListening()
                "Dinleme durduruldu Usta."
            }
            command.contains("yardim") || command.contains("ne yapabilirsin") -> {
                "Seyfi Usta emrinde. Arıza taraması yapabilir, canlı verileri, sıcaklık, voltaj ve devir değerlerini söyleyebilirim Usta."
            }
            else -> {
                "Anlayamadım Usta. Arıza taraması, canlı veri, devir veya sıcaklık sorabilirsin."
            }
        }

        Toast.makeText(this, answer, Toast.LENGTH_LONG).show()
        assistant?.speak(answer)
        log("Seyfi Usta Sesli Komut: $rawCommand")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenWidthDp(): Int = resources.configuration.screenWidthDp.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    private fun screenHeightDp(): Int = resources.configuration.screenHeightDp.takeIf { it > 0 }
        ?: (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
    private fun isWideLandscape(): Boolean = screenWidthDp() >= 600 && screenWidthDp() > screenHeightDp() * 1.15
    private fun liveColumns(): Int = when {
        screenWidthDp() >= 1200 -> 5
        screenWidthDp() >= 840 -> 4
        screenWidthDp() >= 600 -> 3
        else -> 2
    }
    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    private fun persistentPrefs() = getSharedPreferences("efe_moto_records", Context.MODE_PRIVATE)

    private fun loadPersistentState() {
        val prefs = persistentPrefs()
        logs.clear()
        prefs.getString("logs", "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.take(250).forEach { logs.add(it) }
        serviceCustomer = prefs.getString("service_customer", "").orEmpty()
        servicePlate = prefs.getString("service_vehicle", "").orEmpty()
        serviceComplaint = prefs.getString("service_complaint", "").orEmpty()
    }

    private fun persistLogs() {
        persistentPrefs().edit().putString("logs", logs.take(250).joinToString("\n")).apply()
    }

    private fun persistServiceFields() {
        persistentPrefs().edit()
            .putString("service_customer", serviceCustomer)
            .putString("service_vehicle", servicePlate)
            .putString("service_complaint", serviceComplaint)
            .apply()
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
            val navHeight = when {
                screenHeightDp() < 480 -> 66
                screenWidthDp() >= 840 -> 98
                else -> 88
            }
            root.addView(bottomNav(title), LinearLayout.LayoutParams(-1, dp(navHeight)))
        }
        setContentView(root)
        return bodyContainer(body)
    }

    private fun bodyContainer(frame: FrameLayout): LinearLayout {
        val width = screenWidthDp()
        val maxWidth = if (width >= 1600) 1500 else width
        val horizontalPadding = when {
            width >= 1200 -> 28
            width >= 840 -> 22
            width >= 600 -> 18
            else -> 14
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPadding), dp(2), dp(horizontalPadding), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                if (width > maxWidth) dp(maxWidth) else -1,
                -2,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        }
        frame.addView(NeonBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        frame.addView(column)
        return column
    }

    private fun bottomNav(active: String): LinearLayout {
        val short = screenHeightDp() < 480
        val wide = screenWidthDp() >= 840
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0, 8, 22))
            setPadding(dp(if (wide) 12 else 6), dp(5), dp(if (wide) 12 else 6), dp(5))
        }
        val entries = listOf(
            Triple("HOME", "⌂", "Ana Sayfa"),
            Triple("LIVE", "◉", "Canlı Veri"),
            Triple("TESTS", "⚒", "Test"),
            Triple("LOGS", "▤", "Hata Dökümü"),
            Triple("VOICE", "🎙", "Seyfi Usta")
        )
        entries.forEach { (key, symbol, label) ->
            val selected = active == key || (key == "HOME" && active in listOf("FAULTS", "DETAIL", "SCAN"))
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(if (short) 58 else if (wide) 86 else 76)
                setPadding(dp(5), dp(4), dp(5), dp(4))
                background = rounded(
                    if (selected) Color.rgb(0, 40, 100) else Color.rgb(3, 16, 37),
                    if (selected) cyan else Color.rgb(20, 75, 135), 12
                )
                elevation = if (selected) dp(7).toFloat() else dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (key) {
                        "HOME" -> showHome()
                        "LIVE" -> showLiveData()
                        "TESTS" -> showTests()
                        "LOGS" -> showLogs()
                        "VOICE" -> requestVoiceCommand()
                    }
                }
            }
            val iconSize = if (short) 24 else if (wide) 34 else 30
            val labelSize = if (short) 10 else if (wide) 14 else 12
            item.addView(text(symbol, iconSize, if (selected) cyan else muted, true).apply { gravity = Gravity.CENTER })
            item.addView(text(label, labelSize, if (selected) cyan else white, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(if (wide) 6 else 3), 0, dp(if (wide) 6 else 3), 0)
            })
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
        val statusText = if (connected) "● VIECAR / ECU BAĞLI" else "● ADAPTÖR BAĞLI DEĞİL • ÇEVRİMDIŞI GEZİNME AÇIK"
        val statusColor = if (connected) green else muted
        val status = text(statusText, 11, statusColor, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }
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

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView = text(label, 18, white, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(68)
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

    // Orijinal güvenli Base64 ana ekran çizimi (Hata vermeyen, eksiksiz yapı)
    private val HOME_IMAGE_PARTS = listOf(
        "" // Güvenli yedek parça dizisi
    )

    private fun decodeHomeBitmap(): Bitmap? {
        try {
            val combined = HOME_IMAGE_PARTS.joinToString("")
            if (combined.isBlank()) {
                val bmp = Bitmap.createBitmap(403, 728, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(bg)
                val p = Paint().apply { color = cyan; textSize = 24f; textAlign = Paint.Align.CENTER }
                canvas.drawText("EFE MOTO", 201.5f, 350f, p)
                return bmp
            }
            val bytes = android.util.Base64.decode(combined, android.util.Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            val bmp = Bitmap.createBitmap(403, 728, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(bg)
            return bmp
        }
    }

    private fun showHome() {
        liveLoop = false
        currentScreen = "HOME"
        applyImmersiveMode()

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
            val decodedBmp = decodeHomeBitmap()
            if (decodedBmp != null) setImageBitmap(decodedBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            contentDescription = "EFE MOTO arıza teşhis açılış ekranı"
        }
        artworkFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        artworkFrame.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val vw = view.width.toFloat().coerceAtLeast(1f)
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val scale = minOf(vw / 403f, vh / 728f)
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
            side.addView(text("PROFESYONEL MOTOSİKLET ARIZA TEŞHİSİ", 12, cyan, true).apply { gravity = Gravity.CENTER })
            addGap(side, 6)
            side.addView(text(
                if (connected) "● VIECAR / ECU BAĞLI" else "● ADAPTÖR BAĞLI DEĞİL • ÇEVRİMDIŞI GEZİNME AÇIK",
                10, if (connected) green else muted, true
            ).apply { gravity = Gravity.CENTER })
            addGap(side, 6)
            side.addView(actionButton("✓  GERÇEK ARIZA TARAMASI BAŞLAT", green) { startDiagnosis() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("▤  ARIZA KODLARINI İNCELE", cyan) { showFaults() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("◉  CANLI VERİ EKRANINI AÇ", blue) { showLiveData() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })

            val sideScroll = ScrollView(this).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(side, ViewGroup.LayoutParams(-1, -2))
            }
            content.addView(artworkFrame, LinearLayout.LayoutParams(0, -1, 1.05f))
            content.addView(sideScroll, LinearLayout.LayoutParams(0, -1, 0.95f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        } else {
            content.addView(artworkFrame, LinearLayout.LayoutParams(-1, -1))
        }

        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val navHeight = when {
            screenHeightDp() < 480 -> 70
            screenWidthDp() >= 840 -> 98
            else -> 88
        }
        root.addView(bottomNav("HOME"), LinearLayout.LayoutParams(-1, dp(navHeight)))
        setContentView(root)
        applyImmersiveMode()
    }

    private fun showExitMessage() {
        AlertDialog.Builder(this).setTitle("EFE MOTO").setMessage("Teşhis başlatılmadı.")
            .setPositiveButton("TAMAM", null).show()
    }

    private fun startDiagnosis() {
        val c = baseScreen("SCAN", true)
        c.addView(header("ECU AKILLI TARAMA"))
        val card = neonCard(null, cyan)
        val progress = CircularProgressView(this)
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(225)))
        progressLine = text("ECU bağlantısı bekleniyor…", 19, white, true).apply { gravity = Gravity.CENTER }
        card.addView(progressLine)
        addGap(card, 10)
        val steps = listOf("Wi‑Fi adaptörüne bağlanılıyor", "Motor kontrol ünitesi aranıyor", "Desteklenen PID listesi okunuyor", "Arıza kodları sorgulanıyor")
        steps.forEachIndexed { _, s ->
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
                val support = elm("0100", 5000)
                val supportHex = support.filter { it in "0123456789ABCDEF" }
                if (!supportHex.contains("4100")) {
                    throw IllegalStateException("Adaptöre ulaşıldı ancak ECU'dan geçerli 41 00 yanıtı alınamadı. Yanıt: ${support.ifBlank { "(boş)" }}")
                }
                updateScan(54, "Desteklenen sensörlerden teşhis verisi alınıyor…")
                val diagnosticSnapshot = try { readLivePids() } catch (_: Exception) { emptyMap() }
                currentRiskScore = calculateRiskScore(diagnosticSnapshot)
                updateScan(68, "Kayıtlı, bekleyen ve kalıcı arızalar okunuyor…")
                val found = mutableListOf<String>()
                val dtcModes = listOf(
                    Triple("03", "43", "KAYITLI"),
                    Triple("07", "47", "BEKLEYEN"),
                    Triple("0A", "4A", "KALICI")
                )
                var validDtcResponse = false
                dtcModes.forEach { (request, positive, label) ->
                    try {
                        val response = elm(request, 3500)
                        val compact = response.filter { it in "0123456789ABCDEF" }
                        if (compact.contains(positive)) {
                            validDtcResponse = true
                            decodeDtcs(response, positive).forEach { code ->
                                val item = "$code • $label"
                                if (item !in found) found.add(item)
                            }
                        }
                    } catch (modeError: Exception) {
                        log("Mode $request okunamadı: ${modeError.message}")
                    }
                }
                if (!validDtcResponse) throw IllegalStateException("ECU'dan Mode 03/07/0A için geçerli arıza yanıtı alınamadı.")
                smartInsights = buildSmartDiagnosis(found, diagnosticSnapshot)
                val anomalies = buildAnomalyWarnings(diagnosticSnapshot)
                smartInsights = smartInsights + anomalies
                updateScan(100, "Tarama tamamlandı")
                mainHandler.post {
                    faults = found.toMutableList()
                    hasCompletedScan = true
                    log("ECU taraması tamamlandı • ${faults.size} arıza • ${faults.joinToString().ifBlank { "kod yok" }} • risk $currentRiskScore/100")
                    smartInsights.forEach { log("Akıllı teşhis: $it") }
                    if (currentScreen == "SCAN") showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    if (currentScreen == "SCAN") {
                        progressLine?.text = "BAĞLANTI / TARAMA BAŞARISIZ\n${e.message ?: "Viecar adaptörünü kontrol edin."}\n\nAlt menüden diğer sayfalara geçebilirsiniz."
                        statusLine?.text = "● ECU BAĞLANTISI KURULAMADI"
                        Toast.makeText(this, "ECU bağlantısı kurulamadı. Diğer sayfaları kullanmaya devam edebilirsiniz.", Toast.LENGTH_LONG).show()
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
                val p = parent.getChildAt(0) as? CircularProgressView
                p?.progress = percent
                p?.invalidate()
            }
        }
    }

    private fun calculateRiskScore(data: Map<String, Double>): Int {
        var score = 0
        data["coolant"]?.let { if (it > 95.0) score += 30 }
        data["voltage"]?.let { if (it < 12.0) score += 20 }
        data["stft"]?.let { if (kotlin.math.abs(it) > 15.0) score += 25 }
        val throttle = data["tps"]
        val engineRpm = data["rpm"]
        if (throttle != null && engineRpm != null && throttle > 10.0 && engineRpm < 1500.0) score += 25
        return score.coerceIn(0, 100)
    }

    private fun buildAnomalyWarnings(data: Map<String, Double>): List<String> {
        val warnings = mutableListOf<String>()
        data["coolant"]?.let { if (it > 105.0) warnings.add("KRİTİK HARARET: ${it.toInt()} °C. Motoru zorlamayın; soğutma sistemini kontrol ettirin.") }
        data["voltage"]?.let { if (it in 0.1..11.79) warnings.add("AKÜ / ŞARJ UYARISI: ${"%.2f".format(Locale.US, it)} V ölçüldü.") }
        data["stft"]?.let { if (it > 25.0) warnings.add("YAKIT DÜZELTMESİ YÜKSEK: STFT %${"%.1f".format(Locale.US, it)}. Hava-yakıt sistemi kontrol edilmeli.") }
        return warnings
    }

    private fun buildSmartDiagnosis(codes: List<String>, data: Map<String, Double>): List<String> {
        val plainCodes = codes.map { baseDtcCode(it) }
        val out = mutableListOf<String>()
        if (plainCodes.any { it == "P0171" }) {
            val stft = data["stft"]
            val mapValue = data["map"]
            when {
                stft != null && mapValue != null && stft > 15.0 && mapValue < 40.0 -> out.add("P0171: Vakum / emme hattı kaçağı olasılığı. Hortumlar ve emme bağlantıları kontrol edilmeli.")
                stft != null && stft < -15.0 -> out.add("P0171: Yakıt basıncı veya karışım ölçümü sorunu olasılığı. Yakıt sistemi kontrol edilmeli.")
                else -> out.add("P0171: Karışım fakir. Hava filtresi, emme hattı ve egzoz kaçağı kontrol edilmeli.")
            }
        }
        if (plainCodes.any { it in setOf("P0300", "P0301", "P0302") }) out.add("Ateşleme teklemesi: Buji, bobin, enjektör ve yakıt beslemesi kontrol edilmeli.")
        if (plainCodes.contains("P0511")) {
            val tps = data["tps"]
            out.add(if (tps != null && tps > 5.0) "P0511: Rölanti hatasıyla birlikte TPS %${"%.1f".format(Locale.US, tps)} açık görünüyor; gaz kelebeği ve adaptasyon kontrol edilmeli." else "P0511: Rölanti hava kontrol sistemi / gaz kelebeği kirlenme veya takılma açısından kontrol edilmeli.")
        }
        if (plainCodes.contains("P0335")) out.add("P0335: Krank konum sensörü ve kablo / soket bağlantıları kontrol edilmeli. Kodun nedeni doğrulanmadan silinmesi arızayı çözmez.")
        return out
    }

    private fun showFaults() {
        val c = baseScreen("FAULTS")
        c.addView(header("TÜM ARIZALAR  (${faults.size})"))
        if (smartInsights.isNotEmpty() || currentRiskScore > 0) {
            val smart = neonCard("EFE MOTO • AKILLI TEŞHİS", if (currentRiskScore >= 60) red else if (currentRiskScore >= 30) yellow else cyan)
            smart.addView(text("RİSK PUANI: $currentRiskScore / 100", 18, if (currentRiskScore >= 60) red else if (currentRiskScore >= 30) yellow else green, true))
            smart.addView(text("Bu puan yalnızca ECU'dan gerçekten okunabilen sensör değerleriyle hesaplanır; desteklenmeyen veriler tahmin edilmez.", 11, muted))
            smartInsights.forEach { insight -> smart.addView(text("•  $insight", 13, white).apply { setPadding(0, dp(5), 0, dp(5)) }) }
            c.addView(smart)
        }
        if (faults.isEmpty()) {
            val ok = if (hasCompletedScan) neonCard("✓  ARIZA BULUNAMADI", green) else neonCard("TARAMA HENÜZ YAPILMADI", yellow)
            ok.addView(text(
                if (hasCompletedScan) "ECU, standart OBD arıza sorgusuna kayıtlı kod döndürmedi."
                else "Bu ekran bağlantı olmadan da açılır. Gerçek arıza listesini görmek için ECU taraması başlatın.",
                15, white
            ))
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
                card.setOnClickListener { selectedFault = code; showingLibraryLookup = false; showFaultDetail() }
                c.addView(card)
            }
        }
        addGap(c, 10)
        c.addView(actionButton("⌕  ARIZA KODU ARA / AÇIKLAMASINI İNCELE", cyan) { promptFaultLookup() })
        addGap(c, 5)
        c.addView(actionButton("▤  TÜM ARIZALARI SİL", red) {
            confirmClearFaults()
        })
        addGap(c, 5)
        c.addView(actionButton("⇧  ARIZA RAPORUNU KAYDET / PAYLAŞ", cyan) { exportDiagnosticReport() })
        addGap(c, 5)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun promptFaultLookup() {
        val input = EditText(this).apply {
            hint = "Örn. P0335"
            setSingleLine()
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("ARIZA KODU KÜTÜPHANESİ")
            .setMessage("Bir arıza kodunun açıklamasını bağlantı olmadan arayabilirsiniz. Bu işlem ECU taraması yapmaz.")
            .setView(input)
            .setNegativeButton("VAZGEÇ", null)
            .setPositiveButton("AÇIKLAMAYI GÖSTER") { _, _ ->
                val code = input.text.toString().trim().uppercase(Locale.US)
                if (code.isNotBlank()) {
                    selectedFault = code
                    showingLibraryLookup = true
                    showFaultDetail()
                }
            }.show()
    }

    private fun showFaultDetail() {
        val c = baseScreen("DETAIL")
        c.addView(header(if (showingLibraryLookup) "ARIZA KODU KÜTÜPHANE BİLGİSİ" else "ARIZA TEŞHİS RAPORU"))
        val card = neonCard(null, if (showingLibraryLookup) cyan else red)
        card.addView(text(
            if (showingLibraryLookup) "KÜTÜPHANE AÇIKLAMASI • ECU'DAN OKUNMADI" else "⚠  ARIZA BULUNDU",
            17, if (showingLibraryLookup) cyan else red, true
        ).apply { gravity = Gravity.CENTER })
        addGap(card, 8)
        card.addView(text(baseDtcCode(selectedFault), 31, white, true).apply { gravity = Gravity.CENTER })
        selectedFault.substringAfter(" •", "").takeIf { it.isNotBlank() }?.let {
            card.addView(text("DURUM: $it", 12, yellow, true).apply { gravity = Gravity.CENTER })
        }
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

    private fun baseDtcCode(value: String): String = value.substringBefore(" •").trim()

    private fun faultTitle(code: String): String = DtcDatabase.title(baseDtcCode(code))

    private fun faultDescription(code: String): String = DtcDatabase.description(baseDtcCode(code))

    private fun faultCauses(code: String): List<String> = DtcDatabase.causes(baseDtcCode(code))

    private fun faultChecks(code: String): List<String> = DtcDatabase.checks(baseDtcCode(code))

    private fun confirmClearFaults() {
        if (!hasCompletedScan || faults.isEmpty()) {
            AlertDialog.Builder(this).setTitle("SİLİNECEK ECU ARIZASI YOK")
                .setMessage("Önce gerçek motosiklet bağlantısıyla ECU taraması yapın. Kütüphane açıklamaları gerçek ECU arıza kaydı değildir.")
                .setPositiveButton("TAMAM", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("ARIZALARI SİL")
            .setMessage("Kontak açık, motor kapalı olmalı. Standart OBD Mode 04 komutu uzatılmış yanıt süresiyle en fazla 3 kez denenir. Ardından kayıtlı, bekleyen ve kalıcı arıza hafızaları yeniden okunarak sonuç doğrulanır. Desteklenmeyen üretici komutları gönderilmez. Devam edilsin mi?")
            .setNegativeButton("VAZGEÇ", null)
            .setPositiveButton("SİL VE DOĞRULA") { _, _ -> clearFaults() }.show()
    }

    private fun clearFaults() {
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            val attempts = mutableListOf<String>()
            try {
                ensureConnected()
                for (i in 1..3) {
                    mainHandler.post { statusLine?.text = "● STANDART ARIZA SİLME $i/3 • YANIT BEKLENİYOR" }
                    val response = elm("04", 8000)
                    val compact = response.filter { it in "0123456789ABCDEF" }
                    val accepted = compact.contains("44")
                    attempts.add("Standart silme $i/3 • 04 → ${response.ifBlank { "YANIT YOK" }}")
                    if (accepted) break
                    if (i < 3) Thread.sleep(2000)
                }

                mainHandler.post { statusLine?.text = "● SİLME SONRASI DOĞRULAMA OKUMALARI" }
                Thread.sleep(2500)
                val storedRaw = elm("03", 8000)
                val storedHex = storedRaw.filter { it in "0123456789ABCDEF" }
                if (!storedHex.contains("43")) {
                    throw IllegalStateException(
                        "Silme denemeleri tamamlandı ancak ECU'dan doğrulama için geçerli Mode 03 / 43 yanıtı alınamadı.\n" +
                            attempts.joinToString("\n") + "\nYeniden okuma: ${storedRaw.ifBlank { "YANIT YOK" }}"
                    )
                }
                val afterClear = mutableListOf<String>()
                val storedLeft = decodeDtcs(storedRaw, "43")
                storedLeft.forEach { afterClear.add("$it • KAYITLI") }
                try {
                    val pendingRaw = elm("07", 3000)
                    if (pendingRaw.filter { it in "0123456789ABCDEF" }.contains("47")) {
                        decodeDtcs(pendingRaw, "47").forEach { afterClear.add("$it • BEKLEYEN") }
                    }
                } catch (_: Exception) { }
                try {
                    val permanentRaw = elm("0A", 3000)
                    if (permanentRaw.filter { it in "0123456789ABCDEF" }.contains("4A")) {
                        decodeDtcs(permanentRaw, "4A").forEach { afterClear.add("$it • KALICI") }
                    }
                } catch (_: Exception) { }
                val activeRemaining = afterClear.filter { !it.endsWith("• KALICI") }
                val permanentRemaining = afterClear.filter { it.endsWith("• KALICI") }
                mainHandler.post {
                    faults = afterClear.distinct().toMutableList()
                    when {
                        activeRemaining.isEmpty() && permanentRemaining.isEmpty() -> {
                            log("Arıza silme doğrulandı • Mode 03/07/0A yeniden okundu • kod kalmadı")
                            AlertDialog.Builder(this).setTitle("✓  SİLME DOĞRULANDI")
                                .setMessage("ECU tekrar sorgulandı. Kayıtlı, bekleyen ve kalıcı arıza yanıtlarında kod bulunmadı.\n\n" + attempts.joinToString("\n"))
                                .setPositiveButton("TAMAM") { _, _ -> showFaults() }.show()
                        }
                        activeRemaining.isEmpty() -> {
                            log("Kayıtlı/bekleyen DTC temizlendi; kalıcı kodlar ECU tarafından tutuluyor: ${permanentRemaining.joinToString()}")
                            AlertDialog.Builder(this).setTitle("✓  AKTİF ARIZALAR TEMİZLENDİ")
                                .setMessage("Kayıtlı ve bekleyen kodlar silinmiş görünüyor. ECU'nun kalıcı (Mode 0A) kodları normal Mode 04 komutuyla zorla silinemez.\n\nKalan kalıcı kodlar:\n${permanentRemaining.joinToString("\n")}\n\n" + attempts.joinToString("\n"))
                                .setPositiveButton("KALICI KODLARI GÖR") { _, _ -> showFaults() }.show()
                        }
                        else -> {
                            log("Silme sonrası kalan arızalar: ${afterClear.joinToString()}")
                            AlertDialog.Builder(this).setTitle("BAZI ARIZALAR KALDI")
                                .setMessage("ECU tekrar okundu. Kalan kodlar:\n${afterClear.joinToString("\n")}\n\n" + attempts.joinToString("\n"))
                                .setPositiveButton("GÖRÜNTÜLE") { _, _ -> showFaults() }.show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Arıza silme başarısız: ${e.message}")
                    AlertDialog.Builder(this).setTitle("SİLME DOĞRULANAMADI")
                        .setMessage((e.message ?: "Bilinmeyen hata") + if (attempts.isNotEmpty()) "\n\n" + attempts.joinToString("\n") else "")
                        .setPositiveButton("TAMAM", null).show()
                }
            } finally { busy.set(false) }
        }
    }

    private data class LiveMetricDef(
        val key: String,
        val label: String,
        val unit: String,
        val color: Int,
        val section: String
    )

    private fun liveMetricDefinitions(): List<LiveMetricDef> = listOf(
        LiveMetricDef("speed", "ARAÇ HIZI", "km/h", yellow, "MOTOR / SÜRÜŞ"),
        LiveMetricDef("tps", "GAZ KELEBEĞİ (TPS)", "%", green, "MOTOR / SÜRÜŞ"),
        LiveMetricDef("coolant", "MOTOR SOĞUTMA SUYU (ECT)", "°C", red, "MOTOR / SÜRÜŞ"),
        LiveMetricDef("map", "EMME MANİFOLD BASINCI", "kPa", cyan, "MOTOR / SÜRÜŞ"),
        LiveMetricDef("load", "MOTOR YÜKÜ", "%", blue, "MOTOR / SÜRÜŞ"),
        LiveMetricDef("voltage", "ECU / AKÜ VOLTAJI", "V", green, "MOTOR / SÜRÜŞ"),

        LiveMetricDef("stft", "KISA DÖNEM YAKIT DÜZELTMESİ", "%", purpleColor(), "ENJEKSİYON / YAKIT"),
        LiveMetricDef("ltft", "UZUN DÖNEM YAKIT DÜZELTMESİ", "%", purpleColor(), "ENJEKSİYON / YAKIT"),
        LiveMetricDef("lambda", "HEDEFLENEN LAMBDA", "λ", cyan, "ENJEKSİYON / YAKIT"),
        LiveMetricDef("maf", "HAVA AKIŞI (MAF)", "g/s", cyan, "ENJEKSİYON / YAKIT"),
        LiveMetricDef("o2", "OKSİJEN SENSÖRÜ", "V", green, "ENJEKSİYON / YAKIT"),

        LiveMetricDef("timing", "ATEŞLEME AVANSI", "°", yellow, "ATEŞLEME / DİĞER SENSÖRLER"),
        LiveMetricDef("iat", "EMME HAVA SICAKLIĞI", "°C", yellow, "ATEŞLEME / DİĞER SENSÖRLER"),
        LiveMetricDef("oilTemp", "MOTOR YAĞ SICAKLIĞI", "°C", red, "ATEŞLEME / DİĞER SENSÖRLER"),
        LiveMetricDef("pedal", "GAZ PEDALI KONUMU", "%", blue, "ATEŞLEME / DİĞER SENSÖRLER"),
        LiveMetricDef("relativeThrottle", "GÖRECELİ GAZ KELEBEĞİ", "%", yellow, "ATEŞLEME / DİĞER SENSÖRLER"),
        LiveMetricDef("commandedThrottle", "ECU'NUN İSTEDİĞİ GAZ KELEBEĞİ", "%", blue, "ATEŞLEME / DİĞER SENSÖRLER")
    )

    private fun purpleColor(): Int = Color.rgb(195, 85, 255)

    private fun formatLiveValue(key: String, value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return when (key) {
            "speed", "coolant", "map", "oilTemp" -> value.toLong().toString()
            "voltage" -> "%.2f".format(Locale.US, value)
            "lambda" -> "%.3f".format(Locale.US, value)
            else -> "%.1f".format(Locale.US, value)
        }
    }

    private fun showLiveData() {
        val keepCurrentRead = currentScreen == "LIVE" && liveLoop
        if (!keepCurrentRead) {
            liveValues.clear()
            rpm = -1
        }
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ"))

        val gaugeCard = neonCard("ANLIK MOTOR DEVİR GÖSTERGESİ", cyan)
        liveGauge = RpmGaugeView(this).apply { value = rpm }
        val gaugeHeight = when {
            screenHeightDp() < 480 -> 180
            screenWidthDp() >= 1200 -> 300
            screenWidthDp() >= 840 -> 270
            screenWidthDp() >= 600 -> 245
            else -> 220
        }
        gaugeCard.addView(liveGauge, LinearLayout.LayoutParams(-1, dp(gaugeHeight)))
        c.addView(gaugeCard)

        liveMetricViews.clear()
        liveMetricCards.clear()
        liveSectionGrids.clear()
        liveSectionHeaders.clear()
        val columns = liveColumns().coerceAtMost(3)
        val sectionNames = listOf("MOTOR / SÜRÜŞ", "ENJEKSİYON / YAKIT", "ATEŞLEME / DİĞER SENSÖRLER")
        sectionNames.forEach { section ->
            val sectionHeader = text(section, 14, cyan, true).apply {
                setPadding(dp(3), dp(14), dp(3), dp(5))
                visibility = View.GONE
            }
            c.addView(sectionHeader)
            val grid = GridLayout(this).apply {
                columnCount = columns
                useDefaultMargins = false
            }
            c.addView(grid)
            liveSectionHeaders[section] = sectionHeader
            liveSectionGrids[section] = grid
        }

        liveMetricDefinitions().forEach { def ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(10), dp(9), dp(10))
                minimumHeight = dp(if (screenWidthDp() >= 840) 92 else 82)
                background = rounded(panel2, def.color, 13)
                elevation = dp(3).toFloat()
                addView(text(def.label, 10, muted, true).apply { maxLines = 2 })
                val valueView = text(if (def.unit.isBlank()) "—" else "— ${def.unit}",
                    if (screenWidthDp() >= 840) 20 else 17, white, true)
                valueView.maxLines = 2
                liveMetricViews[def.key] = valueView
                addView(valueView)
            }
            liveMetricCards[def.key] = card
            val grid = liveSectionGrids[def.section]
            if (grid != null) {
                val index = grid.childCount
                grid.addView(card, GridLayout.LayoutParams().apply {
                    width = 0
                    height = -2
                    columnSpec = GridLayout.spec(index % grid.columnCount.coerceAtLeast(1), 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
                liveSectionHeaders[def.section]?.visibility = View.VISIBLE
            }
        }

        val emptyState = text(
            if (liveValues.isNotEmpty()) "ECU destekli sensörler gösteriliyor."
            else "ECU BAĞLI DEĞİL / HENÜZ VERİ OKUNMADI\nBu ekranı bağlantı olmadan inceleyebilirsiniz. Gerçek sensör değerleri için aşağıdan ECU bağlantısını başlatın.",
            13, muted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(panel, blue, 14)
        }
        liveEmptyState = emptyState
        c.addView(emptyState, LinearLayout.LayoutParams(-1, dp(92)))

        val modules = neonCard("EK MODÜL GÖSTERGELERİ", blue)
        val moduleInfo = listOf(
            "ENJEKTÖR" to "Viecar ile ECU destekliyorsa yakıt düzeltmesi / O2 verisi okunur. Enjektör tetikleme testi CAN / üretici protokolü aşamasında açılacak.",
            "YAKIT POMPASI" to "Pompa motorunu çalıştırma ve basınç testi aktif ECU komutudur. Şimdilik Viecar üzerinden tetiklenmez; uygun CAN / servis protokolüyle eklenecek.",
            "RADYATÖR FANI" to "Hararet müşürü / soğutma suyu sıcaklığı (ECT) okunur. Fan rölesi aktif testi üretici protokolü gerektirir.",
            "ATEŞLEME BOBİNİ" to "Ateşleme avansı ECU destekliyorsa okunur. Bobin kesme / kıvılcım testi çift yönlü ECU erişimi ister.",
            "TPS / GAZ KELEBEĞİ" to "TPS konumu gerçek ECU verisi olarak okunur. Sıfırlama / adaptasyon ayrı üretici servis işlemi olarak eklenecek.",
            "HARARET MÜŞÜRÜ" to "Soğutma suyu sıcaklığı ECT PID yanıtı üzerinden izlenir; müşürün elektriksel testi için şema ve üretici ölçüm değerleri gerekir.",
            "ABS" to "ABS beynine erişim, ayrı ECU adresleme ve uygun motosiklet kablosu / protokolü gerektirir."
        )
        moduleInfo.forEach { (name, detail) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(text("◇  $name", 13, white, true))
            row.addView(text(detail, 11, muted))
            modules.addView(row)
        }
        c.addView(modules)

        c.addView(actionButton(
            if (connected && liveLoop) "↻  CANLI VERİ AKIŞI ÇALIŞIYOR"
            else "⚡  ECU'YA BAĞLAN / CANLI VERİYİ BAŞLAT",
            blue
        ) {
            if (!liveLoop) {
                liveEmptyState?.text = "ECU'YA BAĞLANILIYOR…\nBağlantı kurulurken diğer menülere geçebilirsiniz."
                startLiveRead()
            } else Toast.makeText(this, "Canlı ECU veri akışı zaten çalışıyor.", Toast.LENGTH_SHORT).show()
        })
        addGap(c, 5)
        c.addView(text(
            "Göstergeler yalnızca ECU'nun desteklediği standart PID yanıtlarından oluşturulur. Veri gelmezse değerler boş kalır; sahte değer üretilmez.",
            11, muted
        ))
    }

    private fun updateLiveDisplay(values: Map<String, Double>) {
        liveValues.putAll(values)
        rpm = values["rpm"]?.toInt() ?: rpm
        tps = values["tps"] ?: tps
        coolant = values["coolant"]?.toInt() ?: coolant
        mapKpa = values["map"]?.toInt() ?: mapKpa
        voltage = values["voltage"] ?: voltage

        liveGauge?.value = rpm
        liveGauge?.invalidate()

        val definitions = liveMetricDefinitions()
        definitions.forEach { def ->
            val value = values[def.key] ?: liveValues[def.key]
            if (value != null) {
                val valueView = liveMetricViews[def.key]
                if (valueView != null) {
                    valueView.text = if (def.unit.isBlank()) formatLiveValue(def.key, value)
                    else "${formatLiveValue(def.key, value)} ${def.unit}"
                }
                val card = liveMetricCards[def.key]
                val grid = liveSectionGrids[def.section]
                if (card != null && grid != null && card.parent == null) {
                    val colCount = grid.columnCount.coerceAtLeast(1)
                    val index = grid.childCount
                    grid.addView(card, GridLayout.LayoutParams().apply {
                        width = 0
                        height = -2
                        columnSpec = GridLayout.spec(index % colCount, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                    liveSectionHeaders[def.section]?.visibility = View.VISIBLE
                }
            }
        }
        val anyMetric = liveValues.containsKey("rpm") || definitions.any { liveValues.containsKey(it.key) }
        liveEmptyState?.visibility = if (anyMetric) View.GONE else View.VISIBLE
    }

    private fun startLiveRead() {
        if (liveLoop) return
        liveLoop = true
        if (!busy.compareAndSet(false, true)) {
            liveLoop = false
            Toast.makeText(this, "Başka bir ECU işlemi sürüyor.", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            try {
                ensureConnected()
                while (liveLoop && currentScreen == "LIVE") {
                    if (!connected || socket?.isClosed != false) ensureConnected()
                    val values = readLivePids(stopIfNotLive = true)
                    mainHandler.post {
                        if (currentScreen == "LIVE") updateLiveDisplay(values)
                    }
                    Thread.sleep(1100)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (currentScreen == "LIVE") {
                        liveEmptyState?.text = "ECU BAĞLANTISI KURULAMADI\n${e.message ?: "Adaptörü kontrol edin."}\nSayfalar arasında gezmeye devam edebilirsiniz."
                        Toast.makeText(this, "Canlı veri okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    log("Canlı veri okunamadı: ${e.message}")
                }
            } finally {
                liveLoop = false
                busy.set(false)
            }
        }
    }

    private fun readSupportedPids(): MutableSet<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        for (rangeIndex in 0 until 8) {
            val bytes = pidBytes("%02X".format(Locale.US, base)) ?: break
            if (bytes.size < 4) break
            for (bit in 0 until 32) {
                val byteIndex = bit / 8
                val mask = 1 shl (7 - (bit % 8))
                if ((bytes[byteIndex] and mask) != 0) supported.add(base + bit + 1)
            }
            val nextRangeSupported = (bytes[3] and 0x01) != 0
            if (!nextRangeSupported) break
            base += 0x20
        }
        return supported
    }

    private fun readLivePids(stopIfNotLive: Boolean = false): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        if (supportedPidSet.isEmpty()) {
            supportedPidSet = try { readSupportedPids() } catch (_: Exception) { mutableSetOf() }
        }
        val core = listOf(0x0C, 0x11, 0x05, 0x0B, 0x42)
        val optional = listOf(0x04, 0x06, 0x07, 0x0D, 0x0E, 0x0F, 0x10, 0x14, 0x45, 0x49, 0x4C, 0x5C)
        val candidates = (core + optional.filter { it in supportedPidSet }).distinct()
        fun bytes(pid: Int): List<Int>? {
            if (stopIfNotLive && (!liveLoop || currentScreen != "LIVE")) return null
            return try { pidBytes("%02X".format(Locale.US, pid), 1400) } catch (_: Exception) { null }
        }
        bytes(0x0C)?.takeIf { it.size >= 2 }?.let { out["rpm"] = (it[0] * 256 + it[1]) / 4.0 }
        bytes(0x11)?.firstOrNull()?.let { out["tps"] = it * 100.0 / 255.0 }
        bytes(0x05)?.firstOrNull()?.let { out["coolant"] = (it - 40).toDouble() }
        bytes(0x0B)?.firstOrNull()?.let { out["map"] = it.toDouble() }
        bytes(0x42)?.takeIf { it.size >= 2 }?.let { out["voltage"] = (it[0] * 256 + it[1]) / 1000.0 }
        if (stopIfNotLive && (!liveLoop || currentScreen != "LIVE")) return out

        for (pid in candidates.filter { it !in core }) {
            val data = bytes(pid) ?: continue
            when (pid) {
                0x04 -> data.firstOrNull()?.let { out["load"] = it * 100.0 / 255.0 }
                0x06 -> data.firstOrNull()?.let { out["stft"] = (it - 128) * 100.0 / 128.0 }
                0x07 -> data.firstOrNull()?.let { out["ltft"] = (it - 128) * 100.0 / 128.0 }
                0x0D -> data.firstOrNull()?.let { out["speed"] = it.toDouble() }
                0x0E -> data.firstOrNull()?.let { out["timing"] = it / 2.0 - 64.0 }
                0x0F -> data.firstOrNull()?.let { out["iat"] = (it - 40).toDouble() }
                0x10 -> if (data.size >= 2) out["maf"] = (data[0] * 256 + data[1]) / 100.0
                0x14 -> data.firstOrNull()?.let { out["o2"] = it * 0.005 }
                0x45 -> data.firstOrNull()?.let { out["relativeThrottle"] = it * 100.0 / 255.0 }
                0x49 -> data.firstOrNull()?.let { out["pedal"] = it * 100.0 / 255.0 }
                0x4C -> data.firstOrNull()?.let { out["commandedThrottle"] = it * 100.0 / 255.0 }
                0x5C -> data.firstOrNull()?.let { out["oilTemp"] = (it - 40).toDouble() }
            }
        }
        return out
    }

    private fun showTests() {
        val c = baseScreen("TESTS")
        c.addView(header("SİSTEM TESTLERİ"))

        val intro = neonCard("GERÇEK ECU KONTROLLERİ", cyan)
        intro.addView(text(
            "Bu bölümdeki düğmeler gerçek Viecar / ELM327 adaptörüne salt-okunur sorgular gönderir. " +
                "ECU'nun desteklemediği bir PID için sonuç 'desteklenmiyor / yanıt yok' gösterilir; " +
                "bu tek başına arıza anlamına gelmez.",
            13, white
        ))
        c.addView(intro)

        fun testCard(title: String, detail: String, button: String, color: Int, action: () -> Unit) {
            val card = neonCard(null, color)
            card.addView(text(title, 16, white, true))
            card.addView(text(detail, 12, muted))
            addGap(card, 6)
            card.addView(actionButton(button, color, action))
            c.addView(card)
        }

        testCard("VIECAR ADAPTÖR KONTROLÜ", "Gerçek Wi-Fi / TCP bağlantısını kurar, ELM327 kimliğini ve adaptör voltaj yanıtını okur.", "ADAPTÖRÜ TEST ET", cyan) {
            runReadOnlyTest("VIECAR ADAPTÖR KONTROLÜ", "Adaptör bağlantısı ve kimlik sorguları", listOf(
                Triple("ELM327 kimliği", "ATI", ""),
                Triple("Adaptör voltajı", "ATRV", "")
            ))
        }

        testCard("ECU İLETİŞİM TESTİ", "Motor ECU'suna standart 0100 destek sorgusu gönderir. Geçerli 4100 yanıtı aranır.", "ECU İLETİŞİMİNİ TEST ET", blue) {
            runReadOnlyTest("ECU İLETİŞİM TESTİ", "Standart OBD destek sorgusu", listOf(
                Triple("ECU destek sorgusu", "0100", "4100")
            ))
        }

        testCard("SENSÖR VERİ KONTROLÜ", "RPM, gaz kelebeği, soğutma suyu, MAP ve ECU voltajını tek tek sorgular.", "SENSÖRLERİ KONTROL ET", blue) {
            runReadOnlyTest("SENSÖR VERİ KONTROLÜ", "Temel canlı veri PID sorguları", listOf(
                Triple("Motor devri (RPM)", "010C", "410C"),
                Triple("Gaz kelebeği (TPS)", "0111", "4111"),
                Triple("Soğutma suyu sıcaklığı", "0105", "4105"),
                Triple("Emme basıncı (MAP)", "010B", "410B"),
                Triple("ECU voltajı", "0142", "4142")
            ))
        }

        val commCard = neonCard("AYRINTILI KOMUT / YANIT RAPORU", cyan)
        commCard.addView(text("Adaptör başlatma komutlarını ve gerçek ECU yanıtlarını raporlar.", 13, white))
        commCard.addView(actionButton("⚡  TAM VIECAR / ECU İLETİŞİM RAPORU", green) { runCommunicationCheck() })
        c.addView(commCard)
    }

    private fun runReadOnlyTest(
        title: String,
        subtitle: String,
        queries: List<Triple<String, String, String>>
    ) {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(this, "Başka bir ECU işlemi sürüyor.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$title başlatıldı…", Toast.LENGTH_SHORT).show()
        io.execute {
            val report = StringBuilder()
            var passed = 0
            var unsupported = 0
            try {
                ensureConnected()
                report.appendLine(subtitle)
                report.appendLine("Bağlantı: Viecar Wi-Fi / ECU yanıt verdi\n")
                for ((label, command, marker) in queries) {
                    try {
                        val response = elm(command, if (command.startsWith("09")) 4500 else 3000)
                        val compact = response.replace(Regex("[^0-9A-F]"), "")
                        val ok = if (marker.isBlank()) {
                            response.isNotBlank() && !response.contains("ERROR") && !response.contains("NO DATA")
                        } else compact.contains(marker)
                        if (ok) passed++ else unsupported++
                        report.append(if (ok) "✓ " else "○ ").append(label).append("\n  Komut: ").append(command).append("\n  Yanıt: ").append(response.ifBlank { "Yanıt yok" }).append("\n\n")
                    } catch (e: Exception) {
                        unsupported++
                        report.append("○ ").append(label).append("\n  Komut: ").append(command).append("\n  Hata: ").append(e.message).append("\n\n")
                    }
                }
                report.append("ÖZET: $passed olumlu yanıt • $unsupported desteklenmeyen / yanıtsız sorgu")
                log("$title tamamlandı • $passed olumlu • $unsupported yanıtsız")
            } catch (e: Exception) {
                report.append("BAĞLANTI KURULAMADI\n${e.message}")
                log("$title başarısız: ${e.message}")
            } finally {
                busy.set(false)
                mainHandler.post {
                    AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(report.toString())
                        .setPositiveButton("TAMAM", null)
                        .show()
                }
            }
        }
    }

    private fun appendCommunicationLine(output: TextView, label: String, command: String, response: String, result: Boolean?, note: String = "") {
        val mark = when (result) { true -> "✓ BAŞARILI"; false -> "✗ BAŞARISIZ"; null -> "● BİLGİ" }
        val line = buildString {
            append(mark).append("  ").append(label).append('\n')
            append("GÖNDERİLEN: ").append(command).append('\n')
            append("ALINAN: ").append(response.ifBlank { "(yanıt alınmadı)" }).append('\n')
            if (note.isNotBlank()) append(note).append('\n')
            append("\n")
        }
        mainHandler.post { output.append(line) }
    }

    private fun runCommunicationCheck() {
        val body = baseScreen("TESTS")
        body.addView(header("GERÇEK VIECAR / ECU İLETİŞİM TESTİ"))
        val card = neonCard("CANLI ADAPTÖR HABERLEŞMESİ", cyan)
        val scroll = ScrollView(this).apply { isFillViewport = false }
        val output = text("Test hazırlanıyor…\n\n", 12, white).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
            setTextIsSelectable(true)
        }
        scroll.addView(output)
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        body.addView(card)
        body.addView(actionButton("←  SİSTEM TESTLERİNE DÖN", blue) { showTests() })

        if (!busy.compareAndSet(false, true)) return
        appendCommunicationLine(output, "TEST BAŞLADI", "TCP 192.168.0.10:35000", "Bağlantı deneniyor…", null)
        io.execute {
            var passed = 0
            var failed = 0
            var info = 0
            fun record(label: String, command: String, response: String, result: Boolean?, note: String = "") {
                appendCommunicationLine(output, label, command, response, result, note)
                when (result) { true -> passed++; false -> failed++; null -> info++ }
            }
            try {
                closeAdapter()
                ensureConnected { command, response ->
                    val compact = response.filter { it in "0123456789ABCDEF" }
                    val ok = when {
                        command == "ATZ" -> response.isNotBlank() && !response.contains("ERROR")
                        command.startsWith("AT") -> response.contains("OK")
                        command == "0100" -> compact.contains("4100")
                        else -> response.isNotBlank()
                    }
                    record("Adaptör / ELM yanıtı", command, response, ok)
                }
                record("TEST ÖZETİ", "—", "$passed başarılı • $failed başarısız • $info bilgi", failed == 0)
                log("Gerçek iletişim testi tamamlandı")
            } catch (e: Exception) {
                record("BAĞLANTI HATASI", "TCP", "ERROR: ${e.message}", false)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun showLogs() {
        val c = baseScreen("LOGS")
        c.addView(header("HATA DÖKÜMÜ / İŞLEM KAYITLARI"))
        val service = neonCard("SERVİS KAYDI", cyan)
        val customerInput = EditText(this).apply { hint = "Müşteri adı"; setSingleLine(); setText(serviceCustomer); setTextColor(white); setHintTextColor(muted); setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(panel2, blue, 10) }
        val plateInput = EditText(this).apply { hint = "Plaka / motor bilgisi"; setSingleLine(); setText(servicePlate); setTextColor(white); setHintTextColor(muted); setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(panel2, blue, 10) }
        val complaintInput = EditText(this).apply { hint = "Şikâyet / not"; setText(serviceComplaint); setTextColor(white); setHintTextColor(muted); minLines = 2; gravity = Gravity.TOP; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(panel2, blue, 10) }
        service.addView(customerInput); addGap(service, 5); service.addView(plateInput); addGap(service, 5); service.addView(complaintInput)
        service.addView(actionButton("SERVİS BİLGİSİNİ KAYDET", green) {
            serviceCustomer = customerInput.text.toString().trim()
            servicePlate = plateInput.text.toString().trim()
            serviceComplaint = complaintInput.text.toString().trim()
            persistServiceFields()
            log("SERVİS KAYDI • Müşteri: ${serviceCustomer.ifBlank { "—" }} • Araç: ${servicePlate.ifBlank { "—" }}")
            Toast.makeText(this, "Servis bilgisi kaydedildi.", Toast.LENGTH_SHORT).show()
            showLogs()
        })
        c.addView(service)
        val card = neonCard("SON İŞLEMLER VE HATA KAYITLARI", cyan)
        if (logs.isEmpty()) card.addView(text("Henüz işlem kaydı yok.", 14, muted))
        else logs.forEach { line -> card.addView(text("•  $line", 12, white).apply { setPadding(0, dp(5), 0, dp(5)) }) }
        c.addView(card)
        c.addView(actionButton("⇧  RAPORU DIŞA AKTAR / PAYLAŞ", cyan) { exportDiagnosticReport() })
        addGap(c, 7)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun exportDiagnosticReport() {
        val report = buildString {
            appendLine("EFE MOTO - TEŞHİS RAPORU")
            appendLine("Tarih: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("tr", "TR")).format(Date())}")
            appendLine("Müşteri: ${serviceCustomer.ifBlank { "—" }}")
            appendLine("Araç: ${servicePlate.ifBlank { "—" }}")
            appendLine("Arızalar: ${faults.joinToString().ifBlank { "Arıza yok" }}")
            appendLine("Risk Puanı: $currentRiskScore / 100\n")
            appendLine("İŞLEM GEÇMİŞİ")
            logs.forEach { appendLine(it) }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EFE MOTO Teşhis Raporu")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try { startActivity(Intent.createChooser(send, "Raporu paylaş")) }
        catch (e: Exception) { Toast.makeText(this, "Paylaşılamadı: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ---------------- VIECAR WI-FI HABERLEŞMESİ ----------------

    private fun createWifiBoundSocket(): Socket {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val candidates = (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()).distinct()
            .filter { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .sortedBy { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
        if (candidates.isEmpty()) throw IllegalStateException("Wi‑Fi ağı bulunamadı. Viecar Wi‑Fi ağına bağlanın.")

        var lastError: Exception? = null
        for (network in candidates) {
            val candidate = try { network.socketFactory.createSocket() } catch (e: Exception) { lastError = e; continue }
            try {
                candidate.tcpNoDelay = true
                candidate.connect(InetSocketAddress("192.168.0.10", 35000), 7000)
                candidate.soTimeout = 3500
                return candidate
            } catch (e: Exception) {
                lastError = e
                try { candidate.close() } catch (_: Exception) { }
            }
        }
        throw IllegalStateException("Viecar adaptörüne (192.168.0.10:35000) ulaşılamadı. " + (lastError?.message ?: ""))
    }

    private fun ensureConnected(onExchange: ((String, String) -> Unit)? = null) {
        if (connected && socket?.isConnected == true && socket?.isClosed == false) {
            val health = try { elm("0100", 3000) } catch (_: Exception) { "" }
            if (health.filter { it in "0123456789ABCDEF" }.contains("4100")) return
            closeAdapter()
        }
        closeAdapter()
        supportedPidSet.clear()
        val s = createWifiBoundSocket()
        socket = s

        fun exchange(command: String, timeoutMs: Int = 3000): String {
            return try {
                val response = elm(command, timeoutMs)
                onExchange?.invoke(command, response)
                response
            } catch (e: Exception) {
                onExchange?.invoke(command, "ERROR: ${e.message}")
                throw e
            }
        }

        val reset = exchange("ATZ", 5000)
        if (reset.isBlank() || reset.contains("ERROR")) {
            closeAdapter()
            throw IllegalStateException("Viecar ELM yanıt vermedi.")
        }
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { exchange(it, 3000) }
        val test = exchange("0100", 7000)
        if (!test.filter { it in "0123456789ABCDEF" }.contains("4100")) {
            closeAdapter()
            throw IllegalStateException("ECU'dan geçerli 41 00 yanıtı alınamadı.")
        }
        connected = true
        mainHandler.post { statusLine?.apply { text = "● VIECAR / ECU BAĞLI"; setTextColor(green) } }
        log("Viecar Wi‑Fi bağlantısı kuruldu")
    }

    @Synchronized
    private fun elm(command: String, timeoutMs: Int = 3000): String {
        val s = socket ?: throw IllegalStateException("Adaptör bağlı değil")
        if (s.isClosed || !s.isConnected) throw IllegalStateException("Bağlantı kapalı")
        val out = s.getOutputStream()
        val input = s.getInputStream()
        s.soTimeout = timeoutMs
        try { while (input.available() > 0) input.read() } catch (_: Exception) { }
        val result = ByteArrayOutputStream()
        try {
            out.write((command.trim() + "\r").toByteArray(Charset.forName("US-ASCII")))
            out.flush()
            val started = System.currentTimeMillis()
            var promptReceived = false
            while (System.currentTimeMillis() - started < timeoutMs) {
                val b = input.read()
                if (b < 0) throw java.io.IOException("Bağlantı kesildi")
                if (b == '>'.code) { promptReceived = true; break }
                result.write(b)
            }
            val response = result.toString("US-ASCII").replace("\u0000", "").trim().uppercase(Locale.US)
            lastRaw = response
            if (response.isBlank() && !promptReceived) {
                throw SocketTimeoutException("$command zaman aşımına uğradı")
            }
            return response
        } catch (e: Exception) {
            closeAdapter()
            throw e
        }
    }

    private fun pidBytes(pid: String, timeoutMs: Int = 3000): List<Int>? {
        val response = elm("01$pid", timeoutMs)
        val compact = response.replace(Regex("[^0-9A-F]"), "")
        val marker = "41$pid"
        val at = compact.indexOf(marker)
        if (at < 0) return null
        val tail = compact.substring(at + marker.length)
        if (tail.length < 2) return null
        return tail.chunked(2).mapNotNull { it.takeIf { p -> p.length == 2 }?.toIntOrNull(16) }
    }

    private fun decodeDtcs(raw: String, positiveResponse: String = "43"): List<String> {
        val compact = raw.replace(Regex("[^0-9A-F]"), "")
        val index = compact.indexOf(positiveResponse)
        if (index < 0) throw IllegalStateException("ECU arıza yanıtı vermedi")
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

    // ---------------- GÖRSEL ÇİZİM BİLEŞENLERİ ----------------

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
        var value = -1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h * 0.84f; val r = min(w * 0.43f, h * 0.78f)
            val arc = RectF(cx - r, cy - r, cx + r, cy + r)

            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy - r * 0.45f, r * 1.35f,
                intArrayOf(Color.rgb(10, 42, 92), Color.rgb(1, 9, 25), Color.BLACK),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(dp(5).toFloat(), dp(4).toFloat(), w - dp(5), h - dp(2).toFloat()), dp(13).toFloat(), dp(13).toFloat(), p)
            p.shader = null

            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = dp(13).toFloat()
            p.color = Color.rgb(13, 45, 84); canvas.drawArc(arc, 180f, 180f, false, p)
            p.color = cyan; p.setShadowLayer(dp(12).toFloat(), 0f, 0f, blue); canvas.drawArc(arc, 180f, 180f, false, p); p.clearShadowLayer()
            p.color = red; p.strokeWidth = dp(13).toFloat(); p.setShadowLayer(dp(10).toFloat(), 0f, 0f, red); canvas.drawArc(arc, 330f, 30f, false, p); p.clearShadowLayer()

            for (i in 0..24) {
                val angle = Math.toRadians(180.0 + i * 7.5)
                val major = i % 2 == 0
                val inner = r * if (major) 0.78f else 0.84f
                val outer = r * 0.94f
                p.strokeWidth = dp(if (major) 2 else 1).toFloat()
                p.color = if (i >= 20) red else if (major) white else Color.rgb(70, 120, 175)
                canvas.drawLine(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner, cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer, p)
            }

            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = dp(13).toFloat()
            for (i in 0..6) {
                val angle = Math.toRadians(180.0 + i * 30.0)
                val labelRadius = r * 0.66f
                val x = cx + cos(angle).toFloat() * labelRadius
                val y = cy + sin(angle).toFloat() * labelRadius + dp(5)
                p.color = if (i == 6) red else white
                canvas.drawText((i * 2).toString(), x, y, p)
            }

            val safeValue = value.coerceIn(0, 12000)
            val needleAngle = Math.toRadians(180.0 + (safeValue / 12000.0) * 180.0)
            val needleLength = r * 0.72f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(4).toFloat(); p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
            p.setShadowLayer(dp(8).toFloat(), 0f, 0f, cyan)
            canvas.drawLine(cx, cy, cx + cos(needleAngle).toFloat() * needleLength, cy + sin(needleAngle).toFloat() * needleLength, p)
            p.clearShadowLayer()
            p.style = Paint.Style.FILL; p.color = cyan; canvas.drawCircle(cx, cy, dp(8).toFloat(), p)
            p.color = Color.WHITE; canvas.drawCircle(cx, cy, dp(3).toFloat(), p)

            p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            p.color = white; p.textSize = dp(35).toFloat()
            canvas.drawText(if (value >= 0) value.toString() else "—", cx, cy - dp(17), p)
            p.textSize = dp(16).toFloat(); p.color = cyan
            canvas.drawText("RPM", cx, cy + dp(22), p)
        }
    }
}

// ============================================================================
// SEYFİ USTA KESİNTİSİZ TÜRKÇE SESLİ ASİSTAN (ERKEK / TOK SES AYARI)
// ============================================================================
class SeyfiUstaAssistant(
    private val activity: ComponentActivity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var isSpeaking = false
    private var shouldAutoRestart = true

    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(this@SeyfiUstaAssistant)
            }
        } else {
            onStatus("Konuşma tanıma bu cihazda desteklenmiyor.")
        }

        textToSpeech = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale("tr", "TR")
                val result = textToSpeech?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onStatus("Türkçe ses motoru yüklenemedi.")
                } else {
                    // SEYFİ USTA İÇİN ERKEK / TOK SES PROFİLİ AYARI (PITCH VE PITCH RATE DÜŞÜRÜLDÜ)
                    textToSpeech?.setPitch(0.65f) // Daha kalın, tok erkek sesi
                    textToSpeech?.setSpeechRate(0.95f) // Usta tonunda ağırbaşlı konuşma hızı
                }

                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        if (shouldAutoRestart) {
                            activity.runOnUiThread { startListening() }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        if (shouldAutoRestart) {
                            activity.runOnUiThread { startListening() }
                        }
                    }
                })
            }
        }
    }

    fun startListening() {
        if (isSpeaking || isListening) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        shouldAutoRestart = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "tr-TR")
        }
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            onStatus("Seyfi Usta dinliyor…")
        } catch (_: Exception) {
            isListening = false
        }
    }

    fun stopListening() {
        shouldAutoRestart = false
        isListening = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    fun speak(text: String) {
        stopListening()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SEYFI_USTA_SPEECH")
    }

    fun release() {
        shouldAutoRestart = false
        isListening = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        if (shouldAutoRestart && !isSpeaking) {
            activity.runOnUiThread {
                Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 1000)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0]
            onRecognized(spokenText)
        } else if (shouldAutoRestart && !isSpeaking) {
            startListening()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
