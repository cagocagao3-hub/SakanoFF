package com.sakano.ff

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.view.WindowManager
import java.io.File

class RecordingService : Service() {
    companion object {
        const val ACTION_STOP = "com.sakano.ff.STOP"
        const val ACTION_PAUSE = "com.sakano.ff.PAUSE"
        const val ACTION_RESUME = "com.sakano.ff.RESUME"
        private const val CHANNEL = "sakano_recording"
        private const val NOTIFICATION_ID = 1001
    }

    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var outputUri: android.net.Uri? = null
    private var outputFile: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var recording = false
    private var paused = false
    private var stopping = false
    private var mic = false
    private var gameMode = true
    private val prefs by lazy { getSharedPreferences("sakano_settings", MODE_PRIVATE) }

    override fun onBind(intent: Intent?) = null

    private fun setStatus(status: String, error: String = "") {
        prefs.edit().putString("recording_status", status).putString("recording_error", error).apply()
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Gravação Sakano FF", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(message: String): Notification {
        val stop = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 10, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("SAKANO FF")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Parar", stopPending).build())
        if (recording && !paused) {
            val pause = Intent(this, RecordingService::class.java).setAction(ACTION_PAUSE)
            val pending = PendingIntent.getService(this, 11, pause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Pausar", pending).build())
        } else if (paused) {
            val resume = Intent(this, RecordingService::class.java).setAction(ACTION_RESUME)
            val pending = PendingIntent.getService(this, 12, resume, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Continuar", pending).build())
        }
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseRecording(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeRecording(); return START_NOT_STICKY }
        }
        if (recording || projection != null) return START_NOT_STICKY
        if (intent == null) {
            setStatus("Erro", "Solicitação de gravação inválida.")
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        mic = intent.getBooleanExtra("audio", false)
        gameMode = intent.getBooleanExtra("gameMode", true)
        try {
            val type = if (Build.VERSION.SDK_INT >= 34 && mic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("Preparando captura..."), type)
            else startForeground(NOTIFICATION_ID, notification("Preparando captura..."))

            val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")
            if (code != Activity.RESULT_OK || data == null) throw IllegalStateException("A autorização de captura de tela não foi recebida.")
            val requestedResolution = intent.getIntExtra("resolution", 720)
            val requestedFps = intent.getIntExtra("fps", 30)
            val effectiveResolution = if (gameMode) minOf(requestedResolution, 720) else requestedResolution
            val effectiveFps = if (gameMode) minOf(requestedFps, 30) else requestedFps
            startRecording(code, data, effectiveResolution, effectiveFps)
        } catch (e: Exception) {
            failRecording(e.message ?: "Não foi possível iniciar a gravação.")
        }
        return START_NOT_STICKY
    }

    private fun createOutput(): java.io.FileDescriptor? {
        val name = "Sakano_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Sakano FF")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("O Android não conseguiu criar o vídeo na galeria.")
            descriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: throw IllegalStateException("Não foi possível abrir o arquivo de vídeo.")
            return descriptor!!.fileDescriptor
        }
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Sakano FF")
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Não foi possível criar a pasta de gravações.")
        outputFile = File(folder, name)
        return null
    }

    private fun startRecording(code: Int, data: Intent, resolution: Int, fps: Int) {
        setStatus("Preparando")
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data) ?: throw IllegalStateException("O Android não forneceu acesso à captura de tela.")
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) stopRecording()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val physicalWidth = metrics.widthPixels.coerceAtLeast(2)
        val physicalHeight = metrics.heightPixels.coerceAtLeast(2)
        val shortSide = minOf(resolution.coerceAtLeast(480), minOf(physicalWidth, physicalHeight))
        val longSide = (shortSide.toFloat() * maxOf(physicalWidth, physicalHeight) / minOf(physicalWidth, physicalHeight)).toInt()
        val width: Int
        val height: Int
        if (physicalHeight >= physicalWidth) {
            width = (shortSide - shortSide % 2).coerceAtLeast(2)
            height = (longSide - longSide % 2).coerceAtLeast(2)
        } else {
            width = (longSide - longSide % 2).coerceAtLeast(2)
            height = (shortSide - shortSide % 2).coerceAtLeast(2)
        }

        val fd = createOutput()
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = media
        if (mic) media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (mic) {
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128000)
            media.setAudioSamplingRate(44100)
        }
        media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        media.setVideoSize(width, height)
        media.setVideoFrameRate(fps.coerceIn(24, 60))
        // Bitrate moderado: reduz o trabalho de codificação sem reduzir a resolução 720p.
        val quality = prefs.getString("quality", "Normal")
        val bitrate = when (quality) {
            "Econômica" -> 2_000_000
            "Normal" -> 3_000_000
            "Alta" -> 4_000_000
            "Máxima" -> 5_500_000
            else -> 3_000_000
        }
        val fpsFactor = fps.coerceIn(24, 60) / 30f
        val resolutionFactor = (width.toLong() * height.toLong()).toFloat() / (1280f * 720f)
        val adjustedBitrate = (bitrate * fpsFactor * resolutionFactor.coerceIn(0.65f, 1.8f)).toInt()
            .coerceIn(1_800_000, 8_000_000)
        media.setVideoEncodingBitRate(adjustedBitrate)
        if (fd != null) media.setOutputFile(fd) else media.setOutputFile(outputFile!!.absolutePath)
        media.prepare()

        display = projection!!.createVirtualDisplay(
            "SakanoFF-Display", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, media.surface, null, null
        ) ?: throw IllegalStateException("Não foi possível iniciar a captura da tela.")
        media.start()
        recording = true
        paused = false
        prefs.edit()
            .putLong("recording_started_at", SystemClock.elapsedRealtime())
            .putLong("recording_elapsed_base", 0L)
            .putLong("last_duration_ms", 0L)
            .apply()
        setStatus("Gravando")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Gravando sua tela agora."))
    }

    private fun pauseRecording() {
        if (!recording || paused || stopping) return
        try {
            if (Build.VERSION.SDK_INT < 24) throw IllegalStateException("Pausar gravação exige Android 7.0 ou superior.")
            recorder?.pause()
            val base = prefs.getLong("recording_elapsed_base", 0L)
            val started = prefs.getLong("recording_started_at", 0L)
            val elapsed = base + (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            prefs.edit().putLong("recording_elapsed_base", elapsed).putLong("recording_started_at", 0L).apply()
            paused = true
            setStatus("Pausado")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Gravação pausada."))
        } catch (e: Exception) {
            prefs.edit().putString("recording_error", e.message ?: "Não foi possível pausar.").apply()
        }
    }

    private fun resumeRecording() {
        if (!recording || !paused || stopping) return
        try {
            recorder?.resume()
            prefs.edit().putLong("recording_started_at", SystemClock.elapsedRealtime()).apply()
            paused = false
            setStatus("Gravando")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Gravando sua tela agora."))
        } catch (e: Exception) {
            prefs.edit().putString("recording_error", e.message ?: "Não foi possível continuar.").apply()
        }
    }

    private fun failRecording(message: String) {
        stopping = true
        setStatus("Erro", message)
        cleanup(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        setStatus("Finalizando")
        var valid = false
        val base = prefs.getLong("recording_elapsed_base", 0L)
        val started = prefs.getLong("recording_started_at", 0L)
        val elapsed = if (recording && !paused && started > 0L) base + (SystemClock.elapsedRealtime() - started) else base
        if (recording) {
            try {
                recorder?.stop()
                valid = true
            } catch (_: Exception) { valid = false }
        }
        prefs.edit().putLong("last_duration_ms", elapsed).apply()
        cleanup(valid)
        setStatus("Pronto", if (valid) "" else "A gravação ficou curta demais ou não gerou um arquivo válido. Tente novamente.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(valid: Boolean) {
        recording = false
        paused = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { descriptor?.close() } catch (_: Exception) {}
        descriptor = null
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = outputUri
            if (uri != null) {
                if (valid) {
                    try {
                        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        contentResolver.update(uri, values, null, null)
                    } catch (_: Exception) { contentResolver.delete(uri, null, null) }
                } else {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        } else {
            val file = outputFile
            if (valid && file != null && file.exists()) {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            } else file?.delete()
        }
        outputUri = null
        outputFile = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            cleanup(false)
            setStatus("Pronto", "O serviço foi encerrado antes de finalizar a gravação.")
        }
        super.onDestroy()
    }
}
