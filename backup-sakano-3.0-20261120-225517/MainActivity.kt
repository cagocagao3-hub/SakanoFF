package com.sakano.ff

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences
    private var currentPage = "home"
    private var pendingRecording = false

    private val RED = Color.rgb(222, 37, 55)
    private val BG = Color.rgb(12, 12, 12)
    private val CARD = Color.rgb(25, 25, 25)
    private val WHITE = Color.WHITE
    private val MUTED = Color.rgb(155, 155, 155)

    data class VideoItem(
        val uri: Uri,
        val name: String,
        val size: Long,
        val date: Long,
        val duration: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("sakano_settings", MODE_PRIVATE)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        showHome()
    }

    private fun label(
        value: String,
        size: Float,
        color: Int = WHITE,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun space(height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(17, 17, 17, 17)
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = 22f
                setStroke(1, Color.rgb(48, 48, 48))
            }
        }
    }

    private fun actionButton(
        title: String,
        secondary: Boolean = false,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = title
            textSize = 13f
            isAllCaps = false
            setTextColor(WHITE)
            background = GradientDrawable().apply {
                setColor(if (secondary) 0xFF303030.toInt() else RED)
                cornerRadius = 15f
            }
            setPadding(12, 8, 12, 8)
            setOnClickListener { action() }
        }
    }

    private fun startPage(title: String) {
        currentPage = title

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(19, 20, 19, 28)
            setBackgroundColor(BG)
        }

        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menu = TextView(this).apply {
            text = "☰"
            textSize = 29f
            setTextColor(WHITE)
            setPadding(0, 0, 18, 0)
            setOnClickListener { openMenu() }
        }

        header.addView(menu)

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(label("SAKANO FF", 21f, WHITE, true))
        titleBox.addView(label("SCREEN RECORDER", 10f, RED, true))

        header.addView(titleBox)
        root.addView(header)

        space(27)

        root.addView(label(title, 24f, WHITE, true))
        space(5)
    }

    private fun openMenu() {
        val options = arrayOf(
            "⌂   Início",
            "▣   Seus Vídeos",
            "⚙   Configurações"
        )

        AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("SAKANO FF")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHome()
                    1 -> showVideos()
                    2 -> showSettings()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showHome() {
        startPage("Sua central de gravação")

        val panel = card()
        root.addView(panel)

        panel.addView(label("STATUS DO GRAVADOR", 11f, MUTED, true))
        spaceIn(panel, 12)
        val recordingStatus = prefs.getString(
            "recording_status",
            "Pronto"
        ) ?: "Pronto"

        val statusText = when (recordingStatus) {
            "Gravando" -> "●  Gravando agora"
            "Preparando" -> "Preparando gravação..."
            "Finalizando" -> "Finalizando vídeo..."
            "Erro" -> "Falha na gravação"
            else -> "Pronto para gravar"
        }

        val statusColor = if (recordingStatus == "Gravando") {
            Color.rgb(60, 210, 110)
        } else if (recordingStatus == "Erro") {
            RED
        } else {
            WHITE
        }

        panel.addView(label(statusText, 22f, statusColor, true))

        if (recordingStatus == "Erro") {
            val error = prefs.getString("recording_error", "") ?: ""
            if (error.isNotBlank()) {
                panel.addView(label(error, 12f, RED))
            }
        }
        panel.addView(label(
            "Seus vídeos serão salvos na galeria do celular.",
            12f, MUTED
        ))

        spaceIn(panel, 18)

        panel.addView(actionButton("●   INICIAR GRAVAÇÃO") {
            checkAudioPermission()
        })

        spaceIn(panel, 8)

        panel.addView(actionButton(
            "■   PARAR GRAVAÇÃO",
            true
        ) {
            stopRecording()
        })

        space(20)

        val quick = card()
        root.addView(quick)

        quick.addView(label("CONFIGURAÇÃO ATUAL", 12f, RED, true))
        spaceIn(quick, 10)

        quick.addView(label(
            "${prefs.getInt("resolution", 720)}p  •  " +
            "${prefs.getInt("fps", 30)} FPS",
            23f, WHITE, true
        ))

        quick.addView(label(
            if (prefs.getBoolean("audio", false))
                "Áudio do microfone ativado"
            else "Gravação sem áudio do microfone",
            12f, MUTED
        ))

        space(18)

        root.addView(actionButton("▣   ABRIR MEUS VÍDEOS") {
            showVideos()
        })

        space(8)

        root.addView(actionButton("⚙   CONFIGURAÇÕES") {
            showSettings()
        }, LinearLayout.LayoutParams(
            -1, -2
        ).apply {
            topMargin = 8
        })

        space(25)

        root.addView(label("SAKANO FF  •  VERSÃO 2.0", 11f, MUTED))
    }

    private fun spaceIn(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun checkAudioPermission() {
        if (
            prefs.getBoolean("audio", false) &&
            Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording = true
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                201
            )
            return
        }

        launchProjection()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 201 && pendingRecording) {
            pendingRecording = false

            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                launchProjection()
            } else {
                Toast.makeText(
                    this,
                    "Permissão de microfone não concedida.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchProjection() {
        prefs.edit()
            .putString("recording_status", "Preparando")
            .putString("recording_error", "")
            .apply()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            101
        )
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 101) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                    putExtra("audio", prefs.getBoolean("audio", false))
                    putExtra("resolution", prefs.getInt("resolution", 720))
                    putExtra("fps", prefs.getInt("fps", 30))
                }

                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                Toast.makeText(
                    this,
                    "Gravação iniciada!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                prefs.edit()
                    .putString("recording_status", "Pronto")
                    .apply()

                Toast.makeText(
                    this,
                    "Gravação cancelada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopRecording() {
        startService(
            Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )

        Toast.makeText(
            this,
            "Solicitação para finalizar gravação enviada.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showSettings() {
        startPage("Configurações")

        setting(
            "Resolução",
            "${prefs.getInt("resolution", 720)}p"
        ) {
            choose(
                "Escolha a resolução",
                arrayOf("480p", "720p", "1080p", "1440p")
            ) { index ->
                prefs.edit().putInt(
                    "resolution",
                    listOf(480, 720, 1080, 1440)[index]
                ).apply()
                showSettings()
            }
        }

        setting(
            "Taxa de quadros",
            "${prefs.getInt("fps", 30)} FPS"
        ) {
            choose(
                "Escolha o FPS",
                arrayOf("24 FPS", "30 FPS", "60 FPS")
            ) { index ->
                prefs.edit().putInt(
                    "fps",
                    listOf(24, 30, 60)[index]
                ).apply()
                showSettings()
            }
        }

        setting(
            "Qualidade de vídeo",
            prefs.getString("quality", "Alta") ?: "Alta"
        ) {
            choose(
                "Qualidade",
                arrayOf("Econômica", "Normal", "Alta", "Máxima")
            ) { index ->
                prefs.edit().putString(
                    "quality",
                    listOf("Econômica", "Normal", "Alta", "Máxima")[index]
                ).apply()
                showSettings()
            }
        }

        setting(
            "Áudio do microfone",
            if (prefs.getBoolean("audio", false)) "Ativado" else "Desativado"
        ) {
            val next = !prefs.getBoolean("audio", false)
            prefs.edit().putBoolean("audio", next).apply()
            showSettings()
        }

        setting(
            "Pasta de destino",
            "Movies/Sakano FF"
        ) {
            AlertDialog.Builder(this)
                .setTitle("Pasta de destino")
                .setMessage(
                    "Os vídeos serão salvos na pasta Movies/Sakano FF " +
                    "da galeria do aparelho."
                )
                .setPositiveButton("Entendi", null)
                .show()
        }

        space(18)

        val info = card()
        root.addView(info)
        info.addView(label("DESEMPENHO", 13f, RED, true))
        spaceIn(info, 10)
        info.addView(label(
            "A resolução e o FPS podem ser limitados pelo hardware. " +
            "Para aparelhos com pouca memória, recomendamos 720p e 30 FPS.",
            13f, MUTED
        ))
    }

    private fun setting(
        title: String,
        value: String,
        action: () -> Unit
    ) {
        val panel = card()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        texts.addView(label(title, 15f, WHITE, true))
        texts.addView(label(value, 12f, MUTED))

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        row.addView(label("›", 27f, RED, true))
        panel.addView(row)

        panel.setOnClickListener { action() }

        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = 10
        root.addView(panel, params)
    }

    private fun choose(
        title: String,
        options: Array<String>,
        selected: (Int) -> Unit
    ) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(options) { _, index -> selected(index) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun getVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()

        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION
            )

            try {
                contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Video.Media.RELATIVE_PATH}=?",
                    arrayOf("Movies/Sakano FF/"),
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "Vídeo"
                        val size = cursor.getLong(2)
                        val date = cursor.getLong(3)
                        val duration = cursor.getLong(4)

                        val uri = Uri.withAppendedPath(
                            collection,
                            id.toString()
                        )

                        result.add(
                            VideoItem(uri, name, size, date, duration)
                        )
                    }
                }
            } catch (_: Exception) {}
        } else {
            val directory = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES
                ),
                "Sakano FF"
            )

            directory.listFiles()?.filter {
                it.extension.equals("mp4", true)
            }?.sortedByDescending {
                it.lastModified()
            }?.forEach { file ->
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.sakano.ff.provider",
                    file
                )

                result.add(
                    VideoItem(
                        uri,
                        file.name,
                        file.length(),
                        file.lastModified() / 1000,
                        0
                    )
                )
            }
        }

        return result
    }

    private fun showVideos() {
        startPage("Seus Vídeos")

        val videos = getVideos()

        if (videos.isEmpty()) {
            val empty = card()
            root.addView(empty)

            empty.gravity = Gravity.CENTER
            empty.addView(label("▣", 42f, RED, true))
            empty.addView(label(
                "Nenhum vídeo encontrado",
                18f, WHITE, true
            ))
            empty.addView(label(
                "Quando você gravar, seus vídeos aparecerão aqui e na galeria do celular.",
                12f, MUTED
            ))

            return
        }

        root.addView(label(
            "${videos.size} vídeo(s) encontrados",
            13f, MUTED
        ))

        space(12)

        videos.forEach { video ->
            val panel = card()

            panel.addView(label("▶  ${video.name}", 15f, WHITE, true))

            val date = SimpleDateFormat(
                "dd/MM/yyyy HH:mm",
                Locale.getDefault()
            ).format(Date(video.date * 1000))

            panel.addView(label(
                "${video.size / 1024 / 1024} MB  •  $date",
                11f, MUTED
            ))

            spaceIn(panel, 10)

            panel.addView(actionButton("▶  REPRODUZIR") {
                startActivity(
                    Intent(this, PlaybackActivity::class.java)
                        .putExtra("uri", video.uri.toString())
                )
            })

            spaceIn(panel, 5)

            panel.addView(actionButton(
                "EXCLUIR VÍDEO",
                true
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Excluir vídeo?")
                    .setMessage(video.name)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        try {
                            contentResolver.delete(video.uri, null, null)
                            Toast.makeText(
                                this,
                                "Vídeo excluído.",
                                Toast.LENGTH_SHORT
                            ).show()
                            showVideos()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this,
                                "Não foi possível excluir o vídeo.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .show()
            })

            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = 14
            root.addView(panel, params)
        }
    }

    override fun onResume() {
        super.onResume()

        if (currentPage == "Seus Vídeos") {
            showVideos()
        } else if (currentPage == "home") {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && currentPage == "home") {
                    showHome()
                }
            }, 1200)
        }
    }
}
