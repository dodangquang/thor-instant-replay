package com.thor.instantreplay.encoder

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Open-Source Audio DSP Noise Filter for AYN Thor Handheld.
 *
 * Designed specifically for gaming handhelds:
 * 1. 2nd-order Butterworth High-Pass Filter (Low-cut at 90Hz) to eliminate cooling fan rumble and motor hum.
 * 2. Adaptive Soft-Knee Noise Gate to eliminate room background hiss and controller button clicking when not speaking.
 * 3. Soft-saturation limiter for clean, broadcast-quality audio mixing without clipping distortion.
 */
class OpenSourceNoiseFilter(sampleRate: Int = 48000) {

    // --- High-Pass Filter (Low-Cut at 90Hz) ---
    private var b0: Float = 1f
    private var b1: Float = 0f
    private var b2: Float = 0f
    private var a1: Float = 0f
    private var a2: Float = 0f

    private var x1: Float = 0f
    private var x2: Float = 0f
    private var y1: Float = 0f
    private var y2: Float = 0f

    // --- Dynamic Noise Gate ---
    // Amplitude threshold in 16-bit PCM: 450 is roughly -37dB
    private val gateThreshold = 450f
    private val gateFloor = 0.05f // ~ -26dB attenuation when gate is closed
    private var currentGain = 1.0f

    private var envelope = 0f
    private val attackCoeff = 0.15f   // ~8ms attack
    private val releaseCoeff = 0.003f // ~180ms release

    init {
        val cutoffFreq = 90.0
        val q = 1.0 / sqrt(2.0)
        val omega = 2.0 * Math.PI * cutoffFreq / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val cosOmega = cos(omega)

        val b0Init = (1.0 + cosOmega) / 2.0
        val b1Init = -(1.0 + cosOmega)
        val b2Init = (1.0 + cosOmega) / 2.0
        val a0Init = 1.0 + alpha
        val a1Init = -2.0 * cosOmega
        val a2Init = 1.0 - alpha

        b0 = (b0Init / a0Init).toFloat()
        b1 = (b1Init / a0Init).toFloat()
        b2 = (b2Init / a0Init).toFloat()
        a1 = (a1Init / a0Init).toFloat()
        a2 = (a2Init / a0Init).toFloat()
    }

    /**
     * In-place filter processing of 16-bit PCM mono samples.
     */
    fun process(samples: ShortArray, offset: Int, length: Int) {
        for (i in offset until (offset + length)) {
            val input = samples[i].toFloat()

            // 1. High-Pass Filter (removes console fan hum & rumble < 90Hz)
            val filtered = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = filtered

            // 2. Envelope follower
            val absVal = abs(filtered)
            if (absVal > envelope) {
                envelope += attackCoeff * (absVal - envelope)
            } else {
                envelope += releaseCoeff * (absVal - envelope)
            }

            // 3. Noise Gate Gain
            val targetGain = if (envelope >= gateThreshold) {
                1.0f
            } else {
                val ratio = (envelope / gateThreshold).coerceIn(0f, 1f)
                gateFloor + (1.0f - gateFloor) * (ratio * ratio)
            }

            if (targetGain > currentGain) {
                currentGain += attackCoeff * (targetGain - currentGain)
            } else {
                currentGain += releaseCoeff * (targetGain - currentGain)
            }

            // 4. Output sample
            val out = (filtered * currentGain).coerceIn(-32768f, 32767f)
            samples[i] = out.toInt().toShort()
        }
    }

    companion object {
        /**
         * Mixes a game audio sample and a mic audio sample with speech gain boost
         * and a soft-limiting saturation curve to avoid clipping.
         */
        fun mixAndLimit(
            gameSample: Short,
            micSample: Short,
            gameGain: Float = 0.85f,
            micGain: Float = 2.0f
        ): Short {
            val g = gameSample.toFloat() * gameGain
            val m = micSample.toFloat() * micGain
            val sum = g + m

            val limitThreshold = 28000f
            val maxVal = 32767f
            val out = if (sum > limitThreshold) {
                val excess = sum - limitThreshold
                limitThreshold + (maxVal - limitThreshold) * (1.0f - exp(-excess / (maxVal - limitThreshold)))
            } else if (sum < -limitThreshold) {
                val excess = -sum - limitThreshold
                -(limitThreshold + (maxVal - limitThreshold) * (1.0f - exp(-excess / (maxVal - limitThreshold))))
            } else {
                sum
            }

            return out.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }
}
