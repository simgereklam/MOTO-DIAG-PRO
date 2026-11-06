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
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

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
    @Volatile private var currentScreen = "HOME"
    private var faults: MutableList<String> = mutableListOf()
    private var selectedFault = ""
    private var hasCompletedScan = false
    private var showingLibraryLookup = false
    private val logs = mutableListOf<String>()
    private var smartInsights: List<String> = emptyList()
    private var currentRiskScore: Int = 0
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
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var scanPercent = 0

    private var seyfiAssistant: SeyfiUstaAssistant? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            seyfiAssistant?.startContinuousListening()
        } else {
            Toast.makeText(this, "Seyfi Usta sürekli kulak vermesi için mikrofon izni şart Usta.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        applyImmersiveMode()
        loadPersistentState()

        seyfiAssistant = SeyfiUstaAssistant(
            activity = this,
            onRecognized = { phrase ->
                mainHandler.post { handleVoiceCommand(phrase) }
            },
            onStatus = { _ -> }
        ).also { it.initialize() }

        checkMicrophoneAndStart()

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

    private fun checkMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            seyfiAssistant?.startContinuousListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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

    private fun handleVoiceCommand(rawCommand: String) {
        val command = rawCommand.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .trim()
        val assistant = seyfiAssistant ?: return

        val answer = when {
            command.contains("seyfi usta") || command.contains("merhaba") || command.contains("selam") -> {
                "Merhaba Usta, ben Seyfi Usta. Motosikletinin arıza teşhisi, canlı verileri ve motor sağlığı için buradayım. Buyur, ne yapıyoruz?"
            }
            command.contains("tarama yap") || command.contains("tara") || command.contains("ariza tara") || command.contains("taramayi baslat") -> {
                startDiagnosis()
                "Hemen ECU arıza taramasını başlatıyorum Usta, adaptör verilerini topluyorum, sabırlı ol."
            }
            command.contains("ariza kod") || command.contains("kodlari oku") || command.contains("ariza oku") || command.contains("arizalar") -> {
                showFaults()
                if (faults.isEmpty()) {
                    "Kontrol ettim Usta, hafızada kayıtlı arıza kodu bulunmuyor, motor temiz."
                } else {
                    "Kayıtlı arıza kodları şunlar Usta: ${faults.joinToString()}. Üzerine tıklayıp detaylı bakabilirsin."
                }
            }
            command.contains("sil") || command.contains("arizalari sil") -> {
                showFaults()
                confirmClearFaults()
                "Arızaları silme komutunu aldım Usta, ekrandan onay vermen gerekiyor."
            }
            command.contains("canli veri") || command.contains("canliyi ac") || command.contains("verileri ac") -> {
                showLiveData()
                "Canlı veri ekranını açtım Usta. Buradan anlık devir, akü ve sensörleri takip ediyoruz."
            }
            command.contains("canli veri yorumla") || command.contains("durum ne") || command.contains("motor nasil") -> {
                evaluateLiveStatusForVoice()
            }
            command.contains("devir") || command.contains("rpm") -> {
                if (rpm >= 0) "Motor devri şu an $rpm devir bölü dakika Usta."
                else "Motor devri henüz ECU'dan okunmadı Usta. Canlı veriyi başlatman gerek."
            }
            command.contains("gaz") || command.contains("tps") -> {
                if (tps >= 0.0) "Gaz kelebeği konumu yüzde ${"%.1f".format(Locale.US, tps)} Usta."
                else "Gaz konumu verisi henüz gelmedi Usta."
            }
            command.contains("sicaklik") || command.contains("hararet") -> {
                if (coolant > -999) {
                    if (coolant > 100) "Dikkat Usta! Motor soğutma suyu sıcaklığı $coolant derece. Hararet yükseliyor, sistemi kontrol et!"
                    else "Motor soğutma suyu sıcaklığı $coolant derece Usta, normal seviyede."
                } else "Motor sıcaklık verisi henüz okunmadı Usta."
            }
            command.contains("aku") || command.contains("voltaj") || command.contains("sarj") -> {
                if (voltage >= 0.0) {
                    if (voltage < 12.0) "Usta dikkat! Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt. Şarj dinamosunu veya konjektörü acilen kontrol etmeliyiz."
                    else "Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt Usta, gayet iyi."
                } else "Akü voltajı henüz okunmadı Usta."
            }
            command.contains("test") -> {
                showTests()
                "Test ekranını açtım Usta."
            }
            command.contains("kayit") || command.contains("log") -> {
                showLogs()
                "İşlem kayıtları ve hata dökümü ekranı açıldı Usta."
            }
            command.contains("ana sayfa") || command.contains("eve don") -> {
                showHome()
                "Ana sayfaya döndüm Usta."
            }
            command.contains("yardim") || command.contains("ne yapabilirsin") -> {
                "Seyfi Usta emrinde Usta. Arıza taraması yapabilir, canlı verileri yorumlayabilir veya akü ve hararet durumunu sana raporlayabilirim."
            }
            else -> {
                "Söylediğini tam çıkaramadım Usta ama buradayım. Arıza taraması yapmamı ya da canlı verileri yorumlamamı isteyebilirsin."
            }
        }

        Toast.makeText(this, answer, Toast.LENGTH_LONG).show()
        assistant.speak(answer)
        log("Seyfi Usta AI Sohbet: $rawCommand")
    }

    private fun evaluateLiveStatusForVoice(): String {
        val issues = mutableListOf<String>()
        if (voltage in 0.1..11.8) issues.add("Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt ile düşük.")
        if (coolant > 102) issues.add("Hararet $coolant dereceye ulaşmış durumda.")
        if (rpm in 1..900) issues.add("Rölanti devri oldukça düşük.")

        return if (issues.isEmpty()) {
            "Canlı verileri inceledim Usta. Akü, hararet ve sensör değerleri normal görünüyor. Şu an olağan dışı bir anormallik yok."
        } else {
            "Canlı verilerde mühendislik uyarısı var Usta! " + issues.joinToString(" ") + " Dikkatli olalım."
        }
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
            Triple("VOICE", "🎙", "Seyfi Usta AI")
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
                        "VOICE" -> {
                            val msg = "Dinliyorum Usta, motorla ilgili neyi merak ediyorsun?"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            seyfiAssistant?.speak(msg)
                        }
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
        val statusText = if (connected) "● VIECAR / ECU BAĞLI • SEYFİ USTA AKTİF" else "● ADAPTÖR BAĞLI DEĞİL • SEYFİ USTA DİNLİYOR"
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

    private val HOME_IMAGE_PARTS = listOf<String>()

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
                if (connected) "● VIECAR / ECU BAĞLI" else "● ADAPTÖR BAĞLI DEĞİL • ÇEVRİMDIŞİ GEZİNME AÇIK",
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
        seyfiAssistant?.speak("Hemen ECU arıza taramasını başlatıyorum Usta, adaptör verilerini topluyorum.")
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
                    throw IllegalStateException("Adaptöre ulaşıldı ancak ECU'dan geçerli 41 00 yanıtı alınamadı.")
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
                if (!validDtcResponse) throw IllegalStateException("ECU'dan arıza yanıtı alınamadı.")
                smartInsights = buildSmartDiagnosis(found, diagnosticSnapshot)
                val anomalies = buildAnomalyWarnings(diagnosticSnapshot)
                smartInsights = smartInsights + anomalies
                updateScan(100, "Tarama tamamlandı")
                mainHandler.post {
                    faults = found.toMutableList()
                    hasCompletedScan = true
                    log("ECU taraması tamamlandı • ${faults.size} arıza • risk $currentRiskScore/100")
                    smartInsights.forEach { log("Akıllı teşhis: $it") }

                    if (faults.isEmpty()) {
                        seyfiAssistant?.speak("Tarama bitti Usta. Hafızada kayıtlı arıza kodu yok, motor saat gibi.")
                    } else {
                        seyfiAssistant?.speak("Tarama tamamlandı Usta! Toplam ${faults.size} tane arıza kodu buldum. Detayları ekrandan inceleyebilirsin.")
                    }

                    if (currentScreen == "SCAN") showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    seyfiAssistant?.speak("Bağlantı başarısız oldu Usta. Cihazın bağlantısını ve kontağı kontrol et, tekrar deniyorum.")
                    if (currentScreen == "SCAN") {
                        progressLine?.text = "BAĞLANTI / TARAMA BAŞARISIZ\n${e.message ?: "Viecar adaptörünü kontrol edin."}\n\nAlt menüden diğer sayfalara geçebilirsiniz."
                        statusLine?.text = "● ECU BAĞLANTISI KURULAMADI"
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
        return score.coerceIn(0, 100)
    }

    private fun buildAnomalyWarnings(data: Map<String, Double>): List<String> {
        val warnings = mutableListOf<String>()
        data["coolant"]?.let { if (it > 105.0) warnings.add("KRİTİK HARARET: ${it.toInt()} °C. Motoru zorlamayın.") }
        data["voltage"]?.let { if (it in 0.1..11.79) warnings.add("AKÜ / ŞARJ UYARISI: ${"%.2f".format(Locale.US, it)} V ölçüldü.") }
        return warnings
    }

    private fun buildSmartDiagnosis(codes: List<String>, data: Map<String, Double>): List<String> {
        return codes.map { "Arıza kodu $it algılandı, kontrol edilmeli." }
    }

    private fun showFaults() {
        val c = baseScreen("FAULTS")
        c.addView(header("TÜM ARIZALAR (${faults.size})"))
        if (faults.isEmpty()) {
            c.addView(neonCard("✓  ARIZA BULUNAMADI", green))
        } else {
            faults.forEach { code ->
                c.addView(neonCard(code, red))
            }
        }
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun confirmClearFaults() {
        clearFaults()
    }

    private fun clearFaults() {
        faults.clear()
        showFaults()
    }

    private fun showLiveData() {
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ"))
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showTests() {
        val c = baseScreen("TESTS")
        c.addView(header("SİSTEM TESTLERİ"))
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showLogs() {
        val c = baseScreen("LOGS")
        c.addView(header("İŞLEM KAYITLARI"))
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showFaultDetail() {}

    private fun createWifiBoundSocket(): Socket {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val candidates = (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()).distinct()
            .filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (candidates.isEmpty()) throw IllegalStateException("Wi-Fi ağı bulunamadı Usta.")
        for (network in candidates) {
            try {
                val s = network.socketFactory.createSocket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("192.168.0.10", 35000), 7000)
                s.soTimeout = 3500
                return s
            } catch (_: Exception) {}
        }
        throw IllegalStateException("Viecar adaptörüne ulaşılamadı Usta.")
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
        if (index < 0) return emptyList()
        val bytes = compact.substring(index + 2).chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        val found = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]; val b = bytes[i + 1]
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

    private fun readLivePids(): Map<String, Double> {
        return emptyMap()
    }

    private fun closeAdapter() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        mainHandler.post { statusLine?.apply { text = "● ADAPTÖR BAĞLI DEĞİL"; setTextColor(muted) } }
    }

    private inner class NeonBackdrop(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(bg)
            paint.shader = RadialGradient(width * .5f, height * .25f, width * .8f, intArrayOf(Color.rgb(0, 30, 85), bg), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private inner class CircularProgressView(context: Context) : View(context) {
        var progress = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f; val r = min(width, height) * .36f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(15).toFloat(); p.strokeCap = Paint.Cap.ROUND
            p.color = Color.rgb(10, 38, 76); canvas.drawArc(cx-r, cy-r, cx+r, cy+r, -90f, 360f, false, p)
            p.color = cyan; canvas.drawArc(cx-r, cy-r, cx+r, cy+r, -90f, 360f * progress / 100f, false, p)
            p.style = Paint.Style.FILL; p.color = white; p.textSize = dp(39).toFloat(); p.textAlign = Paint.Align.CENTER
            canvas.drawText("%$progress", cx, cy + dp(13), p)
        }
    }

    private inner class RpmGaugeView(context: Context) : View(context) {
        var value = -1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h * 0.84f; val r = min(w * 0.43f, h * 0.78f)
            val arc = RectF(cx - r, cy - r, cx + r, cy + r)
            p.style = Paint.Style.FILL; p.color = panel2
            canvas.drawRoundRect(RectF(dp(5).toFloat(), dp(4).toFloat(), w - dp(5), h - dp(2).toFloat()), dp(13).toFloat(), dp(13).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(13).toFloat(); p.color = cyan
            canvas.drawArc(arc, 180f, 180f, false, p)
            p.textAlign = Paint.Align.CENTER; p.textSize = dp(35).toFloat(); p.color = white
            canvas.drawText(if (value >= 0) value.toString() else "—", cx, cy - dp(17), p)
            p.textSize = dp(16).toFloat(); p.color = cyan
            canvas.drawText("RPM", cx, cy + dp(22), p)
        }
    }
}

// ============================================================================
// SEYFİ USTA KESİNTİSİZ YAPAY ZEKA MÜHENDİS DİNLEME SINIFI (HANDS-FREE - GÜVENLİ)
// ============================================================================
class SeyfiUstaAssistant(
    private val activity: ComponentActivity,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isSpeaking = false
    private var isListeningActive = false

    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(this@SeyfiUstaAssistant)
            }
        }

        textToSpeech = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(Locale("tr", "TR"))
                textToSpeech?.setPitch(0.6f)
                textToSpeech?.setSpeechRate(0.95f)

                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { 
                        isSpeaking = true 
                        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        activity.runOnUiThread { restartListeningSafe() }
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        activity.runOnUiThread { restartListeningSafe() }
                    }
                })
            }
        }
    }

    fun startContinuousListening() {
        if (isListeningActive) return
        isListeningActive = true
        restartListeningSafe()
    }

    private fun restartListeningSafe() {
        if (!isListeningActive || isSpeaking) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isListeningActive && !isSpeaking) {
                    try { speechRecognizer?.startListening(intent) } catch (_: Exception) {}
                }
            }, 1200)
        }
    }

    fun speak(text: String) {
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SEYFI_USTA_AI")
    }

    fun release() {
        isListeningActive = false
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        activity.runOnUiThread {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isListeningActive && !isSpeaking) restartListeningSafe()
            }, 1500)
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            onRecognized(matches[0])
        }
        activity.runOnUiThread {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isListeningActive && !isSpeaking) restartListeningSafe()
            }, 500)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
