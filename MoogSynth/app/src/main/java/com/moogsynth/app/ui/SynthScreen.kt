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

// Modelo de Slot de Mapeamento MIDI (ex: "Launchkey MK4")
data class MidiMappingSlot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mappings: Map<String, Int>
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

    // --- SISTEMA DE SLOTS E PERSISTÊNCIA DO MIDI LEARN ---
    var isMidiLearnMode by remember { mutableStateOf(false) }
    var selectedParamForLearn by remember { mutableStateOf<String?>(null) }
    val paramToCCMap = remember { mutableStateMapOf<String, Int>() }

    val midiSlots = remember { mutableStateListOf<MidiMappingSlot>() }
    var currentSlotId by remember { mutableStateOf("") }
    var isSlotDropdownExpanded by remember { mutableStateOf(false) }

    // Estado da caixa de diálogo para Criar ou Renomear Slot
    var showSlotDialog by remember { mutableStateOf(false) }
    var isCreatingNewSlot by remember { mutableStateOf(false) }
    var slotNameInput by remember { mutableStateOf("") }

    // Função que grava todos os Slots e o Slot ativo na memória permanente do Android
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

    // Sincroniza qualquer alteração do mapa atual imediatamente dentro do Slot ativo e salva no disco
    fun syncCurrentMapToActiveSlot() {
        val idx = midiSlots.indexOfFirst { it.id == currentSlotId }
        if (idx != -1) {
            midiSlots[idx] = midiSlots[idx].copy(mappings = paramToCCMap.toMap())
            saveSlotsToDisk(midiSlots, currentSlotId)
        }
    }

    // Carrega um Slot específico para os controles ativos
    fun selectSlot(slot: MidiMappingSlot) {
        currentSlotId = slot.id
        paramToCCMap.clear()
        paramToCCMap.putAll(slot.mappings)
        saveSlotsToDisk(midiSlots, currentSlotId)
    }

    // Carrega os Slots salvos assim que o aplicativo abre
    LaunchedEffect(Unit) {
        val rawJson = prefs.getString("saved_midi_slots_json", null)
        if (rawJson != null) {
            try {
                val root = JSONObject(rawJson)
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
        // Se for a primeira vez abrindo o app, cria o slot inicial padrão
        if (midiSlots.isEmpty()) {
            val defaultSlot = MidiMappingSlot(name = "Launchkey MK4", mappings = emptyMap())
            midiSlots.add(defaultSlot)
            currentSlotId = defaultSlot.id
            saveSlotsToDisk(midiSlots, currentSlotId)
        }
    }

    val currentSlotName = midiSlots.find { it.id == currentSlotId }?.name ?: "Slot 1"

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

    // Escuta comandos MIDI CC e salva automaticamente no Slot atual ao mapear
    LaunchedEffect(incomingMidiCC) {
        incomingMidiCC?.let { event ->
            val norm = (event.value / 127f).coerceIn(0f, 1f)
            if (isMidiLearnMode && selectedParamForLearn != null) {
                val targetParam = selectedParamForLearn!!
                val previousKeys = paramToCCMap.filterValues { it == event.cc }.keys.toList()
                previousKeys.forEach { paramToCCMap.remove(it) }

                paramToCCMap[targetParam] = event.cc
                updateParamById(targetParam, norm)

                // Auto-Save imediato no Slot ativo!
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
                syncCurrentMapToActiveSlot() // Auto-Save ao remover mapeamento no '×'
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

    // Diálogo Modal para Renomear ou Criar Novo Slot
    if (showSlotDialog) {
        AlertDialog(
            onDismissRequest = { showSlotDialog = false },
            containerColor = Color(0xFF181B20),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = {
                Text(
                    text = if (isCreatingNewSlot) "Novo Slot de Controlador" else "Renomear Slot Atual",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Digite o nome do controlador ou perfil MIDI (ex: Launchkey MK4):",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = slotNameInput,
                        onValueChange = { slotNameInput = it },
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
                        val finalName = slotNameInput.trim().ifEmpty { "Controlador MIDI" }
                        if (isCreatingNewSlot) {
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
                        showSlotDialog = false
                    }
                ) {
                    Text("SALVAR", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSlotDialog = false }) {
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
        // Barra Superior: Título + Osciloscópio + Seletor de Slots MIDI + MIDI Learn + Panic
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
                        if (selectedParamForLearn == null) "MIDI LEARN [$currentSlotName]: Clique num botão da tela para mapear..."
                        else "AGUARDANDO HARDWARE: Gire um Knob físico para gravar em [$currentSlotName]!"
                    } else "PERFIL MIDI ATIVO: $currentSlotName (${paramToCCMap.size} controles mapeados)",
                    color = if (isMidiLearnMode) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OscilloscopeView(waveform = waveformBuffer, trigger = frameTrigger)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- SELETOR DE SLOTS DE MAPEAMENTO MIDI (AO LADO DO MIDI LEARN) ---
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFF181B20), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                    ) {
                        // Botão Principal que abre a lista de Slots
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { isSlotDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "🎛️ $currentSlotName",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", color = Color(0xFFF59E0B), fontSize = 10.sp)
                        }

                        // Divisória vertical sutil
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(Color(0xFF334155))
                        )

                        // Botão para Renomear o Slot Atual (Ícone Lápis)
                        Box(
                            modifier = Modifier
                                .clickable {
                                    slotNameInput = currentSlotName
                                    isCreatingNewSlot = false
                                    showSlotDialog = true
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✎", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Menu Dropdown com a Lista de Slots Salvos + Botão [×] para apagar + Novo Slot
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
                                                color = if (isSelected) Color(0xFFF59E0B) else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = "${slot.mappings.size} CCs salvos",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }

                                        // Botão [×] para apagar o Slot da lista
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
                                                        // Se for o único slot, limpa os mapeamentos dele
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

                        // Linha separadora e botão "+ NOVO SLOT"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF334155))
                        )

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
                                slotNameInput = "Controlador ${midiSlots.size + 1}"
                                isCreatingNewSlot = true
                                showSlotDialog = true
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
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isMidiLearnMode) "SAIR DO LEARN" else "MIDI LEARN",
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
            .width(200.dp)
            .height(50.dp)
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
