package com.moogsynth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moogsynth.app.audio.AudioEngine
import com.moogsynth.app.ui.components.Knob

@Composable
fun SynthScreen(audioEngine: AudioEngine) {
    var volume by remember { mutableStateOf(0.5f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("WEBANALOG NATIVE", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(40.dp))
        
        Text("OSC 1 LEVEL", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Knob(
            value = volume,
            onValueChange = { 
                volume = it
                audioEngine.osc1.amplitude = it
            }
        )
    }
}
