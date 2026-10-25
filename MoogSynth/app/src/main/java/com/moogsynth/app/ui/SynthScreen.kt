package com.moogsynth.app.ui

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moogsynth.app.MidiCCEvent
import com.moogsynth.app.audio.NativeAudioEngine
import com.moogsynth.app.ui.components.Knob
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class MidiMappingSlot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mappings: Map<String, Int>
)

data class SynthPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val synthType: Int = 0, // 0 = Moog, 1 = Juno
    
    // Moog Params
    val osc1Vol: Float = 0.8f, val osc1Tune: Float = 0.5f, val osc1Wave: Int = 0,
    val osc2Vol: Float = 0.65f, val osc2Tune: Float = 0.5f, val osc2Wave: Int = 1,
    val osc3Vol: Float = 0.55f, val osc3Tune: Float = 0.25f, val osc3Wave: Int = 2,
    val cutoffNorm: Float = 0.78f, val resNorm: Float = 0.15f, val envAmtNorm: Float = 0.45f,
    val fAttack: Float = 0.01f, val fDecay: Float = 0.28f, val fSustain: Float = 0.45f, val fRelease: Float = 0.22f,
    
    // Juno Params
    val junoRange: Int = 1,
    val junoPulseVol: Float = 0.8f,
    val junoSawVol: Float = 0.8f,
    val junoSubVol: Float = 0.5f,
    val junoNoiseVol: Float = 0.0f,
    val junoPwm: Float = 0.5f,
    val junoHpf: Float = 0.0f,
    val junoVcfCutoff: Float = 0.6f,
    val junoVcfRes: Float = 0.2f,
    val junoEnvAmt: Float = 0.5f,
    val junoChorus: Int = 0,

    // Shared FX / Master
    val lfoRateNorm: Float = 0.25f, val lfoDepthNorm: Float = 0.0f,
    val delayTimeNorm: Float = 0.35f, val delayFbNorm: Float = 0.35f, val delayMixNorm: Float = 0.0f,
    val aAttack: Float = 0.005f, val aDecay: Float = 0.25f, val aSustain: Float = 0.85f, val aRelease: Float = 0.20f,
    val masterNorm: Float = 0.85f
)

fun getDefaultLaunchkeyMK4Mappings(): Map<String, Int> = mapOf(
    "OSC1_VOL" to 29, "OSC1_TUNE" to 36, "OSC2_VOL" to 30, "OSC2_TUNE" to 35,
    "OSC3_VOL" to 31, "OSC3_TUNE" to 34, "CUTOFF" to 88, "RESONANCE" to 85,
    "ENV_AMT" to 86, "F_ATK" to 25, "F_DEC" to 26, "F_SUS" to 27, "F_REL" to 28,
    "LFO_RATE" to 23, "LFO_DEPTH" to 24, "DELAY_TIME" to 21, "DELAY_FB" to 22,
    "DELAY_MIX" to 87, "A_ATK" to 81, "A_DEC" to 82, "A_SUS" to 83, "A_REL" to 84, "MASTER" to 89
)

fun getFactoryPresets(): List<SynthPreset> = listOf(
    // MOOG PRESETS
    SynthPreset(
        name = "1. Pure Analog Init (Moog)", synthType = 0,
        osc1Vol = 0.80f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.65f, osc2Tune = 0.50f, osc2Wave = 1,
        osc3Vol = 0.55f, osc3Tune = 0.25f, osc3Wave = 2,
        cutoffNorm = 0.78f, resNorm = 0.15f, envAmtNorm = 0.45f,
        fAttack = 0.01f, fDecay = 0.28f, fSustain = 0.45f, fRelease = 0.22f,
        lfoRateNorm = 0.25f, lfoDepthNorm = 0.0f, delayTimeNorm = 0.35f, delayFbNorm = 0.35f, delayMixNorm = 0.0f,
        aAttack = 0.005f, aDecay = 0.25f, aSustain = 0.85f, aRelease = 0.20f, masterNorm = 0.85f
    ),
    SynthPreset(
        name = "2. Blade Runner Brass (Moog)", synthType = 0,
        osc1Vol = 0.85f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.85f, osc2Tune = 0.51f, osc2Wave = 0,
        osc3Vol = 0.70f, osc3Tune = 0.49f, osc3Wave = 0,
        cutoffNorm = 0.30f, resNorm = 0.18f, envAmtNorm = 0.65f,
        fAttack = 0.25f, fDecay = 0.35f, fSustain = 0.55f, fRelease = 0.35f,
        lfoRateNorm = 0.25f, lfoDepthNorm = 0.05f, delayTimeNorm = 0.45f, delayFbNorm = 0.45f, delayMixNorm = 0.30f,
        aAttack = 0.20f, aDecay = 0.30f, aSustain = 0.80f, aRelease = 0.40f, masterNorm = 0.85f
    ),
    // JUNO PRESETS
    SynthPreset(
        name = "1. Juno Init Pad", synthType = 1,
        junoRange = 1, junoPulseVol = 0.8f, junoSawVol = 0.8f, junoSubVol = 0.5f, junoNoiseVol = 0.0f,
        junoPwm = 0.6f, junoHpf = 0.1f, junoVcfCutoff = 0.4f, junoVcfRes = 0.1f, junoEnvAmt = 0.3f, junoChorus = 1,
        fAttack = 0.3f, fDecay = 0.4f, fSustain = 0.5f, fRelease = 0.4f,
        aAttack = 0.3f, aDecay = 0.4f, aSustain = 0.8f, aRelease = 0.4f,
        lfoRateNorm = 0.3f, lfoDepthNorm = 0.1f, delayTimeNorm = 0.4f, delayFbNorm = 0.3f, delayMixNorm = 0.2f
    ),
    SynthPreset(
        name = "2. Juno Classic Bass", synthType = 1,
        junoRange = 2, junoPulseVol = 0.0f, junoSawVol = 0.9f, junoSubVol = 0.9f, junoNoiseVol = 0.0f,
        junoPwm = 0.0f, junoHpf = 0.0f, junoVcfCutoff = 0.2f, junoVcfRes = 0.4f, junoEnvAmt = 0.6f, junoChorus = 0,
        fAttack = 0.01f, fDecay = 0.2f, fSustain = 0.1f, fRelease = 0.2f,
        aAttack = 0.01f, aDecay = 0.2f, aSustain = 0.8f, aRelease = 0.2f,
        lfoRateNorm = 0.0f, lfoDepthNorm = 0.0f, delayMixNorm = 0.0f
    )
)

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine, incomingMidiCC: MidiCCEvent? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("moog_midi_slots_prefs", Context.MODE_PRIVATE) }

    // Layers & Synth Selection
    var currentLayer by remember { mutableStateOf(0) } // 0 = Layer A, 1 = Layer B
    var synthTypeA by remember { mutableStateOf(0) } // 0 = Moog, 1 = Juno
    var synthTypeB by remember { mutableStateOf(0) } // 0 = Moog, 1 = Juno

    // MOOG STATES (Layer A)
    var osc1Vol by remember { mutableStateOf(0.80f) }
    var osc1Tune by remember { mutableStateOf(0.50f) }
    var osc1Wave by remember { mutableStateOf(0) }
    var osc2Vol by remember { mutableStateOf(0.65f) }
    var osc2Tune by remember { mutableStateOf(0.50f) }
    var osc2Wave by remember { mutableStateOf(1) }
    var osc3Vol by remember { mutableStateOf(0.55f) }
    var osc3Tune by remember { mutableStateOf(0.25f) }
    var osc3Wave by remember { mutableStateOf(2) }
    var cutoffNorm by remember { mutableStateOf(0.78f) }
    var resNorm by remember { mutableStateOf(0.15f) }
    var envAmtNorm by remember { mutableStateOf(0.45f) }
    var fAttack by remember { mutableStateOf(0.01f) }
    var fDecay by remember { mutableStateOf(0.28f) }
    var fSustain by remember { mutableStateOf(0.45f) }
    var fRelease by remember { mutableStateOf(0.22f) }
    var lfoRateNorm by remember { mutableStateOf(0.25f) }
    var lfoDepthNorm by remember { mutableStateOf(0.0f) }
    var delayTimeNorm by remember { mutableStateOf(0.35f) }
    var delayFbNorm by remember { mutableStateOf(0.35f) }
    var delayMixNorm by remember { mutableStateOf(0.0f) }
    var aAttack by remember { mutableStateOf(0.005f) }
    var aDecay by remember { mutableStateOf(0.25f) }
    var aSustain by remember { mutableStateOf(0.85f) }
    var aRelease by remember { mutableStateOf(0.20f) }
    var masterNorm by remember { mutableStateOf(0.85f) }

    // JUNO STATES (Layer A - Temporário)
    var junoRange by remember { mutableStateOf(1) } // 0=16', 1=8', 2=4'
    var junoPulseVol by remember { mutableStateOf(0.8f) }
    var junoSawVol by remember { mutableStateOf(0.8f) }
    var junoSubVol by remember { mutableStateOf(0.5f) }
    var junoNoiseVol by remember { mutableStateOf(0.0f) }
    var junoPwm by remember { mutableStateOf(0.5f) }
    var junoHpf by remember { mutableStateOf(0.0f) }
    var junoVcfCutoff by remember { mutableStateOf(0.6f) }
    var junoVcfRes by remember { mutableStateOf(0.2f) }
    var junoEnvAmt by remember { mutableStateOf(0.5f) }
    var junoChorus by remember { mutableStateOf(0) } // 0=Off, 1=I, 2=II

    var showAudioSettingsDialog by remember { mutableStateOf(false) }
    var oversamplingFactor by remember { mutableStateOf(2) }
    var analogDrift by remember { mutableStateOf(0.05f) }
    var filterDrive by remember { mutableStateOf(0.0f) }
    var filterKeyTrack by remember { mutableStateOf(0.50f) }
    var stereoSpread by remember { mutableStateOf(0.15f) }
    var velocitySens by remember { mutableStateOf(0.35f) }
    var maxPolyphony by remember { mutableStateOf(16) }
    var bufferBurstMult by remember { mutableStateOf(2) }
    var paramSmoothing by remember { mutableStateOf(true) }

    val engineStatsBuffer = remember { FloatArray(7) }
    var statSampleRate by remember { mutableStateOf(48000) }
    var statBurstFrames by remember { mutableStateOf(192) }
    var statBufferFrames by remember { mutableStateOf(384) }
    var statLatencyMs by remember { mutableStateOf(8.0f) }
    var statXRuns by remember { mutableStateOf(0) }
    var statActiveVoices by remember { mutableStateOf(0) }

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 18000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }
    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    val presetsList = remember { mutableStateListOf<SynthPreset>() }
    var currentPresetId by remember { mutableStateOf("") }
    var isPresetDirty by remember { mutableStateOf(false) }
    
    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val paramToCCMap = remember { mutableStateMapOf<String, Int>() }
    val midiSlots = remember { mutableStateListOf<MidiMappingSlot>() }
    var currentSlotId by remember { mutableStateOf("") }

    val currentSlotName = midiSlots.find { it.id == currentSlotId }?.name ?: "Slot 1"
    val currentPresetName = presetsList.find { it.id == currentPresetId }?.name ?: "Preset 1"
    
    val waveformBuffer = remember { FloatArray(256) }
    var frameTrigger by remember { mutableStateOf(0) }

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
            "MASTER" -> { masterNorm = v; audioEngine.setMasterVolume(v) }
        }
        isPresetDirty = true
    }
    
    @Composable
    fun SmartKnob(paramId: String, label: String, valueText: String, value: Float) {
        KnobControl(
            label = label, valueText = valueText, value = value,
            isLearningSelected = (isMidiLearnMode && selectedParamForLearn == paramId),
            mappedCC = if (isMidiLearnMode) paramToCCMap[paramId] else null,
            onUnmap = { paramToCCMap.remove(paramId) },
            onKnobClick = { if (isMidiLearnMode) selectedParamForLearn = paramId },
            onValueChange = { newVal ->
                if (isMidiLearnMode) selectedParamForLearn = paramId else updateParamById(paramId, newVal)
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115)).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        
        // --- HEADER ---
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("HYBRID NATIVE SYNTH", color = Color(0xFFF59E0B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(if (isMidiLearnMode) "MIDI LEARN MODE" else "TIMBRE: $currentPresetName", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF10B981).copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text("⚙️ DSP (${oversamplingFactor}x)", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        
        // --- TABS (LAYER A / LAYER B) ---
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp)).padding(4.dp)) {
                Row {
                    Box(modifier = Modifier.background(if (currentLayer == 0) Color(0xFFF59E0B) else Color.Transparent, RoundedCornerShape(6.dp)).clickable { currentLayer = 0 }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text("LAYER A", color = if (currentLayer == 0) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.background(if (currentLayer == 1) Color(0xFF0284C7) else Color.Transparent, RoundedCornerShape(6.dp)).clickable { currentLayer = 1 }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text("LAYER B", color = if (currentLayer == 1) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // --- SYNTH SELECTOR ---
        val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
        
        LaunchedEffect(activeSynthType) {
            audioEngine.setSynthType(activeSynthType)
            // Restore active preset for this synth type automatically if possible
            val lastPreset = presetsList.find { it.synthType == activeSynthType }
            if (lastPreset != null) {
                applyPresetToEngine(lastPreset)
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.background(if (activeSynthType == 0) Color(0xFF334155) else Color(0xFF111317), RoundedCornerShape(8.dp)).clickable { if (currentLayer == 0) synthTypeA = 0 else synthTypeB = 0 }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("🎛️ MOOG POLY-D", color = if (activeSynthType == 0) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.background(if (activeSynthType == 1) Color(0xFF7E22CE) else Color(0xFF111317), RoundedCornerShape(8.dp)).clickable { if (currentLayer == 0) synthTypeA = 1 else synthTypeB = 1 }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("🎹 ROLAND JUNO-6", color = if (activeSynthType == 1) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- SYNTH ENGINES UI ---
        if (activeSynthType == 0) {
            // ================= MOOG UI =================
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModuleBox(title = "OSCILADORES 64-BIT (POLYBLEP)") {
                    OscillatorRow(label = "OSC 1", wave = osc1Wave, onWaveChange = { osc1Wave = it }, levelKnob = { SmartKnob("OSC1_VOL", "LEVEL", "${(osc1Vol * 100).toInt()}%", osc1Vol) }, tuneKnob = { SmartKnob("OSC1_TUNE", "TUNE", "${normToSemitones(osc1Tune).toInt()} st", osc1Tune) })
                    Spacer(modifier = Modifier.height(12.dp))
                    OscillatorRow(label = "OSC 2", wave = osc2Wave, onWaveChange = { osc2Wave = it }, levelKnob = { SmartKnob("OSC2_VOL", "LEVEL", "${(osc2Vol * 100).toInt()}%", osc2Vol) }, tuneKnob = { SmartKnob("OSC2_TUNE", "TUNE", "${normToSemitones(osc2Tune).toInt()} st", osc2Tune) })
                    Spacer(modifier = Modifier.height(12.dp))
                    OscillatorRow(label = "OSC 3", wave = osc3Wave, onWaveChange = { osc3Wave = it }, levelKnob = { SmartKnob("OSC3_VOL", "LEVEL", "${(osc3Vol * 100).toInt()}%", osc3Vol) }, tuneKnob = { SmartKnob("OSC3_TUNE", "TUNE", "${normToSemitones(osc3Tune).toInt()} st", osc3Tune) })
                }
                ModuleBox(title = "FILTRO MOOG 24dB LADDER") {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        SmartKnob("CUTOFF", "CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm)
                        SmartKnob("RESONANCE", "RESSON.", "${(resNorm * 100).toInt()}%", resNorm)
                        SmartKnob("ENV_AMT", "ENV AMT", "${"%.1f".format(envAmtNorm * 4.5f)} Oct", envAmtNorm)
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SmartKnob("F_ATK", "ATK", "${(fAttack * 2000).toInt()}ms", fAttack)
                        SmartKnob("F_DEC", "DEC", "${(fDecay * 2000).toInt()}ms", fDecay)
                        SmartKnob("F_SUS", "SUS", "${(fSustain * 100).toInt()}%", fSustain)
                        SmartKnob("F_REL", "REL", "${(fRelease * 3000).toInt()}ms", fRelease)
                    }
                }
                ModuleBox(title = "AMPLIFICADOR (VCA)") {
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
        } else {
            // ================= JUNO-6 UI =================
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                
                // DCO MODULE
                ModuleBox(title = "DCO (DIGITALLY CONTROLLED OSC)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RANGE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            listOf(0 to "16'", 1 to "8'", 2 to "4'").forEach { (idx, label) ->
                                val selected = junoRange == idx
                                Box(modifier = Modifier.background(if (selected) Color(0xFFEAB308) else Color(0xFF262930), RoundedCornerShape(4.dp)).clickable { junoRange = idx; audioEngine.setJunoRange(idx); isPresetDirty = true }.padding(horizontal = 12.dp, vertical = 6.dp).width(30.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (selected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        Box(modifier = Modifier.width(1.dp).height(100.dp).background(Color(0xFF334155)))
                        SmartKnob("JUNO_PULSE", "PULSE", "${(junoPulseVol*100).toInt()}%", junoPulseVol)
                        SmartKnob("JUNO_SAW", "SAW", "${(junoSawVol*100).toInt()}%", junoSawVol)
                        SmartKnob("JUNO_SUB", "SUB", "${(junoSubVol*100).toInt()}%", junoSubVol)
                        SmartKnob("JUNO_NOISE", "NOISE", "${(junoNoiseVol*100).toInt()}%", junoNoiseVol)
                        Box(modifier = Modifier.width(1.dp).height(100.dp).background(Color(0xFF334155)))
                        SmartKnob("JUNO_PWM", "PWM MOD", "${(junoPwm*100).toInt()}%", junoPwm)
                    }
                }

                // FILTER MODULE (HPF + VCF)
                ModuleBox(title = "VCF (VOLTAGE CONTROLLED FILTER)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SmartKnob("JUNO_HPF", "HPF FREQ", "${(junoHpf*100).toInt()}%", junoHpf)
                        Box(modifier = Modifier.width(1.dp).height(80.dp).background(Color(0xFF334155)))
                        SmartKnob("JUNO_CUTOFF", "FREQ", "${(junoVcfCutoff*100).toInt()}%", junoVcfCutoff)
                        SmartKnob("JUNO_RES", "RES", "${(junoVcfRes*100).toInt()}%", junoVcfRes)
                        SmartKnob("JUNO_ENV", "ENV AMT", "${(junoEnvAmt*100).toInt()}%", junoEnvAmt)
                    }
                }
                
                // CHORUS MODULE
                ModuleBox(title = "BBD CHORUS") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MODE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.background(if (junoChorus == 0) Color(0xFFEF4444) else Color(0xFF262930), RoundedCornerShape(4.dp)).clickable { junoChorus = 0; audioEngine.setJunoChorus(0); isPresetDirty = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text("OFF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(modifier = Modifier.background(if (junoChorus == 1 || junoChorus == 3) Color(0xFFEAB308) else Color(0xFF262930), RoundedCornerShape(4.dp)).clickable { junoChorus = if(junoChorus==2) 3 else 1; audioEngine.setJunoChorus(junoChorus); isPresetDirty = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(" I ", color = if(junoChorus==1||junoChorus==3) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(modifier = Modifier.background(if (junoChorus == 2 || junoChorus == 3) Color(0xFFEAB308) else Color(0xFF262930), RoundedCornerShape(4.dp)).clickable { junoChorus = if(junoChorus==1) 3 else 2; audioEngine.setJunoChorus(junoChorus); isPresetDirty = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(" II ", color = if(junoChorus==2||junoChorus==3) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                // ENVELOPE MODULE
                ModuleBox(title = "ENV / VCA") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SmartKnob("A_ATK", "A", "${(aAttack * 2000).toInt()}ms", aAttack)
                        SmartKnob("A_DEC", "D", "${(aDecay * 2000).toInt()}ms", aDecay)
                        SmartKnob("A_SUS", "S", "${(aSustain * 100).toInt()}%", aSustain)
                        SmartKnob("A_REL", "R", "${(aRelease * 3000).toInt()}ms", aRelease)
                    }
                }
            }
        }
    }
}

@Composable
fun OscillatorRow(label: String, wave: Int, onWaveChange: (Int) -> Unit, levelKnob: @Composable () -> Unit, tuneKnob: @Composable () -> Unit) {
    val waveNames = listOf("SAW", "SQR", "TRI", "SIN")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.background(Color(0xFF111317), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))
        levelKnob()
        tuneKnob()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ONDA", color = Color.Gray, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                waveNames.forEachIndexed { idx, name ->
                    val selected = (wave == idx)
                    Box(modifier = Modifier.background(if (selected) Color(0xFFF59E0B) else Color(0xFF262930), RoundedCornerShape(4.dp)).clickable { onWaveChange(idx) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(name, color = if (selected) Color.Black else Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ModuleBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF2D323B), RoundedCornerShape(10.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

@Composable
fun OscilloscopeView(waveform: FloatArray, trigger: Int) {
    Box(modifier = Modifier.width(170.dp).height(48.dp).background(Color(0xFF07090C), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2D323B), RoundedCornerShape(6.dp)).padding(4.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unused = trigger
            val w = size.width; val h = size.height; val midY = h / 2f
            drawLine(color = Color(0xFF1E293B), start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1f)
            if (waveform.isNotEmpty()) {
                val path = Path()
                val stepX = w / (waveform.size - 1).toFloat()
                for (i in waveform.indices) {
                    val x = i * stepX
                    val y = (midY - (waveform[i].coerceIn(-1f, 1f) * (midY * 0.85f)))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = Color(0xFF10B981), style = Stroke(width = 2.2f))
            }
        }
    }
}

@Composable
fun KnobControl(label: String, valueText: String, value: Float, isLearningSelected: Boolean = false, mappedCC: Int? = null, onUnmap: () -> Unit = {}, onKnobClick: () -> Unit = {}, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            Knob(value = value, onValueChange = onValueChange, isLearningSelected = isLearningSelected, onKnobClick = onKnobClick)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = if (isLearningSelected) Color(0xFFFDE047) else Color.LightGray, fontSize = 11.sp, fontWeight = if (isLearningSelected) FontWeight.Bold else FontWeight.Medium)
        Text(valueText, color = Color(0xFFF59E0B), fontSize = 10.sp)
    }
}

