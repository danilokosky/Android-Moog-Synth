package com.moogsynth.app.midi

import android.content.Context
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import kotlin.math.pow

class MidiHandler(context: Context, private val onNoteOn: (Float) -> Unit) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager

    fun setupMidi() {
        val devices = midiManager.devices
        if (devices.isNotEmpty()) {
            val device = devices[0]
            midiManager.openDevice(device, { openedDevice ->
                val outputPort = openedDevice.openOutputPort(0)
                outputPort?.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        if (count >= 3) {
                            val status = msg[offset].toInt() and 0xFF
                            val note = msg[offset + 1].toInt() and 0xFF
                            val velocity = msg[offset + 2].toInt() and 0xFF

                            // Deteta a mensagem Note On (Canal 1)
                            if ((status and 0xF0) == 0x90 && velocity > 0) {
                                val freq = 440.0 * 2.0.pow((note - 69.0) / 12.0)
                                onNoteOn(freq.toFloat())
                            }
                        }
                    }
                })
            }, null)
        }
    }
}
