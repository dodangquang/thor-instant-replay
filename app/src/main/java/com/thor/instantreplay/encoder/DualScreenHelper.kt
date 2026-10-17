package com.thor.instantreplay.encoder

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display

data class DisplayConfig(
    val isDualScreen: Boolean,
    val targetWidth: Int,
    val targetHeight: Int,
    val topDisplayId: Int,
    val bottomDisplayId: Int?,
    val topWidth: Int = 1920,
    val topHeight: Int = 1080,
    val bottomWidth: Int = 0,
    val bottomHeight: Int = 0
)

object DualScreenHelper {
    private const val TAG = "DualScreenHelper"

    fun detectDisplays(context: Context, preferDualScreen: Boolean = false): DisplayConfig {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        val defaultDisplay = displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: displays[0]

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay.getRealMetrics(metrics)

        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels

        // Standard Landscape Full HD for AYN Thor (1920 x 1080 @ 60 FPS)
        val finalWidth = if (rawWidth >= rawHeight && rawWidth > 0) rawWidth else 1920
        val finalHeight = if (rawWidth >= rawHeight && rawHeight > 0) rawHeight else 1080

        Log.i(TAG, "AYN Thor Screen detected: raw=${rawWidth}x${rawHeight}, output=${finalWidth}x${finalHeight} (1080p 16:9 60FPS)")

        return DisplayConfig(
            isDualScreen = false,
            targetWidth = finalWidth,
            targetHeight = finalHeight,
            topDisplayId = defaultDisplay.displayId,
            bottomDisplayId = null,
            topWidth = finalWidth,
            topHeight = finalHeight,
            bottomWidth = 0,
            bottomHeight = 0
        )
    }
}
