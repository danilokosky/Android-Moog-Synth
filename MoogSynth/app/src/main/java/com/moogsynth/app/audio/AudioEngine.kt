package com.moogsynth.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.pow

class AudioEngine {
    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    
    // Polifonia de 8 Vozes
    private val maxVoices = 8
    private val voices = Array(maxVoices) { Voice(sampleRate.toFloat()) }
    
    // Mantemos a referência ao oscilador da voz 1 apenas para o Knob da interface não quebrar
    val osc1 = voices[0].oscillator 

    fun start() {
        if (isPlaying) return
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true

        thread(priority = Thread.MAX_PRIORITY) {
            val buffer = ShortArray(bufferSize)
            while (isPlaying) {
                for (i in buffer.indices) {
                    var mixedSample = 0f
                    // Soma todas as vozes ativas
                    for (voice in voices) {
                        mixedSample += voice.getNextSample()
                    }
                    
                    // Atenuação para evitar distorção (clipping) ao tocar acordes
                    mixedSample *= 0.15f 
                    
                    // Converte para PCM 16-bit com proteção de limites
                    val pcmValue = (mixedSample * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    buffer[i] = pcmValue.toInt().toShort()
                }
                audioTrack?.write(buffer, 0, bufferSize)
            }
        }
    }

    fun stop() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
    }
    
    fun noteOn(note: Int, velocity: Int) {
        val freq = (440.0 * 2.0.pow((note - 69.0) / 12.0)).toFloat()
        
        // Tenta encontrar a voz exata caso a tecla seja pressionada rapidamente duas vezes
        var voice = voices.find { it.currentMidiNote == note && it.isActive() }
        
        // Se não encontrar, pega uma voz livre
        if (voice == null) {
            voice = voices.find { !it.isActive() }
        }
        
        // Se todas estiverem ocupadas (Roubo de Voz / Note Stealing), pega a primeira
        if (voice == null) {
            voice = voices[0] 
        }

        voice.triggerOn(note, freq)
    }

    fun noteOff(note: Int) {
        // Desliga todas as vozes que estão a tocar esta nota
        voices.filter { it.currentMidiNote == note }.forEach { it.triggerOff() }
    }
}
