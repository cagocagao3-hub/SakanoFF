package com.sakano.ff

import android.app.*
import android.content.*
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
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
    private var output: File? = null
    private var recording = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL, "Gravação Sakano FF",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val pending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Sakano FF")
            .setContentText("Gravação de tela em andamento")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .addAction(Notification.Action.Builder(
                null, "Parar gravação", pending
            ).build())
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            intent?.getParcelableExtra("data")
        }

        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startRecording(code, data)
        } catch (e: Exception) {
            stopRecording()
        }

        return START_NOT_STICKY
    }

    private fun startRecording(code: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        projection = manager.getMediaProjection(code, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val directory = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "SakanoFF"
        )
        directory.mkdirs()

        output = File(directory, "Sakano_${System.currentTimeMillis()}.mp4")

        val media = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder = media

        media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        media.setVideoSize(width, height)
        media.setVideoFrameRate(30)
        media.setVideoEncodingBitRate(4_000_000)
        media.setOutputFile(output!!.absolutePath)
        media.prepare()

        display = projection!!.createVirtualDisplay(
            "SakanoFF",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            media.surface, null, null
        )

        projection!!.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            },
            Handler(Looper.getMainLooper())
        )

        media.start()
        recording = true
    }

    private fun stopRecording() {
        if (recording) {
            try {
                recorder?.stop()
            } catch (_: Exception) {
                output?.delete()
            }
        }

        recording = false

        try { display?.release() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}

        display = null
        recorder = null

        val oldProjection = projection
        projection = null
        try { oldProjection?.stop() } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recording || recorder != null) stopRecording()
        super.onDestroy()
    }
}
