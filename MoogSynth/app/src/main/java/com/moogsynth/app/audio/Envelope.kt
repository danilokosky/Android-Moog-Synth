package com.moogsynth.app.audio

class Envelope(private val sampleRate: Float) {
    var attackTime = 0.05f   // 50ms (Rápido)
    var decayTime = 0.3f     // 300ms
    var sustainLevel = 0.6f  // 60% do volume
    var releaseTime = 0.4f   // 400ms (Cauda do som ao soltar a tecla)

    private var state = State.IDLE
    private var currentLevel = 0f
    private var timeInState = 0f

    enum class State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    fun triggerOn() {
        state = State.ATTACK
        timeInState = 0f
    }

    fun triggerOff() {
        if (state != State.IDLE) {
            state = State.RELEASE
            timeInState = 0f
        }
    }

    fun getNextAmplitude(): Float {
        val dt = 1f / sampleRate
        timeInState += dt

        when (state) {
            State.IDLE -> {
                currentLevel = 0f
            }
            State.ATTACK -> {
                currentLevel = timeInState / attackTime
                if (currentLevel >= 1f) {
                    currentLevel = 1f
                    state = State.DECAY
                    timeInState = 0f
                }
            }
            State.DECAY -> {
                currentLevel = 1f - (1f - sustainLevel) * (timeInState / decayTime)
                if (timeInState >= decayTime) {
                    currentLevel = sustainLevel
                    state = State.SUSTAIN
                }
            }
            State.SUSTAIN -> {
                currentLevel = sustainLevel
            }
            State.RELEASE -> {
                // Diminui do nível atual até 0
                val startLevel = if (currentLevel > sustainLevel) currentLevel else sustainLevel
                currentLevel = startLevel * (1f - (timeInState / releaseTime))
                if (currentLevel <= 0f) {
                    currentLevel = 0f
                    state = State.IDLE
                }
            }
        }
        return currentLevel
    }

    fun isIdle() = state == State.IDLE
}
