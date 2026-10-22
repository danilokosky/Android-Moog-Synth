package com.moogsynth.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moogsynth.app.audio.NativeAudioEngine
import com.moogsynth.app.ui.components.Knob
import kotlin.math.exp
import kotlin.math.ln

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine) {
    // Valores normalizados (0.0 a 1.0) para os Knobs
    var cutoffNorm by remember { mutableStateOf(0.65f) }
    var resNorm by remember { mutableStateOf(0.35f) }
    var envAmtNorm by remember { mutableStateOf(0.5f) }
    var masterNorm by remember { mutableStateOf(0.85f) }

    // Conversão Logarítmica para o Cutoff (20Hz a 12000Hz)
    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 12000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "WEBANALOG POLY-D (C++ OBOE + MOOG LADDER VCF)",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))

        // Módulo do Filtro VCF estilo chassi Moog
        Column(
            modifier = Modifier
                .background(Color(0xFF1E2024), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF33363E), RoundedCornerShape(8.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("FILTRO MOOG 24dB (VCF)", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                // Knob Corte (Cutoff)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(
                        value = cutoffNorm,
                        onValueChange = {
                            cutoffNorm = it
                            audioEngine.setFilterCutoff(normToCutoffHz(it))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("CORTE", color = Color.LightGray, fontSize = 12.sp)
                    Text("${normToCutoffHz(cutoffNorm).toInt()} Hz", color = Color(0xFFF59E0B), fontSize = 11.sp)
                }

                // Knob Ressonância
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(
                        value = resNorm,
                        onValueChange = {
                            resNorm = it
                            audioEngine.setFilterResonance(it)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("RESSON.", color = Color.LightGray, fontSize = 12.sp)
                    Text("${(resNorm * 100).toInt()}%", color = Color(0xFFF59E0B), fontSize = 11.sp)
                }

                // Knob Envelope Amount
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(
                        value = envAmtNorm,
                        onValueChange = {
                            envAmtNorm = it
                            audioEngine.setFilterEnvAmount(it * 5000f)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ENV AMT", color = Color.LightGray, fontSize = 12.sp)
                    Text("${(envAmtNorm * 5000).toInt()} Hz", color = Color(0xFFF59E0B), fontSize = 11.sp)
                }

                // Knob Master Volume
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(
                        value = masterNorm,
                        onValueChange = {
                            masterNorm = it
                            audioEngine.setMasterVolume(it)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("MASTER", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${(masterNorm * 100).toInt()}%", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
    }
}
