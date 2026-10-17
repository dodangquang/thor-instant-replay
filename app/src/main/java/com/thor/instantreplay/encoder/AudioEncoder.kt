package com.thor.instantreplay.encoder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.thor.instantreplay.buffer.CircularMediaBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val circularBuffer: CircularMediaBuffer,
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val bitRate: Int = 160_000,
    private val recordMic: Boolean = true,
    private val enableNoiseFilter: Boolean = true,
    gameVolumePercent: Int = 85,
    micVolumePercent: Int = 150
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val TIMEOUT_US = 10_000L
    }

    @Volatile
    var gameGain: Float = (gameVolumePercent / 100f).coerceIn(0f, 1.5f)
        private set

    @Volatile
    var micGain: Float = (micVolumePercent / 100f * 1.8f).coerceIn(0.5f, 4.0f)
        private set

    fun setAudioVolumes(gamePercent: Int, micPercent: Int) {
        gameGain = (gamePercent / 100f).coerceIn(0f, 1.5f)
        micGain = (micPercent / 100f * 1.8f).coerceIn(0.5f, 4.0f)
        Log.i(TAG, "Audio volume updated: gameGain=$gameGain (${gamePercent}%), micGain=$micGain (${micPercent}%)")
    }


    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var mediaCodec: MediaCodec? = null

    private val isRunning = AtomicBoolean(false)
    private val isRecordingAudio = AtomicBoolean(false)

    private var recordThread: Thread? = null
    private var micThread: Thread? = null
    private var drainThread: Thread? = null

    private val micRingBuffer = ConcurrentShortRingBuffer(sampleRate * 2)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get()) return

        try {
            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

            // Setup AAC Encoder
            val format = AndroidMediaFormat.createAudioFormat(AndroidMediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(AndroidMediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(AndroidMediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(AndroidMediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
            }

            val codec = MediaCodec.createEncoderByType(AndroidMediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec
            isRunning.set(true)

            // Start hardware audio capture (AudioRecord)
            startAudioCapture()

            drainThread = Thread({ drainEncoderLoop() }, "AudioEncoderDrainer").apply { start() }
            Log.i(TAG, "AudioEncoder started (recordMic=$recordMic, enableNoiseFilter=$enableNoiseFilter)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioEncoder", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (isRecordingAudio.get()) return

        try {
            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

            // 1. Configure Internal Audio Playback Capture (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                playbackRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .build()
            }

            // 2. Configure Microphone Capture if enabled
            if (recordMic) {
                try {
                    val micMinBufferSize = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(minBufferSize)

                    val mic = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(micMinBufferSize * 2)
                        .build()

                    if (enableNoiseFilter) {
                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                noiseSuppressor = NoiseSuppressor.create(mic.audioSessionId)?.apply {
                                    enabled = true
                                    Log.i(TAG, "Hardware NoiseSuppressor enabled on session ${mic.audioSessionId}")
                                }
                            }
                            if (AcousticEchoCanceler.isAvailable()) {
                                echoCanceler = AcousticEchoCanceler.create(mic.audioSessionId)?.apply {
                                    enabled = true
                                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session ${mic.audioSessionId}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Native audio effects setup failed, will use software DSP filter", e)
                        }
                    }

                    mic.startRecording()
                    micRecord = mic
                    Log.i(TAG, "Microphone recording initialized successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start microphone recording, continuing with internal audio only", e)
                }
            }

            playbackRecord?.startRecording()
            isRecordingAudio.set(true)

            if (micRecord != null) {
                micThread = Thread({ micCaptureLoop() }, "MicCaptureThread").apply { start() }
            }
            recordThread = Thread({ feedEncoderLoop() }, "AudioRecordFeeder").apply { start() }
            Log.i(TAG, "Audio capture started (AudioIn active)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
        }
    }

    private fun stopAudioCapture() {
        if (!isRecordingAudio.getAndSet(false)) return

        try {
            // 1. First stop AudioRecords to unblock any pending native reads
            try { playbackRecord?.stop() } catch (_: Exception) {}
            try { micRecord?.stop() } catch (_: Exception) {}

            // 2. Interrupt and join the reading threads so they exit completely
            recordThread?.interrupt()
            micThread?.interrupt()
            try { recordThread?.join(300) } catch (_: Exception) {}
            try { micThread?.join(300) } catch (_: Exception) {}
            recordThread = null
            micThread = null

            // 3. Fully release AudioRecord instances to destroy AudioIn wakelock in audioserver
            try { playbackRecord?.release() } catch (_: Exception) {}
            playbackRecord = null

            try { micRecord?.release() } catch (_: Exception) {}
            micRecord = null

            try { noiseSuppressor?.release() } catch (_: Exception) {}
            noiseSuppressor = null

            try { echoCanceler?.release() } catch (_: Exception) {}
            echoCanceler = null

            micRingBuffer.clear()
            Log.i(TAG, "Audio capture stopped & released (AudioIn wakelock destroyed)")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture", e)
        }
    }

    fun pause() {
        if (!isRunning.get()) return
        stopAudioCapture()
        Log.i(TAG, "AudioEncoder paused: AudioRecords released for Doze mode")
    }

    fun resume() {
        if (!isRunning.get()) return
        startAudioCapture()
        Log.i(TAG, "AudioEncoder resumed: AudioRecords re-initialized")
    }

    private fun micCaptureLoop() {
        val mic = micRecord ?: return
        val micBuffer = ShortArray(1024)
        val filter = if (enableNoiseFilter) OpenSourceNoiseFilter(sampleRate) else null

        while (isRecordingAudio.get()) {
            val readShorts = mic.read(micBuffer, 0, micBuffer.size)
            if (readShorts <= 0) {
                if (!isRecordingAudio.get()) break
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            if (filter != null) {
                filter.process(micBuffer, 0, readShorts)
            }
            micRingBuffer.write(micBuffer, 0, readShorts)
        }
    }

    private fun feedEncoderLoop() {
        val record = playbackRecord ?: return
        val codec = mediaCodec ?: return
        val gameBuffer = ShortArray(1024)
        val micBuffer = ShortArray(512)
        val outBuffer = ShortArray(1024)

        while (isRecordingAudio.get()) {
            val readShorts = record.read(gameBuffer, 0, gameBuffer.size)
            if (readShorts <= 0) {
                if (!isRecordingAudio.get()) break
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val frames = readShorts / 2
            val hasMic = micRecord != null
            val micRead = if (hasMic) micRingBuffer.read(micBuffer, 0, frames) else 0

            for (i in 0 until frames) {
                val gameL = gameBuffer[i * 2]
                val gameR = gameBuffer[i * 2 + 1]
                val mic = if (i < micRead) micBuffer[i] else 0

                if (hasMic) {
                    outBuffer[i * 2] = OpenSourceNoiseFilter.mixAndLimit(gameL, mic, gameGain, micGain)
                    outBuffer[i * 2 + 1] = OpenSourceNoiseFilter.mixAndLimit(gameR, mic, gameGain, micGain)
                } else {
                    outBuffer[i * 2] = (gameL * gameGain).toInt().coerceIn(-32768, 32767).toShort()
                    outBuffer[i * 2 + 1] = (gameR * gameGain).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.order(ByteOrder.nativeOrder())
                    inputBuffer.asShortBuffer().put(outBuffer, 0, readShorts)
                    val ptsUs = System.nanoTime() / 1000
                    codec.queueInputBuffer(inputBufferIndex, 0, readShorts * 2, ptsUs, 0)
                }
            }
        }
    }

    private fun drainEncoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            if (!isRecordingAudio.get()) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {}
                continue
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    circularBuffer.audioFormat = codec.outputFormat
                    Log.i(TAG, "Audio output format: ${codec.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            circularBuffer.addSample(outputBuffer, bufferInfo, isVideo = false)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        stopAudioCapture()
        try {
            drainThread?.interrupt()
            drainThread?.join(300)
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.i(TAG, "AudioEncoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioEncoder", e)
        }
    }
}

class ConcurrentShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val lock = Any()

    fun write(src: ShortArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in 0 until length) {
                buffer[head] = src[offset + i]
                head = (head + 1) % capacity
                if (count < capacity) {
                    count++
                } else {
                    tail = (tail + 1) % capacity
                }
            }
            val maxAllowed = 4800
            if (count > maxAllowed) {
                val excess = count - maxAllowed
                tail = (tail + excess) % capacity
                count = maxAllowed
            }
        }
    }

    fun read(dest: ShortArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            val toRead = minOf(length, count)
            for (i in 0 until toRead) {
                dest[offset + i] = buffer[tail]
                tail = (tail + 1) % capacity
            }
            count -= toRead
            return toRead
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            count = 0
        }
    }
}
