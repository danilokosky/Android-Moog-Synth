package com.moogsynth.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

class MidiHandler(
    private val context: Context,
    private val onNoteOn: (note: Int, velocity: Int) -> Unit,
    private val onNoteOff: (note: Int) -> Unit
) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val openedDevices = mutableMapOf<MidiDevice, android.media.midi.MidiDeviceInfo>()

    fun setupMidi() {
        // Regista o listener para detetar quando dispositivos USB MIDI são conectados/desconectados
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(deviceInfo: android.media.midi.MidiDeviceInfo) {
                openMidiDevice(deviceInfo)
            }

            override fun onDeviceRemoved(deviceInfo: android.media.midi.MidiDeviceInfo) {
                // Dispositivo removido, se necessário podemos tratar aqui
            }
        }, Handler(Looper.getMainLooper()))

        // Abre os dispositivos que já estão conectados no momento do arranque
        for (deviceInfo in midiManager.devices) {
            openMidiDevice(deviceInfo)
        }
    }

    private fun openMidiDevice(deviceInfo: android.media.midi.MidiDeviceInfo) {
        midiManager.openDevice(deviceInfo, { device ->
            if (device != null) {
                openedDevices[device] = deviceInfo
                val outputPort = device.openOutputPort(0)
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
            }
        }, Handler(Looper.getMainLooper()))
    }
}
