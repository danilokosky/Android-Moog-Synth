package com.moogsynth.app.midi

import android.content.Context
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver

class MidiHandler(
    context: Context, 
    private val onNoteOn: (note: Int, velocity: Int) -> Unit,
    private val onNoteOff: (note: Int) -> Unit
) {
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
                            val command = status and 0xF0
                            val note = msg[offset + 1].toInt() and 0xFF
                            val velocity = msg[offset + 2].toInt() and 0xFF

                            // 0x90 = Note On, 0x80 = Note Off
                            if (command == 0x90 && velocity > 0) {
                                onNoteOn(note, velocity)
                            } else if (command == 0x80 || (command == 0x90 && velocity == 0)) {
                                onNoteOff(note)
                            }
                        }
                    }
                })
            }, null)
        }
    }
}
