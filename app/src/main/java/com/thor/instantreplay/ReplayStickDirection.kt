package com.thor.instantreplay

import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Separate engage/release thresholds keep analog noise from bypassing the hold delay. */
object ReplayStickDirection {
    fun resolve(x: Float, y: Float, previous: Int): Int {
        val magnitude = max(abs(x), abs(y))
        if (magnitude < .35f) return 0
        if (magnitude < .55f) return previous
        // Retain the chosen axis near a diagonal; tiny x/y noise must not
        // restart the initial delay by alternating RIGHT/DOWN every packet.
        val horizontal = when (previous) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> abs(y) <= abs(x) + .12f
            View.FOCUS_UP, View.FOCUS_DOWN -> abs(x) > abs(y) + .12f
            else -> abs(x) > abs(y)
        }
        return if (horizontal) {
            if (x > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT
        } else if (y > 0) View.FOCUS_DOWN else View.FOCUS_UP
    }
}
