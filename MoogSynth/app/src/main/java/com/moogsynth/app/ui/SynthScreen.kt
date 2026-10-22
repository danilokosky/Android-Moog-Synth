package com.moogsynth.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToInt

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine) {
    // Estados dos 3 Osciladores
    var osc1Vol by remember { mutableStateOf(0.75f) }
    var osc1Tune by remember { mutableStateOf(0.5f) } // 0.5 = 0 semitons (-24 a +24)
    var osc1Wave by remember { mutableStateOf(0) }

    var osc2Vol by remember { mutableStateOf(0.55f) }
    var osc2Tune by remember { mutableStateOf(0.25f) } // -12 semitons
    var osc2Wave by remember { mutableStateOf(1) }

    var osc3Vol by remember { mutableStateOf(0.45f) }
    var osc3Tune by remember { mutableStateOf(0.645f) } // +7 semitons
    var osc3Wave by remember { mutableStateOf(2) }

    // Estados do Filtro VCF
    var cutoffNorm by remember { mutableStateOf(0.65f) }
    var resNorm by remember { mutableStateOf(0.35f) }
    var envAmtNorm by remember { mutableStateOf(0.5f) }
    var fAttack by remember { mutableStateOf(0.05f) }
    var fDecay by remember { mutableStateOf(0.25f) }
    var fSustain by remember { mutableStateOf(0.2f) }
    var fRelease by remember { mutableStateOf(0.3f) }

    // Estados do Amplificador VCA & Master
    var aAttack by remember { mutableStateOf(0.02f) }
    var aDecay by remember { mutableStateOf(0.3f) }
    var aSustain by remember { mutableStateOf(0.7f) }
    var aRelease by remember { mutableStateOf(0.3f) }
    var masterNorm by remember { mutableStateOf(0.85f) }

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 12000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }

    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra Superior com Título e Botão PANIC
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MOOG POLY-D NATIVE (C++ OBOE ENGINE)",
                color = Color(0xFFF59E0B),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .background(Color(0xFF991B1B), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp))
                    .clickable { audioEngine.allNotesOff() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("PANIC (ALL NOTES OFF)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Painel Horizontal de Módulos do Sintetizador
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- MÓDULO 1: BANCO DE OSCILADORES ---
            ModuleBox(title = "OSCILADORES (POLYBLEP)") {
                OscillatorRow(
                    label = "OSC 1",
                    vol = osc1Vol,
                    tuneNorm = osc1Tune,
                    wave = osc1Wave,
                    onVolChange = { osc1Vol = it; audioEngine.setOscVolume(0, it) },
                    onTuneChange = { osc1Tune = it; audioEngine.setOscTune(0, normToSemitones(it)) },
                    onWaveChange = { osc1Wave = it; audioEngine.setOscWave(0, it) },
                    semitones = normToSemitones(osc1Tune).toInt()
                )
                Spacer(modifier = Modifier.height(14.dp))
                OscillatorRow(
                    label = "OSC 2",
                    vol = osc2Vol,
                    tuneNorm = osc2Tune,
                    wave = osc2Wave,
                    onVolChange = { osc2Vol = it; audioEngine.setOscVolume(1, it) },
                    onTuneChange = { osc2Tune = it; audioEngine.setOscTune(1, normToSemitones(it)) },
                    onWaveChange = { osc2Wave = it; audioEngine.setOscWave(1, it) },
                    semitones = normToSemitones(osc2Tune).toInt()
                )
                Spacer(modifier = Modifier.height(14.dp))
                OscillatorRow(
                    label = "OSC 3",
                    vol = osc3Vol,
                    tuneNorm = osc3Tune,
                    wave = osc3Wave,
                    onVolChange = { osc3Vol = it; audioEngine.setOscVolume(2, it) },
                    onTuneChange = { osc3Tune = it; audioEngine.setOscTune(2, normToSemitones(it)) },
                    onWaveChange = { osc3Wave = it; audioEngine.setOscWave(2, it) },
                    semitones = normToSemitones(osc3Tune).toInt()
                )
            }

            // --- MÓDULO 2: FILTRO MOOG LADDER 24dB ---
            ModuleBox(title = "FILTRO MOOG 24dB (VCF)") {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    KnobControl("CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm) {
                        cutoffNorm = it
                        audioEngine.setFilterCutoff(normToCutoffHz(it))
                    }
                    KnobControl("RESSON.", "${(resNorm * 100).toInt()}%", resNorm) {
                        resNorm = it
                        audioEngine.setFilterResonance(it)
                    }
                    KnobControl("ENV AMT", "${(envAmtNorm * 5000).toInt()} Hz", envAmtNorm) {
                        envAmtNorm = it
                        audioEngine.setFilterEnvAmount(it * 5000f)
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("ENVELOPE DO FILTRO (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KnobControl("ATK", "${(fAttack * 2000).toInt()}ms", fAttack) {
                        fAttack = it; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f)
                    }
                    KnobControl("DEC", "${(fDecay * 2000).toInt()}ms", fDecay) {
                        fDecay = it; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f)
                    }
                    KnobControl("SUS", "${(fSustain * 100).toInt()}%", fSustain) {
                        fSustain = it; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f)
                    }
                    KnobControl("REL", "${(fRelease * 3000).toInt()}ms", fRelease) {
                        fRelease = it; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f)
                    }
                }
            }

            // --- MÓDULO 3: AMPLIFICADOR (VCA) & MASTER ---
            ModuleBox(title = "AMPLIFICADOR (VCA) & SAÍDA") {
                Text("ENVELOPE DE VOLUME (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KnobControl("ATK", "${(aAttack * 2000).toInt()}ms", aAttack) {
                        aAttack = it; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                    }
                    KnobControl("DEC", "${(aDecay * 2000).toInt()}ms", aDecay) {
                        aDecay = it; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                    }
                    KnobControl("SUS", "${(aSustain * 100).toInt()}%", aSustain) {
                        aSustain = it; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                    }
                    KnobControl("REL", "${(aRelease * 3000).toInt()}ms", aRelease) {
                        aRelease = it; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
                KnobControl("VOLUME MASTER", "${(masterNorm * 100).toInt()}%", masterNorm) {
                    masterNorm = it
                    audioEngine.setMasterVolume(it)
                }
            }
        }
    }
}

@Composable
fun ModuleBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .background(Color(0xFF181B20), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF2D323B), RoundedCornerShape(10.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

@Composable
fun KnobControl(label: String, valueText: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(value = value, onValueChange = onValueChange)
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(valueText, color = Color(0xFFF59E0B), fontSize = 10.sp)
    }
}

@Composable
fun OscillatorRow(
    label: String,
    vol: Float,
    tuneNorm: Float,
    wave: Int,
    onVolChange: (Float) -> Unit,
    onTuneChange: (Float) -> Unit,
    onWaveChange: (Int) -> Unit,
    semitones: Int
) {
    val waveNames = listOf("SAW", "SQR", "TRI", "SIN")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .background(Color(0xFF111317), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))

        KnobControl("LEVEL", "${(vol * 100).toInt()}%", vol, onVolChange)
        KnobControl("TUNE", "${if (semitones > 0) "+$semitones" else semitones} st", tuneNorm, onTuneChange)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ONDA", color = Color.Gray, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                waveNames.forEachIndexed { idx, name ->
                    val selected = (wave == idx)
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) Color(0xFFF59E0B) else Color(0xFF262930),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onWaveChange(idx) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = name,
                            color = if (selected) Color.Black else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
