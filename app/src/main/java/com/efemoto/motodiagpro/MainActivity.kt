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
import android.util.Base64
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

    // SEYFİ USTA SESLİ ASİSTAN SİSTEMİ
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

        var answer = ""
        when {
            // Seyfi Usta arıza taraması
            command.contains("tarama") || command.contains("tara") || command.contains("ariza tara") -> {
                answer = "Usta, ECU arıza taraması başlatılıyor."
                startDiagnosis()
            }
            // Arıza kodlarını oku
            command.contains("ariza kod") || command.contains("kodlari oku") || command.contains("ariza oku") || command.contains("arizalar") -> {
                showFaults()
                answer = if (faults.isEmpty()) {
                    "Seyfi Usta bildirim: Okunmuş arıza kodu bulunmuyor."
                } else {
                    "Kayıtlı arıza kodları: ${faults.joinToString()}"
                }
            }
            // Arızaları sil
            command.contains("sil") || command.contains("arizalari sil") -> {
                showFaults()
                answer = "Arızaları silmek için ekrandaki onay penceresini açıyorum Usta."
                confirmClearFaults()
            }
            // Canlı verileri aç
            command.contains("canli") || command.contains("verileri ac") || command.contains("canli veri") -> {
                showLiveData()
                answer = "Canlı veri ekranı açıldı Usta."
            }
            // Motor devri
            command.contains("devir") || command.contains("rpm") || command.contains("motor devri") -> {
                answer = if (rpm >= 0) {
                    "Motor devri $rpm devir bölü dakika."
                } else {
                    "Motor devri henüz ECU'dan okunmadı. Lütfen canlı veriyi başlatın."
                }
            }
            // Gaz konumu / TPS
            command.contains("gaz") || command.contains("tps") || command.contains("gaz konumu") -> {
                answer = if (tps >= 0.0) {
                    "Gaz kelebeği konumu yüzde ${"%.1f".format(Locale.US, tps)}."
                } else {
                    "Gaz konumu verisi henüz okunmadı."
                }
            }
            // Motor sıcaklığı / Hararet
            command.contains("sicaklik") || command.contains("hararet") || command.contains("motor sicakligi") -> {
                answer = if (coolant > -999) {
                    "Motor soğutma suyu sıcaklığı $coolant derece."
                } else {
                    "Motor sıcaklık verisi ECU'dan okunmadı."
                }
            }
            // Akü voltajı
            command.contains("aku") || command.contains("voltaj") || command.contains("sarj") -> {
                answer = if (voltage >= 0.0) {
                    "Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt."
                } else {
                    "Akü voltaj verisi henüz okunmadı."
                }
            }
            // Emme basıncı / MAP
            command.contains("emme") || command.contains("basinc") || command.contains("map") -> {
                answer = if (mapKpa >= 0) {
                    "Emme manifoldu basıncı $mapKpa kilopaskal."
                } else {
                    "Emme basıncı verisi henüz okunmadı."
                }
            }
            // Bağlantı durumu
            command.contains("baglanti") || command.contains("durum") || command.contains("adaptor") -> {
                answer = if (connected) {
                    "Viecar adaptörü ve ECU bağlantısı aktif Usta."
                } else {
                    "Viecar adaptörü bağlı değil."
                }
            }
            // Test ekranı
            command.contains("test") -> {
                showTests()
                answer = "Test ekranı açıldı."
            }
            // İşlem kayıtları / Loglar
            command.contains("kayit") || command.contains("log") || command.contains("islem kayitlari") -> {
                showLogs()
                answer = "İşlem kayıtları açıldı."
            }
            // Ana sayfa
            command.contains("ana sayfa") || command.contains("anasayfa") || command.contains("eve don") -> {
                showHome()
                answer = "Ana sayfaya dönüldü."
            }
            // Dinlemeyi durdur
            command.contains("durdur") || command.contains("kapat") || command.contains("dinlemeyi durdur") -> {
                answer = "Dinleme durduruldu Usta."
                assistant?.stopListening()
            }
            // Yardım
            command.contains("yardim") || command.contains("ne yapabilirsin") -> {
                answer = "Seyfi Usta hizmetinizde. Arıza taraması yapabilir, canlı verileri, sıcaklık, voltaj ve devir değerlerini söyleyebilirim."
            }
            else -> {
                answer = "Anlayamadım Usta. Arıza taraması, canlı veri, devir veya sıcaklık sorabilirsiniz."
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
            setImageBitmap(homeScreenBitmap)
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
// SEYFİ USTA KESİNTİSİZ TÜRKÇE SESLİ ASİSTAN SINIFI
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
        } catch (e: Exception) {
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
