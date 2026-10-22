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

    // Osciladores (index: 0, 1 ou 2)
    external fun setOscVolume(index: Int, volume: Float)
    external fun setOscTune(index: Int, semitones: Float)
    external fun setOscWave(index: Int, waveType: Int)

    // Master e Filtro Moog Ladder
    external fun setMasterVolume(volume: Float)
    external fun setFilterCutoff(cutoffHz: Float)
    external fun setFilterResonance(resonance: Float)
    external fun setFilterEnvAmount(envAmountHz: Float)

    // Envelopes ADSR
    external fun setAmpADSR(a: Float, d: Float, s: Float, r: Float)
    external fun setFilterADSR(a: Float, d: Float, s: Float, r: Float)
}
