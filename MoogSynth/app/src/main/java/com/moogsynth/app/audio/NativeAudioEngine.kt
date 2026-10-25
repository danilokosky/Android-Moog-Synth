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

    // Controles do Motor Analógico Orgânico & Performance Oboe
    external fun setOversampling(factor: Int)
    external fun setAnalogDrift(drift: Float)
    external fun setFilterDrive(drive: Float)
    external fun setFilterKeyTrack(track: Float)
    external fun setStereoSpread(spread: Float)
    external fun setVelocitySensitivity(sens: Float)
    external fun setMaxPolyphony(maxVoices: Int)
    external fun setBufferBurstMultiplier(mult: Int)
    external fun setParameterSmoothing(enabled: Boolean)
    external fun getEngineStats(outStats: FloatArray)

    // ==========================================================================
    // Controles de Simulação Física de Componentes (Transistores, RC, PSU & Motor)
    // ==========================================================================
    external fun setTransistorTemperatureVt(vtVolts: Float)
    external fun setTransistorMismatch(mismatch: Float)
    external fun setCapacitorLeakage(leakage: Float)
    external fun setComparatorThreshold(threshold: Float)
    external fun setEnvelopeSupplyVoltage(railVolts: Float)
    external fun setPowerSupplySag(sag: Float)
    external fun setTapeMotorInertia(inertia: Float)
    external fun setTapeMotorFriction(friction: Float)
    external fun setTapeMagneticBias(bias: Float)
}
