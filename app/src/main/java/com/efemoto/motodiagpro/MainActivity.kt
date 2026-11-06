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
    private val logs = mutableListOf<String>()
    private var smartInsights: List<String> = emptyList()
    private var currentRiskScore: Int = 0
    private var rpm = -1
    private var tps = -1.0
    private var coolant = -999
    private var voltage = -1.0
    @Volatile private var liveLoop = false
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var scanPercent = 0

    private var seyfiAssistant: SeyfiUstaAI? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            seyfiAssistant?.startListening()
        } else {
            Toast.makeText(this, "Seyfi Usta sesli komut için mikrofon izni istiyor Usta.", Toast.LENGTH_LONG).show()
        }
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
            val bytes = Base64.decode(combined, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            val bmp = Bitmap.createBitmap(403, 728, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(bg)
            return bmp
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        applyImmersiveMode()
        loadPersistentState()

        seyfiAssistant = SeyfiUstaAI(this) { phrase ->
            mainHandler.post { handleVoiceCommand(phrase) }
        }.also { it.initialize() }

        checkMicrophoneAndStart()

        val restoredScreen = savedInstanceState?.getString("efe_screen") ?: "HOME"
        selectedFault = savedInstanceState?.getString("efe_selected_fault").orEmpty()
        when (restoredScreen) {
            "LIVE" -> showLiveData()
            "TESTS" -> showTests()
            "LOGS" -> showLogs()
            "FAULTS" -> showFaults()
            else -> showHome()
        }
    }

    private fun checkMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            seyfiAssistant?.startListening()
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
        card.addView(text("Ekran yönü değişti. Bağlantı korunuyor.", 13, muted))
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

    // İNTERNETSİZ YEREL YAPAY ZEKA & USTA KARAR MEKANİZMASI
    private fun handleVoiceCommand(rawCommand: String) {
        val cmd = rawCommand.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .trim()

        val reply = when {
            cmd.contains("seyfi usta") || cmd.contains("merhaba") || cmd.contains("selam") -> {
                "Aleykümselam Usta! Ben Seyfi Usta. Motorun beyni bende, neyi kurcalıyoruz?"
            }
            cmd.contains("tarama") || cmd.contains("tara") || cmd.contains("ariza bul") -> {
                startDiagnosis()
                "Hemen Wi-Fi adaptöründen ECU'ya bağlanıp taramayı başlatıyorum Usta, sabırlı ol."
            }
            cmd.contains("kod") || cmd.contains("arizalar") || cmd.contains("oku") -> {
                showFaults()
                if (faults.isEmpty()) "Hafızada arıza kodu yok Usta, motor saat gibi maşallah."
                else "Kayıtlı arızalar şunlar Usta: ${faults.joinToString()}. Dikkat etmek lazım."
            }
            cmd.contains("sil") -> {
                confirmClearFaults()
                "Arıza hafızasını silme komutunu işleme alıyorum Usta."
            }
            cmd.contains("canli") || cmd.contains("veri") || cmd.contains("sensor") -> {
                showLiveData()
                "Canlı veri ekranını açtım Usta. Anlık verileri izliyoruz."
            }
            cmd.contains("durum") || cmd.contains("nasıl") || cmd.contains("yorumla") -> {
                evaluateEngineBrain()
            }
            cmd.contains("devir") || cmd.contains("rpm") -> {
                if (rpm >= 0) "Motor devri şu an dakikada $rpm devir Usta."
                else "Devir verisi henüz ECU'dan akmadı Usta."
            }
            cmd.contains("aku") || cmd.contains("voltaj") -> {
                if (voltage >= 0.0) "Akü voltajı ${"%.2f".format(Locale.US, voltage)} Volt Usta."
                else "Akü voltajı okunmadı Usta."
            }
            cmd.contains("hararet") || cmd.contains("sicaklik") -> {
                if (coolant > -999) "Motor suyu sıcaklığı $coolant derece Usta."
                else "Sıcaklık verisi henüz gelmedi Usta."
            }
            cmd.contains("ana sayfa") || cmd.contains("geri") -> {
                showHome()
                "Ana ekrandayız Usta."
            }
            else -> {
                "Söylediğini tam kestiremedim Usta ama buradayım. Arıza taraması yapmamı veya canlı verileri yorumlamamı isteyebilirsin."
            }
        }

        Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
        seyfiAssistant?.speak(reply)
        log("Seyfi Usta AI: $rawCommand")
    }

    private fun evaluateEngineBrain(): String {
        val notes = mutableListOf<String>()
        if (voltage in 0.1..11.8) notes.add("akü zayıf (${"%.2f".format(Locale.US, voltage)}V)")
        if (coolant > 102) notes.add("hararet yüksek ($coolant°C)")
        if (rpm in 1..900) notes.add("rölanti düşük")

        return if (notes.isEmpty()) {
            "Motorun durumu gayet sıhhatli Usta. Anormal bir durum sezmedim."
        } else {
            "Dikkat Usta! Yapay zeka analizinde bazı sorunlar var: " + notes.joinToString(", ") + ". El atman gerekebilir."
        }
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
                            val msg = "Dinliyorum Usta, neyi kontrol ediyoruz?"
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
        val statusText = if (connected) "● VIECAR / ECU BAĞLI • SEYFİ USTA AKTİF" else "● ADAPTÖR BAĞLI DEĞİL • ÇEVRİMDIŞI MOD"
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
            val decodedBmp = decodeHomeBitmap()
            setImageBitmap(decodedBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            contentDescription = "EFE MOTO açılış ekranı"
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
            side.addView(text("PROFESYONEL MOTOSİKLET TEŞHİSİ", 12, cyan, true).apply { gravity = Gravity.CENTER })
            addGap(side, 6)
            side.addView(text(
                if (connected) "● VIECAR / ECU BAĞLI" else "● ÇEVRİMDIŞI MOD",
                10, if (connected) green else muted, true
            ).apply { gravity = Gravity.CENTER })
            addGap(side, 6)
            side.addView(actionButton("✓  ARIZA TARAMASI BAŞLAT", green) { startDiagnosis() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("▤  ARIZA KODLARI", cyan) { showFaults() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("◉  CANLI VERİLER", blue) { showLiveData() },
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

    // Wİ-Fİ BAĞLANTI & TARAMA MOTORU (ORİJİNAL KORUNMUŞTUR)
    private fun startDiagnosis() {
        seyfiAssistant?.speak("Arıza taramasını başlatıyorum Usta, adaptör verilerini topluyorum.")
        val c = baseScreen("SCAN", true)
        c.addView(header("ECU AKILLI TARAMA"))
        val card = neonCard(null, cyan)
        val progress = CircularProgressView(this)
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(225)))
        progressLine = text("ECU bağlantısı bekleniyor…", 19, white, true).apply { gravity = Gravity.CENTER }
        card.addView(progressLine)
        addGap(card, 10)
        c.addView(card)
        c.addView(actionButton("TARAMAYI İPTAL ET", red) { closeAdapter(); showHome() })
        
        io.execute {
            try {
                updateScan(10, "Wi-Fi Viecar adaptörüne bağlanılıyor…")
                ensureConnected()
                updateScan(40, "ECU protokolü test ediliyor…")
                val support = elm("0100", 5000)
                if (!support.contains("4100")) {
                    throw IllegalStateException("ECU yanıt vermedi, kontağı kontrol et Usta.")
                }
                updateScan(70, "Hafızadaki arıza kodları okunuyor…")
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
                    hasCompletedScan = true
                    if (faults.isEmpty()) {
                        seyfiAssistant?.speak("Tarama bitti Usta. Arıza kodu bulunamadı, motor temiz.")
                    } else {
                        seyfiAssistant?.speak("Tarama bitti Usta. ${faults.size} tane arıza kodu buldum.")
                    }
                    if (currentScreen == "SCAN") showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    seyfiAssistant?.speak("Bağlantı kurulamadı Usta. Wi-Fi ayarlarını ve kontağı kontrol et.")
                    if (currentScreen == "SCAN") {
                        progressLine?.text = "BAĞLANTI BAŞARISIZ\n${e.message}"
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
            c.addView(neonCard("✓  KAYITLI ARIZA YOK", green))
        } else {
            faults.forEach { code ->
                c.addView(neonCard(code, red))
            }
        }
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun confirmClearFaults() {
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
}

// SEYFİ USTA YEREL SESLİ ASİSTAN MOTORU (İNTERNET GEREKTİRMEZ)
class SeyfiUstaAI(
    private val activity: ComponentActivity,
    private val onRecognized: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isSpeaking = false
    private var isListeningActive = false

    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(this@SeyfiUstaAI)
            }
        }

        textToSpeech = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(Locale("tr", "TR"))
                textToSpeech?.setPitch(0.6f)
                textToSpeech?.setSpeechRate(0.95f)

                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { isSpeaking = true; stopListeningSafe() }
                    override fun onDone(id: String?) { isSpeaking = false; activity.runOnUiThread { startListening() } }
                    override fun onError(id: String?) { isSpeaking = false; activity.runOnUiThread { startListening() } }
                })
            }
        }
    }

    fun startListening() {
        if (!isListeningActive) isListeningActive = true
        if (isSpeaking) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    private fun stopListeningSafe() {
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
    }

    fun speak(text: String) {
        stopListeningSafe()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SEYFI_USTA_AI")
    }

    fun release() {
        isListeningActive = false
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
    }

    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(r: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
        activity.runOnUiThread {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isListeningActive && !isSpeaking) startListening()
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
                if (isListeningActive && !isSpeaking) startListening()
            }, 500)
        }
    }
    override fun onPartialResults(p: Bundle?) {}
    override fun onEvent(type: Int, p: Bundle?) {}
}
