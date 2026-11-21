package com.sakano.ff

import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Captura H.264 da tela + AAC do áudio reproduzido pelo próprio Android. */
class InternalAudioCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val outputFd: java.io.FileDescriptor?,
    private val outputPath: String?
) {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoSurface: Surface? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val muxLock = Any()
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioPtsUs = 0L
    private var audioPtsInitialized = false
    @Volatile private var failure: String? = null

    fun prepare(): Surface {
        check(Build.VERSION.SDK_INT >= 29) { "Áudio interno exige Android 10 ou superior." }
        muxer = if (outputFd != null) MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoSurface = it.createInputSurface()
            it.start()
        }

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            // Playback capture supports media/game usages. UNKNOWN is not a
            // reliable selector and can leave capture with an empty match set.
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "O Android não disponibilizou entrada para captura de áudio interno." }
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuffer * 4, 32 * 1024))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) { "Não foi possível inicializar a captura de áudio interno." }
        return videoSurface!!
    }

    fun getSurfaceForResume(): Surface? = videoSurface

    fun pauseCapture() {
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    fun resumeCapture() {
        try { audioRecord?.startRecording() } catch (e: Exception) {
            failure = e.message ?: "Não foi possível retomar o áudio interno."
        }
    }

    fun start() {
        check(running.compareAndSet(false, true))
        audioRecord?.startRecording()
        videoThread = Thread({ drainVideo() }, "Sakano-VideoMux").also { it.start() }
        audioThread = Thread({ captureAndDrainAudio() }, "Sakano-InternalAudio").also { it.start() }
    }

    private fun startMuxerIfReady() {
        synchronized(muxLock) {
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer!!.start()
                muxerStarted = true
            }
        }
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            if (muxerStarted && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer?.writeSampleData(track, buffer, info)
            }
        }
    }

    private fun drainVideo() {
        val codec = video ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxLock) { videoTrack = muxer!!.addTrack(codec.outputFormat) }
                        startMuxerIfReady()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) writeSample(videoTrack, buffer, info)
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
                if (stopping.get() && index < 0) Thread.sleep(5)
            }
        } catch (e: Exception) { failure = e.message ?: "Falha ao codificar vídeo." }
    }

    private fun captureAndDrainAudio() {
        val codec = audio ?: return
        val recorder = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(2048)
        try {
            while (running.get()) {
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    var inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        input.clear()
                        // AudioRecord returns interleaved stereo SHORT samples.
                        // PTS advances by frames (L+R), not by individual shorts.
                        val samples = minOf(read, input.remaining() / 2).let { it - (it % CHANNELS) }
                        val frames = samples / CHANNELS
                        val bytes = samples * 2
                        input.asShortBuffer().put(pcm, 0, samples)
                        if (!audioPtsInitialized) {
                            // Surface-input video timestamps use the monotonic clock;
                            // start audio on the same clock so both tracks stay aligned.
                            audioPtsUs = System.nanoTime() / 1_000L
                            audioPtsInitialized = true
                        }
                        val pts = audioPtsUs
                        audioPtsUs += (frames.toLong() * 1_000_000L) / SAMPLE_RATE
                        codec.queueInputBuffer(inputIndex, 0, bytes, pts, 0)
                    }
                } else if (read < 0 && running.get()) {
                    Thread.sleep(10)
                }
                drainAudio(codec, info)
            }
            val eosIndex = codec.dequeueInputBuffer(10_000)
            if (eosIndex >= 0) codec.queueInputBuffer(eosIndex, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) drainAudio(codec, info)
        } catch (e: Exception) { failure = e.message ?: "Falha ao capturar áudio interno." }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxLock) { audioTrack = muxer!!.addTrack(codec.outputFormat) }
                    startMuxerIfReady()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) writeSample(audioTrack, buffer, info)
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
                else -> return
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && video == null && audio == null && muxer == null) return
        stopping.set(true)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { video?.signalEndOfInputStream() } catch (_: Exception) {}
        try { audioThread?.join(2500) } catch (_: Exception) {}
        try { videoThread?.join(2500) } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { video?.stop(); video?.release() } catch (_: Exception) {}
        video = null
        try { audio?.stop(); audio?.release() } catch (_: Exception) {}
        audio = null
        try { videoSurface?.release() } catch (_: Exception) {}
        videoSurface = null
        synchronized(muxLock) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
        }
        if (failure != null) throw IllegalStateException(failure)
        check(muxerStarted) { "Não foi possível iniciar o arquivo de áudio e vídeo. Verifique se o jogo permite captura de áudio." }
    }

    companion object {
        // 48 kHz is the native playback rate on most Android devices and
        // avoids unnecessary resampling for YouTube/media playback.
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
    }
}
