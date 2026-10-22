package com.moogsynth.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.moogsynth.app.MidiCCEvent
import com.moogsynth.app.audio.NativeAudioEngine
import com.moogsynth.app.ui.components.Knob
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine, incomingMidiCC: MidiCCEvent? = null) {
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

    // Sistema MIDI Learn Contínuo
    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val paramToCCMap = remember { mutableStateMapOf<String, Int>() }

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 12000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }

    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    fun updateParamById(paramId: String, normVal: Float) {
        val v = normVal.coerceIn(0f, 1f)
        when (paramId) {
            "OSC1_VOL" -> { osc1Vol = v; audioEngine.setOscVolume(0, v) }
            "OSC1_TUNE" -> { osc1Tune = v; audioEngine.setOscTune(0, normToSemitones(v)) }
            "OSC2_VOL" -> { osc2Vol = v; audioEngine.setOscVolume(1, v) }
            "OSC2_TUNE" -> { osc2Tune = v; audioEngine.setOscTune(1, normToSemitones(v)) }
            "OSC3_VOL" -> { osc3Vol = v; audioEngine.setOscVolume(2, v) }
            "OSC3_TUNE" -> { osc3Tune = v; audioEngine.setOscTune(2, normToSemitones(v)) }
            "CUTOFF" -> { cutoffNorm = v; audioEngine.setFilterCutoff(normToCutoffHz(v)) }
            "RESONANCE" -> { resNorm = v; audioEngine.setFilterResonance(v) }
            "ENV_AMT" -> { envAmtNorm = v; audioEngine.setFilterEnvAmount(v * 5000f) }
            "F_ATK" -> { fAttack = v; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f) }
            "F_DEC" -> { fDecay = v; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f) }
            "F_SUS" -> { fSustain = v; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f) }
            "F_REL" -> { fRelease = v; audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f) }
            "LFO_RATE" -> { lfoRateNorm = v; audioEngine.setLfoRate(0.1f + v * 19.9f) }
            "LFO_DEPTH" -> { lfoDepthNorm = v; audioEngine.setLfoDepth(v * 3000f) }
            "DELAY_TIME" -> { delayTimeNorm = v; audioEngine.setDelayParams(0.05f + v * 0.95f, delayFbNorm, delayMixNorm) }
            "DELAY_FB" -> { delayFbNorm = v; audioEngine.setDelayParams(0.05f + delayTimeNorm * 0.95f, v, delayMixNorm) }
            "DELAY_MIX" -> { delayMixNorm = v; audioEngine.setDelayParams(0.05f + delayTimeNorm * 0.95f, delayFbNorm, v) }
            "A_ATK" -> { aAttack = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_DEC" -> { aDecay = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_SUS" -> { aSustain = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_REL" -> { aRelease = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "MASTER" -> { masterNorm = v; audioEngine.setMasterVolume(v) }
        }
    }

    // Escuta comandos MIDI CC
    LaunchedEffect(incomingMidiCC) {
        incomingMidiCC?.let { event ->
            val norm = (event.value / 127f).coerceIn(0f, 1f)
            if (isMidiLearnMode && selectedParamForLearn != null) {
                val targetParam = selectedParamForLearn!!
                // Remove CC duplicado se já estivesse em outro knob
                val previousKeys = paramToCCMap.filterValues { it == event.cc }.keys.toList()
                previousKeys.forEach { paramToCCMap.remove(it) }

                paramToCCMap[targetParam] = event.cc
                updateParamById(targetParam, norm)
                
                // Mantém o modo MIDI Learn ativo para você poder mapear o próximo knob em sequência!
                selectedParamForLearn = null
            } else {
                paramToCCMap.forEach { (paramId, mappedCC) ->
                    if (mappedCC == event.cc) {
                        updateParamById(paramId, norm)
                    }
                }
            }
        }
    }

    @Composable
    fun SmartKnob(paramId: String, label: String, valueText: String, value: Float) {
        KnobControl(
            label = label,
            valueText = valueText,
            value = value,
            isLearningSelected = (isMidiLearnMode && selectedParamForLearn == paramId),
            mappedCC = if (isMidiLearnMode) paramToCCMap[paramId] else null, // Balãozinho só aparece no modo Learn
            onUnmap = { paramToCCMap.remove(paramId) },
            onKnobClick = {
                if (isMidiLearnMode) {
                    selectedParamForLearn = paramId
                }
            },
            onValueChange = { newVal ->
                if (isMidiLearnMode) {
                    selectedParamForLearn = paramId
                } else {
                    updateParamById(paramId, newVal)
                }
            }
        )
    }

    // Osciloscópio (60 FPS)
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
        // Barra Superior
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
                        if (selectedParamForLearn == null) "MIDI LEARN ATIVO: Clique num botão da tela para selecioná-lo..."
                        else "AGUARDANDO CONTROLLER: Gire um Knob físico para mapear [$selectedParamForLearn]!"
                    } else "C++ OBOE | 3x POLYBLEP VCO | 24dB LADDER | LFO & TAPE DELAY",
                    color = if (isMidiLearnMode) Color(0xFFFBBF24) else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isMidiLearnMode) Color(0xFFD97706) else Color(0xFF1E293B),
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (isMidiLearnMode) Color(0xFFFDE047) else Color(0xFF38BDF8),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            isMidiLearnMode = !isMidiLearnMode
                            selectedParamForLearn = null
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isMidiLearnMode) "SAIR DO LEARN" else "MIDI LEARN",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

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
                    wave = osc1Wave,
                    onWaveChange = { osc1Wave = it; audioEngine.setOscWave(0, it) },
                    levelKnob = { SmartKnob("OSC1_VOL", "LEVEL", "${(osc1Vol * 100).toInt()}%", osc1Vol) },
                    tuneKnob = {
                        val st = normToSemitones(osc1Tune).toInt()
                        SmartKnob("OSC1_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc1Tune)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(
                    label = "OSC 2",
                    wave = osc2Wave,
                    onWaveChange = { osc2Wave = it; audioEngine.setOscWave(1, it) },
                    levelKnob = { SmartKnob("OSC2_VOL", "LEVEL", "${(osc2Vol * 100).toInt()}%", osc2Vol) },
                    tuneKnob = {
                        val st = normToSemitones(osc2Tune).toInt()
                        SmartKnob("OSC2_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc2Tune)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(
                    label = "OSC 3",
                    wave = osc3Wave,
                    onWaveChange = { osc3Wave = it; audioEngine.setOscWave(2, it) },
                    levelKnob = { SmartKnob("OSC3_VOL", "LEVEL", "${(osc3Vol * 100).toInt()}%", osc3Vol) },
                    tuneKnob = {
                        val st = normToSemitones(osc3Tune).toInt()
                        SmartKnob("OSC3_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc3Tune)
                    }
                )
            }

            // --- MÓDULO 2: FILTRO MOOG 24dB ---
            ModuleBox(title = "FILTRO MOOG 24dB (VCF)") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SmartKnob("CUTOFF", "CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm)
                    SmartKnob("RESONANCE", "RESSON.", "${(resNorm * 100).toInt()}%", resNorm)
                    SmartKnob("ENV_AMT", "ENV AMT", "${(envAmtNorm * 5000).toInt()} Hz", envAmtNorm)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("ENVELOPE DO FILTRO (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("F_ATK", "ATK", "${(fAttack * 2000).toInt()}ms", fAttack)
                    SmartKnob("F_DEC", "DEC", "${(fDecay * 2000).toInt()}ms", fDecay)
                    SmartKnob("F_SUS", "SUS", "${(fSustain * 100).toInt()}%", fSustain)
                    SmartKnob("F_REL", "REL", "${(fRelease * 3000).toInt()}ms", fRelease)
                }
            }

            // --- MÓDULO 3: LFO & TAPE DELAY ---
            ModuleBox(title = "MODULAÇÃO (LFO) & DELAY") {
                Text("LFO -> FILTRO MOOG", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    SmartKnob("LFO_RATE", "LFO RATE", "${"%.1f".format(0.1f + lfoRateNorm * 19.9f)} Hz", lfoRateNorm)
                    SmartKnob("LFO_DEPTH", "LFO DEPTH", "${(lfoDepthNorm * 100).toInt()}%", lfoDepthNorm)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("TAPE DELAY ANALÓGICO", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("DELAY_TIME", "TIME", "${((0.05f + delayTimeNorm * 0.95f) * 1000).toInt()}ms", delayTimeNorm)
                    SmartKnob("DELAY_FB", "FEEDBACK", "${(delayFbNorm * 100).toInt()}%", delayFbNorm)
                    SmartKnob("DELAY_MIX", "MIX", "${(delayMixNorm * 100).toInt()}%", delayMixNorm)
                }
            }

            // --- MÓDULO 4: AMPLIFICADOR (VCA) & SAÍDA ---
            ModuleBox(title = "AMPLIFICADOR (VCA) & SAÍDA") {
                Text("ENVELOPE DE VOLUME (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("A_ATK", "ATK", "${(aAttack * 2000).toInt()}ms", aAttack)
                    SmartKnob("A_DEC", "DEC", "${(aDecay * 2000).toInt()}ms", aDecay)
                    SmartKnob("A_SUS", "SUS", "${(aSustain * 100).toInt()}%", aSustain)
                    SmartKnob("A_REL", "REL", "${(aRelease * 3000).toInt()}ms", aRelease)
                }
                Spacer(modifier = Modifier.height(22.dp))
                SmartKnob("MASTER", "VOLUME MASTER", "${(masterNorm * 100).toInt()}%", masterNorm)
            }
        }
    }
}

@Composable
fun KnobControl(
    label: String,
    valueText: String,
    value: Float,
    isLearningSelected: Boolean = false,
    mappedCC: Int? = null,
    onUnmap: () -> Unit = {},
    onKnobClick: () -> Unit = {},
    onValueChange: (Float) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            Knob(
                value = value,
                onValueChange = onValueChange,
                isLearningSelected = isLearningSelected,
                onKnobClick = onKnobClick
            )

            // Balãozinho de CC com botão [×] de desmapear (visível apenas no modo MIDI Learn)
            if (mappedCC != null) {
                Row(
                    modifier = Modifier
                        .offset(x = 6.dp, y = (-4).dp)
                        .background(Color(0xFF0284C7), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF7DD3FC), RoundedCornerShape(10.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "CC $mappedCC",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    // Botão X para desmapear
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(0xFF0369A1), CircleShape)
                            .clickable { onUnmap() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isLearningSelected) Color(0xFFFDE047) else Color.LightGray,
            fontSize = 11.sp,
            fontWeight = if (isLearningSelected) FontWeight.Bold else FontWeight.Medium
        )
        Text(valueText, color = Color(0xFFF59E0B), fontSize = 10.sp)
    }
}

@Composable
fun OscillatorRow(
    label: String,
    wave: Int,
    onWaveChange: (Int) -> Unit,
    levelKnob: @Composable () -> Unit,
    tuneKnob: @Composable () -> Unit
) {
    val waveNames = listOf("SAW", "SQR", "TRI", "SIN")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .background(Color(0xFF111317), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))

        levelKnob()
        tuneKnob()

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
