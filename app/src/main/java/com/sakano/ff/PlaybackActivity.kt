package com.sakano.ff

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.net.Uri

class PlaybackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(15, 20, 15, 20)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "SAKANO FF  •  REPRODUZINDO"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        root.addView(title)

        val video = VideoView(this)
        root.addView(
            video,
            LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        )

        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)

        val raw = intent.getStringExtra("uri")

        if (raw.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Vídeo não encontrado.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        try {
            video.setVideoURI(Uri.parse(raw))
            video.setOnPreparedListener {
                it.setOnVideoSizeChangedListener { _, _, _ ->
                    controls.setAnchorView(video)
                }
                controls.show()
            }

            video.setOnErrorListener { _, _, _ ->
                Toast.makeText(
                    this,
                    "Não foi possível reproduzir este arquivo.",
                    Toast.LENGTH_LONG
                ).show()
                true
            }

            video.requestFocus()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Erro ao abrir o vídeo.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }

        setContentView(root)
    }
}
