package com.moogsynth.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.moogsynth.app.audio.AudioEngine
import com.moogsynth.app.midi.MidiHandler
import com.moogsynth.app.ui.SynthScreen

class MainActivity : ComponentActivity() {
    private lateinit var audioEngine: AudioEngine
    private lateinit var midiHandler: MidiHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        audioEngine = AudioEngine()
        audioEngine.start()

        // Passamos os dois eventos (Pressionar e Soltar) para o Motor
        midiHandler = MidiHandler(this, 
            onNoteOn = { note, velocity ->
                audioEngine.noteOn(note, velocity)
            },
            onNoteOff = { note ->
                audioEngine.noteOff(note)
            }
        )
        midiHandler.setupMidi()

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0a0a0c)),
                contentAlignment = Alignment.Center
            ) {
                SynthScreen(audioEngine)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.stop()
    }
}
