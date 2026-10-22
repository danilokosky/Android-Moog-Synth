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

    external fun setOscVolume(index: Int, volume: Float)
    external fun setOscTune(index: Int, semitones: Float)
    external fun setOscWave(index: Int, waveType: Int)

    external fun setMasterVolume(volume: Float)
    external fun setFilterCutoff(cutoffHz: Float)
    external fun setFilterResonance(resonance: Float)
    external fun setFilterEnvAmount(envAmountHz: Float)

    external fun setLfoRate(rateHz: Float)
    external fun setLfoDepth(depthHz: Float)
    external fun setDelayParams(timeSec: Float, feedback: Float, mix: Float)

    external fun setAmpADSR(a: Float, d: Float, s: Float, r: Float)
    external fun setFilterADSR(a: Float, d: Float, s: Float, r: Float)

    external fun getWaveform(outArray: FloatArray)
}
