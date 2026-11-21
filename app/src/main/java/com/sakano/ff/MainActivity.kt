package com.sakano.ff

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
        showHome()
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
        space(root, 25)
        root.addView(label(title, 24f, white, true))
        space(root, 5)
    }

    private fun openMenu() {
        val options = arrayOf(
            "⌂   Início",
            "▣   Biblioteca",
            "⚙   Ajustes de gravação",
            "◷   Estatísticas",
            "✦   Desempenho",
            "ⓘ   Sobre o Sakano FF"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("SAKANO FF")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHome()
                    1 -> showVideos()
                    2 -> showSettings()
                    3 -> showStats()
                    4 -> showPerformance()
                    5 -> showAbout()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showHome() {
        startPage("home", "Central de gravação")
        root.addView(label("Tudo pronto para capturar sua tela.", 13f, muted))
        space(root, 18)

        val panel = card()
        root.addView(panel)
        panel.addView(label("STATUS DA GRAVAÇÃO", 11f, muted, true))
        space(panel, 13)
        statusView = label("Pronto para gravar", 23f, white, true)
        panel.addView(statusView)
        space(panel, 6)
        timerView = label("00:00:00", 34f, white, true).apply { letterSpacing = 0.06f }
        panel.addView(timerView)
        errorView = label("", 12f, red)
        panel.addView(errorView)
        space(panel, 15)
        recordButton = actionButton("●  INICIAR GRAVAÇÃO") { onRecordButton() }
        panel.addView(recordButton)
        space(panel, 8)
        pauseButton = actionButton("Ⅱ  PAUSAR GRAVAÇÃO", true) { togglePause() }
        panel.addView(pauseButton)
        space(panel, 8)
        panel.addView(label("Os vídeos são salvos automaticamente em Movies/Sakano FF.", 11f, muted))

        space(root, 16)
        val config = card()
        root.addView(config)
        config.addView(label("CONFIGURAÇÃO ATUAL", 11f, red, true))
        space(config, 9)
        val gameModeNow = prefs.getBoolean("game_mode", true)
        val effectiveResolutionNow = if (gameModeNow) minOf(prefs.getInt("resolution", 720), 720) else prefs.getInt("resolution", 720)
        val effectiveFpsNow = if (gameModeNow) minOf(prefs.getInt("fps", 30), 30) else prefs.getInt("fps", 30)
        config.addView(label("${effectiveResolutionNow}p  •  ${effectiveFpsNow} FPS", 21f, white, true))
        config.addView(label("${prefs.getString("quality", "Normal")}  •  ${audioModeLabel()}  •  ${if (prefs.getBoolean("game_mode", true)) "Perfil jogo" else "Perfil personalizado"}", 12f, muted))

        space(root, 16)
        val stats = card()
        root.addView(stats)
        stats.addView(label("ARMAZENAMENTO DISPONÍVEL", 11f, muted, true))
        space(stats, 7)
        stats.addView(label(availableStorage(), 20f, white, true))
        stats.addView(label("${getVideos().size} vídeo(s) salvos no Sakano FF", 12f, muted))
        space(root, 18)
        root.addView(label("SAKANO FF  •  VERSÃO 3.2", 10f, muted))
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
        startPage("settings", "Configurações")
        setting("Resolução", "${prefs.getInt("resolution", 720)}p") {
            choose("Resolução", arrayOf("480p", "720p", "1080p", "1440p")) { i ->
                prefs.edit().putInt("resolution", listOf(480, 720, 1080, 1440)[i]).apply()
                showSettings()
            }
        }
        setting("Taxa de quadros", "${prefs.getInt("fps", 30)} FPS") {
            choose("FPS", arrayOf("24 FPS", "30 FPS", "60 FPS")) { i ->
                prefs.edit().putInt("fps", listOf(24, 30, 60)[i]).apply()
                showSettings()
            }
        }
        setting("Qualidade", prefs.getString("quality", "Normal") ?: "Normal") {
            choose("Qualidade do vídeo", arrayOf("Econômica", "Normal", "Alta", "Máxima")) { i ->
                prefs.edit().putString("quality", listOf("Econômica", "Normal", "Alta", "Máxima")[i]).apply()
                showSettings()
            }
        }
        setting("Modo desempenho para jogos", if (prefs.getBoolean("game_mode", true)) "Ativado • até 720p / 30 FPS" else "Desativado • usa os valores escolhidos") {
            prefs.edit().putBoolean("game_mode", !prefs.getBoolean("game_mode", true)).apply()
            showSettings()
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
                .setMessage("Seus arquivos MP4 ficam na pasta Movies/Sakano FF e também aparecem na galeria do aparelho.")
                .setPositiveButton("OK", null).show()
        }
        space(root, 10)
        val note = card()
        root.addView(note)
        note.addView(label("DICA DE DESEMPENHO", 12f, red, true))
        space(note, 8)
        note.addView(label("O perfil jogo limita a captura a 720p e 30 FPS. Use Sem áudio quando quiser reduzir ainda mais o processamento.", 13f, muted))
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
        startPage("videos", "Biblioteca de vídeos")
        val videos = getVideos()
        root.addView(label("${videos.size} arquivo(s) • Movies/Sakano FF", 12f, muted))
        space(root, 12)
        if (videos.isEmpty()) {
            val empty = card(); root.addView(empty)
            empty.addView(label("▣", 40f, red, true))
            space(empty, 8)
            empty.addView(label("Sua biblioteca está vazia", 18f, white, true))
            empty.addView(label("Quando finalizar uma gravação, o vídeo aparecerá aqui.", 12f, muted))
            return
        }
        videos.forEach { video ->
            val panel = card()
            panel.addView(label(video.name, 15f, white, true))
            space(panel, 5)
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(video.date * 1000))
            panel.addView(label("${video.size / 1024 / 1024} MB  •  $date  •  ${formatDuration(video.duration)}", 11f, muted))
            space(panel, 10)
            panel.addView(actionButton("▶  REPRODUZIR") {
                startActivity(Intent(this, PlaybackActivity::class.java).putExtra("uri", video.uri.toString()))
            })
            space(panel, 6)
            panel.addView(actionButton("↗  COMPARTILHAR", true) {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, video.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Compartilhar vídeo"))
                } catch (_: Exception) {
                    Toast.makeText(this, "Não foi possível compartilhar este vídeo.", Toast.LENGTH_SHORT).show()
                }
            })
            space(panel, 6)
            panel.addView(actionButton("EXCLUIR VÍDEO", true) {
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Excluir vídeo?")
                    .setMessage(video.name).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        try {
                            contentResolver.delete(video.uri, null, null)
                            Toast.makeText(this, "Vídeo excluído.", Toast.LENGTH_SHORT).show()
                            showVideos()
                        } catch (_: Exception) {
                            Toast.makeText(this, "Não foi possível excluir.", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            })
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 12
            root.addView(panel, lp)
        }
    }

    private fun showStats() {
        startPage("stats", "Estatísticas")
        val videos = getVideos()
        val totalBytes = videos.sumOf { it.size }
        val totalMb = totalBytes / (1024.0 * 1024.0)
        val totalMinutes = videos.sumOf { it.duration } / 60000L
        val panel = card(); root.addView(panel)
        panel.addView(label("SUA BIBLIOTECA", 11f, red, true))
        space(panel, 10)
        panel.addView(label("${videos.size} vídeos", 25f, white, true))
        panel.addView(label(String.format(Locale.getDefault(), "%.1f MB ocupados", totalMb), 13f, muted))
        space(root, 12)
        val details = card(); root.addView(details)
        details.addView(label("TEMPO TOTAL DE VÍDEOS", 11f, muted, true))
        space(details, 8)
        details.addView(label("$totalMinutes minutos", 21f, white, true))
        space(root, 12)
        val storage = card(); root.addView(storage)
        storage.addView(label("ARMAZENAMENTO LIVRE", 11f, muted, true))
        space(storage, 8)
        storage.addView(label(availableStorage(), 21f, white, true))
        space(root, 12)
        root.addView(label("As estatísticas são calculadas localmente a partir dos vídeos salvos pelo Sakano FF.", 12f, muted))
    }

    private fun showPerformance() {
        startPage("performance", "Central de desempenho")
        val enabled = prefs.getBoolean("game_mode", true)
        val panel = card(); root.addView(panel)
        panel.addView(label(if (enabled) "MODO JOGO ATIVADO" else "MODO JOGO DESATIVADO", 17f, if (enabled) green else amber, true))
        space(panel, 8)
        panel.addView(label(if (enabled) "Captura limitada a 720p e 30 FPS para reduzir o impacto durante partidas." else "A captura seguirá a resolução e o FPS selecionados nas configurações.", 13f, muted))
        space(root, 12)
        setting("Modo desempenho para jogos", if (enabled) "Ativado" else "Desativado") {
            prefs.edit().putBoolean("game_mode", !enabled).apply()
            showPerformance()
        }
        setting("Perfil recomendado", "720p • 30 FPS • Normal • Sem áudio") {
            prefs.edit().putInt("resolution", 720).putInt("fps", 30).putString("quality", "Normal").putBoolean("audio", false).putString("audio_mode", "none").putBoolean("game_mode", true).apply()
            Toast.makeText(this, "Perfil leve aplicado.", Toast.LENGTH_SHORT).show()
            showPerformance()
        }
        space(root, 8)
        root.addView(label("Dica: feche aplicativos pesados antes de jogar. Nenhum gravador consegue garantir ausência total de quedas de FPS, pois isso depende do processador, temperatura e do próprio jogo.", 12f, muted))
    }

    private fun showAbout() {
        startPage("about", "Ajuda e informações")
        val panel = card(); root.addView(panel)
        panel.addView(label("SAKANO FF 3.2", 22f, white, true))
        space(panel, 8)
        panel.addView(label("Gravador de tela Android com biblioteca local, controles de captura, estatísticas e perfil de desempenho para jogos.", 13f, muted))
        space(root, 12)
        val help = card(); root.addView(help)
        help.addView(label("DICAS RÁPIDAS", 12f, red, true))
        space(help, 8)
        help.addView(label("• Use 720p e 30 FPS para jogar.\n• Deixe o microfone desligado se não precisar dele.\n• Evite gravar enquanto o aparelho estiver muito quente.\n• Mantenha espaço livre no armazenamento.\n• Faça um teste curto antes de uma partida importante.", 13f, muted))
    }
}
