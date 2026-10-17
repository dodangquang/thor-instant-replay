package com.thor.instantreplay.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class ThorKeyTriggerService : AccessibilityService() {

    companion object {
        private const val TAG = "ThorKeyTrigger"
        private const val HOLD_DURATION_MS = 3000L // 3 seconds
        private const val KEYCODE_HOME_GPIO = 102
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isHolding = false
    private var holdTriggered = false

    // L3 + R3 Instant Combo State
    private var isL3Down = false
    private var isR3Down = false
    private var comboTriggered = false

    private val saveClipRunnable = Runnable {
        if (isHolding && !holdTriggered) {
            holdTriggered = true
            Log.i(TAG, "HOME held for 3 seconds! Triggering ShadowPlay clip save once...")
            triggerSaveClip(isInstantCombo = false)
        }
    }

    private fun triggerSaveClip(isInstantCombo: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                if (isInstantCombo) {
                    // Crisp short double click vibration for instant combo
                    vibratorManager.defaultVibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 70, 50, 120), -1)
                    )
                } else {
                    vibratorManager.defaultVibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 200), -1)
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(200)
            }
        } catch (_: Exception) {}

        val intent = Intent(ReplayService.ACTION_SAVE_CLIP).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)

        // 1. Check L3 + R3 Instant Trigger
        val l3R3Enabled = prefs.getBoolean("instant_l3_r3", true)
        val keyCode = event.keyCode
        val isL3 = keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
        val isR3 = keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR

        if (l3R3Enabled && (isL3 || isR3)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (isL3) isL3Down = true
                    if (isR3) isR3Down = true

                    if (isL3Down && isR3Down && !comboTriggered) {
                        comboTriggered = true
                        Log.i(TAG, "L3 + R3 Instant Combo detected! Saving clip immediately (0s delay)...")
                        triggerSaveClip(isInstantCombo = true)
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (isL3) isL3Down = false
                    if (isR3) isR3Down = false
                    if (!isL3Down && !isR3Down) {
                        comboTriggered = false
                    }
                }
            }
        }

        // 2. Check HOME Hold Trigger (3s)
        val homeHoldEnabled = prefs.getBoolean("home_hold", true)
        val isHome = keyCode == KeyEvent.KEYCODE_HOME || event.scanCode == KEYCODE_HOME_GPIO

        if (homeHoldEnabled && isHome) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0 && !isHolding && !holdTriggered) {
                        isHolding = true
                        handler.postDelayed(saveClipRunnable, HOLD_DURATION_MS)
                        Log.d(TAG, "HOME pressed down — timer 3000ms started")
                    }
                    if (holdTriggered) return true
                }
                KeyEvent.ACTION_UP -> {
                    isHolding = false
                    handler.removeCallbacks(saveClipRunnable)
                    val wasTriggered = holdTriggered
                    holdTriggered = false
                    if (wasTriggered) return true
                }
            }
        }

        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        handler.removeCallbacks(saveClipRunnable)
        isHolding = false
        holdTriggered = false
        isL3Down = false
        isR3Down = false
        comboTriggered = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveClipRunnable)
        super.onDestroy()
    }
}
