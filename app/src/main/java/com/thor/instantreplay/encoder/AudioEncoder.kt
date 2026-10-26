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
import android.os.Process
import android.util.Log
import com.thor.instantreplay.buffer.CircularMediaBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Game audio + microphone mixed into a single AAC track.
 *
 * The encoder is driven from the AudioRecord feeder itself: each pass queues one
 * block of PCM and drains whatever the codec has produced in the meantime, with
 * a zero timeout. That removes the dedicated output-polling thread the previous
 * version needed, which woke ~100 times a second even when there was nothing to
 * collect. All PCM buffers are allocated once, outside the loop.
 */
class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val circularBuffer: CircularMediaBuffer,
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val bitRate: Int = 128_000,
    private val recordMic: Boolean = true,
    private val enableNoiseFilter: Boolean = true,
    gameVolumePercent: Int = 85,
    micVolumePercent: Int = 150
) {
    companion object {
        private const val TAG = "AudioEncoder"

        /** 2048 shorts = 1024 stereo frames = 21.3 ms at 48 kHz. */
        private const val READ_SHORTS = 2048
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

    private val micRingBuffer = ConcurrentShortRingBuffer(sampleRate * 2)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get()) return

        try {
            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

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

            startAudioCapture()

            recordThread = Thread({ feedEncoderLoop() }, "AudioRecordFeeder").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
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

            // 1. Internal audio playback capture (Android 10+)
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

            // 2. Microphone, only when the user asked for it
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
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start microphone recording, continuing with internal audio only", e)
                }
            }

            playbackRecord?.startRecording()
            isRecordingAudio.set(true)

            if (micRecord != null) {
                micThread = Thread({ micCaptureLoop() }, "MicCaptureThread").apply { start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
        }
    }

    private fun stopAudioCapture() {
        if (!isRecordingAudio.getAndSet(false)) return

        try {
            // 1. Stop the AudioRecords first so pending native reads unblock
            try { playbackRecord?.stop() } catch (_: Exception) {}
            try { micRecord?.stop() } catch (_: Exception) {}

            // 2. Join the reading threads so they exit completely
            recordThread?.interrupt()
            micThread?.interrupt()
            try { recordThread?.join(300) } catch (_: Exception) {}
            try { micThread?.join(300) } catch (_: Exception) {}
            recordThread = null
            micThread = null

            // 3. Release them to drop the AudioIn wakelock in audioserver
            try { playbackRecord?.release() } catch (_: Exception) {}
            playbackRecord = null

            try { micRecord?.release() } catch (_: Exception) {}
            micRecord = null

            try { noiseSuppressor?.release() } catch (_: Exception) {}
            noiseSuppressor = null

            try { echoCanceler?.release() } catch (_: Exception) {}
            echoCanceler = null

            micRingBuffer.clear()
            Log.i(TAG, "Audio capture stopped & released")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture", e)
        }
    }

    fun pause() {
        if (!isRunning.get()) return
        stopAudioCapture()
        Log.i(TAG, "AudioEncoder paused: AudioRecords released for Doze")
    }

    fun resume() {
        if (!isRunning.get()) return
        startAudioCapture()
        Log.i(TAG, "AudioEncoder resumed: AudioRecords re-initialized")
    }

    private fun micCaptureLoop() {
        val mic = micRecord ?: return
        val micBuffer = ShortArray(READ_SHORTS / 2)
        val filter = if (enableNoiseFilter) OpenSourceNoiseFilter(sampleRate) else null
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

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

    /**
     * Reads one block of captured game audio, mixes in the microphone, queues it,
     * and collects every encoded packet the codec has ready. One thread, one wake
     * per 21 ms block, no polling of the encoder.
     */
    private fun feedEncoderLoop() {
        val record = playbackRecord ?: return
        val codec = mediaCodec ?: return
        val gameBuffer = ShortArray(READ_SHORTS)
        val micBuffer = ShortArray(READ_SHORTS / 2)
        val outBuffer = ShortArray(READ_SHORTS)
        val bufferInfo = MediaCodec.BufferInfo()

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
            val micRead = if (hasMic) micRingBuffer.read(micBuffer, 0, frames.coerceAtMost(micBuffer.size)) else 0

            for (i in 0 until frames) {
                val gameL = gameBuffer[i * 2]
                val gameR = gameBuffer[i * 2 + 1]
                if (hasMic) {
                    val mic = if (i < micRead) micBuffer[i] else 0
                    outBuffer[i * 2] = OpenSourceNoiseFilter.mixAndLimit(gameL, mic, gameGain, micGain)
                    outBuffer[i * 2 + 1] = OpenSourceNoiseFilter.mixAndLimit(gameR, mic, gameGain, micGain)
                } else {
                    outBuffer[i * 2] = (gameL * gameGain).toInt().coerceIn(-32768, 32767).toShort()
                    outBuffer[i * 2 + 1] = (gameR * gameGain).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            val inputIndex = codec.dequeueInputBuffer(5_000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.order(ByteOrder.nativeOrder())
                    inputBuffer.asShortBuffer().put(outBuffer, 0, readShorts)
                    codec.queueInputBuffer(inputIndex, 0, readShorts * 2, System.nanoTime() / 1000, 0)
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }

            drainAvailable(codec, bufferInfo)
        }

        drainAvailable(codec, bufferInfo)
    }

    /** Non-blocking drain; called once per captured block so no thread is needed. */
    private fun drainAvailable(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (e: Exception) {
                Log.w(TAG, "Audio drain failed", e)
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    circularBuffer.audioFormat = codec.outputFormat
                    Log.i(TAG, "Audio output format: ${codec.outputFormat}")
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index >= 0 -> {
                    try {
                        if (bufferInfo.size > 0) {
                            codec.getOutputBuffer(index)?.let { output ->
                                circularBuffer.addSample(output, bufferInfo, isVideo = false)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Dropped audio packet", t)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }
                else -> return
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        stopAudioCapture()
        try {
            mediaCodec?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            mediaCodec = null
            Log.i(TAG, "AudioEncoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioEncoder", e)
        }
    }
}

/**
 * Lock-protected FIFO of 16-bit samples. Copies contiguous runs with
 * `System.arraycopy` instead of walking one sample at a time.
 */
class ConcurrentShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val lock = Any()

    fun write(src: ShortArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            var remaining = length
            var srcOffset = offset
            while (remaining > 0) {
                val run = minOf(remaining, capacity - head)
                System.arraycopy(src, srcOffset, buffer, head, run)
                head = (head + run) % capacity
                srcOffset += run
                remaining -= run
                count = minOf(capacity, count + run)
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
            val toRead = minOf(length, count, dest.size - offset)
            if (toRead <= 0) return 0
            var remaining = toRead
            var dstOffset = offset
            while (remaining > 0) {
                val run = minOf(remaining, capacity - tail)
                System.arraycopy(buffer, tail, dest, dstOffset, run)
                tail = (tail + run) % capacity
                dstOffset += run
                remaining -= run
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
