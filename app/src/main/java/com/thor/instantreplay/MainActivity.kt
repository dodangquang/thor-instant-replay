package com.thor.instantreplay

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.thor.instantreplay.databinding.ActivityMainBinding
import com.thor.instantreplay.databinding.DialogWifiShareBinding
import com.thor.instantreplay.overlay.FloatingTriggerService
import com.thor.instantreplay.service.ReplayService
import com.thor.instantreplay.share.QrCodeHelper
import com.thor.instantreplay.share.ThorWebServer
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private var webServer: ThorWebServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)

        setupUI()
        setupListeners()
        startPeriodicStatusCheck()
    }

    override fun onResume() {
        super.onResume()
        checkAdbPermissionStatus()
        refreshClipsList()
        updateServiceUIState()
    }

    private fun setupUI() {
        // Load settings
        val durationSec = try {
            prefs.getLong("duration_sec", 60L)
        } catch (_: Exception) {
            try {
                prefs.getInt("duration_sec", 60).toLong()
            } catch (_: Exception) {
                60L
            }
        }
        when (durationSec) {
            30L -> binding.chip30s.isChecked = true
            120L -> binding.chip120s.isChecked = true
            else -> binding.chip60s.isChecked = true
        }

        // Resolution preset
        val resolution = prefs.getString("resolution_preset", "1080p") ?: "1080p"
        if (resolution == "720p") {
            binding.chip720p.isChecked = true
        } else {
            binding.chip1080p.isChecked = true
        }

        // Storage Quota
        val quota = prefs.getInt("storage_max_clips", 30)
        when (quota) {
            50 -> binding.chipQuota50.isChecked = true
            0 -> binding.chipQuotaUnlimited.isChecked = true
            else -> binding.chipQuota30.isChecked = true
        }

        binding.swDualScreen.isChecked = prefs.getBoolean("dual_screen", true)
        binding.swHomeHold.isChecked = prefs.getBoolean("home_hold", true)
        binding.swInstantL3R3.isChecked = prefs.getBoolean("instant_l3_r3", true)
        binding.swSoundChime.isChecked = prefs.getBoolean("sound_chime", true)
        binding.swFloatingButton.isChecked = prefs.getBoolean("floating_btn", true)
        binding.swAutoBoot.isChecked = prefs.getBoolean("auto_boot", true)
        binding.swRecordMic.isChecked = prefs.getBoolean("record_mic", true)
        binding.swNoiseFilter.isChecked = prefs.getBoolean("noise_filter", true)
        binding.swNoiseFilter.isEnabled = binding.swRecordMic.isChecked
        binding.swPauseScreenOff.isChecked = prefs.getBoolean("pause_on_screen_off", true)

        val gameVol = prefs.getInt("volume_game", 85)
        binding.sliderGameVolume.value = gameVol.toFloat().coerceIn(0f, 150f)
        binding.tvGameVolumeLabel.text = "Âm lượng Game: ${gameVol}%"

        val micVol = prefs.getInt("volume_mic", 150)
        binding.sliderMicVolume.value = micVol.toFloat().coerceIn(50f, 300f)
        binding.tvMicVolumeLabel.text = "Âm lượng Micro (Voice Boost): ${micVol}%"
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            if (ReplayService.isRunning) {
                stopReplayService()
            } else {
                startReplayService()
            }
        }

        binding.btnSaveClip.setOnClickListener {
            val intent = Intent(ReplayService.ACTION_SAVE_CLIP).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        binding.chipGroupDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            val duration = when {
                checkedIds.contains(R.id.chip30s) -> 30L
                checkedIds.contains(R.id.chip120s) -> 120L
                else -> 60L
            }
            prefs.edit().putLong("duration_sec", duration).apply()
        }

        binding.swDualScreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dual_screen", isChecked).apply()
        }

        binding.swHomeHold.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("home_hold", isChecked).apply()
        }

        binding.swRecordMic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("record_mic", isChecked).apply()
            binding.swNoiseFilter.isEnabled = isChecked
        }

        binding.swNoiseFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("noise_filter", isChecked).apply()
        }

        binding.swPauseScreenOff.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pause_on_screen_off", isChecked).apply()
        }

        binding.swAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_boot", isChecked).apply()
        }

        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            val res = if (checkedIds.contains(R.id.chip720p)) "720p" else "1080p"
            prefs.edit().putString("resolution_preset", res).apply()
        }

        binding.chipGroupQuota.setOnCheckedStateChangeListener { _, checkedIds ->
            val quota = when {
                checkedIds.contains(R.id.chipQuota50) -> 50
                checkedIds.contains(R.id.chipQuotaUnlimited) -> 0
                else -> 30
            }
            prefs.edit().putInt("storage_max_clips", quota).apply()
        }

        binding.swInstantL3R3.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("instant_l3_r3", isChecked).apply()
        }

        binding.btnWifiShare.setOnClickListener {
            showWifiShareDialog()
        }

        binding.swFloatingButton.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("floating_btn", isChecked).apply()
            if (isChecked) {
                checkAndStartFloatingOverlay()
            } else {
                stopService(Intent(this, FloatingTriggerService::class.java))
            }
        }

        binding.swSoundChime.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound_chime", isChecked).apply()
        }

        binding.sliderGameVolume.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.tvGameVolumeLabel.text = "Âm lượng Game: ${v}%"
            if (fromUser) {
                prefs.edit().putInt("volume_game", v).apply()
                sendBroadcast(Intent(ReplayService.ACTION_UPDATE_AUDIO_VOLUMES).setPackage(packageName))
            }
        }

        binding.sliderMicVolume.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.tvMicVolumeLabel.text = "Âm lượng Micro (Voice Boost): ${v}%"
            if (fromUser) {
                prefs.edit().putInt("volume_mic", v).apply()
                sendBroadcast(Intent(ReplayService.ACTION_UPDATE_AUDIO_VOLUMES).setPackage(packageName))
            }
        }

        binding.btnManualRecord.setOnClickListener {
            if (!ReplayService.isRunning) {
                Toast.makeText(this, "Vui lòng bật dịch vụ ShadowPlay trước!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBroadcast(Intent(ReplayService.ACTION_TOGGLE_MANUAL_RECORD).setPackage(packageName))
            handler.postDelayed({
                updateServiceUIState()
                refreshClipsList()
            }, 300)
        }
    }

    private fun startReplayService() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1003)
            Toast.makeText(this, "Vui lòng cho phép quyền Ghi âm để thu âm thanh game!", Toast.LENGTH_SHORT).show()
            return
        }

        if (binding.swFloatingButton.isChecked) {
            checkAndStartFloatingOverlay()
        }
        val intent = Intent(this, TransparentPermissionActivity::class.java)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1003 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startReplayService()
        }
    }

    private fun stopReplayService() {
        val intent = Intent(this, ReplayService::class.java).apply {
            action = ReplayService.ACTION_STOP
        }
        startService(intent)
        stopService(Intent(this, FloatingTriggerService::class.java))
        updateServiceUIState()
    }

    private fun checkAndStartFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Vui lòng cho phép quyền hiển thị nổi (Draw Overlays)", Toast.LENGTH_LONG).show()
        } else {
            startService(Intent(this, FloatingTriggerService::class.java))
        }
    }

    private fun checkAdbPermissionStatus() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow("android:project_media", Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow("android:project_media", Process.myUid(), packageName)
        }

        if (mode == AppOpsManager.MODE_ALLOWED) {
            binding.tvAdbStatus.text = "✓ Quyền PROJECT_MEDIA: ĐÃ KÍCH HOẠT VĨNH VIỄN"
            binding.tvAdbStatus.setTextColor(getColor(R.color.nvidia_green))
        } else {
            binding.tvAdbStatus.text = "⚠ Cần chạy 1 lần lệnh ADB trên máy tính"
            binding.tvAdbStatus.setTextColor(getColor(R.color.accent_red))
        }
    }

    private fun updateServiceUIState() {
        if (ReplayService.isRunning) {
            val isManual = ReplayService.isManualRecording
            binding.btnManualRecord.isEnabled = true

            if (isManual) {
                binding.tvStatus.text = "🔴 ĐANG QUAY VIDEO LIÊN TỤC (MANUAL)"
                binding.tvStatus.setTextColor(getColor(R.color.accent_red))
                binding.btnManualRecord.text = "⏹ DỪNG QUAY THỦ CÔNG"
                binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_red))
                binding.btnManualRecord.setTextColor(getColor(R.color.white))
            } else {
                binding.tvStatus.text = "● SHADOWPLAY: ĐANG GHI ĐỆM RAM"
                binding.tvStatus.setTextColor(getColor(R.color.nvidia_green))
                binding.btnManualRecord.text = "🔴 QUAY DÀI THỦ CÔNG (START/STOP)"
                binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
                binding.btnManualRecord.setTextColor(getColor(R.color.accent_red))
            }

            binding.btnToggleService.text = "TẮT SHADOWPLAY"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_red))
            binding.btnToggleService.setTextColor(getColor(R.color.white))
            binding.btnSaveClip.isEnabled = true
            binding.btnSaveClip.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnSaveClip.setTextColor(getColor(R.color.nvidia_green))

            val mb = ReplayService.bufferSizeBytes / (1024 * 1024)
            val duration = prefs.getLong("duration_sec", 60L)
            binding.tvBufferStats.text = "RAM Buffer: $mb MB • Rolling $duration giây • Zero CPU overhead"
        } else {
            binding.tvStatus.text = "○ SHADOWPLAY: ĐANG TẮT"
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnToggleService.text = "BẬT SHADOWPLAY"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_green))
            binding.btnToggleService.setTextColor(getColor(R.color.nvidia_black))
            binding.btnSaveClip.isEnabled = false
            binding.btnSaveClip.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnSaveClip.setTextColor(getColor(R.color.text_muted))
            binding.btnManualRecord.isEnabled = false
            binding.btnManualRecord.text = "🔴 QUAY THỦ CÔNG"
            binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnManualRecord.setTextColor(getColor(R.color.text_muted))
            binding.tvBufferStats.text = "RAM Buffer: 0 MB • Chế độ chờ"
        }
    }

    private fun startPeriodicStatusCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateServiceUIState()
                handler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun refreshClipsList() {
        binding.layoutClipsList.removeAllViews()
        val clipsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ThorClips")
        if (!clipsDir.exists()) return

        val files = clipsDir.listFiles { file -> file.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (files.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "(Chưa có bản lưu nào. Bấm LƯU CLIP REPLAY để tạo)"
                setTextColor(getColor(R.color.text_muted))
                textSize = 12f
                setPadding(8, 12, 8, 12)
            }
            binding.layoutClipsList.addView(emptyTv)
            return
        }

        val density = resources.displayMetrics.density
        val thumbWidth = (76 * density).toInt()
        val thumbHeight = (42 * density).toInt()

        for (file in files.take(8)) {
            val sizeMb = file.length() / (1024 * 1024)
            val clipCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(getColor(R.color.nvidia_card_bg))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener {
                    openVideo(file)
                }
            }

            val thumbContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbWidth, thumbHeight).apply {
                    marginEnd = (12 * density).toInt()
                }
                setBackgroundColor(getColor(R.color.nvidia_black))
                clipToOutline = true
            }

            val thumbnailIv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val playIcon = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER
                }
                text = "▶"
                setTextColor(getColor(R.color.nvidia_green))
                textSize = 11f
            }

            thumbContainer.addView(thumbnailIv)
            thumbContainer.addView(playIcon)

            thumbnailExecutor.execute {
                val bitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(152, 84), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    }
                } catch (_: Exception) {
                    null
                }
                if (bitmap != null) {
                    handler.post {
                        thumbnailIv.setImageBitmap(bitmap)
                        playIcon.visibility = View.GONE
                    }
                }
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = file.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val metaTv = TextView(this).apply {
                text = "${sizeMb} MB • MP4 60FPS"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(metaTv)
            clipCard.addView(thumbContainer)
            clipCard.addView(infoLayout)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 2, 0, 2)
                }
                setBackgroundColor(getColor(R.color.nvidia_black))
            }
            binding.layoutClipsList.addView(clipCard)
            binding.layoutClipsList.addView(divider)
        }
    }

    private fun openVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Không có ứng dụng phát video nào", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWifiShareDialog() {
        val ip = ThorWebServer.getLocalIpAddress()
        if (ip.isNullOrEmpty()) {
            Toast.makeText(
                this,
                "Không tìm thấy IP mạng Wi-Fi cục bộ. Vui lòng kết nối Wi-Fi trên máy AYN Thor!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (webServer == null) {
            webServer = ThorWebServer(8080)
        }
        val started = webServer?.start() ?: false
        if (!started) {
            Toast.makeText(this, "Không thể mở Web Server (Cổng 8080 đang bận)", Toast.LENGTH_SHORT).show()
            return
        }

        val serverUrl = "http://$ip:8080"
        val qrBitmap = QrCodeHelper.generateQrBitmap(serverUrl, 512)

        val dialogBinding = DialogWifiShareBinding.inflate(layoutInflater)
        if (qrBitmap != null) {
            dialogBinding.ivQrCode.setImageBitmap(qrBitmap)
        }
        dialogBinding.tvServerUrl.text = serverUrl

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnCloseShare.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            webServer?.stop()
            webServer = null
        }

        dialog.show()
    }

    override fun onDestroy() {
        webServer?.stop()
        webServer = null
        thumbnailExecutor.shutdown()
        super.onDestroy()
    }
}

