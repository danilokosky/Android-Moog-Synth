package com.moogsynth.app.audio

class NativeAudioEngine {

    // Carrega a biblioteca C++ que vamos compilar
    init {
        System.loadLibrary("native-lib")
    }

    // Estas funções com a palavra 'external' dizem ao Android que 
    // a execução real delas está escrita no ficheiro C++
    external fun startEngine()
    external fun stopEngine()
    external fun noteOn(note: Int, velocity: Int)
    external fun noteOff(note: Int)
    
    // Controles de Volume
    external fun setMasterVolume(volume: Float)
    external fun setOscVolume(oscIndex: Int, volume: Float)
}
