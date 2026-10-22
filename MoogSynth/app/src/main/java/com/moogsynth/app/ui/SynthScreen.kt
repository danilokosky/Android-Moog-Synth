package com.moogsynth.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moogsynth.app.audio.NativeAudioEngine
import com.moogsynth.app.ui.components.Knob
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine, incomingMidiCC: Pair<Int, Int>? = null) {
    // Estados dos Osciladores
    var osc1Vol by remember { mutableStateOf(0.75f) }
    var osc1Tune by remember { mutableStateOf(0.5f) }
    var osc1Wave by remember { mutableStateOf(0) }

    var osc2Vol by remember { mutableStateOf(0.55f) }
    var osc2Tune by remember { mutableStateOf(0.25f) }
    var osc2Wave by remember { mutableStateOf(1) }

    var osc3Vol by remember { mutableStateOf(0.45f) }
    var osc3Tune by remember { mutableStateOf(0.645f) }
    var osc3Wave by remember { mutableStateOf(2) }

    // Estados do Filtro VCF
    var cutoffNorm by remember { mutableStateOf(0.65f) }
    var resNorm by remember { mutableStateOf(0.35f) }
    var envAmtNorm by remember { mutableStateOf(0.5f) }
    var fAttack by remember { mutableStateOf(0.05f) }
    var fDecay by remember { mutableStateOf(0.25f) }
    var fSustain by remember { mutableStateOf(0.2f) }
    var fRelease by remember { mutableStateOf(0.3f) }

    // Estados LFO & Tape Delay
    var lfoRateNorm by remember { mutableStateOf(0.25f) }
    var lfoDepthNorm by remember { mutableStateOf(0.0f) }
    var delayTimeNorm by remember { mutableStateOf(0.35f) }
    var delayFbNorm by remember { mutableStateOf(0.4f) }
    var delayMixNorm by remember { mutableStateOf(0.2f) }

    // Estados VCA & Master
    var aAttack by remember { mutableStateOf(0.02f) }
    var aDecay by remember { mutableStateOf(0.3f) }
    var aSustain by remember { mutableStateOf(0.7f) }
    var aRelease by remember { mutableStateOf(0.3f) }
    var masterNorm by remember { mutableStateOf(0.85f) }

    // Sistema MIDI Learn: mapeia CC -> ID do Parâmetro
    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val ccMappings = remember { mutableStateMapOf<Int, String>() }

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 12000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }

    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    // Atualiza o parâmetro correspondente pelo seu ID (usado tanto pela tela quanto pelo MIDI CC)
    fun updateParamById(paramId: String, normVal: Float) {
        when (paramId) {
            "OSC1_VOL" -> { osc1Vol = normVal; audioEngine.setOscVolume(0, normVal) }
            "OSC2_VOL" -> { osc2Vol = normVal; audioEngine.setOscVolume(1, normVal) }
            "OSC3_VOL" -> { osc3Vol = normVal; audioEngine.setOscVolume(2, normVal) }
            "CUTOFF" -> { cutoffNorm = normVal; audioEngine.setFilterCutoff(normToCutoffHz(normVal)) }
            "RESONANCE" -> { resNorm = normVal; audioEngine.setFilterResonance(normVal) }
            "ENV_AMT" -> { envAmtNorm = normVal; audioEngine.setFilterEnvAmount(normVal * 5000f) }
            "LFO_RATE" -> { lfoRateNorm = normVal; audioEngine.setLfoRate(0.1f + normVal * 19.9f) }
            "LFO_DEPTH" -> { lfoDepthNorm = normVal; audioEngine.setLfoDepth(normVal * 3000f) }
            "DELAY_TIME" -> { delayTimeNorm = normVal; audioEngine.setDelayParams(0.05f + normVal * 0.95f, delayFbNorm, delayMixNorm) }
            "DELAY_FB" -> { delayFbNorm = normVal; audioEngine.setDelayParams(0.05f + delayTimeNorm * 0.95f, normVal, delayMixNorm) }
            "DELAY_MIX" -> { delayMixNorm = normVal; audioEngine.setDelayParams(0.05f + delayTimeNorm * 0.95f, delayFbNorm, normVal) }
            "MASTER" -> { masterNorm = normVal; audioEngine.setMasterVolume(normVal) }
        }
    }

    // Escuta mensagens CC vindas do controlador MIDI USB
    LaunchedEffect(incomingMidiCC) {
        incomingMidiCC?.let { (cc, rawVal) ->
            val norm = (rawVal / 127f).coerceIn(0f, 1f)
            if (isMidiLearnMode && selectedParamForLearn != null) {
                ccMappings[cc] = selectedParamForLearn!!
                isMidiLearnMode = false
                selectedParamForLearn = null
            } else {
                ccMappings[cc]?.let { paramId ->
                    updateParamById(paramId, norm)
                }
            }
        }
    }

    // Osciloscópio em Tempo Real (60 FPS)
    val waveformBuffer = remember { FloatArray(256) }
    var frameTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            audioEngine.getWaveform(waveformBuffer)
            frameTrigger++
            delay(16L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra Superior: Título + Osciloscópio + MIDI Learn + Panic
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MOOG POLY-D NATIVE (C++ OBOE ENGINE)",
                    color = Color(0xFFF59E0B),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isMidiLearnMode) {
                        if (selectedParamForLearn == null) "MIDI LEARN: Toque num Knob da tela para selecionar..."
                        else "AGUARDANDO CC: Gire um Knob físico para mapear [$selectedParamForLearn]!"
                    } else "C++ OBOE | 3x POLYBLEP VCO | 24dB LADDER | LFO & TAPE DELAY",
                    color = if (isMidiLearnMode) Color(0xFF38BDF8) else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Botão MIDI LEARN
                Box(
                    modifier = Modifier
                        .background(
                            if (isMidiLearnMode) Color(0xFF0284C7) else Color(0xFF1E293B),
                            RoundedCornerShape(6.dp)
                        )
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                        .clickable {
                            isMidiLearnMode = !isMidiLearnMode
                            selectedParamForLearn = null
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isMidiLearnMode) "CANCEL LEARN" else "MIDI LEARN",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Botão PANIC
                Box(
                    modifier = Modifier
                        .background(Color(0xFF991B1B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp))
                        .clickable { audioEngine.allNotesOff() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("PANIC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Painel de Módulos
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- MÓDULO 1: OSCILADORES ---
            ModuleBox(title = "OSCILADORES (POLYBLEP)") {
                OscillatorRow(
                    label = "OSC 1",
                    vol = osc1Vol,
                    tuneNorm = osc1Tune,
                    wave = osc1Wave,
                    onVolChange = {
                        if (isMidiLearnMode) selectedParamForLearn = "OSC1_VOL"
                        else updateParamById("OSC1_VOL", it)
                    },
                    onTuneChange = { osc1Tune = it; audioEngine.setOscTune(0, normToSemitones(it)) },
                    onWaveChange = { osc1Wave = it; audioEngine.setOscWave(0, it) },
                    semitones = normToSemitones(osc1Tune).toInt()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(
                    label = "OSC 2",
                    vol = osc2Vol,
                    tuneNorm = osc2Tune,
                    wave = osc2Wave,
                    onVolChange = {
                        if (isMidiLearnMode) selectedParamForLearn = "OSC2_VOL"
                        else updateParamById("OSC2_VOL", it)
                    },
                    onTuneChange = { osc2Tune = it; audioEngine.setOscTune(1, normToSemitones(it)) },
                    onWaveChange = { osc2Wave = it; audioEngine.setOscWave(1, it) },
                    semitones = normToSemitones(osc2Tune).toInt()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(
                    label = "OSC 3",
                    vol = osc3Vol,
                    tuneNorm = osc3Tune,
                    wave = osc3Wave,
                    onVolChange = {
                        if (isMidiLearnMode) selectedParamForLearn = "OSC3_VOL"
                        else updateParamById("OSC3_VOL", it)
                    },
                    onTuneChange = { osc3Tune = it; audioEngine.setOscTune(2, normToSemitones(it)) },
                    onWaveChange = { osc3Wave = it; audioEngine.setOscWave(2, it) },
                    semitones = normToSemitones(osc3Tune).toInt()
                )
            }

            // --- MÓDULO 2: FILTRO MOOG 24dB ---
            ModuleBox(title = "FILTRO MOOG 24dB (VCF)") {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    KnobControl("CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "CUTOFF"
                        else updateParamById("CUTOFF", it)
                    }
                    KnobControl("RESSON.", "${(resNorm * 100).toInt()}%", resNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "RESONANCE"
                        else updateParamById("RESONANCE", it)
                    }
                    KnobControl("ENV AMT", "${(envAmtNorm * 5000).toInt()} Hz", envAmtNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "ENV_AMT"
                        else updateParamById("ENV_AMT", it)
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

            // --- MÓDULO 3: LFO & TAPE DELAY ---
            ModuleBox(title = "MODULAÇÃO (LFO) & DELAY") {
                Text("LFO -> FILTRO MOOG", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    KnobControl("LFO RATE", "${"%.1f".format(0.1f + lfoRateNorm * 19.9f)} Hz", lfoRateNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "LFO_RATE"
                        else updateParamById("LFO_RATE", it)
                    }
                    KnobControl("LFO DEPTH", "${(lfoDepthNorm * 100).toInt()}%", lfoDepthNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "LFO_DEPTH"
                        else updateParamById("LFO_DEPTH", it)
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("TAPE DELAY ANALÓGICO", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KnobControl("TIME", "${((0.05f + delayTimeNorm * 0.95f) * 1000).toInt()}ms", delayTimeNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "DELAY_TIME"
                        else updateParamById("DELAY_TIME", it)
                    }
                    KnobControl("FEEDBACK", "${(delayFbNorm * 100).toInt()}%", delayFbNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "DELAY_FB"
                        else updateParamById("DELAY_FB", it)
                    }
                    KnobControl("MIX", "${(delayMixNorm * 100).toInt()}%", delayMixNorm) {
                        if (isMidiLearnMode) selectedParamForLearn = "DELAY_MIX"
                        else updateParamById("DELAY_MIX", it)
                    }
                }
            }

            // --- MÓDULO 4: AMPLIFICADOR (VCA) & SAÍDA ---
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
                    if (isMidiLearnMode) selectedParamForLearn = "MASTER"
                    else updateParamById("MASTER", it)
                }
            }
        }
    }
}

@Composable
fun OscilloscopeView(waveform: FloatArray, trigger: Int) {
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(52.dp)
            .background(Color(0xFF07090C), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2D323B), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unused = trigger
            val w = size.width
            val h = size.height
            val midY = h / 2f

            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            if (waveform.isNotEmpty()) {
                val path = Path()
                val stepX = w / (waveform.size - 1).toFloat()
                for (i in waveform.indices) {
                    val x = i * stepX
                    val y = (midY - (waveform[i].coerceIn(-1f, 1f) * (midY * 0.85f)))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF10B981),
                    style = Stroke(width = 2.2f)
                )
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
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .background(Color(0xFF111317), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))

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
