package com.sakano.ff

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private lateinit var gallery: LinearLayout

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val service = Intent(this, RecordingService::class.java).apply {
                    putExtra("code", result.resultCode)
                    putExtra("data", result.data)
                }
                ContextCompat.startForegroundService(this, service)
                status.text = "GRAVAÇÃO INICIADA"
                status.setTextColor(0xFFEF3340.toInt())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt()
        window.navigationBarColor = 0xFF090909.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 35)
            setBackgroundColor(0xFF090909.toInt())
        }
        scroll.addView(container)
        setContentView(scroll)

        buildInterface()
        refreshGallery()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun button(value: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = value
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFCB2638.toInt()))
            setOnClickListener { action() }
        }
    }

    private fun buildInterface() {
        container.addView(text("SAKANO", 17f, 0xFFEF3340.toInt(), true))
        container.addView(text("FF", 42f, 0xFFFFFFFF.toInt(), true))
        container.addView(text("SCREEN RECORDER  •  VERSION 1.0", 11f, 0xFF888888.toInt()))

        addSpace(28)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 22)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171717.toInt())
                cornerRadius = 24f
                setStroke(1, 0xFF303030.toInt())
            }
        }
        container.addView(panel)

        panel.addView(text("CENTRAL DE GRAVAÇÃO", 13f, 0xFFAAAAAA.toInt(), true))
        status = text("PRONTO PARA GRAVAR", 18f, 0xFFFFFFFF.toInt(), true)
        panel.addView(status)

        val start = button("●  INICIAR GRAVAÇÃO") {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
        panel.addView(start)

        panel.addView(button("■  FINALIZAR GRAVAÇÃO") {
            startService(Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            })
            status.text = "GRAVAÇÃO FINALIZADA"
            refreshGallery()
        })

        addSpace(24)

        container.addView(text("SUAS GRAVAÇÕES", 21f, 0xFFFFFFFF.toInt(), true))
        container.addView(text("Seus vídeos ficam organizados aqui.", 12f, 0xFF888888.toInt()))

        addSpace(12)

        gallery = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(gallery)

        addSpace(20)
        container.addView(text("CONFIGURAÇÕES", 18f, 0xFFFFFFFF.toInt(), true))
        container.addView(text(
            "Resolução: tela do aparelho\nFPS: 30\nFormato: MP4\nCodec: H.264\nQualidade: 4 Mbps\nÁudio: não ativado nesta primeira versão",
            13f, 0xFFAAAAAA.toInt()
        ))
    }

    private fun addSpace(height: Int) {
        container.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun recordings(): List<File> {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SakanoFF")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun refreshGallery() {
        if (!::gallery.isInitialized) return
        gallery.removeAllViews()

        val files = recordings()

        if (files.isEmpty()) {
            val empty = text("Nenhuma gravação encontrada.\nSeus vídeos aparecerão aqui.", 14f, 0xFF888888.toInt())
            empty.gravity = Gravity.CENTER
            empty.setPadding(10, 30, 10, 30)
            gallery.addView(empty)
            return
        }

        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF191919.toInt())
                    cornerRadius = 18f
                }
            }

            card.addView(text(file.name, 15f, 0xFFFFFFFF.toInt(), true))
            card.addView(text(
                "${file.length() / 1024 / 1024} MB  •  ${
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        .format(Date(file.lastModified()))
                }",
                11f, 0xFF999999.toInt()
            ))

            card.addView(button("▶  REPRODUZIR") {
                val uri = FileProvider.getUriForFile(
                    this, "com.sakano.ff.provider", file
                )
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            })

            card.addView(button("EXCLUIR VÍDEO") {
                AlertDialog.Builder(this)
                    .setTitle("Excluir gravação?")
                    .setMessage("Deseja excluir ${file.name}?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        file.delete()
                        refreshGallery()
                    }
                    .show()
            })

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 14
            gallery.addView(card, params)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::gallery.isInitialized) refreshGallery()
    }
}
