package com.moogsynth.app.audio

class NativeAudioEngine {

    init {
        System.loadLibrary("native-lib")
    }

    external fun startEngine()
    external fun stopEngine()
    external fun noteOn(note: Int, velocity: Int)
    external fun noteOff(note: Int)
    external fun allNotesOff()

    external fun setMasterVolume(volume: Float)
    external fun setFilterCutoff(cutoffHz: Float)
    external fun setFilterResonance(resonance: Float)
    external fun setFilterEnvAmount(envAmountHz: Float)
    external fun setFilterADSR(a: Float, d: Float, s: Float, r: Float)
}
