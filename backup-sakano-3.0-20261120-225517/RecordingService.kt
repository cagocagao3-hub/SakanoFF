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
import java.io.File

class RecordingService : Service() {

    companion object {
        const val ACTION_STOP = "com.sakano.ff.STOP"
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
    private var stopping = false
    private var mic = false

    private val prefs by lazy {
        getSharedPreferences("sakano_settings", MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?) = null

    private fun setStatus(status: String, error: String = "") {
        prefs.edit()
            .putString("recording_status", status)
            .putString("recording_error", error)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL,
            "Gravação Sakano FF",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun notification(
        message: String,
        withStop: Boolean = true
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("SAKANO FF")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(withStop)
            .setOnlyAlertOnce(true)

        if (withStop) {
            val stopIntent = Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP
            }

            val pending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Parar gravação",
                    pending
                ).build()
            )
        }

        return builder.build()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        if (recording || projection != null) {
            return START_NOT_STICKY
        }

        if (intent == null) {
            setStatus("Erro", "Solicitação de gravação inválida.")
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        mic = intent.getBooleanExtra("audio", false)

        try {
            val type =
                if (Build.VERSION.SDK_INT >= 34 && mic) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification("Preparando gravação..."),
                    type
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification("Preparando gravação...")
                )
            }

            val code = intent.getIntExtra(
                "code",
                Activity.RESULT_CANCELED
            )

            @Suppress("DEPRECATION")
            val data: Intent? =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    intent.getParcelableExtra("data")
                }

            if (code != Activity.RESULT_OK || data == null) {
                throw IllegalStateException(
                    "A autorização para capturar a tela não foi recebida."
                )
            }

            val resolution = intent.getIntExtra("resolution", 720)
            val fps = intent.getIntExtra("fps", 30)

            startRecording(code, data, resolution, fps)

        } catch (e: Exception) {
            failRecording(
                e.message ?: "Não foi possível iniciar a gravação."
            )
        }

        return START_NOT_STICKY
    }

    private fun createOutput(): java.io.FileDescriptor? {
        val name = "Sakano_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/Sakano FF"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "O Android não conseguiu criar o vídeo na galeria."
            )

            descriptor = contentResolver.openFileDescriptor(
                outputUri!!,
                "w"
            ) ?: throw IllegalStateException(
                "Não foi possível abrir o arquivo de vídeo."
            )

            return descriptor!!.fileDescriptor
        }

        val folder = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            ),
            "Sakano FF"
        )

        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException(
                "Não foi possível criar a pasta de gravações."
            )
        }

        outputFile = File(folder, name)
        return null
    }

    private fun startRecording(
        code: Int,
        data: Intent,
        resolution: Int,
        fps: Int
    ) {
        setStatus("Preparando")

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        projection = manager.getMediaProjection(code, data)
            ?: throw IllegalStateException(
                "O Android não forneceu acesso à captura de tela."
            )

        projection!!.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) {
                        stopRecording()
                    }
                }
            },
            Handler(Looper.getMainLooper())
        )

        val metrics = resources.displayMetrics
        val physicalWidth = metrics.widthPixels
        val physicalHeight = metrics.heightPixels

        val shortSide = minOf(
            resolution.coerceAtLeast(480),
            minOf(physicalWidth, physicalHeight)
        )

        val longSide = (
            shortSide.toFloat() *
                maxOf(physicalWidth, physicalHeight) /
                minOf(physicalWidth, physicalHeight)
            ).toInt()

        val width: Int
        val height: Int

        if (physicalHeight >= physicalWidth) {
            width = shortSide - shortSide % 2
            height = longSide - longSide % 2
        } else {
            width = longSide - longSide % 2
            height = shortSide - shortSide % 2
        }

        val outputDescriptor = createOutput()

        val media = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder = media

        if (mic) {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
        }

        media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (mic) {
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128000)
            media.setAudioSamplingRate(44100)
        }

        media.setVideoSize(width, height)
        media.setVideoFrameRate(fps.coerceIn(24, 60))

        val quality = prefs.getString("quality", "Alta")

        val bitrate = when (quality) {
            "Econômica" -> 2_000_000
            "Normal" -> 3_000_000
            "Máxima" -> 8_000_000
            else -> 4_000_000
        }

        media.setVideoEncodingBitRate(
            if (fps >= 60) bitrate * 2 else bitrate
        )

        if (outputDescriptor != null) {
            media.setOutputFile(outputDescriptor)
        } else {
            media.setOutputFile(outputFile!!.absolutePath)
        }

        media.prepare()

        display = projection!!.createVirtualDisplay(
            "SakanoFF-Display",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            media.surface,
            null,
            null
        ) ?: throw IllegalStateException(
            "Não foi possível iniciar a captura da tela."
        )

        media.start()

        recording = true
        setStatus("Gravando")

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("Gravando sua tela agora.")
        )
    }

    private fun failRecording(message: String) {
        setStatus("Erro", message)
        cleanup(false)

        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Falha: $message", false)
            )
        } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording() {
        if (stopping) return

        stopping = true
        setStatus("Finalizando")

        var valid = false

        if (recording) {
            try {
                recorder?.stop()
                valid = true
            } catch (_: Exception) {
                valid = false
            }
        }

        cleanup(valid)

        setStatus(
            if (valid) "Pronto" else "Pronto",
            if (valid) "" else "A gravação não gerou um vídeo válido."
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(valid: Boolean) {
        recording = false

        try {
            display?.release()
        } catch (_: Exception) {}
        display = null

        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null

        try {
            descriptor?.close()
        } catch (_: Exception) {}
        descriptor = null

        if (Build.VERSION.SDK_INT >= 29) {
            val uri = outputUri

            if (uri != null) {
                if (valid) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }

                        contentResolver.update(uri, values, null, null)
                    } catch (_: Exception) {
                        contentResolver.delete(uri, null, null)
                    }
                } else {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {}
                }
            }
        } else {
            val file = outputFile

            if (valid && file != null && file.exists()) {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } else {
                file?.delete()
            }
        }

        outputUri = null
        outputFile = null

        try {
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            cleanup(false)
        }
        super.onDestroy()
    }
}
