import java.util.*;

/**
 * Host-side simulation of CircularMediaBuffer's arena arithmetic.
 *
 * Mirrors addSample / ensureRoomLocked / evictByDurationLocked / dropHeadLocked /
 * exportToMp4 slot maths one-for-one (same field names, same order of operations)
 * so wrap-around, FIFO eviction and snapshot pinning can be checked without a
 * device. Payload contents are verified byte-for-byte on every read-back.
 */
public final class RingSim {

    private static final int KEY_FRAME = 1;

    private final byte[] arena;
    private final long[] startAbs;
    private final int[] sizes;
    private final long[] ptsArr;
    private final int[] flagsArr;
    private final boolean[] videoArr;
    private final int slotCount;
    private int head = 0;
    private int count = 0;
    private long writeAbs = 0L;
    private long snapshotFloorAbs = -1L;
    private final long bufferDurationSeconds;

    private long totalBytesInBuffer = 0L;

    /** Reference copies keyed by absolute start so read-back can be verified. */
    private final Map<Long, byte[]> payloads = new HashMap<>();

    RingSim(int capBytes, int slots, long durationSec) {
        arena = new byte[capBytes];
        slotCount = slots;
        startAbs = new long[slots];
        sizes = new int[slots];
        ptsArr = new long[slots];
        flagsArr = new int[slots];
        videoArr = new boolean[slots];
        bufferDurationSeconds = durationSec;
    }

    // --- capture path (mirror) ---------------------------------------------
    boolean addSample(byte[] payload, long ptsUs, boolean isVideo, int flags) {
        int size = payload.length;
        if (size <= 0) return false;

        int cap = arena.length;
        if (cap == 0 || slotCount == 0) return false;
        if (!ensureRoomLocked(size, cap)) return false;

        int off = (int) (writeAbs % cap);
        int firstChunk = Math.min(size, cap - off);
        System.arraycopy(payload, 0, arena, off, firstChunk);
        if (firstChunk < size) {
            System.arraycopy(payload, firstChunk, arena, 0, size - firstChunk);
        }

        int slot = (head + count) % slotCount;
        startAbs[slot] = writeAbs;
        sizes[slot] = size;
        ptsArr[slot] = ptsUs;
        flagsArr[slot] = flags;
        videoArr[slot] = isVideo;
        writeAbs += size;
        count++;

        evictByDurationLocked(ptsUs);
        totalBytesInBuffer = writeAbs - startAbs[head];

        payloads.put(writeAbs - size, payload);
        return true;
    }

    private boolean ensureRoomLocked(int size, int cap) {
        if (size > cap || slotCount == 0) return false;
        if (count >= slotCount) return false;
        while (count > 0 && (writeAbs - startAbs[head]) + size > cap) {
            if (snapshotFloorAbs >= 0) return false;
            dropHeadLocked();
        }
        return true;
    }

    private void evictByDurationLocked(long newestPtsUs) {
        long windowUs = bufferDurationSeconds * 1_000_000L;
        while (count > 0) {
            if (snapshotFloorAbs >= 0) return;
            if (newestPtsUs - ptsArr[head] <= windowUs) return;
            dropHeadLocked();
        }
    }

    private void dropHeadLocked() {
        head = (head + 1) % slotCount;
        count--;
        if (count == 0) {
            writeAbs = 0L;
            head = 0;
        }
    }

    // --- save path (mirror) ------------------------------------------------
    void beginExport() {
        if (count == 0) throw new IllegalStateException("nothing to export");
        snapshotFloorAbs = startAbs[head];
    }

    /** Verifies every pinned packet reads back intact, then releases the pin. */
    int verifyPinnedExport() {
        int snapHead = head;
        int snapCount = count;
        int cap = arena.length;
        int muxed = 0;
        long baseTimeUs = -1;
        for (int j = 0; j < snapCount; j++) {
            int s = (snapHead + j) % slotCount;
            if (baseTimeUs < 0) {
                if (!(videoArr[s] && (flagsArr[s] & KEY_FRAME) != 0)) continue;
                baseTimeUs = ptsArr[s];
            }
            int size = sizes[s];
            if (size <= 0) continue;
            int off = (int) (startAbs[s] % cap);
            byte[] read = new byte[size];
            if (off + size <= cap) {
                System.arraycopy(arena, off, read, 0, size);
            } else {
                int firstChunk = cap - off;
                System.arraycopy(arena, off, read, 0, firstChunk);
                System.arraycopy(arena, 0, read, firstChunk, size - firstChunk);
            }
            byte[] expected = payloads.get(startAbs[s]);
            if (expected == null) throw new IllegalStateException("missing payload at " + startAbs[s]);
            if (!Arrays.equals(expected, read)) {
                throw new IllegalStateException("CORRUPT packet abs=" + startAbs[s] + " size=" + size + " off=" + off);
            }
            if (ptsArr[s] - baseTimeUs < 0) throw new IllegalStateException("non-monotonic pts");
            muxed++;
        }
        snapshotFloorAbs = -1L;
        return muxed;
    }

    /** Full export path including the keyframe scan; returns packets muxed or -1. */
    int exportStart() {
        if (count == 0 || arena.length == 0) return -1;
        snapshotFloorAbs = startAbs[head];
        try {
            int snapHead = head;
            int snapCount = count;
            int firstIndex = -1;
            for (int j = 0; j < snapCount; j++) {
                int s = (snapHead + j) % slotCount;
                if (videoArr[s] && (flagsArr[s] & KEY_FRAME) != 0) { firstIndex = j; break; }
            }
            if (firstIndex < 0) return -1;
            return verifyPinnedExport();
        } finally {
            snapshotFloorAbs = -1L;
        }
    }

    // --- invariants --------------------------------------------------------
    void checkInvariants(String phase) {
        long live = 0;
        long prevEnd = -1;
        for (int i = 0; i < count; i++) {
            int s = (head + i) % slotCount;
            if (startAbs[s] < prevEnd) throw new IllegalStateException(phase + ": overlapping packet at slot " + s);
            prevEnd = startAbs[s] + sizes[s];
            if (sizes[s] <= 0 || sizes[s] > arena.length) throw new IllegalStateException(phase + ": bad size");
            live += sizes[s];
        }
        if (live > arena.length) throw new IllegalStateException(phase + ": live " + live + " > capacity " + arena.length);
        if (live != totalBytesInBuffer) {
            throw new IllegalStateException(phase + ": totalBytesInBuffer " + totalBytesInBuffer + " != live " + live);
        }
        if (totalBytesInBuffer < 0) throw new IllegalStateException(phase + ": negative total");
        if (count == 0 && writeAbs != 0) throw new IllegalStateException(phase + ": writeAbs not reset");
        if (payloads.size() > 200_000) payloads.clear(); // keep the reference map bounded
    }

    public static void main(String[] args) {
        Random rnd = new Random(20261026L);

        // ---- 1. long run: constant wrap-around, eviction, periodic export ----
        RingSim ring = new RingSim(400_000, 512, 5);
        long pts = 0;
        int frame = 0, exports = 0, dropped = 0;
        long muxedTotal = 0;

        for (int i = 0; i < 60_000; i++) {
            frame++;
            boolean isVideo = (i % 3) != 2;
            int size = 200 + rnd.nextInt(isVideo ? 3_000 : 200);
            byte[] payload = new byte[size];
            rnd.nextBytes(payload);
            pts += isVideo ? 16_666L : 21_333L;
            // A keyframe ~every 90 packets, always landing on a video packet.
            int flags = (isVideo && (i % 90) == 0) ? KEY_FRAME : 0;

            if (!ring.addSample(payload, pts, isVideo, flags)) dropped++;
            ring.checkInvariants("iter " + i);

            if (i % 2500 == 0) {
                int muxed = ring.exportStart();
                if (muxed > 0) { exports++; muxedTotal += muxed; }
            }
        }
        if (exports == 0) throw new IllegalStateException("export path was never exercised");
        System.out.println("PASS long run: iterations=60000 exports=" + exports
                + " muxedTotal=" + muxedTotal + " dropped=" + dropped
                + " live=" + ring.totalBytesInBuffer + "/" + ring.arena.length);

        // ---- 2. a pinned export must never be overwritten -------------------
        RingSim pin = new RingSim(60_000, 64, 1000);
        for (int i = 0; i < 3; i++) {
            byte[] p = new byte[20_000];
            rnd.nextBytes(p);
            if (!pin.addSample(p, i * 100_000L, true, i == 0 ? KEY_FRAME : 0)) {
                throw new IllegalStateException("unexpected drop while filling");
            }
        }
        pin.beginExport();
        int droppedWhilePinned = 0;
        for (int k = 0; k < 20; k++) {
            byte[] p = new byte[20_000];
            rnd.nextBytes(p);
            if (!pin.addSample(p, 1_000_000L + k * 100_000L, true, 0)) droppedWhilePinned++;
            pin.checkInvariants("pinned " + k);
        }
        int muxed = pin.verifyPinnedExport();
        if (muxed < 3) throw new IllegalStateException("pinned export lost packets: " + muxed);
        if (droppedWhilePinned == 0) throw new IllegalStateException("expected drops while the arena was pinned");
        System.out.println("PASS pinning: pinned packets intact=" + muxed + ", dropped while pinned=" + droppedWhilePinned);

        // ---- 3. eviction resumes once the pin is released ------------------
        byte[] p = new byte[20_000];
        rnd.nextBytes(p);
        if (!pin.addSample(p, 9_000_000L, true, 0)) {
            throw new IllegalStateException("eviction did not resume after the export released the arena");
        }
        pin.checkInvariants("after unpin");
        System.out.println("PASS eviction resumes after the snapshot is released");

        // ---- 4. single packet larger than the arena is dropped -------------
        RingSim tiny = new RingSim(10_000, 8, 10);
        if (tiny.addSample(new byte[20_000], 0, true, 0)) {
            throw new IllegalStateException("oversized packet must be dropped");
        }
        tiny.checkInvariants("oversized");
        System.out.println("PASS oversized packet is dropped, not written past the arena");

        System.out.println("ALL SIMULATION CHECKS PASSED");
    }
}
