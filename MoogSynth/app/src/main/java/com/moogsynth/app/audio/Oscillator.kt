package com.moogsynth.app.audio

import kotlin.math.*

class Oscillator(private val sampleRate: Float) {
    var frequency = 440f
    var amplitude = 0.5f
    var waveType = 0 // 0: Sawtooth, 1: Square, 2: Triangle, 3: Sine
    private var phase = 0f

    fun getNextSample(): Float {
        val phaseIncrement = frequency / sampleRate
        phase = (phase + phaseIncrement) % 1f
        val t = phase
        
        val sample = when (waveType) {
            0 -> 2f * (t - 0.5f) // Sawtooth
            1 -> if (t < 0.5f) 1f else -1f // Square
            2 -> 2f * abs(2f * (t - 0.5f)) - 1f // Triangle
            3 -> sin(2.0 * Math.PI * t).toFloat() // Sine
            else -> 0f
        }
        return sample * amplitude
    }
}
