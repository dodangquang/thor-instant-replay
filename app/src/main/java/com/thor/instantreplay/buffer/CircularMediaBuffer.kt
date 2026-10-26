package com.thor.instantreplay.buffer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Instant-replay ring of **already encoded** H.264/AAC packets.
 *
 * The buffer is one preallocated off-heap arena plus preallocated parallel slot
 * arrays, so capturing a frame costs a single `memcpy` and allocates no Java
 * object. The previous implementation allocated one `ByteArray` per packet
 * (~107/s, ~2 MB/s at the old 1080p60/16 Mbps defaults), which forced periodic
 * garbage collections while the encoder drain thread was still holding
 * MediaCodec output buffers. A collection pause there stops the codec accepting
 * input, the virtual-display producer blocks on the full input queue, and the
 * blocked compositor shows up as a 100-500 ms hole in the game's frame pacing.
 * Keeping the steady-state path allocation-free removes that mechanism.
 *
 * RAM is bounded by [configure]: `duration x bitrate x headroom`, clamped to
 * [MAX_ARENA_BYTES], so the replay window can never grow without limit.
 */
class CircularMediaBuffer {

    companion object {
        private const val TAG = "CircularMediaBuffer"
        private const val MIN_ARENA_BYTES = 6L * 1024 * 1024
        private const val MAX_ARENA_BYTES = 128L * 1024 * 1024

        /** VBR encoders overshoot the nominal bitrate; leave room so the window holds. */
        private const val ARENA_HEADROOM = 1.3

        /** 60 fps video + ~47 AAC packets/s, with headroom for higher frame rates. */
        private const val SLOTS_PER_SECOND = 160

        /** Bounded hand-off to the manual-recording writer: ~4 MB at 720p, dropped when full. */
        private const val MANUAL_QUEUE_PACKETS = 400
    }

    // --- Configuration -------------------------------------------------------

    @Volatile
    var bufferDurationSeconds: Long = 60L
        private set

    // --- Encoder output formats ---------------------------------------------

    @Volatile
    var videoFormat: MediaFormat? = null

    @Volatile
    var audioFormat: MediaFormat? = null

    // --- Ring state (guarded by [lock]) -------------------------------------

    private val lock = Any()

    /** Off-heap packet arena; direct so MediaMuxer writes from it without a copy. */
    private var arena: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** Capture-thread view of [arena]; owns its own position so it never races a read. */
    private var writeView: ByteBuffer = ByteBuffer.allocateDirect(0)

    private var slotCount = 0
    private var startAbs = LongArray(0)
    private var sizes = IntArray(0)
    private var ptsArr = LongArray(0)
    private var flagsArr = IntArray(0)
    private var videoArr = BooleanArray(0)

    private var head = 0
    private var count = 0

    /** Monotonic write cursor; live bytes are `writeAbs - startAbs[head]`. */
    private var writeAbs = 0L

    /**
     * While an export walks the ring, eviction may not move the head or the muxer
     * would read packets that were already recycled. Packets that no longer fit
     * are dropped for the (short) duration of the export instead.
     */
    private var snapshotFloorAbs = -1L

    @Volatile
    var totalBytesInBuffer: Long = 0L
        private set

    val capacityBytes: Int get() = arena.capacity()

    /**
     * Sizes the ring for [durationSeconds] of [videoBitRate]. Safe to call again on
     * restart: it drops everything buffered.
     *
     * Direct buffers are only reclaimed by the GC once unreachable, and the arena
     * can be ~100 MB, so a start/stop cycle reuses it whenever the new size still
     * fits. Repeated restarts at the same settings allocate nothing.
     */
    fun configure(durationSeconds: Long, videoBitRate: Int) {
        stopManualRecording()
        val wanted = (durationSeconds.coerceAtLeast(1L) * videoBitRate.toLong() / 8L * ARENA_HEADROOM).toLong()
        val arenaBytes = wanted.coerceIn(MIN_ARENA_BYTES, MAX_ARENA_BYTES).toInt()
        val slots = (durationSeconds.coerceAtLeast(1L) * SLOTS_PER_SECOND).toInt().coerceAtLeast(512)
        synchronized(lock) {
            bufferDurationSeconds = durationSeconds
            if (arena.capacity() < arenaBytes) {
                arena = ByteBuffer.allocateDirect(arenaBytes)
                writeView = arena.duplicate()
            }
            if (slotCount < slots) {
                slotCount = slots
                startAbs = LongArray(slots)
                sizes = IntArray(slots)
                ptsArr = LongArray(slots)
                flagsArr = IntArray(slots)
                videoArr = BooleanArray(slots)
            }
            head = 0
            count = 0
            writeAbs = 0L
            snapshotFloorAbs = -1L
            totalBytesInBuffer = 0L
        }
        Log.i(
            TAG,
            "Replay ring ready for ${durationSeconds}s @ ${videoBitRate / 1_000_000} Mbps: " +
                "arena=${arena.capacity() / (1024 * 1024)} MB, slots=$slotCount"
        )
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
            writeAbs = 0L
            snapshotFloorAbs = -1L
            totalBytesInBuffer = 0L
        }
    }

    // ------------------------------------------------------------------------
    // Manual continuous recording
    // ------------------------------------------------------------------------

    @Volatile
    var isManualRecording: Boolean = false
        private set

    private class ManualPacket(
        val data: ByteArray,
        val isVideo: Boolean,
        val ptsUs: Long,
        val flags: Int
    )

    private val manualLock = Any()
    private val manualQueue = ArrayBlockingQueue<ManualPacket>(MANUAL_QUEUE_PACKETS)
    private val manualQueuedBytes = AtomicLong(0L)
    private var manualMuxer: MediaMuxer? = null
    private var manualVideoTrack = -1
    private var manualAudioTrack = -1
    private var manualKeyframeSeen = false
    private var manualBaseTimeUs = 0L
    private var manualWriter: Thread? = null
    private var manualAccepting = false
    private var manualDroppedBytes = 0L

    /**
     * Starts a continuous recording. Packets go to a dedicated writer thread
     * through a bounded queue: the capture thread never performs disk I/O, so a
     * slow write cannot stall the encoder drain or the display pipeline.
     *
     * The muxer is created and started outside [manualLock] on purpose. Holding
     * the lock across that I/O would block the codec callback inside
     * [offerManual] while it still owns an output buffer, which is the exact
     * back-pressure path this refactor exists to avoid.
     */
    fun startManualRecording(outputFile: File): Boolean {
        val currentVideo = videoFormat
        if (currentVideo == null) {
            Log.w(TAG, "Cannot start manual recording: videoFormat is not ready yet")
            return false
        }
        synchronized(manualLock) { if (isManualRecording) return true }

        var muxer: MediaMuxer? = null
        try {
            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = muxer.addTrack(currentVideo)
            val audioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()

            synchronized(manualLock) {
                if (isManualRecording) {
                    // Lost a race with another start; keep the first one.
                    runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                    return true
                }
                manualVideoTrack = videoTrack
                manualAudioTrack = audioTrack
                manualMuxer = muxer
                manualBaseTimeUs = 0L
                manualKeyframeSeen = false
                manualQueuedBytes.set(0L)
                manualDroppedBytes = 0L
                manualQueue.clear()
                isManualRecording = true
                manualAccepting = true
                manualWriter = Thread({ manualWriterLoop(muxer, videoTrack, audioTrack) }, "ReplayClipWriter").apply {
                    priority = Thread.MIN_PRIORITY
                    start()
                }
            }
            Log.i(TAG, "Manual continuous recording started -> ${outputFile.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start manual recording", e)
            if (muxer != null) {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            synchronized(manualLock) { cleanupManualLocked() }
            return false
        }
    }

    fun stopManualRecording(): Boolean {
        val writer: Thread?
        synchronized(manualLock) {
            if (!isManualRecording) return false
            manualAccepting = false
            writer = manualWriter
        }
        // The writer owns the muxer: let it drain what it already accepted.
        var writerExited = true
        if (writer != null) {
            try {
                writer.join(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            writerExited = !writer.isAlive
        }
        synchronized(manualLock) {
            val muxer = manualMuxer
            val dropped = manualDroppedBytes
            if (!writerExited) {
                // Still writing: releasing the muxer here would race the writer and
                // crash. Drop our reference and let the file be truncated instead.
                Log.e(TAG, "Clip writer is still blocking on I/O; abandoning the muxer")
                cleanupManualLocked()
                return false
            }
            cleanupManualLocked()
            return try {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                    Log.i(TAG, "Manual recording stopped (${dropped / 1024} KB dropped under back-pressure)")
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

    private fun cleanupManualLocked() {
        isManualRecording = false
        manualAccepting = false
        manualMuxer = null
        manualWriter = null
        manualVideoTrack = -1
        manualAudioTrack = -1
        manualKeyframeSeen = false
        manualQueue.clear()
        manualQueuedBytes.set(0L)
    }

    private fun manualWriterLoop(muxer: MediaMuxer, videoTrack: Int, audioTrack: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val packet = manualQueue.poll(250, TimeUnit.MILLISECONDS)
            if (packet == null) {
                // Idle: exit once a stop has been requested and nothing is left.
                if (!manualAccepting && manualQueue.isEmpty()) return
                continue
            }
            manualQueuedBytes.addAndGet(-packet.data.size.toLong())
            val track = if (packet.isVideo) videoTrack else audioTrack
            if (track < 0) continue
            info.set(0, packet.data.size, packet.ptsUs, packet.flags)
            try {
                muxer.writeSampleData(track, ByteBuffer.wrap(packet.data), info)
            } catch (e: Exception) {
                Log.w(TAG, "Error writing manual recording sample", e)
            }
        }
    }

    /** Copies a packet for the clip writer; returns immediately once the queue is full. */
    private fun offerManual(src: ByteBuffer, srcOffset: Int, size: Int, ptsUs: Long, flags: Int, isVideo: Boolean) {
        synchronized(manualLock) {
            if (!manualAccepting) return
            if (!manualKeyframeSeen) {
                if (!isVideo || (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return
                manualKeyframeSeen = true
                manualBaseTimeUs = ptsUs
            }
            if (manualQueue.remainingCapacity() == 0) {
                manualDroppedBytes += size
                return
            }
            try {
                val bytes = ByteArray(size)
                val view = src.duplicate()
                view.position(srcOffset)
                view.limit(srcOffset + size)
                view.get(bytes)
                manualQueue.offer(
                    ManualPacket(
                        data = bytes,
                        isVideo = isVideo,
                        ptsUs = (ptsUs - manualBaseTimeUs).coerceAtLeast(0),
                        flags = flags
                    )
                )
                manualQueuedBytes.addAndGet(size.toLong())
            } catch (e: Exception) {
                Log.w(TAG, "Dropped manual recording sample", e)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Capture path
    // ------------------------------------------------------------------------

    /**
     * Appends one encoder output packet. Never blocks and never allocates: when
     * the ring is full the oldest packets are recycled, and if room still cannot
     * be made the new packet is dropped.
     */
    fun addSample(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isVideo: Boolean) {
        val size = bufferInfo.size
        if (size <= 0) return
        val offset = bufferInfo.offset

        if (isManualRecording) {
            offerManual(byteBuffer, offset, size, bufferInfo.presentationTimeUs, bufferInfo.flags, isVideo)
        }

        try {
            synchronized(lock) {
                val cap = arena.capacity()
                if (cap == 0 || slotCount == 0) return
                if (!ensureRoomLocked(size, cap)) return // dropped rather than waiting on the encoder

                val off = (writeAbs % cap).toInt()
                val firstChunk = minOf(size, cap - off)

                byteBuffer.position(offset)
                byteBuffer.limit(offset + firstChunk)
                writeView.position(off)
                writeView.put(byteBuffer)
                if (firstChunk < size) {
                    byteBuffer.position(offset + firstChunk)
                    byteBuffer.limit(offset + size)
                    writeView.position(0)
                    writeView.put(byteBuffer)
                }

                val slot = (head + count) % slotCount
                startAbs[slot] = writeAbs
                sizes[slot] = size
                ptsArr[slot] = bufferInfo.presentationTimeUs
                flagsArr[slot] = bufferInfo.flags
                videoArr[slot] = isVideo
                writeAbs += size
                count++

                evictByDurationLocked(bufferInfo.presentationTimeUs)
                totalBytesInBuffer = writeAbs - startAbs[head]
            }
        } catch (e: Exception) {
            // A malformed buffer must never take the drain thread down with it.
            Log.w(TAG, "Dropped encoder packet", e)
        }
    }

    /** Must hold [lock]. Returns false when the packet does not fit and cannot be made to fit. */
    private fun ensureRoomLocked(size: Int, cap: Int): Boolean {
        if (size > cap || slotCount == 0) return false
        if (count >= slotCount) return false
        while (count > 0 && (writeAbs - startAbs[head]) + size > cap) {
            if (snapshotFloorAbs >= 0) return false // an export is reading the arena; keep it intact
            dropHeadLocked()
        }
        return true
    }

    private fun evictByDurationLocked(newestPtsUs: Long) {
        val windowUs = bufferDurationSeconds * 1_000_000L
        while (count > 0) {
            if (snapshotFloorAbs >= 0) return
            if (newestPtsUs - ptsArr[head] <= windowUs) return
            dropHeadLocked()
        }
    }

    private fun dropHeadLocked() {
        head = (head + 1) % slotCount
        count--
        if (count == 0) {
            writeAbs = 0L
            head = 0
        }
    }

    // ------------------------------------------------------------------------
    // Save path
    // ------------------------------------------------------------------------

    /**
     * Writes the buffered window to [outputFile] by muxing the packets that are
     * already encoded. Starts at the first keyframe so the clip decodes cleanly.
     */
    fun exportToMp4(outputFile: File): Boolean {
        val currentVideoFormat = videoFormat ?: run {
            Log.w(TAG, "Cannot export: videoFormat null")
            return false
        }

        // Snapshot by reference so a concurrent configure() cannot swap arrays mid-run.
        val snapHead: Int
        val snapCount: Int
        val starts: LongArray
        val szs: IntArray
        val pts: LongArray
        val flags: IntArray
        val vids: BooleanArray
        val slots: Int
        val arenaRef: ByteBuffer
        val cap: Int
        synchronized(lock) {
            if (count == 0 || arena.capacity() == 0) {
                Log.w(TAG, "Cannot export: buffer empty")
                return false
            }
            snapHead = head
            snapCount = count
            starts = startAbs
            szs = sizes
            pts = ptsArr
            flags = flagsArr
            vids = videoArr
            slots = slotCount
            arenaRef = arena
            cap = arenaRef.capacity()
            snapshotFloorAbs = starts[snapHead]
        }

        try {
            // Find the first keyframe up front so an undecodable window writes nothing.
            var firstIndex = -1
            for (j in 0 until snapCount) {
                val s = (snapHead + j) % slots
                if (vids[s] && (flags[s] and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    firstIndex = j
                    break
                }
            }
            if (firstIndex < 0) {
                Log.w(TAG, "Cannot export: no keyframe in the buffered window")
                return false
            }

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val videoTrackIndex = muxer.addTrack(currentVideoFormat)
                val audioTrackIndex = audioFormat?.let { muxer.addTrack(it) } ?: -1
                muxer.start()

                val baseTimeUs = pts[(snapHead + firstIndex) % slots]
                val info = MediaCodec.BufferInfo()
                // One reusable read view for the whole export: no per-packet allocation.
                val view = arenaRef.duplicate()
                var straddleScratch = ByteArray(0)

                for (j in firstIndex until snapCount) {
                    val s = (snapHead + j) % slots
                    val isVideo = vids[s]
                    val track = if (isVideo) videoTrackIndex else audioTrackIndex
                    if (track < 0) continue

                    val size = szs[s]
                    if (size <= 0) continue
                    val off = (starts[s] % cap).toInt()
                    info.set(0, size, (pts[s] - baseTimeUs).coerceAtLeast(0), flags[s])

                    if (off + size <= cap) {
                        view.clear()
                        view.position(off)
                        view.limit(off + size)
                        muxer.writeSampleData(track, view, info)
                    } else {
                        // Packet wraps the end of the arena: rebuild it contiguously.
                        if (straddleScratch.size < size) straddleScratch = ByteArray(size)
                        val firstChunk = cap - off
                        view.clear()
                        view.position(off)
                        view.limit(cap)
                        view.get(straddleScratch, 0, firstChunk)
                        view.position(0)
                        view.limit(size - firstChunk)
                        view.get(straddleScratch, firstChunk, size - firstChunk)
                        muxer.writeSampleData(track, ByteBuffer.wrap(straddleScratch, 0, size), info)
                    }
                }

                muxer.stop()
                Log.i(TAG, "Exported replay clip (${snapCount - firstIndex} packets) to ${outputFile.absolutePath}")
                return true
            } finally {
                runCatching { muxer.release() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export MP4", e)
            runCatching { outputFile.delete() }
            return false
        } finally {
            synchronized(lock) { snapshotFloorAbs = -1L }
        }
    }

    fun getBufferStats(): Pair<Int, Long> = synchronized(lock) { Pair(count, totalBytesInBuffer) }
}
