package com.sakano.ff

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.app.DownloadManager
import android.database.Cursor
import android.provider.Settings
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.StatFs

class MainActivity : Activity() {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var root: LinearLayout
    private var currentPage = "home"
    private var pendingRecording = false
    private var statusView: TextView? = null
    private var timerView: TextView? = null
    private var recordButton: Button? = null
    private var pauseButton: Button? = null
    private var errorView: TextView? = null
    private var pendingInstallUri: Uri? = null

    private val bg = Color.rgb(10, 12, 16)
    private val cardColor = Color.rgb(22, 26, 33)
    private val white = Color.WHITE
    private val muted = Color.rgb(151, 161, 176)
    private val red = Color.rgb(239, 55, 72)
    private val green = Color.rgb(42, 203, 119)
    private val amber = Color.rgb(255, 177, 66)
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!isFinishing && currentPage == "home") refreshHomeStatus()
            handler.postDelayed(this, 1000)
        }
    }

    data class VideoItem(val uri: Uri, val name: String, val size: Long, val date: Long, val duration: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sakano_settings", MODE_PRIVATE)
        // Configuração inicial equilibrada para aparelhos de entrada.
        val savedAudioMode = prefs.getString("audio_mode", if (prefs.getBoolean("audio", false)) "mic" else "none") ?: "none"
        val normalizedAudioMode = if (savedAudioMode == "mix") "none" else savedAudioMode
        prefs.edit()
            .putString("audio_mode", normalizedAudioMode)
            .putBoolean("audio", normalizedAudioMode == "mic")
            .putInt("resolution", prefs.getInt("resolution", 720))
            .putInt("fps", prefs.getInt("fps", 30))
            .putString("quality", prefs.getString("quality", "Normal") ?: "Normal")
            .putString("audio_mode", prefs.getString("audio_mode", if (prefs.getBoolean("audio", false)) "mic" else "none") ?: "none")
            .putBoolean("game_mode", prefs.getBoolean("game_mode", true))
            .apply()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        checkForRequiredUpdate()
    }

    private fun checkForRequiredUpdate(manual: Boolean = false) {
        showUpdateChecking()
        Thread {
            try {
                val connection = (URL("https://api.github.com/repos/cagocagao3-hub/SakanoFF/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SakanoFF-Android")
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                connection.disconnect()
                val tag = json.optString("tag_name", "")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    if (item.optString("name").endsWith(".apk", true)) { apkUrl = item.optString("browser_download_url"); break }
                }
                val currentCode = if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt() else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
                runOnUiThread {
                    val currentName = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    if (isVersionNewer(tag.removePrefix("v"), currentName) && !apkUrl.isNullOrBlank()) showRequiredUpdate(tag.removePrefix("v"), apkUrl!!)
                    else if (manual) showUpdates("Seu aplicativo já está na versão mais recente.") else showHome()
                }
            } catch (_: Exception) {
                runOnUiThread { if (manual) showUpdates("Não foi possível consultar o GitHub. Verifique sua internet e tente novamente.") else showHome() }
            }
        }.start()
    }

    private fun showUpdates(message: String = "Verifique se existe uma nova versão disponível.") {
        startPage("updates", "Atualizações")
        val panel = card()
        panel.addView(label("SAKANO FF", 11f, red, true))
        space(panel, 8)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "4.0.1"
        panel.addView(label("Versão instalada: $version", 17f, white, true))
        space(panel, 10)
        panel.addView(label(message, 13f, muted))
        space(panel, 18)
        panel.addView(actionButton("VERIFICAR ATUALIZAÇÕES") { checkForRequiredUpdate(manual = true) }, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 })
        root.addView(label("As atualizações são baixadas das Releases oficiais do Sakano FF. A instalação é confirmada pelo Android.", 12f, muted))
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }; val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private fun showUpdateChecking() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(28, 28, 28, 28); setBackgroundColor(bg) }
        box.addView(label("SAKANO FF", 28f, white, true).apply { gravity = Gravity.CENTER })
        space(box, 14)
        box.addView(label("Verificando atualizações...", 15f, muted).apply { gravity = Gravity.CENTER })
        setContentView(box)
    }

    private fun showRequiredUpdate(version: String, apkUrl: String) {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(26, 30, 26, 30); setBackgroundColor(bg) }
        val panel = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 30, 24, 26) }
        panel.addView(label("SAKANO FF", 13f, red, true).apply { gravity = Gravity.CENTER })
        space(panel, 18)
        panel.addView(label("Uma nova versão está disponível", 25f, white, true).apply { gravity = Gravity.CENTER; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER })
        space(panel, 12)
        panel.addView(label("Versão $version", 17f, green, true).apply { gravity = Gravity.CENTER })
        space(panel, 12)
        panel.addView(label("Esta atualização é obrigatória para continuar usando o aplicativo. Atualize para receber as melhorias e correções mais recentes.", 15f, muted).apply { gravity = Gravity.CENTER; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER })
        space(panel, 24)
        panel.addView(actionButton("BAIXAR E ATUALIZAR") { downloadAndInstall(apkUrl) }, LinearLayout.LayoutParams(-1, -2))
        space(panel, 12)
        panel.addView(label("A instalação será confirmada pelo Android.", 11f, muted).apply { gravity = Gravity.CENTER })
        page.addView(panel, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
    }

    private fun downloadAndInstall(apkUrl: String) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Atualização Sakano FF")
                .setDescription("Baixando a versão mais recente...")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "SakanoFF-update.apk")
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)

            var downloadId = -1L
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                    try {
                        unregisterReceiver(this)
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        manager.query(query).use { cursor ->
                            if (!cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                                throw Exception("O download não foi concluído. Tente novamente.")
                            }
                        }
                        val uri = manager.getUriForDownloadedFile(downloadId)
                            ?: throw Exception("Não foi possível localizar o APK baixado.")
                        openInstaller(uri)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Falha na atualização: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Registra o receptor antes de iniciar o download para não perder o aviso de conclusão.
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            downloadId = manager.enqueue(request)
            Toast.makeText(this, "Download iniciado. Acompanhe pela notificação.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao iniciar atualização: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInstaller(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = uri
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(settingsIntent, 4102)
            Toast.makeText(this, "Permita instalar apps pelo Sakano FF e volte ao aplicativo.", Toast.LENGTH_LONG).show()
            return
        }
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(install)
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized && currentPage == "home") refreshHomeStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun label(text: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(android.view.View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 18, 18, 18)
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = 22f
            setStroke(1, Color.rgb(42, 49, 60))
        }
    }

    private fun actionButton(title: String, secondary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 14f
            isAllCaps = false
            setTextColor(white)
            background = GradientDrawable().apply {
                setColor(if (secondary) Color.rgb(43, 51, 63) else red)
                cornerRadius = 16f
            }
            setPadding(14, 10, 14, 10)
            minHeight = 52
            setOnClickListener { action() }
        }

    private fun startPage(key: String, title: String) {
        currentPage = key
        statusView = null
        timerView = null
        recordButton = null
        pauseButton = null
        errorView = null
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            setFillViewport(true)
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 28)
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menu = label("☰", 28f, white, true).apply {
            setPadding(0, 0, 18, 0)
            setOnClickListener { openMenu() }
        }
        header.addView(menu)
        val logo = ImageView(this).apply {
            setImageResource(com.sakano.ff.R.drawable.sakano_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cardColor); setStroke(2, red) }
            clipToOutline = true
        }
        header.addView(logo, LinearLayout.LayoutParams(54, 54).apply { rightMargin = 12 })
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        branding.addView(label("SAKANO FF", 21f, white, true))
        branding.addView(label("GRAVADOR DE TELA", 10f, red, true))
        header.addView(branding)
        root.addView(header)
        space(root, if (title.isBlank()) 17 else 23)
        if (title.isNotBlank()) {
            root.addView(label(title, 24f, white, true))
            space(root, 5)
        }
    }

    private fun openMenu() {
        val options = arrayOf(
            "⌂   Início",
            "▣   Biblioteca",
            "⚙   Ajustes de gravação",
            "ⓘ   Sobre o Sakano FF",
            "⇧   Atualizações"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("SAKANO FF")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHome()
                    1 -> showVideos()
                    2 -> showSettings()
                    3 -> showAbout()
                    4 -> showUpdates()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showHome() {
        startPage("home", "")

        // Tela principal focada exclusivamente na captura, sem cards informativos.
        space(root, 34)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 28, 22, 24)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(29, 34, 44), Color.rgb(15, 18, 24))).apply {
                cornerRadius = 30f
                setStroke(1, Color.rgb(58, 66, 80))
            }
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        val live = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(13, 7, 13, 7)
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 58, 47)); cornerRadius = 40f
            }
        }
        live.addView(label("●", 10f, green, true))
        val liveText = label("  CAPTURA PRONTA", 10f, green, true)
        live.addView(liveText)
        panel.addView(live)
        space(panel, 26)

        statusView = label("PRONTO PARA GRAVAR", 18f, white, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.045f
        }
        panel.addView(statusView)
        space(panel, 5)
        timerView = label("00:00:00", 48f, white, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }
        panel.addView(timerView)
        space(panel, 12)
        errorView = label("", 11f, amber).apply { gravity = Gravity.CENTER }
        panel.addView(errorView)
        space(panel, 22)

        recordButton = actionButton("●   INICIAR GRAVAÇÃO") { onRecordButton() }.apply {
            textSize = 16f
            minHeight = 66
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(255, 65, 84), Color.rgb(218, 39, 59))).apply { cornerRadius = 19f }
        }
        panel.addView(recordButton, LinearLayout.LayoutParams(-1, -2))
        space(panel, 12)
        pauseButton = actionButton("Ⅱ   PAUSAR GRAVAÇÃO", true) { togglePause() }.apply {
            textSize = 14f
            minHeight = 58
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 50, 63)); cornerRadius = 18f
                setStroke(1, Color.rgb(64, 74, 90))
            }
        }
        panel.addView(pauseButton, LinearLayout.LayoutParams(-1, -2))
        space(panel, 18)

        val mode = label("${prefs.getInt("resolution", 720)}p  ·  ${prefs.getInt("fps", 30)} FPS   |   ${audioModeLabel()}", 11f, muted, true).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(mode)
        space(root, 18)
        refreshHomeStatus()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun availableStorage(): String = try {
        val path = android.os.Environment.getExternalStorageDirectory().path
        val stat = StatFs(path)
        val bytes = stat.availableBytes
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        String.format(Locale.getDefault(), "%.1f GB livres", gb)
    } catch (_: Exception) { "Não disponível" }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private fun refreshHomeStatus() {
        if (currentPage != "home" || statusView == null) return
        val status = prefs.getString("recording_status", "Pronto") ?: "Pronto"
        val base = prefs.getLong("recording_elapsed_base", 0L)
        val started = prefs.getLong("recording_started_at", 0L)
        val elapsed = if (status == "Gravando" && started > 0L) base + (SystemClock.elapsedRealtime() - started) else base
        timerView?.text = formatDuration(elapsed)
        when (status) {
            "Gravando" -> {
                statusView?.text = "●  GRAVANDO"
                statusView?.setTextColor(red)
                recordButton?.text = "■  PARAR E SALVAR"
                pauseButton?.text = "Ⅱ  PAUSAR GRAVAÇÃO"
                pauseButton?.isEnabled = true
            }
            "Pausado" -> {
                statusView?.text = "Ⅱ  PAUSADO"
                statusView?.setTextColor(amber)
                recordButton?.text = "■  PARAR E SALVAR"
                pauseButton?.text = "▶  CONTINUAR GRAVAÇÃO"
                pauseButton?.isEnabled = true
            }
            "Preparando", "Finalizando" -> {
                statusView?.text = if (status == "Preparando") "PREPARANDO..." else "SALVANDO VÍDEO..."
                statusView?.setTextColor(amber)
                recordButton?.text = "AGUARDE..."
                pauseButton?.isEnabled = false
            }
            "Erro" -> {
                statusView?.text = "FALHA NA GRAVAÇÃO"
                statusView?.setTextColor(red)
                recordButton?.text = "●  TENTAR NOVAMENTE"
                pauseButton?.isEnabled = false
            }
            else -> {
                statusView?.text = "Pronto para gravar"
                statusView?.setTextColor(white)
                recordButton?.text = "●  INICIAR GRAVAÇÃO"
                pauseButton?.text = "Ⅱ  PAUSAR GRAVAÇÃO"
                pauseButton?.isEnabled = false
            }
        }
        val error = prefs.getString("recording_error", "") ?: ""
        errorView?.text = error
    }

    private fun onRecordButton() {
        val status = prefs.getString("recording_status", "Pronto") ?: "Pronto"
        if (status == "Gravando" || status == "Pausado") {
            stopRecording()
        } else if (status == "Preparando" || status == "Finalizando") {
            Toast.makeText(this, "Aguarde a operação atual.", Toast.LENGTH_SHORT).show()
        } else {
            checkAudioPermission()
        }
    }

    private fun togglePause() {
        val status = prefs.getString("recording_status", "Pronto")
        val action = if (status == "Pausado") RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
        if (status == "Gravando" || status == "Pausado") {
            startService(Intent(this, RecordingService::class.java).setAction(action))
        }
    }

    private fun checkAudioPermission() {
        val audioMode = prefs.getString("audio_mode", "none") ?: "none"
        if ((audioMode == "mic" || audioMode == "internal") && Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecording = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201)
            return
        }
        launchProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && pendingRecording) {
            pendingRecording = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchProjection()
            else Toast.makeText(this, "Permissão de áudio não concedida. Escolha outra fonte nas configurações.", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchProjection() {
        prefs.edit().putString("recording_status", "Preparando").putString("recording_error", "").putLong("recording_elapsed_base", 0L).apply()
        refreshHomeStatus()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 101)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102) {
            val uri = pendingInstallUri
            pendingInstallUri = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                if (uri != null) openInstaller(uri)
            } else {
                Toast.makeText(this, "A permissão de instalação não foi concedida.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != 101) return
        if (resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
                putExtra("audioMode", prefs.getString("audio_mode", "none") ?: "none")
                putExtra("audio", (prefs.getString("audio_mode", "none") ?: "none") == "mic")
                putExtra("resolution", prefs.getInt("resolution", 720))
                putExtra("fps", prefs.getInt("fps", 30))
                putExtra("gameMode", prefs.getBoolean("game_mode", true))
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            } catch (e: Exception) {
                prefs.edit().putString("recording_status", "Erro").putString("recording_error", e.message ?: "Não foi possível iniciar o serviço.").apply()
            }
        } else {
            prefs.edit().putString("recording_status", "Pronto").putString("recording_error", "Autorização de captura cancelada.").apply()
        }
        refreshHomeStatus()
    }

    private fun stopRecording() {
        try {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        } catch (e: Exception) {
            prefs.edit().putString("recording_error", e.message ?: "Não foi possível parar a gravação.").apply()
        }
    }

    private fun showSettings() {
        startPage("settings", "Ajustes")
        val intro = card().apply {
            addView(label("CONTROLE DA GRAVAÇÃO", 11f, red, true))
            space(this, 5)
            addView(label("Configure a imagem e o áudio do seu vídeo.", 13f, muted))
        }
        root.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 })

        val gameModeEnabled = prefs.getBoolean("game_mode", true)
        setting("Modo Jogo", if (gameModeEnabled) "Ativado · 720p · 30 FPS · Econômica" else "Desativado · configurações personalizadas") {
            val enable = !prefs.getBoolean("game_mode", true)
            val edit = prefs.edit().putBoolean("game_mode", enable)
            if (enable) {
                edit.putInt("resolution", 720)
                    .putInt("fps", 30)
                    .putString("quality", "Econômica")
            }
            edit.apply()
            showSettings()
        }
        setting("Resolução", "${prefs.getInt("resolution", 720)}p") {
            choose("Resolução", arrayOf("480p", "720p", "1080p", "1440p")) { i ->
                prefs.edit().putInt("resolution", listOf(480, 720, 1080, 1440)[i]).apply(); showSettings()
            }
        }
        setting("Taxa de quadros", "${prefs.getInt("fps", 30)} FPS") {
            choose("Taxa de quadros", arrayOf("24 FPS", "30 FPS", "60 FPS")) { i ->
                prefs.edit().putInt("fps", listOf(24, 30, 60)[i]).apply(); showSettings()
            }
        }
        setting("Qualidade", prefs.getString("quality", "Normal") ?: "Normal") {
            choose("Qualidade do vídeo", arrayOf("Econômica", "Normal", "Alta", "Máxima")) { i ->
                prefs.edit().putString("quality", listOf("Econômica", "Normal", "Alta", "Máxima")[i]).apply(); showSettings()
            }
        }
        setting("Áudio da gravação", audioModeLabel()) {
            choose("Fonte de áudio", arrayOf("Sem áudio", "Áudio interno", "Microfone")) { i ->
                val mode = listOf("none", "internal", "mic")[i]
                prefs.edit().putString("audio_mode", mode).putBoolean("audio", mode == "mic").apply()
                if (mode == "internal" && Build.VERSION.SDK_INT < 29) Toast.makeText(this, "Áudio interno exige Android 10 ou superior.", Toast.LENGTH_LONG).show()
                showSettings()
            }
        }
        setting("Pasta de gravação", "Movies/Sakano FF") {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Pasta de gravação")
                .setMessage("Seus vídeos ficam em Movies/Sakano FF e também aparecem na galeria do aparelho.")
                .setPositiveButton("Entendi", null).show()
        }
    }

    private fun audioModeLabel(): String = when (prefs.getString("audio_mode", "none")) {
        "mic" -> "Microfone"
        "internal" -> "Áudio interno"
        else -> "Sem áudio"
    }

    private fun setting(title: String, value: String, action: () -> Unit) {
        val panel = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(label(title, 15f, white, true))
        textCol.addView(label(value, 12f, muted))
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 28f, red, true))
        panel.addView(row)
        panel.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 10
        root.addView(panel, lp)
    }

    private fun choose(title: String, options: Array<String>, selected: (Int) -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title)
            .setItems(options) { _, index -> selected(index) }.setNegativeButton("Cancelar", null).show()
    }

    private fun getVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION)
            try {
                contentResolver.query(collection, projection, "${MediaStore.Video.Media.RELATIVE_PATH}=?", arrayOf("Movies/Sakano FF/"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                        result.add(VideoItem(uri, c.getString(1) ?: "Vídeo", c.getLong(2), c.getLong(3), c.getLong(4)))
                    }
                }
            } catch (_: Exception) {}
        } else {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Sakano FF")
            dir.listFiles()?.filter { it.extension.equals("mp4", true) }?.sortedByDescending { it.lastModified() }?.forEach { file ->
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.sakano.ff.provider", file)
                result.add(VideoItem(uri, file.name, file.length(), file.lastModified() / 1000, 0))
            }
        }
        return result
    }

    private fun showVideos() {
        startPage("videos", "Biblioteca")
        val videos = getVideos()
        val summary = card().apply {
            addView(label("SEUS VÍDEOS", 11f, red, true))
            space(this, 5)
            addView(label("${videos.size} ${if (videos.size == 1) "gravação salva" else "gravações salvas"}", 19f, white, true))
            addView(label("Armazenados em Movies/Sakano FF", 12f, muted))
        }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 })
        if (videos.isEmpty()) {
            val empty = card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(22, 30, 22, 30)
                addView(label("▣", 42f, red, true).apply { gravity = Gravity.CENTER })
                space(this, 12)
                addView(label("Sua biblioteca está pronta", 18f, white, true).apply { gravity = Gravity.CENTER })
                space(this, 5)
                addView(label("Quando você salvar sua primeira gravação, ela aparecerá aqui para assistir ou compartilhar.", 13f, muted).apply { gravity = Gravity.CENTER; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER })
                space(this, 18)
                addView(actionButton("●  IR PARA GRAVAÇÃO") { showHome() }, LinearLayout.LayoutParams(-1, -2))
            }
            root.addView(empty)
            return
        }
        videos.forEach { video ->
            val panel = card()
            panel.addView(label(video.name, 15f, white, true))
            space(panel, 5)
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(video.date * 1000))
            panel.addView(label("${video.size / 1024 / 1024} MB  •  $date  •  ${formatDuration(video.duration)}", 11f, muted))
            space(panel, 12)
            panel.addView(actionButton("▶  ASSISTIR") {
                startActivity(Intent(this, PlaybackActivity::class.java).putExtra("uri", video.uri.toString()))
            })
            space(panel, 7)
            panel.addView(actionButton("↗  COMPARTILHAR", true) {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, video.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    startActivity(Intent.createChooser(send, "Compartilhar vídeo"))
                } catch (_: Exception) { Toast.makeText(this, "Não foi possível compartilhar este vídeo.", Toast.LENGTH_SHORT).show() }
            })
            space(panel, 7)
            panel.addView(actionButton("EXCLUIR VÍDEO", true) {
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Excluir vídeo?")
                    .setMessage(video.name).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        try { contentResolver.delete(video.uri, null, null); Toast.makeText(this, "Vídeo excluído.", Toast.LENGTH_SHORT).show(); showVideos() }
                        catch (_: Exception) { Toast.makeText(this, "Não foi possível excluir.", Toast.LENGTH_LONG).show() }
                    }.show()
            })
            root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
    }

    private fun socialIconButton(drawableId: Int, description: String, url: String, tint: Int) {
        val icon = ImageView(this).apply {
            setImageResource(drawableId)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(9, 9, 9, 9)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (description == "Instagram") {
                    colors = intArrayOf(Color.rgb(131, 58, 180), Color.rgb(225, 48, 108), Color.rgb(252, 175, 69))
                    orientation = GradientDrawable.Orientation.TL_BR
                } else {
                    setColor(tint)
                }
                setStroke(0, Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            elevation = 5f
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "Não foi possível abrir o link.", Toast.LENGTH_SHORT).show() }
            }
        }
        val lp = LinearLayout.LayoutParams(96, 96).apply { leftMargin = 16; rightMargin = 16 }
        socialIconsRow?.addView(icon, lp)
    }

    private var socialIconsRow: LinearLayout? = null

    private fun showAbout() {
        startPage("about", "Sobre o Sakano FF")
        val hero = card().apply {
            setPadding(20, 22, 20, 22)
            addView(label("SAKANO FF 4.0.1", 22f, white, true))
            space(this, 7)
            addView(label("GRAVADOR LEVE PARA CELULARES DE ENTRADA", 10f, red, true))
            space(this, 13)
            addView(label("Um gravador de tela Android em desenvolvimento, pensado para funcionar com eficiência em aparelhos com pouca memória e recursos modestos.", 14f, muted))
        }
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })

        val mission = card()
        mission.addView(label("NOSSA PROPOSTA", 11f, red, true))
        space(mission, 8)
        mission.addView(label("Gravação simples, controles essenciais e foco em baixo consumo, sem recursos desnecessários.", 13f, muted))
        root.addView(mission, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })

        val features = card()
        features.addView(label("RECURSOS", 11f, red, true))
        space(features, 8)
        features.addView(label("• Gravação de tela\n• Biblioteca para assistir e compartilhar\n• Áudio interno quando permitido pelo Android e microfone\n• Modo Jogo com perfil leve de 720p e 30 FPS", 13f, muted))
        root.addView(features, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 22 })

        val socialArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 18)
        }
        socialArea.addView(label("SIGA O SAKANO FF", 11f, muted, true).apply { gravity = Gravity.CENTER })
        space(socialArea, 16)
        socialIconsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        socialArea.addView(socialIconsRow)
        socialIconButton(R.drawable.ic_youtube_social, "YouTube", "https://youtube.com/@sakanoffz?feature=shared", Color.rgb(210, 35, 48))
        socialIconButton(R.drawable.ic_instagram_social, "Instagram", "https://www.instagram.com/sakanoffz?gwpx=MXU2ZW0yc20zMDUzNw==", Color.rgb(155, 65, 145))
        root.addView(socialArea, LinearLayout.LayoutParams(-1, -2))
        val footer = label("SAKANO FF  •  DESENVOLVIMENTO EM ANDAMENTO", 10f, muted, true).apply { gravity = Gravity.CENTER }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4; bottomMargin = 18 })
    }

}
