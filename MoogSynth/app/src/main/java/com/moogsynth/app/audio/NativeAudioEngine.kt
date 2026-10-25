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


    external fun setSynthType(type: Int) // 0 = Moog, 1 = Juno
    
    // Controles Roland Juno-6
    external fun setJunoRange(range: Int)
    external fun setJunoPulseVol(vol: Float)
    external fun setJunoSawVol(vol: Float)
    external fun setJunoSubVol(vol: Float)
    external fun setJunoNoiseVol(vol: Float)
    external fun setJunoPwm(pwm: Float)
    external fun setJunoHpf(cutoffHz: Float)
    external fun setJunoVcfCutoff(cutoffHz: Float)
    external fun setJunoVcfRes(res: Float)
    external fun setJunoEnvAmt(envAmount: Float)
    external fun setJunoChorus(mode: Int) // 0=Off, 1=I, 2=II, 3=I+II

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

