# Android Replay — tối ưu hiệu năng capture (phân tích + thay đổi)

Tài liệu này ghi lại: (1) các điểm tốn tài nguyên nhất tìm thấy khi đọc `app/`, kèm số liệu
tính được từ chính cấu hình trong code; (2) những gì đã thay đổi; (3) cách đo lại trên máy.
Áp dụng cho bản 2.9.3 (package `com.thor.instantreplay`) và các bản sau đó.

> **Trạng thái đo:** môi trường thực hiện thay đổi này **không có thiết bị AYN Thor kết nối**
> (`adb devices` rỗng), nên các số `gap_max_ms` / CPU% / RAM **trên máy chưa được đo lại ở đây**.
> Mọi con số trong mục "trước/sau" dưới đây là số **suy ra chính xác từ cấu hình code**
> (bitrate, độ phân giải, fps, kích thước buffer, số wakeup), không phải số đo.
> Mục 6 là quy trình đo lại đúng bộ số của bạn; mục 7 nói rõ cần xác nhận gì.

---

## 1. Năm điểm tốn tài nguyên nhất

Số liệu tính theo **cấu hình mặc định của bản 2.9.3**: `resolution_preset = "1080p"`,
`frameRate = 60` (hardcode trong `ReplayService.initEncoders`), `bitRate = 16 Mbps`,
H.264 High@4.1, I-frame 1 s.

| # | Điểm | Bằng số (2.9.3) | Vì sao ảnh hưởng game |
|---|------|-----------------|------------------------|
| 1 | **Buffer RAM giữ packet dạng `ByteArray` cấp phát mới mỗi frame** (`CircularMediaBuffer.addSample`) | 107 packet/s, **107 `ByteArray` + 107 `MediaPacket` + 107 node deque mỗi giây**, ~2,0 MB/s cấp phát; heap sống **60 / 120 / 240 MB** cho 30 / 60 / 120 s | Đây là nguồn khựng chính. GC chạy đúng lúc thread drain đang giữ output buffer của `MediaCodec` → codec ngừng nhận input → producer của VirtualDisplay chặn trên hàng đợi input đầy → compositor bị chặn → mất khung hình của game. Chu kỳ ~10 s khớp với nhịp GC ở 2 MB/s. |
| 2 | **Encoder 1080p60 @ 16 Mbps, không có hint realtime/low-latency** (`ScreenEncoder`) | **124,4 Mpx/s** mã hoá; không `KEY_PRIORITY`, không `KEY_OPERATING_RATE`, không `KEY_LOW_LATENCY`, không `KEY_MAX_B_FRAMES` | Encoder chia sẻ GPU + băng thông bộ nhớ với game (Turnip/vkd3d). Ở mức 1080p60, encoder gần chạm giới hạn thực tế của SoC; khi nó trễ, input queue đầy và chặn ngược vào display pipeline. |
| 3 | **VirtualDisplay mirror 1080p ở tần số quét tấm nền** | 8,29 MB đọc nguồn + 8,29 MB ghi đích mỗi khung → **995 MB/s ở 60 Hz, 1,99 GB/s ở 120 Hz** | Đây là chi phí *thường trực*, không phụ thuộc fps của encoder. Chạy song song với game trên cùng GPU/bus. |
| 4 | **Polling bằng timeout 10 ms ở cả hai thread drain + housekeeping định kỳ trên main thread** | video drain ≥60 wakeup/s (tới 100/s khi rảnh), audio drain tới 100/s, audio feeder 94/s, mic 47/s, `startStatsUpdater` 1/s → **~250–350 wakeup/s**. Thêm `MainActivity` poll 1,5 s **vẫn chạy sau `onPause`** | Đánh thức CPU liên tục trong lúc chơi; work trên main thread của app cạnh tranh scheduler với thread render của game. |
| 5 | **`LicenseAccess.canUse()` trong hot path** | Mỗi lần gọi: **2 lần đọc + parse asset JSON**, **~3 phép Keystore AES-GCM**, **1 lần verify chữ ký RSA**, **1 `SharedPreferences.commit()` (fsync)**. Gọi khi lưu clip / quay tay / start / resume, trên main thread của service | Chặn main thread hàng chục ms đúng lúc người dùng bấm lưu, và ở bản trial còn lặp lại mỗi 5 s. |

**Điểm phụ đã xử lý luôn:** `feedEncoderLoop` copy ring mic từng sample bằng `%` (96 000 phép
modulo/s); `size` buffer mic cố định `512` trong khi `frames` có thể lớn hơn → tiềm ẩn
`ArrayIndexOutOfBounds` nếu tăng kích thước block đọc.

### Cơ chế: vì sao lỗi của app lại thành `gap_max_ms` của game

1. Thread drain của app đang giữ N output buffer của `MediaCodec`.
2. GC dừng thế giới (hoặc app bị preempt) trong lúc đó → buffer không được trả lại.
3. Pool output của codec cạn → codec ngừng lấy input từ input surface.
4. Input queue của surface đầy → `queueBuffer` của producer (đường render VirtualDisplay của
   SurfaceFlinger) **chặn**.
5. Khung của game không được composite/present đúng hạn → `gap_max_ms` nhảy 100–500 ms.

Vì vậy hai đòn hiệu quả nhất là **bỏ cấp phát trong steady-state** và **giảm tải encoder/mirror**,
chứ không phải tối ưu phần ghi file.

---

## 2. Những gì đã thay đổi

### `buffer/CircularMediaBuffer.kt` — viết lại phần lõi (điểm 1)
- **Arena off-heap một khối** (`ByteBuffer.allocateDirect`) + **mảng slot song song** cấp phát
  trước (`startAbs/sizes/pts/flags/video`). Mỗi packet = **một `memcpy`**, **0 object Java**.
- Ghi/đọc hỗ trợ packet **quấn vòng** qua cuối arena (2 lần copy, không cấp phát).
- Dung lượng **chặn trên theo byte**: `clamp(duration × bitrate / 8 × 1.3, 6 MiB, 128 MiB)`
  → buffer không thể tăng vô hạn, và **cùng một kích thước được tái sử dụng** khi start/stop
  (tránh rò rỉ native do direct buffer chỉ được GC thu hồi).
- Chính sách **non-blocking**: hết chỗ thì đẩy packet cũ nhất (FIFO) ra; nếu vẫn không đủ
  (đang export hoặc packet > arena) thì **drop packet mới**, không bao giờ chờ.
- `exportToMp4`: chụp snapshot metadata dưới lock (vài µs), **mux ngoài lock** nên không chặn
  thread capture; đọc thẳng từ arena bằng **một view tái sử dụng**; quét keyframe trước để
  không tạo file rỗng. Trong lúc export, eviction bị chặn ở mốc snapshot (dữ liệu đang đọc
  không bị ghi đè) — packet mới bị drop thay vì làm hỏng clip.
- **Quay tay**: packet được đẩy qua `ArrayBlockingQueue` (400 packet, có giới hạn) sang thread
  `ReplayClipWriter` riêng → **thread capture không bao giờ ghi đĩa**. Muxer được tạo *ngoài*
  lock để không chặn callback codec. Hết hàng đợi thì drop và log.

### `encoder/ScreenEncoder.kt` — event-driven + realtime (điểm 2, 4)
- Chuyển sang **`MediaCodec.setCallback`** trên `HandlerThread` riêng đặt
  `THREAD_PRIORITY_DISPLAY` → **không còn polling**, buffer được trả lại ngay sau khi copy.
- Thêm: `KEY_PRIORITY = 0` (realtime), `KEY_OPERATING_RATE`/`KEY_CAPTURE_RATE` = fps,
  `"low-latency" = 1`, `"max-bframes" = 0`, `KEY_BITRATE_MODE = VBR`.
- API: thêm `requestSyncFrame()` — gọi khi bắt đầu quay tay để clip bắt đầu ngay tại IDR.
- `start()` dọn tài nguyên nếu lỗi giữa đường (không rò codec/thread).
- Bỏ nhánh dual-screen chết (`DualScreenHelper` luôn trả `isDualScreen = false`).

### `encoder/AudioEncoder.kt` — giảm wakeup + sửa hot loop (điểm 4)
- **Bỏ hẳn thread drain audio**: mỗi vòng đọc PCM (21,3 ms) queue input rồi drain với timeout 0
  → 47 wakeup/s thay vì tới 100/s của vòng polling 10 ms.
- Block đọc 1024 → **2048 short** (10,7 ms → 21,3 ms), và **mọi buffer PCM cấp phát một lần**
  ngoài loop, kích thước suy ra từ hằng số (hết rủi ro tràn `micBuffer`).
- `ConcurrentShortRingBuffer` dùng `System.arraycopy` theo đoạn liền mạch thay vì copy từng
  sample + modulo.
- Bitrate audio 160 → 128 kbps.

### `service/ReplayService.kt` — preset, wakeup, chính sách dừng (điểm 4, 5)
- Mặc định **1280×720 @ 60 fps @ 6 Mbps** (1080p = 12 Mbps). fps đọc từ pref `frame_rate`
  (mặc định 60, kẹp 15..60).
- `circularBuffer.configure(durationSec, bitRate)` thay cho set duration đơn thuần.
- **Xoá `startStatsUpdater`** (timer 1 s trên main thread); `bufferSizeBytes` giờ là thuộc tính
  đọc trực tiếp từ ring.
- Trial check 5 s → **30 s** (`TRIAL_CHECK_INTERVAL_MS`).
- **Chính sách dừng**: `ACTION_STOP` đặt latch `user_stopped`; `onStartCommand` **từ chối**
  `ACTION_START` (trừ khi có `EXTRA_FORCE_START` do người dùng chủ động) và `stopSelf()`;
  service trả `START_NOT_STICKY`; nếu không còn MediaProjection token thì **tự dừng** thay vì
  hiện notification "đang ghi đệm" sai sự thật.

### `MainActivity.kt` — poll chỉ khi hiện (điểm 4)
- Timer 1,5 s chuyển thành `statusTicker` **start ở `onResume`, stop ở `onPause`** (trước đây
  vẫn chạy sau khi vào game).

### `service/BootReceiver.kt`, `TransparentPermissionActivity.kt`
- Boot/package-replaced **tôn trọng latch `user_stopped`**.
- `TransparentPermissionActivity` nhận `--ei extra_duration_seconds` để chọn cửa sổ replay mà
  không cần thao tác UI (phục vụ đo), và gắn `EXTRA_FORCE_START` cho lượt start do người dùng chủ động.

### `shared/LicenseAccess.java` (điểm 5)
- `canUse()` **cache kết quả 30 s** (fail-closed), `clearSession()` xoá cache.
- Đánh đổi: license bị thu hồi có thể còn hiệu lực tối đa 30 s.

---

## 3. Trước / sau (số suy ra từ cấu hình — chính xác, không phải số đo)

| Đại lượng | 2.9.3 | Sau thay đổi | Δ |
|---|---|---|---|
| Độ phân giải encode | 1920×1080 | 1280×720 | pixels 2,07 M → 0,92 M |
| Bitrate video | 16 Mbps | 6 Mbps | **−62,5 %** |
| Payload encode | 2,00 MB/s | 0,75 MB/s | **−62,5 %** |
| Pixel mã hoá | 124,4 Mpx/s | 55,3 Mpx/s | **−55,6 %** |
| Băng thông mirror (60 Hz) | 995 MB/s | 719 MB/s | **−27,8 %** |
| Băng thông mirror (120 Hz) | 1 991 MB/s | 1 438 MB/s | **−27,8 %** |
| Heap buffer 30 / 60 / 120 s | 60 / 120 / 240 MB | 29 / 58 / 117 MB | **−51 %** |
| Cấp phát trong steady-state | ~321 object/s, ~2 MB/s | **0** | **−100 %** |
| Packet/s vào ring | 107 | 107 (fps giữ nguyên) | 0 |
| Wakeup của app | ~250–350/s | **~94/s** | **−65 %…−73 %** |
| Thread chạy nền của pipeline | 2 drain + 1 feeder + 1 mic | feeder + mic + callback thread | −1 |

Ghi chú về băng thông mirror: phần **đọc nguồn vẫn là 1080p** (MediaProjection mirror theo độ
phân giải thật của màn hình); chỉ phần **ghi đích** giảm theo độ phân giải encode. Vì vậy mức
giảm là −28 %, không phải −56 %.

---

## 4. Những đánh đổi có chủ ý

1. **Giữ 60 fps thay vì 30 fps như đề bài gợi ý.** Encode chậm hơn tần số quét làm producer của
   SurfaceFlinger chặn trên input queue của encoder (xem mục 1) — đúng cơ chế gây khựng cần
   loại bỏ. Ở 720p, chi phí encode 60 fps vẫn rất thấp trên 8 Gen 2. Nếu muốn tiết kiệm hơn nữa:
   `adb shell` đặt pref `frame_rate = 30` (đã đọc sẵn trong code) — nhưng hãy đo lại `gap_max`
   vì rủi ro chặn producer tăng.
2. **I-frame interval giữ 1 s.** Đề bài nói "chỉ xin keyframe khi lưu clip"; thực tế keyframe
   định kỳ là thứ cho phép clip bắt đầu sạch. Thay vào đó `requestSyncFrame()` được gọi **khi
   bắt đầu quay tay** — chỗ mà việc chờ keyframe thật sự làm mất 0–1 s đầu clip.
3. **Drop thay vì chờ.** Khi ring đầy hoặc đang export, packet mới bị bỏ. Hệ quả: ngay sau khi
   lưu clip, cửa sổ replay có thể hụt ~1 s (thời gian mux). Đây là lựa chọn có chủ ý.
4. **Cache license 30 s** (mục 2, `LicenseAccess`).
5. **`largeHeap="true"` được giữ nguyên.** Arena giờ nằm ngoài Java heap nên cờ này không còn
   cần thiết cho capture, nhưng bỏ đi không mang lại lợi ích đo được và có rủi ro ở đường thumbnail.

---

## 5. Chính sách dừng (vấn đề "app tự bật lại sau force-stop")

`am force-stop` không chạy `onDestroy`, nên nó để lại token MediaProjection treo và không phải
đường dừng sạch. Đường dừng đúng:

```powershell
adb -s c2b670a0 shell am broadcast -a com.thor.instantreplay.ACTION_STOP -p com.thor.instantreplay
```

Sau lệnh này:
- service dừng, `user_stopped = true` được ghi lại;
- `BootReceiver` **không** auto-start nữa;
- nếu bất kỳ thành phần nào (watchdog của ROM, launcher, adb) gửi lại `ACTION_START`,
  service **từ chối và tự dừng** — không có notification giả, không capture lại;
- người dùng mở app và bấm Start thì latch được xoá (`EXTRA_FORCE_START`).

Lưu ý thứ tự: broadcast chỉ tới được app khi app còn sống. Nếu đã `am force-stop` trước đó thì
package ở trạng thái "stopped" và broadcast không được giao — khi đó latch chưa được đặt, nhưng
`force-stop` đã giết tiến trình nên baseline vẫn sạch. Vì vậy script đo **luôn broadcast trước,
force-stop sau** khi cần trạng thái `off`.

---

## 6. Đo lại

Script: [`tools/measure-replay-impact.ps1`](../tools/measure-replay-impact.ps1).

```powershell
# 3 trạng thái × 60 s, cùng cảnh game, xuất JSON để so sánh
.\tools\measure-replay-impact.ps1 -All -Seconds 60 -OutFile results\after-optimised.json

# một trạng thái
.\tools\measure-replay-impact.ps1 -State 120s -Seconds 60
```

Script tự: dừng replay (baseline sạch) → start với cửa sổ 30/120 s qua
`TransparentPermissionActivity` → warm-up → `logcat -c` → đo 60 s (sample `top` mỗi 5 s) →
parse `THOR_METRICS` (`gap_max_ms`, `display_fps`, `source_fps`) → in mean / p50 / **p95** / max,
số cửa sổ ≤ 33,4 ms, CPU% và RSS peak, rồi kết luận PASS/MISS theo tiêu chí p95 ≤ 33 ms.

Thủ công (nếu cần):

```powershell
adb -s c2b670a0 logcat -d -s ThorNativeLayer:I | Select-String 'THOR_METRICS' | Select-Object -Last 10
adb -s c2b670a0 logcat -d | Select-String 'DroidDeckWayland: \[perf\]'
adb -s c2b670a0 shell top -b -n1 -o %CPU,RES,ARGS | findstr instantreplay
```

Kiểm tra RAM buffer không tăng vô hạn (log của ring):

```powershell
adb -s c2b670a0 logcat -d -s CircularMediaBuffer:I | Select-String 'Replay ring'
# ví dụ: "Replay ring ready for 120s @ 6 Mbps: arena=111 MB, slots=19200"
```

---

## 7. Cần xác nhận trên máy (chưa làm được ở đây)

Không có thiết bị trong môi trường này, nên **các điểm sau phải đo mới được coi là đạt**:

1. `gap_max_ms` p95 ≤ 33 ms khi buffer chạy (cả 30 s và 120 s), và phân bố không còn chu kỳ
   ~10 s. Đây là tiêu chí quyết định.
2. CPU app trung bình < 3 % khi đang buffer.
3. Chơi 30 phút: không crash, không bị system kill, RSS ổn định (không tăng dần).
4. Lưu clip bằng cả 3 đường (phím cứng HOME 3 s, L3+R3, nút nổi) → file MP4 mở được, hình
   bắt đầu ở keyframe, audio khớp hình, độ dài ≈ cửa sổ đã chọn.
5. Quay tay 5 phút → file hợp lệ, không rơi packet (log `dropped under back-pressure` phải ~0).

Nếu (1) vẫn MISS: nghi vấn tiếp theo theo thứ tự là **băng thông mirror ở 120 Hz** (mục 1, #3) —
thử ép game về 60 Hz để so, rồi thử `frame_rate = 30` để xem producer có bị chặn không.

---

## 8. Nút chỉnh (không cần sửa code)

| Pref (`thor_replay_prefs`) | Mặc định | Tác dụng |
|---|---|---|
| `resolution_preset` | `720p` | `720p` (6 Mbps) hoặc `1080p` (12 Mbps) |
| `frame_rate` | `60` | fps encode, kẹp 15..60 |
| `duration_sec` | `60` | cửa sổ replay 30/60/120 s |
| `record_mic`, `noise_filter` | `true` | tắt mic sẽ bỏ luôn thread mic (−47 wakeup/s) |
| `auto_boot` | `true` | auto-start sau reboot — vẫn bị chặn bởi latch `user_stopped` |

---

## 9. Kiểm chứng đã chạy được ở đây

- **Build:** `:app:assembleRelease` thành công (Gradle 9.4.1, AGP 9.2.1, JDK 21 target 17,
  compileSdk 35) → `app/build/outputs/apk/release/app-release.apk` (~13,4 MB).
- **Số học của ring:** [`tools/RingSim.java`](../tools/RingSim.java) là bản mô phỏng trung thực
  các bước `addSample / ensureRoom / evict / dropHead / export` của `CircularMediaBuffer`.
  Chạy: 60 000 lần append (liên tục quấn vòng arena, 24 lần export đọc lại **so sánh từng byte**),
  0 sai lệch, `totalBytesInBuffer` luôn khớp, không packet nào chồng lấn; kịch bản pin snapshot
  giữ nguyên dữ liệu đang đọc và drop packet mới; eviction hoạt động lại sau khi export xong;
  packet lớn hơn arena bị drop an toàn.

  ```powershell
  $j = 'C:\Program Files\Android\Android Studio\jbr\bin'
  & "$j\javac.exe" -d tools\out tools\RingSim.java; & "$j\java.exe" -cp tools\out RingSim
  ```

  Lưu ý: file này **mirror** logic, không import code thật — nếu sửa `CircularMediaBuffer`
  thì phải cập nhật tương ứng.
