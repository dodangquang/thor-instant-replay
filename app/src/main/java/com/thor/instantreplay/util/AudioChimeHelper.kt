package com.thor.instantreplay.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

object AudioChimeHelper {
    private const val TAG = "AudioChimeHelper"

    /**
     * Synthesizes and plays a crystal-clear console-grade "Ting" chime (Nintendo Switch style E6 pitch)
     * using AudioTrack with zero audio asset dependencies.
     */
    fun playTingChime() {
        Thread({
            try {
                val sampleRate = 44100
                val durationMs = 280
                val numSamples = (sampleRate * durationMs) / 1000
                val samples = ShortArray(numSamples)
                val decay = 13.5 // Exponential decay curve

                // Note E6 (1318.5 Hz) with octave harmonic E7 (2637 Hz) for crystalline metallic sound
                val f1 = 1318.51
                val f2 = 2637.02

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = Math.exp(-decay * t)
                    val wave = 0.72 * Math.sin(2.0 * Math.PI * f1 * t) +
                            0.28 * Math.sin(2.0 * Math.PI * f2 * t)
                    val sampleVal = (wave * envelope * 28000.0).toInt().coerceIn(-32768, 32767)
                    samples[i] = sampleVal.toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()
                Thread.sleep(300)
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play audio chime", e)
            }
        }, "AudioChimeThread").start()
    }
}
