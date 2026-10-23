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

// Banco de Fábrica com os 8 Presets Clássicos e Ressonâncias Equilibradas
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
    ),
    SynthPreset(
        name = "5. Funky Bernie Worm",
        osc1Vol = 0.85f, osc1Tune = 0.25f, osc1Wave = 0,
        osc2Vol = 0.75f, osc2Tune = 0.50f, osc2Wave = 1,
        osc3Vol = 0.50f, osc3Tune = 0.50f, osc3Wave = 3,
        cutoffNorm = 0.35f, resNorm = 0.52f, envAmtNorm = 0.75f,
        fAttack = 0.01f, fDecay = 0.16f, fSustain = 0.08f, fRelease = 0.12f,
        lfoRateNorm = 0.32f, lfoDepthNorm = 0.18f,
        delayTimeNorm = 0.28f, delayFbNorm = 0.30f, delayMixNorm = 0.15f,
        aAttack = 0.005f, aDecay = 0.20f, aSustain = 0.70f, aRelease = 0.12f,
        masterNorm = 0.85f
    ),
    SynthPreset(
        name = "6. Stranger Synth Pluck",
        osc1Vol = 0.85f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.70f, osc2Tune = 0.25f, osc2Wave = 1,
        osc3Vol = 0.55f, osc3Tune = 0.75f, osc3Wave = 2,
        cutoffNorm = 0.28f, resNorm = 0.32f, envAmtNorm = 0.72f,
        fAttack = 0.005f, fDecay = 0.12f, fSustain = 0.0f, fRelease = 0.15f,
        lfoRateNorm = 0.18f, lfoDepthNorm = 0.02f,
        delayTimeNorm = 0.32f, delayFbNorm = 0.50f, delayMixNorm = 0.32f,
        aAttack = 0.005f, aDecay = 0.15f, aSustain = 0.0f, aRelease = 0.18f,
        masterNorm = 0.88f
    ),
    SynthPreset(
        name = "7. Cosmic Analog Pad",
        osc1Vol = 0.75f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.65f, osc2Tune = 0.25f, osc2Wave = 1,
        osc3Vol = 0.60f, osc3Tune = 0.645f, osc3Wave = 2,
        cutoffNorm = 0.52f, resNorm = 0.26f, envAmtNorm = 0.42f,
        fAttack = 0.35f, fDecay = 0.55f, fSustain = 0.65f, fRelease = 0.65f,
        lfoRateNorm = 0.12f, lfoDepthNorm = 0.12f,
        delayTimeNorm = 0.55f, delayFbNorm = 0.55f, delayMixNorm = 0.38f,
        aAttack = 0.30f, aDecay = 0.50f, aSustain = 0.80f, aRelease = 0.70f,
        masterNorm = 0.82f
    ),
    SynthPreset(
        name = "8. Sci-Fi Filter Sweep",
        osc1Vol = 0.80f, osc1Tune = 0.50f, osc1Wave = 0,
        osc2Vol = 0.80f, osc2Tune = 0.25f, osc2Wave = 1,
        osc3Vol = 0.70f, osc3Tune = 0.645f, osc3Wave = 0,
        cutoffNorm = 0.48f, resNorm = 0.62f, envAmtNorm = 0.55f,
        fAttack = 0.08f, fDecay = 0.40f, fSustain = 0.40f, fRelease = 0.50f,
        lfoRateNorm = 0.40f, lfoDepthNorm = 0.50f,
        delayTimeNorm = 0.45f, delayFbNorm = 0.60f, delayMixNorm = 0.40f,
        aAttack = 0.02f, aDecay = 0.40f, aSustain = 0.80f, aRelease = 0.55f,
        masterNorm = 0.82f
    )
)

@Composable
fun SynthScreen(audioEngine: NativeAudioEngine, incomingMidiCC: MidiCCEvent? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("moog_midi_slots_prefs", Context.MODE_PRIVATE) }

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
    var resNorm by remember { mutableStateOf(0.25f) }
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

    fun normToCutoffHz(norm: Float): Float {
        val minHz = 20f
        val maxHz = 12000f
        return exp(norm * (ln(maxHz) - ln(minHz)) + ln(minHz))
    }

    fun normToSemitones(norm: Float): Float = ((norm * 48f) - 24f).roundToInt().toFloat()

    // --- 1. GERENCIADOR DE PRESETS DE TIMBRES (COM SAVE EXPLÍCITO) ---
    val presetsList = remember { mutableStateListOf<SynthPreset>() }
    var currentPresetId by remember { mutableStateOf("") }
    var isPresetDirty by remember { mutableStateOf(false) } // Indica se o preset atual foi alterado mas não salvo!
    var isPresetDropdownExpanded by remember { mutableStateOf(false) }

    // --- 2. GERENCIADOR DE SLOTS MIDI LEARN (AUTO-SAVE) ---
    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val paramToCCMap = remember { mutableStateMapOf<String, Int>() }
    val midiSlots = remember { mutableStateListOf<MidiMappingSlot>() }
    var currentSlotId by remember { mutableStateOf("") }
    var isSlotDropdownExpanded by remember { mutableStateOf(false) }

    // Estado da caixa de diálogo compartilhada
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
        prefs.edit().putString("saved_synth_presets_v2_json", root.toString()).apply()
    }

    // Função chamada APENAS quando o usuário clica explicitamente no botão "💾 SALVAR"
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

    // Carrega um Preset da lista (descartando alterações não salvas se você tiver se arrependido!)
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
        prefs.edit().putString("saved_midi_slots_json", root.toString()).apply()
    }

    fun syncCurrentMapToActiveSlot() {
        val idx = midiSlots.indexOfFirst { it.id == currentSlotId }
        if (idx != -1) {
            midiSlots[idx] = midiSlots[idx].copy(mappings = paramToCCMap.toMap())
            saveSlotsToDisk(midiSlots, currentSlotId)
        }
    }

    fun selectSlot(slot: MidiMappingSlot) {
        currentSlotId = slot.id
        paramToCCMap.clear()
        paramToCCMap.putAll(slot.mappings)
        saveSlotsToDisk(midiSlots, currentSlotId)
    }

    // Inicialização ao abrir o aplicativo
    LaunchedEffect(Unit) {
        val rawSlotsJson = prefs.getString("saved_midi_slots_json", null)
        if (rawSlotsJson != null) {
            try {
                val root = JSONObject(rawSlotsJson)
                val savedActiveId = root.optString("activeSlotId", "")
                val arr = root.getJSONArray("slots")
                val loaded = mutableListOf<MidiMappingSlot>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val mapObj = obj.getJSONObject("mappings")
                    val map = mutableMapOf<String, Int>()
                    val keys = mapObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = mapObj.getInt(k)
                    }
                    loaded.add(MidiMappingSlot(id, name, map))
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
            val defaultSlot = MidiMappingSlot(name = "Launchkey MK4", mappings = emptyMap())
            midiSlots.add(defaultSlot)
            currentSlotId = defaultSlot.id
            saveSlotsToDisk(midiSlots, currentSlotId)
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
                            id = o.getString("id"),
                            name = o.getString("name"),
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
                    presetsList.clear()
                    presetsList.addAll(loadedPresets)
                    val activePreset = loadedPresets.find { it.id == savedActivePresetId } ?: loadedPresets.first()
                    applyPresetToEngine(activePreset)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (presetsList.isEmpty()) {
            val factory = getFactoryPresets()
            presetsList.addAll(factory)
            applyPresetToEngine(factory.first())
        }
    }

    val currentSlotName = midiSlots.find { it.id == currentSlotId }?.name ?: "Slot 1"
    val currentPresetName = presetsList.find { it.id == currentPresetId }?.name ?: "Preset 1"

    // Atualiza o parâmetro no motor C++ e acende o indicador de modificação (NÃO sobrescreve o Preset sem você mandar!)
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
            mappedCC = if (isMidiLearnMode) paramToCCMap[paramId] else null,
            onUnmap = {
                paramToCCMap.remove(paramId)
                syncCurrentMapToActiveSlot()
            },
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

    val waveformBuffer = remember { FloatArray(256) }
    var frameTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            audioEngine.getWaveform(waveformBuffer)
            frameTrigger++
            delay(16L)
        }
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
                        text = if (dialogTargetIsPreset)
                            "Digite o nome do Timbre/Patch:"
                        else
                            "Digite o nome do controlador ou perfil MIDI (ex: Launchkey MK4):",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInputText,
                        onValueChange = { nameInputText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF59E0B),
                            unfocusedBorderColor = Color(0xFF334155),
                            cursorColor = Color(0xFFF59E0B)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName = nameInputText.trim().ifEmpty {
                            if (dialogTargetIsPreset) "Custom Patch" else "Controlador MIDI"
                        }
                        if (dialogTargetIsPreset) {
                            if (isCreatingNewItem) {
                                val newPreset = SynthPreset(
                                    name = finalName,
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
                                presetsList.add(newPreset)
                                applyPresetToEngine(newPreset)
                            } else {
                                val idx = presetsList.indexOfFirst { it.id == currentPresetId }
                                if (idx != -1) {
                                    presetsList[idx] = presetsList[idx].copy(name = finalName)
                                    savePresetsToDisk(presetsList, currentPresetId)
                                }
                            }
                        } else {
                            if (isCreatingNewItem) {
                                val newSlot = MidiMappingSlot(name = finalName, mappings = emptyMap())
                                midiSlots.add(newSlot)
                                selectSlot(newSlot)
                            } else {
                                val idx = midiSlots.indexOfFirst { it.id == currentSlotId }
                                if (idx != -1) {
                                    midiSlots[idx] = midiSlots[idx].copy(name = finalName)
                                    saveSlotsToDisk(midiSlots, currentSlotId)
                                }
                            }
                        }
                        showNameDialog = false
                    }
                ) {
                    Text("SALVAR", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("CANCELAR", color = Color.Gray)
                }
            }
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = "MOOG POLY-D NATIVE",
                    color = Color(0xFFF59E0B),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isMidiLearnMode) {
                        if (selectedParamForLearn == null) "MIDI LEARN [$currentSlotName]: Toque num Knob..."
                        else "GIRAR HARDWARE -> [$selectedParamForLearn]"
                    } else if (isPresetDirty) {
                        "TIMBRE EDITADO (Não Salvo) | Clique em SALVAR ou recarregue na lista"
                    } else {
                        "TIMBRE: $currentPresetName | MIDI: $currentSlotName"
                    },
                    color = if (isMidiLearnMode || isPresetDirty) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)

            Spacer(modifier = Modifier.width(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- 1. SELETOR DE PRESETS + BOTÃO EXPLÍCITO "SALVAR" ---
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFF181B20), RoundedCornerShape(6.dp))
                            .border(
                                1.dp,
                                if (isPresetDirty) Color(0xFFFDE047) else Color(0xFFF59E0B).copy(alpha = 0.6f),
                                RoundedCornerShape(6.dp)
                            )
                    ) {
                        // Abre lista de Presets
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { isPresetDropdownExpanded = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "🎹 $currentPresetName" + if (isPresetDirty) " *" else "",
                                color = if (isPresetDirty) Color(0xFFFDE047) else Color(0xFFF59E0B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", color = Color(0xFFF59E0B), fontSize = 10.sp)
                        }

                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))

                        // BOTÃO EXPLÍCITO DE SAVE EM CIMA DO PRESET ATUAL
                        Box(
                            modifier = Modifier
                                .background(if (isPresetDirty) Color(0xFFD97706) else Color.Transparent)
                                .clickable { saveCurrentStateToActivePresetExplicitly() }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "💾 SALVAR",
                                color = if (isPresetDirty) Color.White else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))

                        // Botão para Renomear o Preset Atual
                        Box(
                            modifier = Modifier
                                .clickable {
                                    nameInputText = currentPresetName
                                    dialogTargetIsPreset = true
                                    isCreatingNewItem = false
                                    showNameDialog = true
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✎", color = Color(0xFFF59E0B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    DropdownMenu(
                        expanded = isPresetDropdownExpanded,
                        onDismissRequest = { isPresetDropdownExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1E2229))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .width(275.dp)
                    ) {
                        presetsList.forEach { preset ->
                            val isSelected = (preset.id == currentPresetId)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = preset.name,
                                                color = if (isSelected) Color(0xFFF59E0B) else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = if (isSelected && isPresetDirty)
                                                    "Clique para descartar edições e reverter"
                                                else
                                                    "Corte: ${normToCutoffHz(preset.cutoffNorm).toInt()}Hz | Res: ${(preset.resNorm * 100).toInt()}%",
                                                color = if (isSelected && isPresetDirty) Color(0xFFFBBF24) else Color.Gray,
                                                fontSize = 9.sp
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color(0xFF7F1D1D), CircleShape)
                                                .clickable {
                                                    if (presetsList.size > 1) {
                                                        val removingActive = (preset.id == currentPresetId)
                                                        presetsList.remove(preset)
                                                        if (removingActive) {
                                                            applyPresetToEngine(presetsList.first())
                                                        } else {
                                                            savePresetsToDisk(presetsList, currentPresetId)
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                onClick = {
                                    // Clicar no preset (mesmo no atual) recarrega os valores salvos e desfaz edições indesejadas!
                                    applyPresetToEngine(preset)
                                    isPresetDropdownExpanded = false
                                }
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "+ SALVAR COMO NOVO PRESET...",
                                    color = Color(0xFFF59E0B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                isPresetDropdownExpanded = false
                                nameInputText = "User Patch ${presetsList.size + 1}"
                                dialogTargetIsPreset = true
                                isCreatingNewItem = true
                                showNameDialog = true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "↺ RESTAURAR 8 PRESETS DE FÁBRICA",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                isPresetDropdownExpanded = false
                                val factory = getFactoryPresets()
                                presetsList.clear()
                                presetsList.addAll(factory)
                                applyPresetToEngine(factory.first())
                            }
                        )
                    }
                }

                // --- 2. SELETOR DE SLOTS DE MAPEAMENTO MIDI ---
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFF181B20), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { isSlotDropdownExpanded = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "🎛️ $currentSlotName",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", color = Color(0xFF38BDF8), fontSize = 10.sp)
                        }

                        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155)))

                        Box(
                            modifier = Modifier
                                .clickable {
                                    nameInputText = currentSlotName
                                    dialogTargetIsPreset = false
                                    isCreatingNewItem = false
                                    showNameDialog = true
                                }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✎", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    DropdownMenu(
                        expanded = isSlotDropdownExpanded,
                        onDismissRequest = { isSlotDropdownExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1E2229))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                            .width(250.dp)
                    ) {
                        midiSlots.forEach { slot ->
                            val isSelected = (slot.id == currentSlotId)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = slot.name,
                                                color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = "${slot.mappings.size} CCs salvos",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color(0xFF7F1D1D), CircleShape)
                                                .clickable {
                                                    if (midiSlots.size > 1) {
                                                        val removingActive = (slot.id == currentSlotId)
                                                        midiSlots.remove(slot)
                                                        if (removingActive) {
                                                            selectSlot(midiSlots.first())
                                                        } else {
                                                            saveSlotsToDisk(midiSlots, currentSlotId)
                                                        }
                                                    } else {
                                                        paramToCCMap.clear()
                                                        syncCurrentMapToActiveSlot()
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                onClick = {
                                    selectSlot(slot)
                                    isSlotDropdownExpanded = false
                                }
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "+ NOVO SLOT DE CONTROLADOR...",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                isSlotDropdownExpanded = false
                                nameInputText = "Controlador ${midiSlots.size + 1}"
                                dialogTargetIsPreset = false
                                isCreatingNewItem = true
                                showNameDialog = true
                            }
                        )
                    }
                }

                // Botão MIDI LEARN
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
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isMidiLearnMode) "SAIR DO LEARN" else "MIDI LEARN",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Botão PANIC
                Box(
                    modifier = Modifier
                        .background(Color(0xFF991B1B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp))
                        .clickable { audioEngine.allNotesOff() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("PANIC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    onWaveChange = {
                        osc1Wave = it
                        audioEngine.setOscWave(0, it)
                        isPresetDirty = true
                    },
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
                    onWaveChange = {
                        osc2Wave = it
                        audioEngine.setOscWave(1, it)
                        isPresetDirty = true
                    },
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
                    onWaveChange = {
                        osc3Wave = it
                        audioEngine.setOscWave(2, it)
                        isPresetDirty = true
                    },
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
            .width(170.dp)
            .height(48.dp)
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
