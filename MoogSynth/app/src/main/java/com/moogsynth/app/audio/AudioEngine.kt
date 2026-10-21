package com.moogsynth.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

class AudioEngine {
    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    
    val osc1 = Oscillator(sampleRate.toFloat())

    fun start() {
        if (isPlaying) return
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            .build()

        audioTrack?.play()
        isPlaying = true

        thread {
            val buffer = ShortArray(bufferSize)
            while (isPlaying) {
                for (i in buffer.indices) {
                    val sample = osc1.getNextSample()
                    // Converter de Float (-1.0 a 1.0) para PCM 16-bit
                    buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
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
    
    fun playNote(freq: Float) {
        osc1.frequency = freq
    }
}

