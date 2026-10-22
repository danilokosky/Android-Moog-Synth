package com.moogsynth.app.audio

import kotlin.math.abs
import kotlin.math.sin

class Oscillator(private val sampleRate: Float) {
    var frequency = 440f
    var amplitude = 0.5f
    var waveType = 0 // 0: Sawtooth, 1: Square, 2: Triangle, 3: Sine
    private var phase = 0f

    // Algoritmo PolyBLEP (Polynomial Band-Limited Step) para suavizar as bordas da onda
    private fun polyBlep(t: Float, dt: Float): Float {
        var t1 = t
        if (t1 < dt) {
            t1 /= dt
            return t1 + t1 - t1 * t1 - 1f
        } else if (t1 > 1f - dt) {
            t1 = (t1 - 1f) / dt
            return t1 * t1 + t1 + t1 + 1f
        }
        return 0f
    }

    fun getNextSample(): Float {
        val dt = frequency / sampleRate
        phase += dt
        if (phase >= 1f) phase -= 1f

        var sample = 0f
        when (waveType) {
            0 -> {
                // Sawtooth com PolyBLEP
                sample = (2f * phase) - 1f
                sample -= polyBlep(phase, dt)
            }
            1 -> {
                // Square com PolyBLEP
                sample = if (phase < 0.5f) 1f else -1f
                sample += polyBlep(phase, dt)
                sample -= polyBlep((phase + 0.5f) % 1f, dt)
            }
            2 -> {
                // Triangle (naturalmente menos propensa a aliasing severo)
                sample = 2f * abs(2f * phase - 1f) - 1f
            }
            3 -> {
                // Sine
                sample = sin(2.0 * Math.PI * phase).toFloat()
            }
        }
        return sample * amplitude
    }
}
