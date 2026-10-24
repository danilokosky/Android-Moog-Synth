package com.moogsynth.app.ui

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
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
    val osc1Vol: Float, val osc1Tune: Float, val osc1Wave: Int,
    val osc2Vol: Float, val osc2Tune: Float, val osc2Wave: Int,
    val osc3Vol: Float, val osc3Tune: Float, val osc3Wave: Int,
    val cutoffNorm: Float, val resNorm: Float, val envAmtNorm: Float,
    val fAttack: Float, val fDecay: Float, val fSustain: Float, val fRelease: Float,
    val lfoRateNorm: Float, val lfoDepthNorm: Float,
    val delayTimeNorm: Float, val delayFbNorm: Float, val delayMixNorm: Float,
    val aAttack: Float, val aDecay: Float, val aSustain: Float, val aRelease: Float,
    val masterNorm: Float
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
    SynthPreset(
        name = "1. Classic Minimoog Lead",
        osc1Vol = 0.85f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.75f, osc2Tune = 0.50f, osc2Wave = 0,
        osc3Vol = 0.60f, osc3Tune = 0.25f, osc3Wave = 0,
        cutoffNorm = 0.62f, resNorm = 0.25f, envAmtNorm = 0.50f,
        fAttack = 0.02f, fDecay = 0.30f, fSustain = 0.35f, fRelease = 0.25f,
        lfoRateNorm = 0.28f, lfoDepthNorm = 0.03f,
        delayTimeNorm = 0.35f, delayFbNorm = 0.40f, delayMixNorm = 0.25f,
        aAttack = 0.01f, aDecay = 0.30f, aSustain = 0.85f, aRelease = 0.22f,
        masterNorm = 0.85f
    ),
    SynthPreset(
        name = "2. Taurus Sub Bass",
        osc1Vol = 0.90f, osc1Tune = 0.25f, osc1Wave = 0,
        osc2Vol = 0.85f, osc2Tune = 0.25f, osc2Wave = 1,
        osc3Vol = 0.70f, osc3Tune = 0.50f, osc3Wave = 2,
        cutoffNorm = 0.30f, resNorm = 0.30f, envAmtNorm = 0.55f,
        fAttack = 0.005f, fDecay = 0.18f, fSustain = 0.12f, fRelease = 0.15f,
        lfoRateNorm = 0.15f, lfoDepthNorm = 0.0f,
        delayTimeNorm = 0.25f, delayFbNorm = 0.15f, delayMixNorm = 0.0f,
        aAttack = 0.005f, aDecay = 0.25f, aSustain = 0.80f, aRelease = 0.15f,
        masterNorm = 0.90f
    ),
    SynthPreset(
        name = "3. Lucky Man Solo",
        osc1Vol = 0.85f, osc1Tune = 0.50f, osc1Wave = 1,
        osc2Vol = 0.75f, osc2Tune = 0.75f, osc2Wave = 1,
        osc3Vol = 0.65f, osc3Tune = 0.25f, osc3Wave = 2,
        cutoffNorm = 0.68f, resNorm = 0.22f, envAmtNorm = 0.40f,
        fAttack = 0.03f, fDecay = 0.35f, fSustain = 0.50f, fRelease = 0.35f,
        lfoRateNorm = 0.30f, lfoDepthNorm = 0.05f,
        delayTimeNorm = 0.42f, delayFbNorm = 0.45f, delayMixNorm = 0.30f,
        aAttack = 0.02f, aDecay = 0.30f, aSustain = 0.90f, aRelease = 0.35f,
        masterNorm = 0.85f
    ),
    SynthPreset(
        name = "4. Blade Runner Brass",
        osc1Vol = 0.85f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.80f, osc2Tune = 0.50f, osc2Wave = 0,
        osc3Vol = 0.55f, osc3Tune = 0.25f, osc3Wave = 1,
        cutoffNorm = 0.40f, resNorm = 0.20f, envAmtNorm = 0.68f,
        fAttack = 0.18f, fDecay = 0.45f, fSustain = 0.55f, fRelease = 0.45f,
        lfoRateNorm = 0.22f, lfoDepthNorm = 0.04f,
        delayTimeNorm = 0.48f, delayFbNorm = 0.50f, delayMixNorm = 0.35f,
        aAttack = 0.12f, aDecay = 0.40f, aSustain = 0.85f, aRelease = 0.50f,
        masterNorm = 0.85f
    )
)

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine, incomingMidiCC: MidiCCEvent? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("moog_midi_slots_prefs", Context.MODE_PRIVATE) }
    val midiManager = remember { context.getSystemService(Context.MIDI_SERVICE) as? MidiManager }

    var detectedMidiDeviceName by remember { mutableStateOf<String?>(null) }
    var lastReceivedMidiInfo by remember { mutableStateOf<String?>(null) }
    var isTestTonePlaying by remember { mutableStateOf(false) }

    var osc1Vol by remember { mutableStateOf(0.75f) }
    var osc1Tune by remember { mutableStateOf(0.5f) }
    var osc1Wave by remember { mutableStateOf(0) }
    var osc2Vol by remember { mutableStateOf(0.55f) }
    var osc2Tune by remember { mutableStateOf(0.25f) }
    var osc2Wave by remember { mutableStateOf(1) }
    var osc3Vol by remember { mutableStateOf(0.45f) }
    var osc3Tune by remember { mutableStateOf(0.645f) }
    var osc3Wave by remember { mutableStateOf(2) }

    var cutoffNorm by remember { mutableStateOf(0.65f) }
    var resNorm by remember { mutableStateOf(0.25f) }
    var envAmtNorm by remember { mutableStateOf(0.5f) }
    var fAttack by remember { mutableStateOf(0.05f) }
    var fDecay by remember { mutableStateOf(0.25f) }
    var fSustain by remember { mutableStateOf(0.2f) }
    var fRelease by remember { mutableStateOf(0.3f) }

    var lfoRateNorm by remember { mutableStateOf(0.25f) }
    var lfoDepthNorm by remember { mutableStateOf(0.0f) }
    var delayTimeNorm by remember { mutableStateOf(0.35f) }
    var delayFbNorm by remember { mutableStateOf(0.4f) }
    var delayMixNorm by remember { mutableStateOf(0.2f) }

    var aAttack by remember { mutableStateOf(0.02f) }
    var aDecay by remember { mutableStateOf(0.3f) }
    var aSustain by remember { mutableStateOf(0.7f) }
    var aRelease by remember { mutableStateOf(0.3f) }
    var masterNorm by remember { mutableStateOf(0.85f) }

    var showAudioSettingsDialog by remember { mutableStateOf(false) }
    var oversamplingFactor by remember { mutableStateOf(2) }
    var analogDrift by remember { mutableStateOf(0.35f) }
    var filterDrive by remember { mutableStateOf(0.25f) }
    var filterKeyTrack by remember { mutableStateOf(0.50f) }
    var stereoSpread by remember { mutableStateOf(0.45f) }
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
        prefs.edit().putString("saved_engine_dsp_settings_json", obj.toString()).commit()
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
        val maxHz = 12000f
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
            arr.put(o)
        }
        root.put("presets", arr)
        prefs.edit().putString("saved_synth_presets_v2_json", root.toString()).commit()
    }

    fun saveCurrentStateToActivePresetExplicitly() {
        val idx = presetsList.indexOfFirst { it.id == currentPresetId }
        if (idx != -1) {
            presetsList[idx] = presetsList[idx].copy(
                osc1Vol = osc1Vol, osc1Tune = osc1Tune, osc1Wave = osc1Wave,
                osc2Vol = osc2Vol, osc2Tune = osc2Tune, osc2Wave = osc2Wave,
                osc3Vol = osc3Vol, osc3Tune = osc3Tune, osc3Wave = osc3Wave,
                cutoffNorm = cutoffNorm, resNorm = resNorm, envAmtNorm = envAmtNorm,
                fAttack = fAttack, fDecay = fDecay, fSustain = fSustain, fRelease = fRelease,
                lfoRateNorm = lfoRateNorm, lfoDepthNorm = lfoDepthNorm,
                delayTimeNorm = delayTimeNorm, delayFbNorm = delayFbNorm, delayMixNorm = delayMixNorm,
                aAttack = aAttack, aDecay = aDecay, aSustain = aSustain, aRelease = aRelease,
                masterNorm = masterNorm
            )
            savePresetsToDisk(presetsList, currentPresetId)
            isPresetDirty = false
        }
    }

    fun applyPresetToEngine(p: SynthPreset) {
        currentPresetId = p.id
        isPresetDirty = false

        osc1Vol = p.osc1Vol; osc1Tune = p.osc1Tune; osc1Wave = p.osc1Wave
        osc2Vol = p.osc2Vol; osc2Tune = p.osc2Tune; osc2Wave = p.osc2Wave
        osc3Vol = p.osc3Vol; osc3Tune = p.osc3Tune; osc3Wave = p.osc3Wave

        cutoffNorm = p.cutoffNorm; resNorm = p.resNorm; envAmtNorm = p.envAmtNorm
        fAttack = p.fAttack; fDecay = p.fDecay; fSustain = p.fSustain; fRelease = p.fRelease

        lfoRateNorm = p.lfoRateNorm; lfoDepthNorm = p.lfoDepthNorm
        delayTimeNorm = p.delayTimeNorm; delayFbNorm = p.delayFbNorm; delayMixNorm = p.delayMixNorm

        aAttack = p.aAttack; aDecay = p.aDecay; aSustain = p.aSustain; aRelease = p.aRelease
        masterNorm = p.masterNorm

        audioEngine.setOscVolume(0, osc1Vol); audioEngine.setOscTune(0, normToSemitones(osc1Tune)); audioEngine.setOscWave(0, osc1Wave)
        audioEngine.setOscVolume(1, osc2Vol); audioEngine.setOscTune(1, normToSemitones(osc2Tune)); audioEngine.setOscWave(1, osc2Wave)
        audioEngine.setOscVolume(2, osc3Vol); audioEngine.setOscTune(2, normToSemitones(osc3Tune)); audioEngine.setOscWave(2, osc3Wave)

        audioEngine.setFilterCutoff(normToCutoffHz(cutoffNorm))
        audioEngine.setFilterResonance(resNorm)
        audioEngine.setFilterEnvAmount(envAmtNorm * 5000f)
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
        // commit() grava de forma SÍNCRONA imediata na memória flash
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

    // Monitoramento contínuo de dispositivos MIDI conectados via USB
    LaunchedEffect(Unit) {
        val checkMidi = {
            val devs = midiManager?.devices
            if (devs != null && devs.isNotEmpty()) {
                val names = devs.mapNotNull { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) }
                detectedMidiDeviceName = if (names.isNotEmpty()) names.joinToString(", ") else "Controladora USB Conectada"
            } else {
                detectedMidiDeviceName = null
            }
        }
        checkMidi()
        while (true) {
            delay(1500L)
            checkMidi()
        }
    }

    LaunchedEffect(Unit) {
        val rawDspJson = prefs.getString("saved_engine_dsp_settings_json", null)
        if (rawDspJson != null) {
            try {
                val o = JSONObject(rawDspJson)
                oversamplingFactor = o.optInt("oversamplingFactor", 2)
                analogDrift = o.optDouble("analogDrift", 0.35).toFloat()
                filterDrive = o.optDouble("filterDrive", 0.25).toFloat()
                filterKeyTrack = o.optDouble("filterKeyTrack", 0.50).toFloat()
                stereoSpread = o.optDouble("stereoSpread", 0.45).toFloat()
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

        val rawPresetsJson = prefs.getString("saved_synth_presets_v2_json", null)
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
                            masterNorm = o.getDouble("masterNorm").toFloat()
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
            "A_ATK" -> { aAttack = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_DEC" -> { aDecay = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_SUS" -> { aSustain = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "A_REL" -> { aRelease = v; audioEngine.setAmpADSR(aAttack * 2f, aDecay * 2f, aSustain, aRelease * 3f) }
            "MASTER" -> { masterNorm = v; audioEngine.setMasterVolume(v) }
        }
        isPresetDirty = true
    }

    LaunchedEffect(incomingMidiCC) {
        incomingMidiCC?.let { event ->
            lastReceivedMidiInfo = "CC ${event.cc} = ${event.value}"
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

    // Verificação de silêncio (caso os faders físicos estejam abaixados a 0%)
    val isVolumeMuted = (masterNorm <= 0.04f)
    val isCutoffClosed = (cutoffNorm <= 0.04f)
    val areOscsMuted = (osc1Vol <= 0.04f && osc2Vol <= 0.04f && osc3Vol <= 0.04f)

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
                            text = "⚙️ MOTOR DE ÁUDIO & DSP",
                            color = Color(0xFFF59E0B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                                .clickable {
                                    oversamplingFactor = 2; analogDrift = 0.35f; filterDrive = 0.25f
                                    filterKeyTrack = 0.50f; stereoSpread = 0.45f; velocitySens = 0.35f
                                    maxPolyphony = 16; bufferBurstMult = 2; paramSmoothing = true
                                    applyEngineSettingsToNative()
                                    saveEngineSettingsToDisk()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("↺ RESET", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Calibração Acústica Moog + Performance Oboe",
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
                            listOf(1 to "1x (Eco / Leve)", 2 to "2x (Studio HD)", 4 to "4x (Ultra Analog)").forEach { (factor, title) ->
                                val selected = (oversamplingFactor == factor)
                                Box(modifier = Modifier.weight(1f).background(if (selected) Color(0xFFF59E0B) else Color(0xFF0F1115), RoundedCornerShape(6.dp)).border(1.dp, if (selected) Color(0xFFFDE047) else Color(0xFF334155), RoundedCornerShape(6.dp)).clickable { oversamplingFactor = factor; audioEngine.setOversampling(factor); saveEngineSettingsToDisk() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(title, color = if (selected) Color.Black else Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    DspSliderRow("ANALOG DRIFT (INSTABILIDADE VCO/VCF)", "Micro-flutuações orgânicas", analogDrift, "${(analogDrift * 100).toInt()}%") { analogDrift = it; audioEngine.setAnalogDrift(it); saveEngineSettingsToDisk() }
                    DspSliderRow("FILTER DRIVE (SATURAÇÃO)", "Calor harmônico de entrada", filterDrive, "${(filterDrive * 100).toInt()}%") { filterDrive = it; audioEngine.setFilterDrive(it); saveEngineSettingsToDisk() }
                    DspSliderRow("KEYBOARD TRACKING (1V/OCT)", "Filtro acompanha as notas agudas", filterKeyTrack, "${(filterKeyTrack * 100).toInt()}%") { filterKeyTrack = it; audioEngine.setFilterKeyTrack(it); saveEngineSettingsToDisk() }
                    DspSliderRow("ABERTURA ESTÉREO & TAPE WOW", "Espacialização e fita do Delay", stereoSpread, "${(stereoSpread * 100).toInt()}%") { stereoSpread = it; audioEngine.setStereoSpread(it); saveEngineSettingsToDisk() }
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
                            Text("Elimina degraus digitais no Cutoff via MIDI CC.", color = Color(0xFF94A3B8), fontSize = 10.sp)
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
                                val newPreset = SynthPreset(
                                    name = finalName, osc1Vol = osc1Vol, osc1Tune = osc1Tune, osc1Wave = osc1Wave, osc2Vol = osc2Vol, osc2Tune = osc2Tune, osc2Wave = osc2Wave, osc3Vol = osc3Vol, osc3Tune = osc3Tune, osc3Wave = osc3Wave,
                                    cutoffNorm = cutoffNorm, resNorm = resNorm, envAmtNorm = envAmtNorm, fAttack = fAttack, fDecay = fDecay, fSustain = fSustain, fRelease = fRelease, lfoRateNorm = lfoRateNorm, lfoDepthNorm = lfoDepthNorm,
                                    delayTimeNorm = delayTimeNorm, delayFbNorm = delayFbNorm, delayMixNorm = delayMixNorm, aAttack = aAttack, aDecay = aDecay, aSustain = aSustain, aRelease = aRelease, masterNorm = masterNorm
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
        // Alerta dinâmico de silêncio caso o fader Master ou Cutoff estejam abaixados
        if (isVolumeMuted || isCutoffClosed || areOscsMuted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(Color(0xFF7F1D1D).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠️ ATENÇÃO: SOM MUDO (" + when {
                        isVolumeMuted -> "Master está em ${(masterNorm * 100).toInt()}% - Fader CC 89 abaixado"
                        isCutoffClosed -> "Filtro fechado em ${normToCutoffHz(cutoffNorm).toInt()}Hz - Fader CC 88 abaixado"
                        else -> "Osciladores em 0% - Knobs CC 29/30/31 abaixados"
                    } + ")",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .background(Color(0xFFDC2626), RoundedCornerShape(6.dp))
                        .clickable {
                            masterNorm = 0.85f; audioEngine.setMasterVolume(0.85f)
                            cutoffNorm = 0.65f; audioEngine.setFilterCutoff(normToCutoffHz(0.65f))
                            if (osc1Vol < 0.2f) { osc1Vol = 0.75f; audioEngine.setOscVolume(0, 0.75f) }
                            if (osc2Vol < 0.2f) { osc2Vol = 0.55f; audioEngine.setOscVolume(1, 0.55f) }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("🔊 RESTAURAR SOM AGORA", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("MOOG POLY-D NATIVE", color = Color(0xFFF59E0B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isMidiLearnMode) {
                        if (selectedParamForLearn == null) "MIDI LEARN [$currentSlotName]: Toque num Knob..." else "GIRAR HARDWARE -> [$selectedParamForLearn]"
                    } else if (isPresetDirty) {
                        "TIMBRE EDITADO (Não Salvo) | Clique em SALVAR"
                    } else {
                        "TIMBRE: $currentPresetName | DSP: ${oversamplingFactor}x OS"
                    },
                    color = if (isMidiLearnMode || isPresetDirty) Color(0xFFFBBF24) else Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)
            Spacer(modifier = Modifier.width(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Indicador de Status do Cabo / Conexão USB MIDI
                Box(
                    modifier = Modifier
                        .background(
                            if (detectedMidiDeviceName != null) Color(0xFF064E3B).copy(alpha = 0.75f) else Color(0xFF451A03).copy(alpha = 0.75f),
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (detectedMidiDeviceName != null) Color(0xFF10B981) else Color(0xFFF59E0B),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(
                            text = if (detectedMidiDeviceName != null) "● $detectedMidiDeviceName" else "○ MIDI: Reconecte Cabo USB",
                            color = if (detectedMidiDeviceName != null) Color(0xFF34D399) else Color(0xFFFCD34D),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (lastReceivedMidiInfo != null) {
                            Text(
                                text = "RX: $lastReceivedMidiInfo",
                                color = Color(0xFF94A3B8),
                                fontSize = 8.sp
                            )
                        }
                    }
                }

                // Botão de Teste de Som Direto
                Box(
                    modifier = Modifier
                        .background(if (isTestTonePlaying) Color(0xFF059669) else Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .border(1.dp, if (isTestTonePlaying) Color(0xFF34D399) else Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                        .clickable {
                            if (isTestTonePlaying) {
                                audioEngine.noteOff(60)
                                isTestTonePlaying = false
                            } else {
                                isTestTonePlaying = true
                                audioEngine.noteOn(60, 100)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isTestTonePlaying) "⏹ PARAR" else "🔊 TESTAR SOM",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(modifier = Modifier.background(Color(0xFF181B20), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF10B981).copy(alpha = 0.7f), RoundedCornerShape(6.dp)).clickable { showAudioSettingsDialog = true }.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text("⚙️ DSP (${oversamplingFactor}x)", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
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
                        presetsList.forEach { preset ->
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
                        DropdownMenuItem(text = { Text("↺ RESTAURAR 8 PRESETS DE FÁBRICA", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }, onClick = { isPresetDropdownExpanded = false; val factory = getFactoryPresets(); presetsList.clear(); presetsList.addAll(factory); applyPresetToEngine(factory.first()) })
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
                Box(modifier = Modifier.background(Color(0xFF991B1B), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp)).clickable {
                    audioEngine.allNotesOff()
                    isTestTonePlaying = false
                }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("PANIC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ModuleBox(title = "OSCILADORES (POLYBLEP)") {
                OscillatorRow(label = "OSC 1", wave = osc1Wave, onWaveChange = { osc1Wave = it; audioEngine.setOscWave(0, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC1_VOL", "LEVEL", "${(osc1Vol * 100).toInt()}%", osc1Vol) }, tuneKnob = { val st = normToSemitones(osc1Tune).toInt(); SmartKnob("OSC1_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc1Tune) })
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(label = "OSC 2", wave = osc2Wave, onWaveChange = { osc2Wave = it; audioEngine.setOscWave(1, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC2_VOL", "LEVEL", "${(osc2Vol * 100).toInt()}%", osc2Vol) }, tuneKnob = { val st = normToSemitones(osc2Tune).toInt(); SmartKnob("OSC2_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc2Tune) })
                Spacer(modifier = Modifier.height(12.dp))
                OscillatorRow(label = "OSC 3", wave = osc3Wave, onWaveChange = { osc3Wave = it; audioEngine.setOscWave(2, it); isPresetDirty = true }, levelKnob = { SmartKnob("OSC3_VOL", "LEVEL", "${(osc3Vol * 100).toInt()}%", osc3Vol) }, tuneKnob = { val st = normToSemitones(osc3Tune).toInt(); SmartKnob("OSC3_TUNE", "TUNE", "${if (st > 0) "+$st" else st} st", osc3Tune) })
            }

            ModuleBox(title = "FILTRO MOOG 24dB (VCF)") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SmartKnob("CUTOFF", "CORTE", "${normToCutoffHz(cutoffNorm).toInt()} Hz", cutoffNorm)
                    SmartKnob("RESONANCE", "RESSON.", "${(resNorm * 100).toInt()}%", resNorm)
                    SmartKnob("ENV_AMT", "ENV AMT", "${"%.1f".format(envAmtNorm * 4.8f)} Oct", envAmtNorm)
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

            ModuleBox(title = "MODULAÇÃO (LFO) & DELAY") {
                Text("LFO -> FILTRO MOOG (1V/OCT)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    SmartKnob("LFO_RATE", "LFO RATE", "${"%.1f".format(0.1f + lfoRateNorm * 19.9f)} Hz", lfoRateNorm)
                    SmartKnob("LFO_DEPTH", "LFO DEPTH", "${(lfoDepthNorm * 100).toInt()}%", lfoDepthNorm)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text("STEREO TAPE DELAY (WOW & FLUTTER)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmartKnob("DELAY_TIME", "TIME", "${((0.05f + delayTimeNorm * 0.95f) * 1000).toInt()}ms", delayTimeNorm)
                    SmartKnob("DELAY_FB", "FEEDBACK", "${(delayFbNorm * 100).toInt()}%", delayFbNorm)
                    SmartKnob("DELAY_MIX", "MIX", "${(delayMixNorm * 100).toInt()}%", delayMixNorm)
                }
            }

            ModuleBox(title = "AMPLIFICADOR (VCA) & SAÍDA") {
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
        }
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
