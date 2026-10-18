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
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.chip.Chip
import com.thorqol.shared.LicenseAccess
import com.thorqol.shared.TrialAccess
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

    private var selectedTab = 0
    private var setupDialog: ReplaySetupDialog? = null
    private var statusPulse: ValueAnimator? = null
    private var uiResumed = false
    private var confirmArmed = false
    private var confirmTarget: View? = null
    private var stickDirection = 0
    private val stickRepeat = object : Runnable {
        override fun run() {
            if (stickDirection == 0 || isFinishing || isDestroyed) return
            moveStickFocus(stickDirection)
            handler.postDelayed(this, 150L)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private fun t(vi: String, en: String): String = AppLanguage.text(this, vi, en)
    private fun durationSeconds(): Long = try { prefs.getLong("duration_sec", 60L) }
        catch (_: ClassCastException) { prefs.getInt("duration_sec", 60).toLong() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setupUI()
        setupListeners()
        setupNavigation()
        selectTab(savedInstanceState?.getInt("tab", 0) ?: 0, false)
        if (ValueAnimator.areAnimatorsEnabled()) {
            binding.heroPanel.alpha = 0f
            binding.heroPanel.translationX = 12f
            binding.heroPanel.animate().alpha(1f).translationX(0f).setDuration(240).start()
            binding.contentScroll.alpha = 0f
            binding.contentScroll.translationY = -10f
            binding.contentScroll.animate().alpha(1f).translationY(0f).setDuration(200).setStartDelay(35).start()
        }
        binding.btnToggleService.requestFocusFromTouch()
        startPeriodicStatusCheck()
    }

    override fun onResume() {
        super.onResume()
        uiResumed = true
        if (!LicenseAccess.guard(this)) return
        checkAdbPermissionStatus()
        refreshClipsList()
        updateServiceUIState()
        setupDialog?.refreshStatus()
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
        binding.tvGameVolumeLabel.text = t("Âm lượng game: ${gameVol}%", "Game volume: ${gameVol}%")

        val micVol = prefs.getInt("volume_mic", 150)
        binding.sliderMicVolume.value = micVol.toFloat().coerceIn(50f, 300f)
        binding.tvMicVolumeLabel.text = t("Âm lượng micro: ${micVol}%", "Microphone volume: ${micVol}%")
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
            binding.tvGameVolumeLabel.text = t("Âm lượng game: ${v}%", "Game volume: ${v}%")
            if (fromUser) {
                prefs.edit().putInt("volume_game", v).apply()
                sendBroadcast(Intent(ReplayService.ACTION_UPDATE_AUDIO_VOLUMES).setPackage(packageName))
            }
        }

        binding.sliderMicVolume.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.tvMicVolumeLabel.text = t("Âm lượng micro: ${v}%", "Microphone volume: ${v}%")
            if (fromUser) {
                prefs.edit().putInt("volume_mic", v).apply()
                sendBroadcast(Intent(ReplayService.ACTION_UPDATE_AUDIO_VOLUMES).setPackage(packageName))
            }
        }

        binding.btnManualRecord.setOnClickListener {
            if (!ReplayService.isRunning) {
                Toast.makeText(this, t("Bật Replay trước khi quay video.", "Start Replay before recording a video."), Toast.LENGTH_SHORT).show()
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
        if (!LicenseAccess.guard(this)) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1003)
            Toast.makeText(this, t("Cho phép ghi âm để thu âm thanh game.", "Allow audio recording to capture game sound."), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, t("Cho phép hiển thị nút nổi CLIP.", "Allow the floating CLIP button to appear over other apps."), Toast.LENGTH_LONG).show()
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
            binding.tvAdbStatus.text = getString(R.string.adb_status_ok)
            binding.tvAdbStatus.setTextColor(getColor(R.color.nvidia_green))
        } else {
            binding.tvAdbStatus.text = getString(R.string.adb_status_need)
            binding.tvAdbStatus.setTextColor(getColor(R.color.accent_red))
        }
    }

    private fun updateServiceUIState() {
        if (TrialAccess.enabled(this)) {
            binding.tvTrialStatus.visibility = View.VISIBLE
            binding.tvTrialStatus.text = TrialAccess.statusText(this)
            binding.btnAccount.text = t("Dùng thử", "Trial")
        }
        if (ReplayService.isRunning) {
            val isManual = ReplayService.isManualRecording
            binding.btnManualRecord.isEnabled = true

            if (isManual) {
                binding.tvStatus.text = t("ĐANG QUAY VIDEO", "RECORDING VIDEO")
                binding.tvStatus.setTextColor(getColor(R.color.accent_red))
                binding.btnManualRecord.text = getString(R.string.btn_manual_record_stop)
                binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_red))
                binding.btnManualRecord.setTextColor(getColor(R.color.white))
            } else {
                binding.tvStatus.text = getString(R.string.status_running)
                binding.tvStatus.setTextColor(getColor(R.color.nvidia_green))
                binding.btnManualRecord.text = getString(R.string.btn_manual_record_start)
                binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
                binding.btnManualRecord.setTextColor(getColor(R.color.accent_red))
            }

            binding.btnToggleService.text = getString(R.string.btn_stop)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_red))
            binding.btnToggleService.setTextColor(getColor(R.color.white))
            binding.btnSaveClip.isEnabled = true
            binding.btnSaveClip.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnSaveClip.setTextColor(getColor(R.color.nvidia_green))

            val mb = ReplayService.bufferSizeBytes / (1024 * 1024)
            val duration = durationSeconds()
            binding.tvBufferStats.text = t("RAM · $mb MB / $duration giây", "RAM · $mb MB / $duration seconds")
        } else {
            binding.tvStatus.text = getString(R.string.status_stopped)
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnToggleService.text = getString(R.string.btn_start)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_green))
            binding.btnToggleService.setTextColor(getColor(R.color.nvidia_black))
            binding.btnSaveClip.isEnabled = false
            binding.btnSaveClip.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnSaveClip.setTextColor(getColor(R.color.text_muted))
            binding.btnManualRecord.isEnabled = false
            binding.btnManualRecord.text = getString(R.string.btn_manual_record_start)
            binding.btnManualRecord.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nvidia_card_inner))
            binding.btnManualRecord.setTextColor(getColor(R.color.text_muted))
            binding.tvBufferStats.text = t("RAM · 0 MB / Đang chờ", "RAM · 0 MB / Standby")
        }
        val running = ReplayService.isRunning
        val manual = ReplayService.isManualRecording
        binding.btnToggleService.setIconResource(if (running) R.drawable.ic_replay_stop else R.drawable.ic_replay_play)
        binding.btnManualRecord.setIconResource(if (manual) R.drawable.ic_replay_stop else R.drawable.ic_replay_record)
        styleButton(binding.btnToggleService, getColor(if (running) R.color.accent_red else R.color.nvidia_green), getColor(R.color.nvidia_black))
        styleButton(binding.btnSaveClip, getColor(R.color.nvidia_card_inner), getColor(R.color.nvidia_green))
        styleButton(binding.btnManualRecord, getColor(if (manual) R.color.accent_red else R.color.nvidia_card_inner), getColor(if (manual) R.color.nvidia_black else R.color.accent_red))
        binding.statusDot.imageTintList = ColorStateList.valueOf(getColor(if (manual) R.color.accent_red else if (running) R.color.nvidia_green else R.color.text_muted))
        updateStatusPulse()
    }

    private fun styleButton(button: MaterialButton, background: Int, text: Int) {
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf())
        button.backgroundTintList = ColorStateList(states, intArrayOf(getColor(R.color.nvidia_card_inner), getColor(R.color.nvidia_green), background))
        val foreground = ColorStateList(states, intArrayOf(getColor(R.color.text_muted), getColor(R.color.nvidia_black), text))
        button.setTextColor(foreground)
        button.iconTint = foreground
    }

    private fun updateStatusPulse() {
        val animate = uiResumed && hasWindowFocus() && ReplayService.isRunning && ValueAnimator.areAnimatorsEnabled()
        if (animate) {
            if (statusPulse?.isRunning == true) return
            statusPulse = ValueAnimator.ofFloat(.45f, 1f).apply {
                duration = 1100L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { binding.statusDot.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            statusPulse?.cancel(); statusPulse = null; binding.statusDot.alpha = 1f
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


        val files = clipsDir.listFiles { file -> file.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.library_empty)
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
                setPadding((12*density).toInt(), (10*density).toInt(), (12*density).toInt(), (10*density).toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                tag = "clip"
                background = ReplayCutDrawable(density, interactive = true)
                minimumHeight = (68 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin=(8*density).toInt() }
                setOnClickListener { openVideo(file) }
                contentDescription = file.name
                installFocusMotion(this)
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

            val playIcon = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setImageResource(R.drawable.ic_replay_play)
                imageTintList = ColorStateList.valueOf(getColor(R.color.nvidia_green))
                minimumWidth = (24*density).toInt(); minimumHeight = (24*density).toInt()
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
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val metaTv = TextView(this).apply {
                tag = "clip_meta"
                text = "${sizeMb} MB · MP4"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(metaTv)
            clipCard.addView(thumbContainer)
            clipCard.addView(infoLayout)

            binding.layoutClipsList.addView(clipCard)
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
            Toast.makeText(this, t("Chưa có ứng dụng phát video.", "No video player is installed."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWifiShareDialog() {
        val ip = ThorWebServer.getLocalIpAddress()
        if (ip.isNullOrEmpty()) {
            Toast.makeText(
                this,
                t("Kết nối Wi-Fi để chia sẻ clip.", "Connect to Wi-Fi to share clips."),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (webServer == null) {
            webServer = ThorWebServer(8080, AppLanguage.get(this) == "en")
        }
        val started = webServer?.start() ?: false
        if (!started) {
            Toast.makeText(this, t("Không thể chia sẻ: cổng 8080 đang được dùng.", "Cannot share: port 8080 is already in use."), Toast.LENGTH_SHORT).show()
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
        binding.heroPanel.animate().cancel()
        binding.contentScroll.animate().cancel()
        statusPulse?.cancel()
        statusPulse = null
        setupDialog?.dismiss()
        setupDialog = null
        handler.removeCallbacksAndMessages(null)
        webServer?.stop()
        webServer = null
        thumbnailExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun setupNavigation() {
        val tabs = listOf(binding.tabCapture, binding.tabAudio, binding.tabClips, binding.tabControls)
        tabs.forEachIndexed { index, button -> button.setOnClickListener { selectTab(index) } }
        binding.btnAccount.setOnClickListener { LicenseAccess.open(this) }
        binding.tvTrialStatus.setOnClickListener { LicenseAccess.open(this) }
        binding.btnSetup.setOnClickListener { showSetupGuide() }
        binding.btnLanguage.setOnClickListener {
            val current = if (AppLanguage.get(this) == "en") 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(arrayOf("Tiếng Việt", "English"), current) { dialog, which ->
                    AppLanguage.set(this, if (which == 1) "en" else "vi")
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton(t("Đóng", "Close"), null).show()
        }
        fun visit(view: View) {
            if (view.isFocusable && (view !is ViewGroup || view.isClickable)) installFocusMotion(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(binding.root)
    }

    private fun installFocusMotion(view: View) {
        view.setOnFocusChangeListener { target, focused ->
            if (target is MaterialButton) {
                target.strokeWidth = ((if (focused) 3f else 1.5f) * resources.displayMetrics.density).toInt()
                target.strokeColor = if (focused) ColorStateList.valueOf(getColor(R.color.accent_red))
                    else getColorStateList(R.color.replay_focus_stroke)
            }
            if (target is Chip) {
                target.chipStrokeColor = if (focused) ColorStateList.valueOf(getColor(R.color.accent_red))
                    else getColorStateList(R.color.replay_focus_stroke)
            }
            if (target.tag == "clip" && target is ViewGroup) {
                fun recolor(view: View) {
                    if (view is TextView) view.setTextColor(getColor(if (focused) R.color.nvidia_black else if (view.tag == "clip_meta") R.color.text_secondary else R.color.text_primary))
                    if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i))
                }
                recolor(target)
            }
            // Draw focus inside the control. Growing edge-aligned controls clips
            // their outlines in tab rows, ChipGroups and scrolling clip lists.
            target.animate().cancel()
            val scale = if (focused) .985f else 1f
            if (ValueAnimator.areAnimatorsEnabled()) target.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            else { target.scaleX = scale; target.scaleY = scale }
        }
    }

    private fun showSetupGuide() {
        if (setupDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = ReplaySetupDialog(this)
        setupDialog = dialog
        dialog.setOnDismissListener {
            setupDialog = null
            if (!isFinishing && !isDestroyed) binding.btnSetup.requestFocusFromTouch()
        }
        dialog.show()
        // UI-only state: never write into thor_replay_prefs or grant a permission.
        getSharedPreferences("android_replay_ui", MODE_PRIVATE).edit()
            .putBoolean("setup_guide_seen_v1", true).apply()
    }

    private fun selectTab(index: Int, animate: Boolean = true) {
        selectedTab = index.coerceIn(0, 3)
        val sections = listOf(binding.sectionCapture, binding.sectionAudio, binding.sectionClips, binding.sectionControls)
        val tabs = listOf(binding.tabCapture, binding.tabAudio, binding.tabClips, binding.tabControls)
        sections.forEachIndexed { i, view -> view.visibility = if (i == selectedTab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, button ->
            styleButton(button, getColor(if (i == selectedTab) R.color.nvidia_green else R.color.nvidia_card_inner), getColor(if (i == selectedTab) R.color.nvidia_black else R.color.text_primary))
        }
        binding.contentScroll.scrollTo(0, 0)
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            sections.forEach { it.animate().cancel(); it.alpha = 1f; it.translationX = 0f }
            sections[selectedTab].alpha = 0f
            sections[selectedTab].translationX = 14f
            sections[selectedTab].animate().alpha(1f).translationX(0f).setDuration(170).start()
        }
        if (selectedTab == 2) refreshClipsList()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (currentFocus == null) binding.btnToggleService.requestFocusFromTouch()
                    confirmArmed = true
                    confirmTarget = currentFocus
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    val target = confirmTarget
                    val click = confirmArmed && !event.isCanceled && target === currentFocus
                    confirmArmed = false
                    confirmTarget = null
                    if (click) target?.takeIf { it.isEnabled && it.isShown }?.performClick()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    selectTab((selectedTab + if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else 3) % 4)
                    listOf(binding.tabCapture, binding.tabAudio, binding.tabClips, binding.tabControls)[selectedTab].requestFocusFromTouch()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (selectedTab != 0) selectTab(0) else finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return super.dispatchGenericMotionEvent(event)
        }
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val direction = ReplayStickDirection.resolve(x, y, stickDirection)
        if (direction == 0) {
            stickDirection = 0
            handler.removeCallbacks(stickRepeat)
            return true
        }
        if (direction != stickDirection) {
            handler.removeCallbacks(stickRepeat)
            stickDirection = direction
            moveStickFocus(direction)
            handler.postDelayed(stickRepeat, 330L)
        }
        return true
    }

    private fun moveStickFocus(direction: Int) {
            val focus = currentFocus
            // Sliders consume horizontal input instead of jumping to another control.
            if (focus is Slider && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
                focus.value = (focus.value + (if (direction == View.FOCUS_RIGHT) 1 else -1) * focus.stepSize)
                    .coerceIn(focus.valueFrom, focus.valueTo)
                val key = if (focus === binding.sliderGameVolume) "volume_game" else "volume_mic"
                prefs.edit().putInt(key, focus.value.toInt()).apply()
                sendBroadcast(Intent(ReplayService.ACTION_UPDATE_AUDIO_VOLUMES).setPackage(packageName))
            } else focus?.focusSearch(direction)?.requestFocusFromTouch() ?: binding.btnToggleService.requestFocusFromTouch()
    }

    override fun onPause() {
        uiResumed = false
        updateStatusPulse()
        confirmArmed = false
        confirmTarget = null
        stickDirection = 0
        handler.removeCallbacks(stickRepeat)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateStatusPulse()
        if (hasFocus && !getSharedPreferences("android_replay_ui", MODE_PRIVATE)
                .getBoolean("setup_guide_seen_v1", false)) {
            binding.root.post { if (hasWindowFocus()) showSetupGuide() }
        }
        if (!hasFocus) {
            confirmArmed = false
            confirmTarget = null
            stickDirection = 0
            handler.removeCallbacks(stickRepeat)
        }
    }
}

