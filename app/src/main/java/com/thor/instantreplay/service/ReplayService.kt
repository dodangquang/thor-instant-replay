package com.thor.instantreplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.thor.instantreplay.MainActivity
import com.thor.instantreplay.AppLanguage
import com.thorqol.shared.LicenseAccess
import com.thorqol.shared.TrialAccess
import com.thor.instantreplay.overlay.FloatingTriggerService
import com.thor.instantreplay.buffer.CircularMediaBuffer
import com.thor.instantreplay.encoder.AudioEncoder
import com.thor.instantreplay.encoder.DualScreenHelper
import com.thor.instantreplay.encoder.ScreenEncoder
import com.thor.instantreplay.util.AudioChimeHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReplayService : Service() {
    private fun t(vi: String, en: String) = AppLanguage.text(this, vi, en)

    companion object {
        private const val TAG = "ReplayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "thor_shadowplay_channel"

        const val ACTION_START = "com.thor.instantreplay.ACTION_START"
        const val ACTION_STOP = "com.thor.instantreplay.ACTION_STOP"
        const val ACTION_SAVE_CLIP = "com.thor.instantreplay.ACTION_SAVE_CLIP"
        const val ACTION_TOGGLE_MANUAL_RECORD = "com.thor.instantreplay.ACTION_TOGGLE_MANUAL_RECORD"
        const val ACTION_START_MANUAL_RECORD = "com.thor.instantreplay.ACTION_START_MANUAL_RECORD"
        const val ACTION_STOP_MANUAL_RECORD = "com.thor.instantreplay.ACTION_STOP_MANUAL_RECORD"
        const val ACTION_UPDATE_AUDIO_VOLUMES = "com.thor.instantreplay.ACTION_UPDATE_AUDIO_VOLUMES"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_DURATION_SECONDS = "extra_duration_seconds"
        const val EXTRA_DUAL_SCREEN = "extra_dual_screen"
        const val EXTRA_RECORD_MIC = "extra_record_mic"
        const val EXTRA_NOISE_FILTER = "extra_noise_filter"

        @Volatile
        var isRunning: Boolean = false
        @Volatile
        var isManualRecording: Boolean = false
        @Volatile
        var manualRecordStartTimeMs: Long = 0L
        @Volatile
        var bufferSizeBytes: Long = 0L

        var activeProjection: MediaProjection? = null
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isSaving = AtomicBoolean(false)
    private var lastSaveTimeMs = 0L
    private var isPaused = false
    @Volatile private var preserveClipsOnStop = false
    private val trialExpiryCheck = object : Runnable {
        override fun run() {
            if (!TrialAccess.enabled(this@ReplayService)) return
            if (!LicenseAccess.canUse(this@ReplayService)) { stopForAccessExpiry(); return }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    /** Expiry flushes an active recording without pruning any saved clip. */
    private fun stopForAccessExpiry() {
        preserveClipsOnStop = true
        if (isManualRecording) stopManualRecording()
        stopService(Intent(this, FloatingTriggerService::class.java))
        mainHandler.removeCallbacks(trialExpiryCheck)
        stopSelf()
    }

    private fun captureAllowed(): Boolean {
        if (LicenseAccess.canUse(this)) return true
        preserveClipsOnStop = true
        mainHandler.post { stopForAccessExpiry() }
        return false
    }

    private val circularBuffer = CircularMediaBuffer()
    private var screenEncoder: ScreenEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var mediaProjection: MediaProjection? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STOP && !LicenseAccess.canUse(this@ReplayService)) {
                stopForAccessExpiry()
                return
            }
            when (intent?.action) {
                ACTION_SAVE_CLIP -> saveCurrentReplay()
                ACTION_STOP -> stopSelf()
                ACTION_TOGGLE_MANUAL_RECORD -> toggleManualRecording()
                ACTION_START_MANUAL_RECORD -> startManualRecording()
                ACTION_STOP_MANUAL_RECORD -> stopManualRecording()
                ACTION_UPDATE_AUDIO_VOLUMES -> reloadAudioVolumes()
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
            val pauseOnScreenOff = prefs.getBoolean("pause_on_screen_off", true)
            if (!pauseOnScreenOff) return

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF detected -> Pausing ShadowPlay for Doze mode")
                    pauseShadowPlay()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "Screen ON detected -> Resuming ShadowPlay")
                    resumeShadowPlay()
                }
            }
        }
    }

    private fun pauseShadowPlay() {
        if (!isRunning) return
        isPaused = true
        screenEncoder?.pause()
        audioEncoder?.pause()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(t("Replay tạm dừng khi tắt màn hình", "Replay paused while the screen is off")))
    }

    private fun resumeShadowPlay() {
        if (!isRunning || !captureAllowed()) return
        isPaused = false
        screenEncoder?.resume()
        audioEncoder?.resume()
        val duration = circularBuffer.bufferDurationSeconds
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(t("Đang ghi đệm $duration giây", "Buffering $duration seconds")))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_SAVE_CLIP)
            addAction(ACTION_STOP)
            addAction(ACTION_TOGGLE_MANUAL_RECORD)
            addAction(ACTION_START_MANUAL_RECORD)
            addAction(ACTION_STOP_MANUAL_RECORD)
            addAction(ACTION_UPDATE_AUDIO_VOLUMES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)
        if (TrialAccess.enabled(this)) mainHandler.postDelayed(trialExpiryCheck, 5000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_STOP && !LicenseAccess.canUse(this)) {
            stopForAccessExpiry()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAVE_CLIP -> {
                saveCurrentReplay()
                return START_STICKY
            }
            ACTION_TOGGLE_MANUAL_RECORD -> {
                toggleManualRecording()
                return START_STICKY
            }
            ACTION_START_MANUAL_RECORD -> {
                startManualRecording()
                return START_STICKY
            }
            ACTION_STOP_MANUAL_RECORD -> {
                stopManualRecording()
                return START_STICKY
            }
            ACTION_UPDATE_AUDIO_VOLUMES -> {
                reloadAudioVolumes()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val durationSec = intent?.getLongExtra(EXTRA_DURATION_SECONDS, 60L) ?: 60L
                val preferDualScreen = intent?.getBooleanExtra(EXTRA_DUAL_SCREEN, true) ?: true
                val recordMic = intent?.getBooleanExtra(EXTRA_RECORD_MIC, true) ?: true
                val enableNoiseFilter = intent?.getBooleanExtra(EXTRA_NOISE_FILTER, true) ?: true

                startForeground(NOTIFICATION_ID, buildNotification(t("Đang ghi đệm $durationSec giây", "Buffering $durationSec seconds")))

                if (resultData != null && resultCode != 0) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    mediaProjection = projection
                    activeProjection = projection
                    initEncoders(projection, durationSec, preferDualScreen, recordMic, enableNoiseFilter)
                } else if (activeProjection != null) {
                    initEncoders(activeProjection!!, durationSec, preferDualScreen, recordMic, enableNoiseFilter)
                } else {
                    Log.w(TAG, "No MediaProjection token available")
                }
            }
        }

        return START_STICKY
    }

    private fun initEncoders(
        projection: MediaProjection,
        durationSec: Long,
        preferDualScreen: Boolean,
        recordMic: Boolean = true,
        enableNoiseFilter: Boolean = true
    ) {
        circularBuffer.clear()
        circularBuffer.bufferDurationSeconds = durationSec

        val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
        val resolutionPreset = prefs.getString("resolution_preset", "1080p") ?: "1080p"
        val is720p = resolutionPreset == "720p"
        val targetWidth = if (is720p) 1280 else 1920
        val targetHeight = if (is720p) 720 else 1080
        val bitRate = if (is720p) 8_000_000 else 16_000_000

        val displayConfig = DualScreenHelper.detectDisplays(this, preferDualScreen)
        val metrics = resources.displayMetrics

        screenEncoder = ScreenEncoder(
            mediaProjection = projection,
            circularBuffer = circularBuffer,
            width = targetWidth,
            height = targetHeight,
            dpi = metrics.densityDpi,
            displayConfig = displayConfig,
            frameRate = 60,
            bitRate = bitRate
        ).also { it.start() }

        val gameVol = prefs.getInt("volume_game", 85)
        val micVol = prefs.getInt("volume_mic", 150)

        audioEncoder = AudioEncoder(
            mediaProjection = projection,
            circularBuffer = circularBuffer,
            recordMic = recordMic,
            enableNoiseFilter = enableNoiseFilter,
            gameVolumePercent = gameVol,
            micVolumePercent = micVol
        ).also { it.start() }

        isRunning = true
        startStatsUpdater()
        Log.i(TAG, "NVIDIA ShadowPlay started with ${targetWidth}x${targetHeight} @ 60fps ($resolutionPreset, Mic: $recordMic, NoiseFilter: $enableNoiseFilter, GameVol: $gameVol%, MicVol: $micVol%)")
    }

    private fun startStatsUpdater() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isRunning) {
                    if (!isPaused) {
                        bufferSizeBytes = circularBuffer.totalBytesInBuffer
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        mainHandler.postDelayed(this, 10000)
                    }
                }
            }
        }, 1000)
    }

    fun reloadAudioVolumes() {
        val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
        val gameVol = prefs.getInt("volume_game", 85)
        val micVol = prefs.getInt("volume_mic", 150)
        audioEncoder?.setAudioVolumes(gameVol, micVol)
    }

    fun toggleManualRecording() {
        if (isManualRecording) {
            stopManualRecording()
        } else {
            startManualRecording()
        }
    }

    fun startManualRecording() {
        if (!captureAllowed()) return
        if (!isRunning) {
            Toast.makeText(this, t("Bật Replay trước khi quay video.", "Start Replay before recording video."), Toast.LENGTH_SHORT).show()
            return
        }
        if (isManualRecording) return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val clipsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ThorClips")
        clipsDir.mkdirs()
        val outputFile = File(clipsDir, "Record_$timestamp.mp4")

        val ok = circularBuffer.startManualRecording(outputFile)
        if (ok) {
            isManualRecording = true
            manualRecordStartTimeMs = System.currentTimeMillis()
            vibrateFeedback()
            Toast.makeText(this, t("Đã bắt đầu quay video", "Video recording started"), Toast.LENGTH_SHORT).show()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(t("Đang quay video liên tục", "Recording continuous video")))
        } else {
            Toast.makeText(this, t("Đang chờ khung hình đầu tiên", "Waiting for the first video frame"), Toast.LENGTH_SHORT).show()
        }
    }

    fun stopManualRecording() {
        if (!isManualRecording) return
        val ok = circularBuffer.stopManualRecording()
        isManualRecording = false
        manualRecordStartTimeMs = 0L

        if (ok) {
            if (!preserveClipsOnStop) enforceStorageQuota(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ThorClips"))
            vibrateFeedback()
            playChimeIfEnabled()
            Toast.makeText(this, t("Đã dừng và lưu video", "Recording stopped and video saved"), Toast.LENGTH_SHORT).show()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(t("Đang ghi đệm Replay", "Replay is buffering")))
        } else {
            Toast.makeText(this, t("Không thể lưu video", "Could not save the video"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun playChimeIfEnabled() {
        val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("sound_chime", true)) {
            AudioChimeHelper.playTingChime()
        }
    }

    fun saveCurrentReplay() {
        if (!captureAllowed()) return
        val now = System.currentTimeMillis()
        if (now - lastSaveTimeMs < 3000L) {
            Log.w(TAG, "Save ignored: cooldown active (${now - lastSaveTimeMs}ms < 3000ms)")
            return
        }
        if (!isSaving.compareAndSet(false, true)) {
            Log.w(TAG, "Save ignored: save already in progress")
            return
        }
        lastSaveTimeMs = now

        executor.execute {
            try {
                if (!captureAllowed()) return@execute
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val clipsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ThorClips")
                clipsDir.mkdirs()
                val outputFile = File(clipsDir, "Clip_$timestamp.mp4")

                val success = circularBuffer.exportToMp4(outputFile)
                if (success) {
                    if (!preserveClipsOnStop) enforceStorageQuota(clipsDir)
                    mainHandler.post {
                        vibrateFeedback()
                        playChimeIfEnabled()
                        Toast.makeText(this, t("Đã lưu clip Replay", "Replay clip saved"), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(this, t("Chưa đủ dữ liệu để lưu clip", "Not enough buffered video to save a clip"), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                mainHandler.postDelayed({ isSaving.set(false) }, 1000L)
            }
        }
    }

    private fun enforceStorageQuota(clipsDir: File) {
        try {
            val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
            val maxClips = prefs.getInt("storage_max_clips", 30) // 30 clips by default; <= 0 means unlimited
            if (maxClips <= 0) return

            val clips = clipsDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() } ?: return

            if (clips.size > maxClips) {
                val toDelete = clips.drop(maxClips)
                for (oldFile in toDelete) {
                    oldFile.delete()
                    Log.i(TAG, "Storage quota reached ($maxClips clips). Auto-deleted: ${oldFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enforcing storage quota", e)
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(120)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val saveClipIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_SAVE_CLIP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Replay")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_save, t("Lưu clip", "Save clip"), saveClipIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, t("Dừng", "Stop"), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Android Replay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = t("Ghi đệm video để lưu khoảnh khắc vừa chơi", "Buffer video to save a recent moment")
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (TrialAccess.enabled(this) && !LicenseAccess.canUse(this)) preserveClipsOnStop = true
        if (isManualRecording) {
            stopManualRecording()
        }
        isRunning = false
        screenEncoder?.stop()
        audioEncoder?.stop()
        if (preserveClipsOnStop) {
            val projection = mediaProjection ?: activeProjection
            runCatching { projection?.stop() }
            if (activeProjection === projection) activeProjection = null
        }
        circularBuffer.clear()
        executor.shutdown()
        try {
            unregisterReceiver(actionReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
        Log.i(TAG, "ShadowPlay Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
