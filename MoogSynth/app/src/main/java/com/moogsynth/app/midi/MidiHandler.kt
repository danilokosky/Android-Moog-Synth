package com.moogsynth.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

class MidiHandler(
    context: Context,
    private val onNoteOn: (note: Int, velocity: Int) -> Unit,
    private val onNoteOff: (note: Int) -> Unit,
    private val onControlChange: (cc: Int, value: Int) -> Unit = { _, _ -> },
    private val onAllNotesOff: () -> Unit = {}
) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val openedDevices = mutableMapOf<MidiDeviceInfo, MidiDevice>()
    // CRÍTICO: Guarda as referências das portas para que o Garbage Collector do Android NÃO as feche!
    private val openedPorts = mutableListOf<MidiOutputPort>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setupMidi() {
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(deviceInfo: MidiDeviceInfo) {
                openMidiDevice(deviceInfo)
            }

            override fun onDeviceRemoved(deviceInfo: MidiDeviceInfo) {
                openedDevices.remove(deviceInfo)?.close()
                openedPorts.forEach { try { it.close() } catch(e: Exception) {} }
                openedPorts.clear()
                onAllNotesOff()
            }
        }, mainHandler)

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
                    if (outputPort != null) {
                        openedPorts.add(outputPort)
                        outputPort.connect(MidiStreamParser())
                    }
                }
            }
        }, mainHandler)
    }

    private inner class MidiStreamParser : MidiReceiver() {
        private var runningStatus = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count

            while (i < end) {
                val byte = msg[i].toInt() and 0xFF

                if (byte >= 0xF8) {
                    i++
                    continue
                }

                val status: Int
                if (byte and 0x80 != 0) {
                    status = byte
                    runningStatus = if (byte < 0xF0) status else 0
                    i++
                } else {
                    status = runningStatus
                    if (status == 0) {
                        i++
                        continue
                    }
                }

                val command = status and 0xF0

                when (command) {
                    0x90 -> {
                        if (i + 1 < end) {
                            val note = msg[i].toInt() and 0x7F
                            val vel = msg[i + 1].toInt() and 0x7F
                            if (vel > 0) onNoteOn(note, vel) else onNoteOff(note)
                            i += 2
                        } else break
                    }
                    0x80 -> {
                        if (i + 1 < end) {
                            val note = msg[i].toInt() and 0x7F
                            onNoteOff(note)
                            i += 2
                        } else break
                    }
                    0xB0 -> { // Control Change (Knobs e Faders)
                        if (i + 1 < end) {
                            val cc = msg[i].toInt() and 0x7F
                            val value = msg[i + 1].toInt() and 0x7F
                            if (cc == 120 || cc == 123) {
                                onAllNotesOff()
                            } else {
                                mainHandler.post { onControlChange(cc, value) }
                            }
                            i += 2
                        } else break
                    }
                    0xA0, 0xE0 -> i += 2
                    0xC0, 0xD0 -> i += 1
                    else -> i++
                }
            }
        }
    }
}
