package com.thor.instantreplay.buffer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque

data class MediaPacket(
    val data: ByteArray,
    val isVideo: Boolean,
    val presentationTimeUs: Long,
    val flags: Int
)

class CircularMediaBuffer(
    var bufferDurationSeconds: Long = 60
) {
    companion object {
        private const val TAG = "CircularMediaBuffer"
    }

    private val bufferQueue = ConcurrentLinkedDeque<MediaPacket>()
    
    @Volatile
    var videoFormat: MediaFormat? = null
    
    @Volatile
    var audioFormat: MediaFormat? = null

    @Volatile
    var totalBytesInBuffer: Long = 0L

    // --- Manual Continuous Recording Engine ---
    @Volatile
    var isManualRecording: Boolean = false
        private set

    private var manualMuxer: MediaMuxer? = null
    private var manualVideoTrack: Int = -1
    private var manualAudioTrack: Int = -1
    private var manualBaseTimeUs: Long = 0L
    private var manualKeyframeSeen: Boolean = false
    private val manualLock = Any()

    fun clear() {
        bufferQueue.clear()
        totalBytesInBuffer = 0L
    }

    fun startManualRecording(outputFile: File): Boolean {
        synchronized(manualLock) {
            val currentVideo = videoFormat
            if (currentVideo == null) {
                Log.w(TAG, "Cannot start manual recording: videoFormat is not ready yet")
                return false
            }

            return try {
                outputFile.parentFile?.mkdirs()
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                manualVideoTrack = muxer.addTrack(currentVideo)
                manualAudioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
                muxer.start()
                manualMuxer = muxer
                manualBaseTimeUs = 0L
                manualKeyframeSeen = false
                isManualRecording = true
                Log.i(TAG, "Manual continuous recording started -> ${outputFile.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start manual recording", e)
                false
            }
        }
    }

    fun stopManualRecording(): Boolean {
        synchronized(manualLock) {
            isManualRecording = false
            return try {
                val muxer = manualMuxer
                manualMuxer = null
                manualVideoTrack = -1
                manualAudioTrack = -1
                manualKeyframeSeen = false
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                    Log.i(TAG, "Manual continuous recording stopped successfully")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop manual recording", e)
                false
            }
        }
    }

    fun addSample(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isVideo: Boolean) {
        val bytes = ByteArray(bufferInfo.size)
        byteBuffer.position(bufferInfo.offset)
        byteBuffer.get(bytes, 0, bufferInfo.size)

        // 1. Direct stream to Manual Recording Muxer if active
        if (isManualRecording) {
            synchronized(manualLock) {
                val muxer = manualMuxer
                if (muxer != null) {
                    if (!manualKeyframeSeen) {
                        if (isVideo && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                            manualKeyframeSeen = true
                            manualBaseTimeUs = bufferInfo.presentationTimeUs
                        }
                    }
                    if (manualKeyframeSeen) {
                        val track = if (isVideo) manualVideoTrack else manualAudioTrack
                        if (track >= 0) {
                            val info = MediaCodec.BufferInfo().apply {
                                offset = 0
                                size = bytes.size
                                presentationTimeUs = (bufferInfo.presentationTimeUs - manualBaseTimeUs).coerceAtLeast(0)
                                flags = bufferInfo.flags
                            }
                            try {
                                muxer.writeSampleData(track, ByteBuffer.wrap(bytes), info)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error writing manual recording sample", e)
                            }
                        }
                    }
                }
            }
        }

        // 2. Add to Instant Replay Circular RAM Buffer
        val packet = MediaPacket(
            data = bytes,
            isVideo = isVideo,
            presentationTimeUs = bufferInfo.presentationTimeUs,
            flags = bufferInfo.flags
        )

        bufferQueue.addLast(packet)
        totalBytesInBuffer += bytes.size

        // Trim old packets beyond the duration window
        val newestTime = packet.presentationTimeUs
        val bufferThresholdUs = bufferDurationSeconds * 1_000_000L

        while (bufferQueue.isNotEmpty()) {
            val oldest = bufferQueue.peekFirst() ?: break
            if (newestTime - oldest.presentationTimeUs > bufferThresholdUs) {
                val removed = bufferQueue.removeFirst()
                totalBytesInBuffer -= removed.data.size
            } else {
                break
            }
        }
    }

    /**
     * Dumps the current circular buffer in RAM into an MP4 file.
     * Ensures video starts cleanly at an IDR Keyframe.
     */
    fun exportToMp4(outputFile: File): Boolean {
        val currentVideoFormat = videoFormat
        if (currentVideoFormat == null || bufferQueue.isEmpty()) {
            Log.w(TAG, "Cannot export: videoFormat null or buffer empty")
            return false
        }

        try {
            outputFile.parentFile?.mkdirs()
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrackIndex = muxer.addTrack(currentVideoFormat)
            val audioTrackIndex = audioFormat?.let { muxer.addTrack(it) } ?: -1

            muxer.start()

            var keyFrameFound = false
            var baseTimeUs = 0L

            val snapshotList = bufferQueue.toList()

            for (packet in snapshotList) {
                if (!keyFrameFound) {
                    if (packet.isVideo && (packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                        keyFrameFound = true
                        baseTimeUs = packet.presentationTimeUs
                    } else {
                        continue
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = packet.data.size
                    presentationTimeUs = (packet.presentationTimeUs - baseTimeUs).coerceAtLeast(0)
                    flags = packet.flags
                }

                val track = if (packet.isVideo) videoTrackIndex else audioTrackIndex
                if (track >= 0) {
                    muxer.writeSampleData(track, ByteBuffer.wrap(packet.data), bufferInfo)
                }
            }

            muxer.stop()
            muxer.release()
            Log.i(TAG, "Successfully exported replay clip to ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export MP4", e)
            return false
        }
    }

    fun getBufferStats(): Pair<Int, Long> {
        return Pair(bufferQueue.size, totalBytesInBuffer)
    }
}
