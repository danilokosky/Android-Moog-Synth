package com.moogsynth.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

class MidiHandler(
    context: Context,
    private val onNoteOn: (note: Int, velocity: Int) -> Unit,
    private val onNoteOff: (note: Int) -> Unit,
    private val onAllNotesOff: () -> Unit = {}
) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val openedDevices = mutableMapOf<MidiDeviceInfo, MidiDevice>()

    fun setupMidi() {
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(deviceInfo: MidiDeviceInfo) {
                openMidiDevice(deviceInfo)
            }

            override fun onDeviceRemoved(deviceInfo: MidiDeviceInfo) {
                openedDevices.remove(deviceInfo)?.close()
                onAllNotesOff() // Evita notas presas se o cabo MIDI for desconectado
            }
        }, Handler(Looper.getMainLooper()))

        for (deviceInfo in midiManager.devices) {
            openMidiDevice(deviceInfo)
        }
    }

    private fun openMidiDevice(deviceInfo: MidiDeviceInfo) {
        midiManager.openDevice(deviceInfo, { device ->
            if (device != null) {
                openedDevices[deviceInfo] = device
                val portCount = deviceInfo.outputPortCount
                for (i in 0 until portCount) {
                    val outputPort = device.openOutputPort(i)
                    outputPort?.connect(MidiStreamParser())
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    // Parser de fluxo MIDI completo: suporta múltiplos eventos por pacote USB e Running Status
    private inner class MidiStreamParser : MidiReceiver() {
        private var runningStatus = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count

            while (i < end) {
                val byte = msg[i].toInt() and 0xFF

                // Mensagens de Sistema em Tempo Real (Clock, Active Sensing, etc. >= 0xF8)
                // Ignoramos sem alterar o Running Status
                if (byte >= 0xF8) {
                    i++
                    continue
                }

                val status: Int
                if (byte and 0x80 != 0) {
                    // É um byte de Status novo
                    status = byte
                    if (byte < 0xF0) {
                        runningStatus = status
                    } else {
                        runningStatus = 0 // Mensagens System Common limpam o Running Status
                    }
                    i++
                } else {
                    // Running Status: o byte atual já é o primeiro byte de dados!
                    status = runningStatus
                    if (status == 0) {
                        i++ // Byte órfão, avança
                        continue
                    }
                }

                val command = status and 0xF0

                when (command) {
                    0x90 -> { // Note On
                        if (i + 1 < end) {
                            val note = msg[i].toInt() and 0x7F
                            val vel = msg[i + 1].toInt() and 0x7F
                            if (vel > 0) onNoteOn(note, vel) else onNoteOff(note)
                            i += 2
                        } else break
                    }
                    0x80 -> { // Note Off
                        if (i + 1 < end) {
                            val note = msg[i].toInt() and 0x7F
                            onNoteOff(note)
                            i += 2
                        } else break
                    }
                    0xB0 -> { // Control Change (CC)
                        if (i + 1 < end) {
                            val cc = msg[i].toInt() and 0x7F
                            // CC 120 (All Sound Off) ou CC 123 (All Notes Off)
                            if (cc == 120 || cc == 123) {
                                onAllNotesOff()
                            }
                            i += 2
                        } else break
                    }
                    0xA0, 0xE0 -> { // Poly Aftertouch, Pitch Bend (2 bytes de dados)
                        i += 2
                    }
                    0xC0, 0xD0 -> { // Program Change, Channel Aftertouch (1 byte de dado)
                        i += 1
                    }
                    else -> {
                        // Outras mensagens (ex: SysEx 0xF0), avança
                        i++
                    }
                }
            }
        }
    }
}
