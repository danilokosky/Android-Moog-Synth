package com.moogsynth.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.tanh

class AudioEngine {
    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    
    private val maxVoices = 8
    private val voices = Array(maxVoices) { Voice(sampleRate.toFloat()) }
    
    var masterVolume = 0.8f
    
    // Drive simula o ganho de entrada de um circuito analógico antes do filtro VCA
    private val drive = 2.0f 

    fun start() {
        if (isPlaying) return
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        isPlaying = true

        thread(start = true) {
            // Prioridade máxima absoluta exigida para DSP em tempo real
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(bufferSize)
            
            while (isPlaying) {
                for (i in buffer.indices) {
                    var mixedSample = 0f
                    
                    for (voice in voices) {
                        if (voice.isActive()) {
                            mixedSample += voice.getNextSample()
                        }
                    }
                    
                    // Saturação Analógica (Soft Clipping via Tanh)
                    // Substitui a divisão seca, comprimindo o áudio musicalmente nas extremidades
                    val saturatedSample = tanh(mixedSample * drive * masterVolume)
                    
                    val pcmValue = (saturatedSample * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    buffer[i] = pcmValue.toInt().toShort()
                }
                audioTrack?.write(buffer, 0, bufferSize, AudioTrack.WRITE_BLOCKING)
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
        var voice = voices.find { it.currentMidiNote == note && it.isActive() }
        if (voice == null) voice = voices.find { !it.isActive() }
        if (voice == null) voice = voices[0] 
        voice.triggerOn(note, freq)
    }

    fun noteOff(note: Int) {
        voices.filter { it.currentMidiNote == note }.forEach { it.triggerOff() }
    }
}
