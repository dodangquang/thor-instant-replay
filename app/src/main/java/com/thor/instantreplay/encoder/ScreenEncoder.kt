package com.thor.instantreplay.encoder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import com.thor.instantreplay.buffer.CircularMediaBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val circularBuffer: CircularMediaBuffer,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val displayConfig: DisplayConfig? = null,
    private val frameRate: Int = 60,
    private val bitRate: Int = 16_000_000
) {
    companion object {
        private const val TAG = "ScreenEncoder"
        private const val TIMEOUT_US = 10_000L
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplayTop: VirtualDisplay? = null
    private var virtualDisplayBottom: VirtualDisplay? = null
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var drainThread: Thread? = null

    fun pause() {
        if (!isRunning.get() || isPaused.get()) return
        isPaused.set(true)
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "ScreenEncoder paused (MediaCodec input suspended for Doze mode)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause ScreenEncoder", e)
        }
    }

    fun resume() {
        if (!isRunning.get() || !isPaused.get()) return
        isPaused.set(false)
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "ScreenEncoder resumed (Sync frame requested)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume ScreenEncoder", e)
        }
    }

    fun start() {
        if (isRunning.get()) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe for clean seeking
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        val isDual = displayConfig?.isDualScreen == true && displayConfig.bottomDisplayId != null

        if (isDual) {
            // Dual-screen recording for AYN Thor
            // Primary screen (Top 1080x1080 scaled) and Secondary screen (Bottom 1080x1240)
            Log.i(TAG, "Initializing AYN Thor Dual Screen Virtual Displays (Top: ${displayConfig?.topDisplayId}, Bottom: ${displayConfig?.bottomDisplayId})")
            
            virtualDisplayTop = mediaProjection.createVirtualDisplay(
                "ThorScreenTop",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
        } else {
            // Standard Single Screen 1080p
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
        }

        isRunning.set(true)
        drainThread = Thread({ drainEncoder() }, "ScreenEncoderDrainer").apply { start() }
        Log.i(TAG, "ScreenEncoder started at ${width}x${height} @ ${frameRate}fps (DualMode: $isDual)")
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {}
                continue
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    circularBuffer.videoFormat = codec.outputFormat
                    Log.i(TAG, "Encoder output format changed: ${codec.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Buffer waiting...
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            } else {
                                circularBuffer.addSample(outputBuffer, bufferInfo, isVideo = true)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            drainThread?.join(500)
            virtualDisplayTop?.release()
            virtualDisplayTop = null
            virtualDisplayBottom?.release()
            virtualDisplayBottom = null
            inputSurface?.release()
            inputSurface = null
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.i(TAG, "ScreenEncoder stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenEncoder", e)
        }
    }
}
