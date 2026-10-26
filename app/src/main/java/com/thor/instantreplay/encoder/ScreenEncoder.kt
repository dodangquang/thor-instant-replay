package com.thor.instantreplay.encoder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.thor.instantreplay.buffer.CircularMediaBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen capture straight into the hardware H.264 encoder.
 *
 * The MediaProjection virtual display renders into the encoder's input surface,
 * so frames never touch the CPU. Output buffers are handled through the codec's
 * async callback on a dedicated high-priority thread: nothing polls, buffers are
 * returned as soon as they are copied into the packet ring, and a slow consumer
 * can therefore never leave the display pipeline waiting on us.
 */
class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val circularBuffer: CircularMediaBuffer,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val displayConfig: DisplayConfig? = null,
    private val frameRate: Int = 60,
    private val bitRate: Int = 6_000_000,
    private val iFrameIntervalSec: Int = 1
) {
    companion object {
        private const val TAG = "ScreenEncoder"

        /**
         * MediaFormat keys newer than minSdk 29; referenced as literals so the app
         * keeps running on API 29 instead of failing on a missing static field.
         */
        private const val KEY_LOW_LATENCY = "low-latency"
        private const val KEY_MAX_B_FRAMES = "max-bframes"
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplayTop: VirtualDisplay? = null
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var callbackThread: HandlerThread? = null

    fun pause() {
        if (!isRunning.get() || isPaused.get()) return
        isPaused.set(true)
        try {
            mediaCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1) })
            Log.i(TAG, "ScreenEncoder paused (codec input suspended for Doze)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause ScreenEncoder", e)
        }
    }

    fun resume() {
        if (!isRunning.get() || !isPaused.get()) return
        isPaused.set(false)
        try {
            mediaCodec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            Log.i(TAG, "ScreenEncoder resumed (sync frame requested)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume ScreenEncoder", e)
        }
    }

    /** Asks for an IDR on the next frame so a clip can start on a clean boundary. */
    fun requestSyncFrame() {
        if (!isRunning.get()) return
        try {
            mediaCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request a sync frame", e)
        }
    }

    fun start() {
        if (isRunning.get()) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // Real-time mode: never trade latency for bitrate accuracy.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate)
            // Emit each frame as it is encoded instead of batching a GOP.
            setInteger(KEY_LOW_LATENCY, 1)
            // No B-frames: no reordering delay and no extra reference frames held.
            setInteger(KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        val thread = HandlerThread("ScreenEncoder").apply { start() }
        callbackThread = thread
        Process.setThreadPriority(thread.threadId, Process.THREAD_PRIORITY_DISPLAY)

        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec = codec
            codec.setCallback(CodecCallback(), Handler(thread.looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            virtualDisplayTop = mediaProjection.createVirtualDisplay(
                "ThorScreenMain",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            isRunning.set(true)
            Log.i(TAG, "ScreenEncoder started at ${width}x${height} @ ${frameRate}fps, ${bitRate / 1000} kbps (async, realtime)")
        } catch (e: Exception) {
            // Release whatever was created so a failed start cannot leak a codec.
            Log.e(TAG, "Failed to start ScreenEncoder", e)
            stop()
            throw e
        }
    }

    private inner class CodecCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: the codec pulls frames from the virtual display itself.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && !isPaused.get()) {
                    codec.getOutputBuffer(index)?.let { buffer ->
                        circularBuffer.addSample(buffer, info, isVideo = true)
                    }
                }
            } catch (t: Throwable) {
                // Never let a buffering hiccup escalate into a codec error.
                Log.w(TAG, "Dropped video packet", t)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            circularBuffer.videoFormat = format
            Log.i(TAG, "Encoder output format: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient}): ${e.diagnosticInfo}", e)
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            virtualDisplayTop?.release()
            virtualDisplayTop = null
            inputSurface?.release()
            inputSurface = null
            mediaCodec?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            mediaCodec = null
            callbackThread?.quitSafely()
            callbackThread = null
            Log.i(TAG, "ScreenEncoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenEncoder", e)
        }
    }
}
