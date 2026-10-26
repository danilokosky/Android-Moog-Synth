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
    "OSC1_VOL" to 29,
    "OSC1_TUNE" to 36,
    "OSC2_VOL" to 30,
    "OSC2_TUNE" to 35,
    "OSC3_VOL" to 31,
    "OSC3_TUNE" to 34,
    "CUTOFF" to 88,
    "RESONANCE" to 85,
    "ENV_AMT" to 86,
    "F_ATK" to 25,
    "F_DEC" to 26,
    "F_SUS" to 27,
    "F_REL" to 28,
    "LFO_RATE" to 23,
    "LFO_DEPTH" to 24,
    "DELAY_TIME" to 21,
    "DELAY_FB" to 22,
    "DELAY_MIX" to 87,
    "A_ATK" to 81,
    "A_DEC" to 82,
    "A_SUS" to 83,
    "A_REL" to 84,
    "MASTER" to 89
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

    // Estado inicial padrão 100% puro e afinado (OSC1=0st, OSC2=0st, OSC3=-12st, Delay Mix=0%)
    
    // Layers & Synth Selection
    var currentLayer by remember { mutableStateOf(0) } // 0 = Layer A, 1 = Layer B
    var synthTypeA by remember { mutableStateOf(0) } // 0 = Moog, 1 = Juno
    var synthTypeB by remember { mutableStateOf(0) } // 0 = Moog, 1 = Juno

    // JUNO STATES
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
    var junoChorus by remember { mutableStateOf(0) } // 0=Off, 1=I, 2=II, 3=I+II

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

    var showAudioSettingsDialog by remember { mutableStateOf(false) }
    var oversamplingFactor by remember { mutableStateOf(2) }
    // Calibração padrão 100% Pura: Filter Drive = 0% (linear), Drift sutil = 5%, Spread = 15%
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

    fun saveEngineSettingsToDisk() {
        val obj = JSONObject()
        obj.put("oversamplingFactor", oversamplingFactor)
        obj.put("analogDrift", analogDrift.toDouble())
        obj.put("filterDrive", filterDrive.toDouble())
        obj.put("filterKeyTrack", filterKeyTrack.toDouble())
        obj.put("stereoSpread", stereoSpread.toDouble())
        obj.put("velocitySens", velocitySens.toDouble())
        obj.put("maxPolyphony", maxPolyphony)
        obj.put("bufferBurstMult", bufferBurstMult)
        obj.put("paramSmoothing", paramSmoothing)
        prefs.edit().putString("saved_engine_dsp_settings_v3_json", obj.toString()).commit()
    }

    fun applyEngineSettingsToNative() {
        audioEngine.setOversampling(oversamplingFactor)
        audioEngine.setAnalogDrift(analogDrift)
        audioEngine.setFilterDrive(filterDrive)
        audioEngine.setFilterKeyTrack(filterKeyTrack)
        audioEngine.setStereoSpread(stereoSpread)
        audioEngine.setVelocitySensitivity(velocitySens)
        audioEngine.setMaxPolyphony(maxPolyphony)
        audioEngine.setBufferBurstMultiplier(bufferBurstMult)
        audioEngine.setParameterSmoothing(paramSmoothing)
    }

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 18000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }
    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    val presetsList = remember { mutableStateListOf<SynthPreset>() }
    var currentPresetId by remember { mutableStateOf("") }
    var isPresetDirty by remember { mutableStateOf(false) }
    var isPresetDropdownExpanded by remember { mutableStateOf(false) }

    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val paramToCCMap = remember { mutableStateMapOf<String, Int>() }
    val midiSlots = remember { mutableStateListOf<MidiMappingSlot>() }
    var currentSlotId by remember { mutableStateOf("") }
    var isSlotDropdownExpanded by remember { mutableStateOf(false) }

    var showNameDialog by remember { mutableStateOf(false) }
    var dialogTargetIsPreset by remember { mutableStateOf(true) }
    var isCreatingNewItem by remember { mutableStateOf(false) }
    var nameInputText by remember { mutableStateOf("") }

    fun savePresetsToDisk(list: List<SynthPreset>, activeId: String) {
        val root = JSONObject()
        root.put("activePresetId", activeId)
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name)
            o.put("osc1Vol", p.osc1Vol.toDouble()); o.put("osc1Tune", p.osc1Tune.toDouble()); o.put("osc1Wave", p.osc1Wave)
            o.put("osc2Vol", p.osc2Vol.toDouble()); o.put("osc2Tune", p.osc2Tune.toDouble()); o.put("osc2Wave", p.osc2Wave)
            o.put("osc3Vol", p.osc3Vol.toDouble()); o.put("osc3Tune", p.osc3Tune.toDouble()); o.put("osc3Wave", p.osc3Wave)
            o.put("cutoffNorm", p.cutoffNorm.toDouble()); o.put("resNorm", p.resNorm.toDouble()); o.put("envAmtNorm", p.envAmtNorm.toDouble())
            o.put("fAttack", p.fAttack.toDouble()); o.put("fDecay", p.fDecay.toDouble()); o.put("fSustain", p.fSustain.toDouble()); o.put("fRelease", p.fRelease.toDouble())
            o.put("lfoRateNorm", p.lfoRateNorm.toDouble()); o.put("lfoDepthNorm", p.lfoDepthNorm.toDouble())
            o.put("delayTimeNorm", p.delayTimeNorm.toDouble()); o.put("delayFbNorm", p.delayFbNorm.toDouble()); o.put("delayMixNorm", p.delayMixNorm.toDouble())
            o.put("aAttack", p.aAttack.toDouble()); o.put("aDecay", p.aDecay.toDouble()); o.put("aSustain", p.aSustain.toDouble()); o.put("aRelease", p.aRelease.toDouble())
            o.put("masterNorm", p.masterNorm.toDouble())
            o.put("synthType", p.synthType)
            o.put("junoRange", p.junoRange); o.put("junoPulseVol", p.junoPulseVol.toDouble()); o.put("junoSawVol", p.junoSawVol.toDouble())
            o.put("junoSubVol", p.junoSubVol.toDouble()); o.put("junoNoiseVol", p.junoNoiseVol.toDouble()); o.put("junoPwm", p.junoPwm.toDouble())
            o.put("junoHpf", p.junoHpf.toDouble()); o.put("junoVcfCutoff", p.junoVcfCutoff.toDouble()); o.put("junoVcfRes", p.junoVcfRes.toDouble())
            o.put("junoEnvAmt", p.junoEnvAmt.toDouble()); o.put("junoChorus", p.junoChorus)
            arr.put(o)
        }
        root.put("presets", arr)
        prefs.edit().putString("saved_synth_presets_v3_json", root.toString()).commit()
    }

    fun saveCurrentStateToActivePresetExplicitly() {
        val idx = presetsList.indexOfFirst { it.id == currentPresetId }
        if (idx != -1) {
            val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
            presetsList[idx] = presetsList[idx].copy(
                synthType = activeSynthType,
                osc1Vol = osc1Vol, osc1Tune = osc1Tune, osc1Wave = osc1Wave,
                osc2Vol = osc2Vol, osc2Tune = osc2Tune, osc2Wave = osc2Wave,
                osc3Vol = osc3Vol, osc3Tune = osc3Tune, osc3Wave = osc3Wave,
                cutoffNorm = cutoffNorm, resNorm = resNorm, envAmtNorm = envAmtNorm,
                fAttack = fAttack, fDecay = fDecay, fSustain = fSustain, fRelease = fRelease,
                lfoRateNorm = lfoRateNorm, lfoDepthNorm = lfoDepthNorm,
                delayTimeNorm = delayTimeNorm, delayFbNorm = delayFbNorm, delayMixNorm = delayMixNorm,
                aAttack = aAttack, aDecay = aDecay, aSustain = aSustain, aRelease = aRelease,
                masterNorm = masterNorm,
                junoRange = junoRange, junoPulseVol = junoPulseVol, junoSawVol = junoSawVol,
                junoSubVol = junoSubVol, junoNoiseVol = junoNoiseVol, junoPwm = junoPwm,
                junoHpf = junoHpf, junoVcfCutoff = junoVcfCutoff, junoVcfRes = junoVcfRes,
                junoEnvAmt = junoEnvAmt, junoChorus = junoChorus
            )
            savePresetsToDisk(presetsList, currentPresetId)
            isPresetDirty = false
        }
    }

    fun applyPresetToEngine(p: SynthPreset) {
        currentPresetId = p.id
        isPresetDirty = false

        if (p.synthType == 0) {
            if (currentLayer == 0) synthTypeA = 0 else synthTypeB = 0
            osc1Vol = p.osc1Vol; osc1Tune = p.osc1Tune; osc1Wave = p.osc1Wave
            osc2Vol = p.osc2Vol; osc2Tune = p.osc2Tune; osc2Wave = p.osc2Wave
            osc3Vol = p.osc3Vol; osc3Tune = p.osc3Tune; osc3Wave = p.osc3Wave
            cutoffNorm = p.cutoffNorm; resNorm = p.resNorm; envAmtNorm = p.envAmtNorm
            
            audioEngine.setOscVolume(0, osc1Vol); audioEngine.setOscTune(0, normToSemitones(osc1Tune)); audioEngine.setOscWave(0, osc1Wave)
            audioEngine.setOscVolume(1, osc2Vol); audioEngine.setOscTune(1, normToSemitones(osc2Tune)); audioEngine.setOscWave(1, osc2Wave)
            audioEngine.setOscVolume(2, osc3Vol); audioEngine.setOscTune(2, normToSemitones(osc3Tune)); audioEngine.setOscWave(2, osc3Wave)
            audioEngine.setFilterCutoff(normToCutoffHz(cutoffNorm))
            audioEngine.setFilterResonance(resNorm)
            audioEngine.setFilterEnvAmount(envAmtNorm * 5000f)
        } else {
            if (currentLayer == 0) synthTypeA = 1 else synthTypeB = 1
            junoRange = p.junoRange; junoPulseVol = p.junoPulseVol; junoSawVol = p.junoSawVol
            junoSubVol = p.junoSubVol; junoNoiseVol = p.junoNoiseVol; junoPwm = p.junoPwm
            junoHpf = p.junoHpf; junoVcfCutoff = p.junoVcfCutoff; junoVcfRes = p.junoVcfRes
            junoEnvAmt = p.junoEnvAmt; junoChorus = p.junoChorus
            
            audioEngine.setJunoRange(junoRange)
            audioEngine.setJunoPulseVol(junoPulseVol)
            audioEngine.setJunoSawVol(junoSawVol)
            audioEngine.setJunoSubVol(junoSubVol)
            audioEngine.setJunoNoiseVol(junoNoiseVol)
            audioEngine.setJunoPwm(junoPwm)
            audioEngine.setJunoHpf(normToCutoffHz(junoHpf))
            audioEngine.setJunoVcfCutoff(normToCutoffHz(junoVcfCutoff))
            audioEngine.setJunoVcfRes(junoVcfRes)
            audioEngine.setJunoEnvAmt(junoEnvAmt * 5.0f)
            audioEngine.setJunoChorus(junoChorus)
        }

        fAttack = p.fAttack; fDecay = p.fDecay; fSustain = p.fSustain; fRelease = p.fRelease
        if (p.synthType == 1) {
            fAttack = p.aAttack; fDecay = p.aDecay; fSustain = p.aSustain; fRelease = p.aRelease
        }
        lfoRateNorm = p.lfoRateNorm; lfoDepthNorm = p.lfoDepthNorm
        delayTimeNorm = p.delayTimeNorm; delayFbNorm = p.delayFbNorm; delayMixNorm = p.delayMixNorm
        aAttack = p.aAttack; aDecay = p.aDecay; aSustain = p.aSustain; aRelease = p.aRelease
        masterNorm = p.masterNorm

        audioEngine.setFilterADSR(fAttack * 2f, fDecay * 2f, fSustain, fRelease * 3f)
        audioEngine.setLfoRate(0.1f + lfoRateNorm * 19.9f)
        audioEngine.setLfoDepth(lfoDepthNorm * 3000f)
        audioEngine.setDelayParams(0.05f + delayTimeNorm * 0.95f, delayFbNorm, delayMixNorm)
        audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
        audioEngine.setMasterVolume(masterNorm)

        savePresetsToDisk(presetsList, currentPresetId)
    }

    fun saveSlotsToDisk(slots: List<MidiMappingSlot>, activeId: String) {
        val root = JSONObject()
        root.put("activeSlotId", activeId)
        val arr = JSONArray()
        slots.forEach { slot ->
            val obj = JSONObject()
            obj.put("id", slot.id)
            obj.put("name", slot.name)
            val mapObj = JSONObject()
            slot.mappings.forEach { (k, v) -> mapObj.put(k, v) }
            obj.put("mappings", mapObj)
            arr.put(obj)
        }
        root.put("slots", arr)
        prefs.edit().putString("saved_midi_slots_json", root.toString()).commit()
    }

    fun syncCurrentMapToActiveSlot() {
        val snapshotMap = paramToCCMap.toMap()
        val idx = midiSlots.indexOfFirst { it.id == currentSlotId }
        if (idx != -1) {
            midiSlots[idx] = midiSlots[idx].copy(mappings = snapshotMap)
            saveSlotsToDisk(midiSlots, currentSlotId)
        } else if (midiSlots.isNotEmpty()) {
            val fallbackSlot = midiSlots.first()
            currentSlotId = fallbackSlot.id
            midiSlots[0] = fallbackSlot.copy(mappings = snapshotMap)
            saveSlotsToDisk(midiSlots, currentSlotId)
        }
    }

    fun selectSlot(slot: MidiMappingSlot) {
        if (currentSlotId.isNotEmpty() && currentSlotId != slot.id) {
            val prevIdx = midiSlots.indexOfFirst { it.id == currentSlotId }
            if (prevIdx != -1) {
                midiSlots[prevIdx] = midiSlots[prevIdx].copy(mappings = paramToCCMap.toMap())
            }
        }
        currentSlotId = slot.id
        paramToCCMap.clear()
        paramToCCMap.putAll(slot.mappings)
        saveSlotsToDisk(midiSlots, slot.id)
    }

    LaunchedEffect(Unit) {
        val rawDspJson = prefs.getString("saved_engine_dsp_settings_v3_json", null)
        if (rawDspJson != null) {
            try {
                val o = JSONObject(rawDspJson)
                oversamplingFactor = o.optInt("oversamplingFactor", 2)
                analogDrift = o.optDouble("analogDrift", 0.05).toFloat()
                filterDrive = o.optDouble("filterDrive", 0.0).toFloat()
                filterKeyTrack = o.optDouble("filterKeyTrack", 0.50).toFloat()
                stereoSpread = o.optDouble("stereoSpread", 0.15).toFloat()
                velocitySens = o.optDouble("velocitySens", 0.35).toFloat()
                maxPolyphony = o.optInt("maxPolyphony", 16)
                bufferBurstMult = o.optInt("bufferBurstMult", 2)
                paramSmoothing = o.optBoolean("paramSmoothing", true)
            } catch (e: Exception) {}
        }
        applyEngineSettingsToNative()

        val rawSlotsJson = prefs.getString("saved_midi_slots_json", null)
        if (rawSlotsJson != null) {
            try {
                val root = JSONObject(rawSlotsJson)
                val savedActiveId = root.optString("activeSlotId", "")
                val arr = root.optJSONArray("slots")
                val loaded = mutableListOf<MidiMappingSlot>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "")
                        val name = obj.optString("name", "Slot ${i + 1}")
                        if (id.isEmpty()) continue
                        val mapObj = obj.optJSONObject("mappings")
                        val map = mutableMapOf<String, Int>()
                        if (mapObj != null) {
                            val keys = mapObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                map[key] = mapObj.optInt(key, -1)
                            }
                            map.entries.removeAll { it.value < 0 }
                        }
                        loaded.add(MidiMappingSlot(id, name, map))
                    }
                }
                if (loaded.isNotEmpty()) {
                    midiSlots.clear()
                    midiSlots.addAll(loaded)
                    val active = loaded.find { it.id == savedActiveId } ?: loaded.first()
                    currentSlotId = active.id
                    paramToCCMap.clear()
                    paramToCCMap.putAll(active.mappings)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (midiSlots.isEmpty()) {
            val defaultSlot = MidiMappingSlot(
                name = "Launchkey MK4",
                mappings = getDefaultLaunchkeyMK4Mappings()
            )
            midiSlots.add(defaultSlot)
            currentSlotId = defaultSlot.id
            paramToCCMap.clear()
            paramToCCMap.putAll(defaultSlot.mappings)
            saveSlotsToDisk(midiSlots, defaultSlot.id)
        }

        val rawPresetsJson = prefs.getString("saved_synth_presets_v3_json", null)
        if (rawPresetsJson != null) {
            try {
                val root = JSONObject(rawPresetsJson)
                val savedActivePresetId = root.optString("activePresetId", "")
                val arr = root.getJSONArray("presets")
                val loadedPresets = mutableListOf<SynthPreset>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    loadedPresets.add(
                        SynthPreset(
                            id = o.getString("id"), name = o.getString("name"),
                            osc1Vol = o.getDouble("osc1Vol").toFloat(), osc1Tune = o.getDouble("osc1Tune").toFloat(), osc1Wave = o.getInt("osc1Wave"),
                            osc2Vol = o.getDouble("osc2Vol").toFloat(), osc2Tune = o.getDouble("osc2Tune").toFloat(), osc2Wave = o.getInt("osc2Wave"),
                            osc3Vol = o.getDouble("osc3Vol").toFloat(), osc3Tune = o.getDouble("osc3Tune").toFloat(), osc3Wave = o.getInt("osc3Wave"),
                            cutoffNorm = o.getDouble("cutoffNorm").toFloat(), resNorm = o.getDouble("resNorm").toFloat(), envAmtNorm = o.getDouble("envAmtNorm").toFloat(),
                            fAttack = o.getDouble("fAttack").toFloat(), fDecay = o.getDouble("fDecay").toFloat(), fSustain = o.getDouble("fSustain").toFloat(), fRelease = o.getDouble("fRelease").toFloat(),
                            lfoRateNorm = o.getDouble("lfoRateNorm").toFloat(), lfoDepthNorm = o.getDouble("lfoDepthNorm").toFloat(),
                            delayTimeNorm = o.getDouble("delayTimeNorm").toFloat(), delayFbNorm = o.getDouble("delayFbNorm").toFloat(), delayMixNorm = o.getDouble("delayMixNorm").toFloat(),
                            aAttack = o.getDouble("aAttack").toFloat(), aDecay = o.getDouble("aDecay").toFloat(), aSustain = o.getDouble("aSustain").toFloat(), aRelease = o.getDouble("aRelease").toFloat(),
                            masterNorm = o.getDouble("masterNorm").toFloat(),
                            synthType = o.optInt("synthType", 0),
                            junoRange = o.optInt("junoRange", 1),
                            junoPulseVol = o.optDouble("junoPulseVol", 0.8).toFloat(),
                            junoSawVol = o.optDouble("junoSawVol", 0.8).toFloat(),
                            junoSubVol = o.optDouble("junoSubVol", 0.5).toFloat(),
                            junoNoiseVol = o.optDouble("junoNoiseVol", 0.0).toFloat(),
                            junoPwm = o.optDouble("junoPwm", 0.5).toFloat(),
                            junoHpf = o.optDouble("junoHpf", 0.0).toFloat(),
                            junoVcfCutoff = o.optDouble("junoVcfCutoff", 0.6).toFloat(),
                            junoVcfRes = o.optDouble("junoVcfRes", 0.2).toFloat(),
                            junoEnvAmt = o.optDouble("junoEnvAmt", 0.5).toFloat(),
                            junoChorus = o.optInt("junoChorus", 0)
                        )
                    )
                }
                if (loadedPresets.isNotEmpty()) {
                    presetsList.clear(); presetsList.addAll(loadedPresets)
                    val activePreset = loadedPresets.find { it.id == savedActivePresetId } ?: loadedPresets.first()
                    applyPresetToEngine(activePreset)
                }
            } catch (e: Exception) {}
        }
        if (presetsList.isEmpty()) {
            val factory = getFactoryPresets()
            presetsList.addAll(factory); applyPresetToEngine(factory.first())
        }
    }

    val currentSlotName = midiSlots.find { it.id == currentSlotId }?.name ?: "Slot 1"
    val currentPresetName = presetsList.find { it.id == currentPresetId }?.name ?: "Preset 1"

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
            "A_ATK" -> { 
                aAttack = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                if (activeSynthType == 1) { fAttack = v; audioEngine.setFilterADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            }
            "A_DEC" -> { 
                aDecay = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                if (activeSynthType == 1) { fDecay = v; audioEngine.setFilterADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            }
            "A_SUS" -> { 
                aSustain = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                if (activeSynthType == 1) { fSustain = v; audioEngine.setFilterADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            }
            "A_REL" -> { 
                aRelease = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f)
                val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                if (activeSynthType == 1) { fRelease = v; audioEngine.setFilterADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            }
            "MASTER" -> { masterNorm = v; audioEngine.setMasterVolume(v) }
            "JUNO_PULSE" -> { junoPulseVol = v; audioEngine.setJunoPulseVol(v) }
            "JUNO_SAW" -> { junoSawVol = v; audioEngine.setJunoSawVol(v) }
            "JUNO_SUB" -> { junoSubVol = v; audioEngine.setJunoSubVol(v) }
            "JUNO_NOISE" -> { junoNoiseVol = v; audioEngine.setJunoNoiseVol(v) }
            "JUNO_PWM" -> { junoPwm = v; audioEngine.setJunoPwm(v) }
            "JUNO_HPF" -> { junoHpf = v; audioEngine.setJunoHpf(normToCutoffHz(v)) }
            "JUNO_CUTOFF" -> { junoVcfCutoff = v; audioEngine.setJunoVcfCutoff(normToCutoffHz(v)) }
            "JUNO_RES" -> { junoVcfRes = v; audioEngine.setJunoVcfRes(v) }
            "JUNO_ENV" -> { junoEnvAmt = v; audioEngine.setJunoEnvAmt(v * 5.0f) }
        }
        isPresetDirty = true
    }

    LaunchedEffect(incomingMidiCC) {
        incomingMidiCC?.let { event ->
            val norm = (event.value / 127f).coerceIn(0f, 1f)
            if (isMidiLearnMode && selectedParamForLearn != null) {
                val targetParam = selectedParamForLearn!!
                val previousKeys = paramToCCMap.filterValues { it == event.cc }.keys.toList()
                previousKeys.forEach { paramToCCMap.remove(it) }

                paramToCCMap[targetParam] = event.cc
                updateParamById(targetParam, norm)
                syncCurrentMapToActiveSlot()
                selectedParamForLearn = null
            } else {
                paramToCCMap.forEach { (paramId, mappedCC) ->
                    if (mappedCC == event.cc) updateParamById(paramId, norm)
                }
            }
        }
    }

    @Composable
    fun SmartKnob(paramId: String, label: String, valueText: String, value: Float) {
        KnobControl(
            label = label, valueText = valueText, value = value,
            isLearningSelected = (isMidiLearnMode && selectedParamForLearn == paramId),
            mappedCC = if (isMidiLearnMode) paramToCCMap[paramId] else null,
            onUnmap = { paramToCCMap.remove(paramId); syncCurrentMapToActiveSlot() },
            onKnobClick = { if (isMidiLearnMode) selectedParamForLearn = paramId },
            onValueChange = { newVal ->
                if (isMidiLearnMode) selectedParamForLearn = paramId else updateParamById(paramId, newVal)
            }
        )
    }

    val waveformBuffer = remember { FloatArray(256) }
    var frameTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var statsTick = 0
        while (true) {
            audioEngine.getWaveform(waveformBuffer)
            frameTrigger++
            if (++statsTick >= 10) {
                statsTick = 0
                audioEngine.getEngineStats(engineStatsBuffer)
                statSampleRate = engineStatsBuffer[0].toInt()
                statBurstFrames = engineStatsBuffer[1].toInt()
                statBufferFrames = engineStatsBuffer[2].toInt()
                statLatencyMs = engineStatsBuffer[3]
                statXRuns = engineStatsBuffer[4].toInt()
                statActiveVoices = engineStatsBuffer[5].toInt()
            }
            delay(16L)
        }
    }

    if (showAudioSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showAudioSettingsDialog = false },
            containerColor = Color(0xFF14171C),
            modifier = Modifier.fillMaxWidth(0.9f),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚙️ MOTOR DE ÁUDIO & DSP (PURE ANALOG)",
                            color = Color(0xFFF59E0B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                                .clickable {
                                    oversamplingFactor = 2; analogDrift = 0.05f; filterDrive = 0.0f
                                    filterKeyTrack = 0.50f; stereoSpread = 0.15f; velocitySens = 0.35f
                                    maxPolyphony = 16; bufferBurstMult = 2; paramSmoothing = true
                                    applyEngineSettingsToNative()
                                    saveEngineSettingsToDisk()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("↺ MODO PURO (RESET)", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Caminho de Sinal Linear 64-Bit + Filtro Moog TPT Zero-Delay Feedback",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0D10), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryBadge("SAMPLE RATE", "${statSampleRate} Hz", Color(0xFF10B981))
                        TelemetryBadge("DSP INTERNO", "${(statSampleRate * oversamplingFactor) / 1000} kHz", Color(0xFFF59E0B))
                        TelemetryBadge("LATÊNCIA OBOE", "${"%.1f".format(statLatencyMs)} ms", Color(0xFF38BDF8))
                        TelemetryBadge("VOZES ATIVAS", "$statActiveVoices / $maxPolyphony", Color(0xFFE2E8F0))
                        TelemetryBadge("FALHAS", "$statXRuns", if (statXRuns == 0) Color(0xFF10B981) else Color(0xFFEF4444))
                    }

                    Text("1. CARÁTER ANALÓGICO & MODELAGEM FÍSICA", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2026), RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("OVERSAMPLING DO FILTRO MOOG LADDER", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "1x (Nativo)", 2 to "2x (Studio Pure)", 4 to "4x (Ultra HD)").forEach { (factor, title) ->
                                val selected = (oversamplingFactor == factor)
                                Box(modifier = Modifier.weight(1f).background(if (selected) Color(0xFFF59E0B) else Color(0xFF0F1115), RoundedCornerShape(6.dp)).border(1.dp, if (selected) Color(0xFFFDE047) else Color(0xFF334155), RoundedCornerShape(6.dp)).clickable { oversamplingFactor = factor; audioEngine.setOversampling(factor); saveEngineSettingsToDisk() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(title, color = if (selected) Color.Black else Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    DspSliderRow("ANALOG DRIFT (INSTABILIDADE VCO)", "0% = Precisão absoluta | Acima = Micro-flutuação", analogDrift, "${(analogDrift * 100).toInt()}%") { analogDrift = it; audioEngine.setAnalogDrift(it); saveEngineSettingsToDisk() }
                    DspSliderRow("FILTER DRIVE (SATURAÇÃO DE ENTRADA)", "0% = 100% Linear e Puro (Sem achatar a onda)", filterDrive, "${(filterDrive * 100).toInt()}%") { filterDrive = it; audioEngine.setFilterDrive(it); saveEngineSettingsToDisk() }
                    DspSliderRow("KEYBOARD TRACKING (1V/OCT)", "Filtro acompanha harmonicamente a nota tocada", filterKeyTrack, "${(filterKeyTrack * 100).toInt()}%") { filterKeyTrack = it; audioEngine.setFilterKeyTrack(it); saveEngineSettingsToDisk() }
                    DspSliderRow("ABERTURA ESTÉREO (CONSTANT-POWER PAN)", "Espacialização pura sem defasagem de tempo", stereoSpread, "${(stereoSpread * 100).toInt()}%") { stereoSpread = it; audioEngine.setStereoSpread(it); saveEngineSettingsToDisk() }
                    DspSliderRow("SENSIBILIDADE AO VELOCITY MIDI", "Força nas teclas afeta volume/brilho", velocitySens, "${(velocitySens * 100).toInt()}%") { velocitySens = it; audioEngine.setVelocitySensitivity(it); saveEngineSettingsToDisk() }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("2. PERFORMANCE DE HARDWARE & BUFFER OBOE", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2026), RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("PERFIL DE BUFFER DE ÁUDIO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "Ultra-Baixa", 2 to "Equilibrada", 4 to "Estável").forEach { (mult, label) ->
                                val selected = (bufferBurstMult == mult)
                                Box(modifier = Modifier.weight(1f).background(if (selected) Color(0xFF0284C7) else Color(0xFF0F1115), RoundedCornerShape(6.dp)).border(1.dp, if (selected) Color(0xFF7DD3FC) else Color(0xFF334155), RoundedCornerShape(6.dp)).clickable { bufferBurstMult = mult; audioEngine.setBufferBurstMultiplier(mult); saveEngineSettingsToDisk() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2026), RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("LIMITE DE POLIFONIA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(4, 8, 12, 16).forEach { voicesCount ->
                                val selected = (maxPolyphony == voicesCount)
                                Box(modifier = Modifier.weight(1f).background(if (selected) Color(0xFF0284C7) else Color(0xFF0F1115), RoundedCornerShape(6.dp)).border(1.dp, if (selected) Color(0xFF7DD3FC) else Color(0xFF334155), RoundedCornerShape(6.dp)).clickable { maxPolyphony = voicesCount; audioEngine.setMaxPolyphony(voicesCount); saveEngineSettingsToDisk() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(if (voicesCount == 4) "4 (Poly-D Real)" else "$voicesCount Vozes", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2026), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SUAVIZAÇÃO DE KNOBS (ANTI-ZIPPER)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Elimina degraus digitais no Cutoff e Volumes via MIDI CC.", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }
                        Switch(checked = paramSmoothing, onCheckedChange = { paramSmoothing = it; audioEngine.setParameterSmoothing(it); saveEngineSettingsToDisk() }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF59E0B), checkedTrackColor = Color(0xFF78350F)))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioSettingsDialog = false }) {
                    Text("FECHAR PAINEL", color = Color(0xFFF59E0B), fontWeight = FontWeight.ExtraBold)
                }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Color(0xFF181B20),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = {
                Text(
                    text = when {
                        dialogTargetIsPreset && isCreatingNewItem -> "Salvar Como Novo Preset"
                        dialogTargetIsPreset && !isCreatingNewItem -> "Renomear Preset Atual"
                        !dialogTargetIsPreset && isCreatingNewItem -> "Novo Slot de Controlador MIDI"
                        else -> "Renomear Slot MIDI Atual"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (dialogTargetIsPreset) "Digite o nome do Timbre/Patch:" else "Digite o nome do controlador ou perfil MIDI (ex: Launchkey MK4):",
                        fontSize = 12.sp, color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInputText, onValueChange = { nameInputText = it }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFFF59E0B), unfocusedBorderColor = Color(0xFF334155), cursorColor = Color(0xFFF59E0B))
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName = nameInputText.trim().ifEmpty { if (dialogTargetIsPreset) "Custom Patch" else "Controlador MIDI" }
                        if (dialogTargetIsPreset) {
                            if (isCreatingNewItem) {
                                val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                                val newPreset = SynthPreset(
                                    name = finalName, synthType = activeSynthType, 
                                    osc1Vol = osc1Vol, osc1Tune = osc1Tune, osc1Wave = osc1Wave, osc2Vol = osc2Vol, osc2Tune = osc2Tune, osc2Wave = osc2Wave, osc3Vol = osc3Vol, osc3Tune = osc3Tune, osc3Wave = osc3Wave,
                                    cutoffNorm = cutoffNorm, resNorm = resNorm, envAmtNorm = envAmtNorm, fAttack = fAttack, fDecay = fDecay, fSustain = fSustain, fRelease = fRelease, lfoRateNorm = lfoRateNorm, lfoDepthNorm = lfoDepthNorm,
                                    delayTimeNorm = delayTimeNorm, delayFbNorm = delayFbNorm, delayMixNorm = delayMixNorm, aAttack = aAttack, aDecay = aDecay, aSustain = aSustain, aRelease = aRelease, masterNorm = masterNorm,
                                    junoRange = junoRange, junoPulseVol = junoPulseVol, junoSawVol = junoSawVol, junoSubVol = junoSubVol, junoNoiseVol = junoNoiseVol, junoPwm = junoPwm,
                                    junoHpf = junoHpf, junoVcfCutoff = junoVcfCutoff, junoVcfRes = junoVcfRes, junoEnvAmt = junoEnvAmt, junoChorus = junoChorus
                                )
                                presetsList.add(newPreset); applyPresetToEngine(newPreset)
                            } else {
                                val idx = presetsList.indexOfFirst { it.id == currentPresetId }
                                if (idx != -1) { presetsList[idx] = presetsList[idx].copy(name = finalName); savePresetsToDisk(presetsList, currentPresetId) }
                            }
                        } else {
                            if (isCreatingNewItem) {
                                val newSlot = MidiMappingSlot(name = finalName, mappings = emptyMap())
                                midiSlots.add(newSlot); selectSlot(newSlot)
                            } else {
                                val idx = midiSlots.indexOfFirst { it.id == currentSlotId }
                                if (idx != -1) {
                                    midiSlots[idx] = midiSlots[idx].copy(name = finalName, mappings = paramToCCMap.toMap())
                                    saveSlotsToDisk(midiSlots, currentSlotId)
                                }
                            }
                        }
                        showNameDialog = false
                    }
                ) { Text("SALVAR", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("CANCELAR", color = Color.Gray) }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115)).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("MOOG POLY-D NATIVE", color = Color(0xFFF59E0B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isMidiLearnMode) {
                        if (selectedParamForLearn == null) "MIDI LEARN [$currentSlotName]: Toque num Knob..." else "GIRAR HARDWARE -> [$selectedParamForLearn]"
                    } else if (isPresetDirty) {
                        "TIMBRE EDITADO (Não Salvo) | Clique em SALVAR"
                    } else {
                        "TIMBRE: $currentPresetName | DSP: ${oversamplingFactor}x PURE LINEAR"
                    },
                    color = if (isMidiLearnMode || isPresetDirty) Color(0xFFFBBF24) else Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)
            Spacer(modifier = Modifier.width(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF10B981).copy(alpha = 0.7f), RoundedCornerShape(6.dp)).clickable { showAudioSettingsDialog = true }.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text("⚙️ ÁUDIO & DSP (${oversamplingFactor}x)", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(6.dp)).border(1.dp, if (isPresetDirty) Color(0xFFFDE047) else Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(6.dp))) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isPresetDropdownExpanded = true }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("🎹 $currentPresetName" + if (isPresetDirty) " *" else "", color = if (isPresetDirty) Color(0xFFFDE047) else Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", color = Color(0xFFF59E0B), fontSize = 10.sp)
                        }
                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))
                        Box(modifier = Modifier.background(if (isPresetDirty) Color(0xFFD97706) else Color.Transparent).clickable { saveCurrentStateToActivePresetExplicitly() }.padding(horizontal = 9.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("💾 SALVAR", color = if (isPresetDirty) Color.White else Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))
                        Box(modifier = Modifier.clickable { nameInputText = currentPresetName; dialogTargetIsPreset = true; isCreatingNewItem = false; showNameDialog = true }.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("✎", color = Color(0xFFF59E0B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    DropdownMenu(expanded = isPresetDropdownExpanded, onDismissRequest = { isPresetDropdownExpanded = false }, modifier = Modifier.background(Color(0xFF1E2229)).border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(6.dp)).width(275.dp)) {
                        val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
                        presetsList.filter { it.synthType == activeSynthType }.forEach { preset ->
                            val isSelected = (preset.id == currentPresetId)
                            DropdownMenuItem(
                                text = {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(preset.name, color = if (isSelected) Color(0xFFF59E0B) else Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                        Box(modifier = Modifier.size(22.dp).background(Color(0xFF7F1D1D), CircleShape).clickable { if (presetsList.size > 1) { val removingActive = (preset.id == currentPresetId); presetsList.remove(preset); if (removingActive) applyPresetToEngine(presetsList.first()) else savePresetsToDisk(presetsList, currentPresetId) } }, contentAlignment = Alignment.Center) {
                                            Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                onClick = { applyPresetToEngine(preset); isPresetDropdownExpanded = false }
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))
                        DropdownMenuItem(text = { Text("+ SALVAR COMO NOVO PRESET...", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold) }, onClick = { isPresetDropdownExpanded = false; nameInputText = "User Patch ${presetsList.size + 1}"; dialogTargetIsPreset = true; isCreatingNewItem = true; showNameDialog = true })
                        DropdownMenuItem(text = { Text("↺ RESTAURAR PRESETS DE FÁBRICA PUROS", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }, onClick = { isPresetDropdownExpanded = false; val factory = getFactoryPresets(); presetsList.clear(); presetsList.addAll(factory); applyPresetToEngine(factory.first()) })
                    }
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isSlotDropdownExpanded = true }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("🎛️ $currentSlotName", color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", color = Color(0xFF38BDF8), fontSize = 10.sp)
                        }
                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))
                        Box(modifier = Modifier.clickable { nameInputText = currentSlotName; dialogTargetIsPreset = false; isCreatingNewItem = false; showNameDialog = true }.padding(horizontal = 9.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("✎", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    DropdownMenu(expanded = isSlotDropdownExpanded, onDismissRequest = { isSlotDropdownExpanded = false }, modifier = Modifier.background(Color(0xFF1E2229)).border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp)).width(250.dp)) {
                        midiSlots.forEach { slot ->
                            val isSelected = (slot.id == currentSlotId)
                            DropdownMenuItem(
                                text = {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(slot.name, color = if (isSelected) Color(0xFF38BDF8) else Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                        Box(modifier = Modifier.size(22.dp).background(Color(0xFF7F1D1D), CircleShape).clickable { if (midiSlots.size > 1) { val removingActive = (slot.id == currentSlotId); midiSlots.remove(slot); if (removingActive) selectSlot(midiSlots.first()) else saveSlotsToDisk(midiSlots, currentSlotId) } else { paramToCCMap.clear(); syncCurrentMapToActiveSlot() } }, contentAlignment = Alignment.Center) {
                                            Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                onClick = { selectSlot(slot); isSlotDropdownExpanded = false }
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))
                        DropdownMenuItem(text = { Text("+ NOVO SLOT DE CONTROLADOR...", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold) }, onClick = { isSlotDropdownExpanded = false; nameInputText = "Controlador ${midiSlots.size + 1}"; dialogTargetIsPreset = false; isCreatingNewItem = true; showNameDialog = true })
                        DropdownMenuItem(text = { Text("↺ RESTAURAR PADRÃO LAUNCHKEY MK4", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }, onClick = {
                            isSlotDropdownExpanded = false
                            val defaultSlot = MidiMappingSlot(name = "Launchkey MK4", mappings = getDefaultLaunchkeyMK4Mappings())
                            val existingIdx = midiSlots.indexOfFirst { it.name == "Launchkey MK4" }
                            if (existingIdx != -1) {
                                midiSlots[existingIdx] = defaultSlot
                            } else {
                                midiSlots.add(0, defaultSlot)
                            }
                            selectSlot(defaultSlot)
                        })
                    }
                }
                Box(modifier = Modifier.background(if (isMidiLearnMode) Color(0xFFD97706) else Color(0xFF1E293B), RoundedCornerShape(6.dp)).border(1.dp, if (isMidiLearnMode) Color(0xFFFDE047) else Color(0xFF38BDF8), RoundedCornerShape(6.dp)).clickable { isMidiLearnMode = !isMidiLearnMode; selectedParamForLearn = null }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (isMidiLearnMode) "SAIR DO LEARN" else "MIDI LEARN", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.background(Color(0xFF991B1B), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp)).clickable { audioEngine.allNotesOff() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("PANIC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val activeSynthType = if (currentLayer == 0) synthTypeA else synthTypeB
        
        LaunchedEffect(activeSynthType) {
            audioEngine.setSynthType(activeSynthType)
            val lastPreset = presetsList.find { it.synthType == activeSynthType }
            if (lastPreset != null && currentPresetId != lastPreset.id) {
                applyPresetToEngine(lastPreset)
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

        if (activeSynthType == 0) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ModuleBox(title = "OSCILADORES 64-BIT (POLYBLEP/BLAMP)") {
                OscillatorRow(label = "OSC 1", wave = osc1Wave, onWaveChange = { osc1Wave = it; audioEngine.setOscWave(0, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC1_VOL", "LEVEL", "${(osc1Vol * 100).toInt()}%", osc1Vol) }, tuneKnob = { val st = normToSemitones(osc1Tune).toInt(); SmartKnob("OSC1_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc1Tune) })
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(label = "OSC 2", wave = osc2Wave, onWaveChange = { osc2Wave = it; audioEngine.setOscWave(1, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC2_VOL", "LEVEL", "${(osc2Vol * 100).toInt()}%", osc2Vol) }, tuneKnob = { val st = normToSemitones(osc2Tune).toInt(); SmartKnob("OSC2_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc2Tune) })
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(label = "OSC 3", wave = osc3Wave, onWaveChange = { osc3Wave = it; audioEngine.setOscWave(2, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC3_VOL", "LEVEL", "${(osc3Vol * 100).toInt()}%", osc3Vol) }, tuneKnob = { val st = normToSemitones(osc3Tune).toInt(); SmartKnob("OSC3_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc3Tune) })
            }

            ModuleBox(title = "FILTRO MOOG 24dB TPT (LINEAR-CORE)") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SmartKnob("CUTOFF", "CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm)
                    SmartKnob("RESONANCE", "RESSON.", "${(resNorm * 100).toInt()}%", resNorm)
                    SmartKnob("ENV_AMT", "ENV AMT", "${"%.1f".format(envAmtNorm * 4.5f)} Oct", envAmtNorm)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("ENVELOPE RC EXPONENCIAL (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("F_ATK", "ATK", "${(fAttack * 2000).toInt()}ms", fAttack)
                    SmartKnob("F_DEC", "DEC", "${(fDecay * 2000).toInt()}ms", fDecay)
                    SmartKnob("F_SUS", "SUS", "${(fSustain * 100).toInt()}%", fSustain)
                    SmartKnob("F_REL", "REL", "${(fRelease * 3000).toInt()}ms", fRelease)
                }
            }

            ModuleBox(title = "MODULAÇÃO (LFO) & MASTER DELAY") {
                Text("LFO -> FILTRO MOOG (1V/OCT)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    SmartKnob("LFO_RATE", "LFO RATE", "${"%.1f".format(0.1f + lfoRateNorm * 19.9f)} Hz", lfoRateNorm)
                    SmartKnob("LFO_DEPTH", "LFO DEPTH", "${(lfoDepthNorm * 100).toInt()}%", lfoDepthNorm)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("PARALLEL SEND TAPE DELAY (DRY 100% PURO)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("DELAY_TIME", "TIME", "${((0.05f + delayTimeNorm * 0.95f) * 1000).toInt()}ms", delayTimeNorm)
                    SmartKnob("DELAY_FB", "FEEDBACK", "${(delayFbNorm * 100).toInt()}%", delayFbNorm)
                    SmartKnob("DELAY_MIX", "MIX", "${(delayMixNorm * 100).toInt()}%", delayMixNorm)
                }
            }

            ModuleBox(title = "AMPLIFICADOR (VCA) & MASTER") {
                Text("ENVELOPE DE VOLUME RC (ADSR)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            } // Fechamento do if do moog row
        } else {
            // ROLAND JUNO-6 NATIVE UI
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
                // MODULAÇÃO (LFO) E DELAY
                ModuleBox(title = "MODULAÇÃO & DELAY") {
                    Text("LFO GLOBAL (DCO & PWM)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        SmartKnob("LFO_RATE", "LFO RATE", "${"%.1f".format(0.1f + lfoRateNorm * 19.9f)} Hz", lfoRateNorm)
                        SmartKnob("LFO_DEPTH", "LFO DEPTH", "${(lfoDepthNorm * 100).toInt()}%", lfoDepthNorm)
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text("PARALLEL SEND TAPE DELAY", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SmartKnob("DELAY_TIME", "TIME", "${((0.05f + delayTimeNorm * 0.95f) * 1000).toInt()}ms", delayTimeNorm)
                        SmartKnob("DELAY_FB", "FEEDBACK", "${(delayFbNorm * 100).toInt()}%", delayFbNorm)
                        SmartKnob("DELAY_MIX", "MIX", "${(delayMixNorm * 100).toInt()}%", delayMixNorm)
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
                    Spacer(modifier = Modifier.height(22.dp))
                    SmartKnob("MASTER", "VOLUME MASTER", "${(masterNorm * 100).toInt()}%", masterNorm)
                }
            }
        } // Fechamento if synth selector
    }
}

@Composable
fun TelemetryBadge(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun DspSliderRow(title: String, subtitle: String, value: Float, valueLabel: String, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2026), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = valueLabel, color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 9.sp)
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Color(0xFFF59E0B), activeTrackColor = Color(0xFFF59E0B), inactiveTrackColor = Color(0xFF0F1115)))
    }
}

@Composable
fun KnobControl(label: String, valueText: String, value: Float, isLearningSelected: Boolean = false, mappedCC: Int? = null, onUnmap: () -> Unit = {}, onKnobClick: () -> Unit = {}, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            Knob(value = value, onValueChange = onValueChange, isLearningSelected = isLearningSelected, onKnobClick = onKnobClick)
            if (mappedCC != null) {
                Row(modifier = Modifier.offset(x = 6.dp, y = (-4).dp).background(Color(0xFF0284C7), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF7DD3FC), RoundedCornerShape(10.dp)).padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("CC $mappedCC", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                    Box(modifier = Modifier.size(12.dp).background(Color(0xFF0369A1), CircleShape).clickable { onUnmap() }, contentAlignment = Alignment.Center) { Text("×", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = if (isLearningSelected) Color(0xFFFDE047) else Color.LightGray, fontSize = 11.sp, fontWeight = if (isLearningSelected) FontWeight.Bold else FontWeight.Medium)
        Text(valueText, color = Color(0xFFF59E0B), fontSize = 10.sp)
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
fun OscilloscopeView(waveform: FloatArray, trigger: Int) {
    Box(modifier = Modifier.width(170.dp).height(48.dp).background(Color(0xFF07090C), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2D323B), RoundedCornerShape(6.dp)).padding(4.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unused = trigger
            val w = size.width
            val h = size.height
            val midY = h / 2f
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
fun ModuleBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF2D323B), RoundedCornerShape(10.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

