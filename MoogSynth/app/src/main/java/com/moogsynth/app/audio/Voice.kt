package com.moogsynth.app.audio

class Voice(sampleRate: Float) {
    val oscillator = Oscillator(sampleRate)
    val envelope = Envelope(sampleRate)
    var currentMidiNote: Int = -1

    fun triggerOn(note: Int, frequency: Float) {
        currentMidiNote = note
        oscillator.frequency = frequency
        envelope.triggerOn()
    }

    fun triggerOff() {
        envelope.triggerOff()
    }

    fun getNextSample(): Float {
        if (envelope.isIdle()) return 0f
        return oscillator.getNextSample() * envelope.getNextAmplitude()
    }
    
    fun isActive() = !envelope.isIdle()
}
