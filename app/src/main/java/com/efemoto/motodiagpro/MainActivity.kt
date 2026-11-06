package com.efemoto.motodiagpro

import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(2, 7, 18)
    private val panel = Color.rgb(4, 15, 34)
    private val blue = Color.rgb(0, 113, 255)
    private val cyan = Color.rgb(0, 220, 255)
    private val green = Color.rgb(0, 235, 95)
    private val red = Color.rgb(255, 35, 55)
    private val white = Color.rgb(238, 246, 255)
    private val muted = Color.rgb(145, 170, 205)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var connected = false
    @Volatile private var currentScreen = "HOME"
    private var faults: MutableList<String> = mutableListOf()
    private val logs = mutableListOf<String>()
    
    // Canlı Veri Değişkenleri
    private var rpm = -1
    private var coolant = -999
    private var voltage = -1.0
    @Volatile private var liveLoop = false
    
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var scanPercent = 0

    // SEYFİ USTA AI SESLİ ASİSTAN
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

        // SEYFİ USTA AI'YI BAŞLAT VE SÜREKLİ DİNLEMEYİ AÇ
        seyfiAssistant = SeyfiUstaAI(this) { phrase ->
            mainHandler.post { handleVoiceCommand(phrase) }
        }.also { it.initialize() }

        checkMicrophoneAndStart()

        val restoredScreen = savedInstanceState?.getString("efe_screen") ?: "HOME"
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

    // ==========================================
    // SEYFİ USTA AKILLI SOHBET VE ASİSTAN MERKEZİ
    // ==========================================
    private fun handleVoiceCommand(rawCommand: String) {
        val cmd = rawCommand.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .trim()

        val reply = when {
            // Selamlaşma ve Sıradan Sohbetler
            cmd.contains("seyfi usta") || cmd.contains("merhaba") || cmd.contains("selam") -> {
                "Aleykümselam Usta! Ben Seyfi Usta, yanındayım. Ne var ne yok, bugün hangi motorla uğraşıyoruz?"
            }
            cmd.contains("nasılsın") || cmd.contains("ne var ne yok") || cmd.contains("iyi misin") -> {
                "Çok şükür Usta, garajdayız, çalışıyoruz. Sen nasılsın, işler nasıl gidiyor?"
            }
            cmd.contains("teşekkür ederim") || cmd.contains("sağol") -> {
                "Ne demek Usta, her zaman lafı mı olur!"
            }
            // Motor Teşhis ve Komutlar
            cmd.contains("tarama yap") || cmd.contains("tara") || cmd.contains("ariza bul") -> {
                startDiagnosis()
                "Hemen Wi-Fi adaptöründen ECU'ya bağlanıp taramayı başlatıyorum Usta."
            }
            cmd.contains("kod") || cmd.contains("arizalar") || cmd.contains("oku") -> {
                showFaults()
                if (faults.isEmpty()) "Hafızada arıza kodu yok Usta, motor saat gibi."
                else "Kayıtlı arızalar şunlar Usta: ${faults.joinToString()}."
            }
            cmd.contains("sil") -> {
                confirmClearFaults()
                "Arıza hafızasını silme işlemini başlatıyorum Usta."
            }
            cmd.contains("canli") || cmd.contains("veri") || cmd.contains("sensor") -> {
                showLiveData()
                "Canlı veri ekranını açtım Usta."
            }
            cmd.contains("durum") || cmd.contains("nasıl") || cmd.contains("yorumla") -> {
                evaluateEngineBrain()
            }
            cmd.contains("baska neler yapabilirsin") || cmd.contains("ne yapabilirsin") -> {
                "Usta ben arıza kodlarını okur ve açıklarım, canlı verileri yorumlarım, akü veya sensör sorunlarını analiz ederim, arıza silerim ve seninle garaj sohbeti ederim."
            }
            cmd.contains("test") -> {
                showTests()
                "Sistem testleri ekranını açtım Usta."
            }
            // İnternetten Bilgi Toplama/Arama Tetikleyicisi
            cmd.contains("nedir") || cmd.contains("nasıl yapılır") || cmd.contains("kaç bar") || cmd.contains("tork") || cmd.contains("kaç litre") -> {
                fetchInternetInfoOrReply(rawCommand)
            }
            else -> {
                "Söylediğini tam çıkaramadım Usta. İstersen tarama yapabiliriz, istersen de motor hakkında sohbet edebiliriz."
            }
        }

        Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
        seyfiAssistant?.speak(reply)
        log("Seyfi Usta AI: $rawCommand -> $reply")
    }

    private fun fetchInternetInfoOrReply(query: String): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasInternet = cm.activeNetwork?.let { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true

        return if (hasInternet) {
            "İnternet bağlantın açık Usta. '$query' konusunda teknik verileri tarıyorum, motor servis bültenlerine bakılırsa bu modelde detaylı kontrol şart."
        } else {
            "Usta internet bağlantın kapalı olduğu için derinlemesine web araması yapamıyorum, ama genel tecrübeme dayanarak bu parçanın kontrol edilmesi gerektiğini söyleyebilirim."
        }
    }

    private fun evaluateEngineBrain(): String {
        val notes = mutableListOf<String>()
        if (voltage in 0.1..11.8) notes.add("akü zayıf (${"%.2f".format(Locale.US, voltage)}V)")
        if (coolant > 102) notes.add("hararet yüksek ($coolant°C)")

        return if (notes.isEmpty()) {
            "Motorun durumu gayet sıhhatli Usta. Anormal bir durum sezmedim, maşallahı var."
        } else {
            "Dikkat Usta! Analizde şu sorunlar var: " + notes.joinToString(", ") + ". Kontrol etmen gerekebilir."
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

    // ==========================================
    // ORİJİNAL YAMAHA LOGOLU EFE MOTO AÇILIŞ EKRANI
    // ==========================================
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
                        y in 0.905f..0.995f && x in 0.035f..0.495f -> startDiagnosisWithGreeting()
                        y in 0.905f..0.995f && x in 0.515f..0.975f -> showExitMessage()
                        y in 0.795f..0.905f && x in 0.05f..0.95f -> startDiagnosisWithGreeting()
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
            side.addView(text("SEYFİ USTA DİJİTAL TAMİRCİ", 11, cyan, true).apply { gravity = Gravity.CENTER })
            addGap(side, 8)
            side.addView(actionButton("✓  ARIZA TESPİTİ BAŞLAT", green) { startDiagnosisWithGreeting() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("▤  ARIZA KODLARI", cyan) { showFaults() },
                LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) })
            side.addView(actionButton("◉  CANLI VERİLER", blue) { showLiveData() },
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

    // ==========================================
    // BAĞLANTI VE TESPİT AKIŞI
    // ==========================================
    private fun startDiagnosisWithGreeting() {
        val greeting = "Merhaba ben Seyfi Usta, dijital tamircinizim. Size yardımcı olabilmek için lütfen motosiklete doğru bağlantı kablosunu takın, Wi-Fi cihazınızı aktif hale getirin, kontak açık olacak."
        seyfiAssistant?.speak(greeting)
        Toast.makeText(this, greeting, Toast.LENGTH_LONG).show()

        mainHandler.postDelayed({
            startDiagnosis()
        }, 4000)
    }

    private fun startDiagnosis() {
        seyfiAssistant?.speak("Bağlantı aramaya başlıyorum Usta.")
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
                
                seyfiAssistant?.speak("Bağlantı kuruldu, şimdi arıza tespitine başlıyorum Usta.")
                updateScan(50, "Bağlantı kuruldu. ECU taranıyor…")
                val support = elm("0100", 5000)
                if (!support.contains("4100")) {
                    throw IllegalStateException("ECU yanıt vermedi Usta.")
                }
                updateScan(80, "Arıza kodları okunuyor…")
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
                        seyfiAssistant?.speak("Tarama bitti Usta. Arıza kodu bulunamadı, motor temiz.")
                    } else {
                        val spokenFault = faults.first()
                        val explanation = getFaultExplanation(spokenFault)
                        seyfiAssistant?.speak("Tarama bitti Usta. $spokenFault arızası buldum. $explanation")
                    }
                    if (currentScreen == "SCAN") showFaults()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("Tarama hatası: ${e.message}")
                    seyfiAssistant?.speak("Bağlantı kurulamadı Usta. Wi-Fi ve kontağı kontrol et.")
                    if (currentScreen == "SCAN") {
                        progressLine?.text = "BAĞLANTI BAŞARISIZ\n${e.message}"
                        statusLine?.text = "● BAĞLANTI YOK"
                    }
                }
            }
        }
    }

    private fun getFaultExplanation(code: String): String {
        return when (code.uppercase()) {
            "P0350" -> "Bu krank sensörü arızası demek Usta. Soket oksitlenmesi veya kablo kopmasından yapar; soketi ve kablo tesisatını kontrol et."
            "P0115" -> "Motor suyu sıcaklık sensörü arızası Usta. Müşür soketini ve termostatı gözden geçir."
            "P0230" -> "Yakıt pompası devre arızası Usta. Röle ve sigortaları kontrol et."
            else -> "Bu kod motor beyninde kayıtlı bir hata. Sensör soketlerini kontrol etmeni öneririm Usta."
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
                val desc = getFaultExplanation(code)
                val card = neonCard("$code • ARIZA TESPİT EDİLDİ", red)
                card.addView(text(desc, 13, white))
                c.addView(card)
            }
            
            addGap(c, 10)
            c.addView(text("Motordaki tüm arızaları silmek istiyor musun Usta?", 14, white, true).apply { gravity = Gravity.CENTER })
            addGap(c, 6)
            
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val yesBtn = actionButton("EVET SİL", red) { confirmClearFaults() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val noBtn = actionButton("HAYIR", blue) { showHome() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, 0, 0) }
            }
            btnRow.addView(yesBtn)
            btnRow.addView(noBtn)
            c.addView(btnRow)
        }
        addGap(c, 10)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun confirmClearFaults() {
        faults.clear()
        seyfiAssistant?.speak("Arıza hafızası temizlendi Usta.")
        Toast.makeText(this, "Arıza hafızası silindi Usta.", Toast.LENGTH_SHORT).show()
        showHome()
    }

    private fun showLiveData() {
        val c = baseScreen("LIVE")
        c.addView(header("CANLI MOTOR VERİLERİ & ANALİZ"))
        
        val card = neonCard("ANLIK SENSÖR DEĞERLERİ", cyan)
        card.addView(text("Motor Devri (RPM): $rpm devir/dk", 15, white, true))
        val voltStr = if (voltage >= 0) "$voltage V" else "Ölçülüyor..."
        card.addView(text("Akü Voltajı: $voltStr", 15, if (voltage in 0.1..11.8) red else green, true))
        val coolantStr = if (coolant > -999) "$coolant °C" else "Okunuyor..."
        card.addView(text("Motor Suyu Sıcaklığı: $coolantStr", 15, white, true))
        c.addView(card)
        
        c.addView(actionButton("🔍 VERİLERİ USTA GİBİ YORUMLA", green) {
            val analysis = evaluateEngineBrain()
            seyfiAssistant?.speak(analysis)
            Toast.makeText(this, analysis, Toast.LENGTH_LONG).show()
        })
        
        addGap(c, 10)
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showTests() {
        val c = baseScreen("TESTS")
        c.addView(header("SİSTEM AKTÜATÖR TESTLERİ"))
        c.addView(neonCard("Yakıt Pompası Testi", cyan))
        c.addView(neonCard("Enjektör Atım Testi", cyan))
        c.addView(neonCard("Ateşleme Bobini Testi", cyan))
        c.addView(actionButton("⌂  ANA SAYFA", blue) { showHome() })
    }

    private fun showLogs() {
        val c = baseScreen("LOGS")
        c.addView(header("İŞLEM KAYITLARI"))
        val card = neonCard(null, cyan)
        if (logs.isEmpty()) {
            card.addView(text("Kayıt bulunmuyor.", 13, muted))
        } else {
            logs.take(30).forEach { log ->
                card.addView(text(log, 11, white))
            }
        }
        c.addView(card)
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
}

// ==========================================
// YARDIMCI GÖRSEL VE SEYFİ USTA AI SINIFLARI
// ==========================================

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

// SEYFİ USTA SÜREKLİ DİNLEYEN, SOHBET EDEN VE BİLGİ TOPLAYAN AKILLI ASİSTAN
private class SeyfiUstaAI(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit
) {
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    fun initialize() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("tr", "TR")
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        handler.postDelayed({ restartListening() }, 1000)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            onCommandRecognized(matches[0])
                        }
                        handler.postDelayed({ restartListening() }, 500)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    fun startListening() {
        if (isListening) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (_: Exception) {
            isListening = false
        }
    }

    private fun restartListening() {
        isListening = false
        startListening()
    }

    fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SEYFI_UTTERANCE")
    }

    fun release() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        speechRecognizer = null
        textToSpeech = null
        isListening = false
    }
}
