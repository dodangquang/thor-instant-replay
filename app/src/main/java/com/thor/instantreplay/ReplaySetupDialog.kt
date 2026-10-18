package com.thor.instantreplay

import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

/** Displays/copies the requested ADB setup; never executes commands or grants permissions. */
class ReplaySetupDialog(private val host: Activity) : AppCompatDialog(host) {
    companion object {
        const val ADB_COMMANDS = "adb devices\n\n" +
            "# 1. Am thanh / Audio\n" +
            "adb shell pm grant com.thor.instantreplay android.permission.RECORD_AUDIO\n\n" +
            "# 2. Thong bao / Notifications - Android 13+\n" +
            "adb shell pm grant com.thor.instantreplay android.permission.POST_NOTIFICATIONS\n\n" +
            "# 3. Quay nen / Background capture\n" +
            "adb shell appops set com.thor.instantreplay PROJECT_MEDIA allow\n\n" +
            "# 4. Nut noi / Floating HUD\n" +
            "adb shell appops set com.thor.instantreplay SYSTEM_ALERT_WINDOW allow\n\n" +
            "# 5. Khong toi uu pin / Battery exemption\n" +
            "adb shell dumpsys deviceidle whitelist +com.thor.instantreplay\n\n" +
            "# 6. Accessibility: Replay + Thor Wayfinder\n" +
            "# THAY TOAN BO danh sach. Them service khac bang dau : neu dang dung.\n" +
            "# REPLACES the service list. Append other enabled services with : before running.\n" +
            "adb shell settings put secure enabled_accessibility_services com.thor.instantreplay/.service.ThorKeyTriggerService:com.thorwayfinder.app/.ForegroundAppService\n" +
            "adb shell settings put secure accessibility_enabled 1"
        @JvmStatic fun adbCommands(packageName: String): String {
            if (packageName == "com.thor.instantreplay") return ADB_COMMANDS
            require(packageName == "com.thor.instantreplay.trial")
            return ADB_COMMANDS.replace("com.thor.instantreplay", packageName)
                .replace("$packageName/.service.ThorKeyTriggerService", "$packageName/com.thor.instantreplay.service.ThorKeyTriggerService")
        }
        fun projectionAllowed(context: Context): Boolean = try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow("android:project_media", Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow("android:project_media", Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) { false }
    }

    private val commands = adbCommands(host.packageName)
    private val handler = Handler(Looper.getMainLooper())
    private val scroll: ScrollView
    private val commandsScroll: ScrollView
    private var activeScroll: ScrollView
    private val close: MaterialButton
    private var armed: View? = null
    private var stickDirection = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (stickDirection == 0 || !isShowing) return
            move(stickDirection)
            handler.postDelayed(this, 150L)
        }
    }

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_setup)
        scroll = findViewById<ScrollView>(R.id.setupScroll)!!
        commandsScroll = findViewById<ScrollView>(R.id.setupCommandsScroll)!!
        activeScroll = scroll
        close = findViewById<MaterialButton>(R.id.btnSetupClose)!!
        close.setIconResource(R.drawable.ic_replay_back)
        findViewById<TextView>(R.id.setupCommand)!!.text = commands
        findViewById<MaterialButton>(R.id.btnSetupCopy)!!.setIconResource(R.drawable.ic_replay_copy)
        findViewById<MaterialButton>(R.id.btnSetupAccessibility)!!.setIconResource(R.drawable.ic_replay_controller)
        findViewById<MaterialButton>(R.id.btnSetupOverlay)!!.setIconResource(R.drawable.ic_replay_setup)
        close.setOnClickListener { dismiss() }
        findViewById<MaterialButton>(R.id.btnSetupCopy)!!.setOnClickListener {
            (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Android Replay ADB", commands))
            Toast.makeText(host, R.string.setup_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnSetupAccessibility)!!.setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnSetupOverlay)!!.setOnClickListener {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${host.packageName}")))
        }
        scroll.setOnFocusChangeListener { _, focused -> if (focused) activeScroll = scroll }
        commandsScroll.setOnFocusChangeListener { _, focused -> if (focused) activeScroll = commandsScroll }
        for (id in intArrayOf(R.id.btnSetupCopy, R.id.btnSetupAccessibility, R.id.btnSetupOverlay)) {
            findViewById<MaterialButton>(id)!!.setOnFocusChangeListener { _, focused -> if (focused) activeScroll = commandsScroll }
        }
        refreshStatus()
    }

    fun refreshStatus() {
        findViewById<TextView>(R.id.setupPermissionStatus)?.apply {
            val ready = projectionAllowed(host)
            setText(if (ready) R.string.setup_permission_ready else R.string.setup_permission_needed)
            setTextColor(host.getColor(if (ready) R.color.nvidia_green else R.color.text_primary))
        }
    }

    private fun openSettings(intent: Intent) {
        try { host.startActivity(intent) }
        catch (_: RuntimeException) { Toast.makeText(host, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show() }
    }

    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((host.resources.displayMetrics.widthPixels * .94f).toInt(),
                (host.resources.displayMetrics.heightPixels * .92f).toInt())
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        close.requestFocusFromTouch()
    }

    private fun page(direction: Int) {
        activeScroll.smoothScrollBy(0, (activeScroll.height * .7f * direction).toInt())
    }

    private fun move(direction: Int) {
        val focus = currentFocus
        val pane = if (focus === scroll) scroll else if (focus === commandsScroll) commandsScroll else null
        val vertical = direction == View.FOCUS_DOWN || direction == View.FOCUS_UP
        val sign = if (direction == View.FOCUS_DOWN) 1 else -1
        if (pane != null && vertical && pane.canScrollVertically(sign)) {
            pane.smoothScrollBy(0, (64 * sign * host.resources.displayMetrics.density).toInt())
            return
        }
        val next = focus?.focusSearch(direction)
        if (next == null || next.rootView !== window?.decorView?.rootView || !next.isShown || !next.isEnabled || !next.requestFocusFromTouch()) {
            close.requestFocusFromTouch()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) armed = currentFocus
                if (event.action == KeyEvent.ACTION_UP) {
                    val target = armed
                    armed = null
                    if (!event.isCanceled && target === currentFocus && target?.isEnabled == true && target.isShown) target.performClick()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismiss()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) page(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (currentFocus === scroll || currentFocus === commandsScroll) {
                if (event.action == KeyEvent.ACTION_DOWN) move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val direction = ReplayStickDirection.resolve(x, y, stickDirection)
        if (direction == 0) { stickDirection = 0; handler.removeCallbacks(repeat) }
        else if (direction != stickDirection) {
            handler.removeCallbacks(repeat)
            stickDirection = direction
            move(direction)
            handler.postDelayed(repeat, 330L)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { armed = null; stickDirection = 0; handler.removeCallbacks(repeat) }
    }

    override fun onStop() {
        armed = null
        stickDirection = 0
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }
}
