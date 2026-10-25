#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <cstdint>
#include <array>
#include <vector>
#include <atomic>
#include <algorithm>
#include <mutex>
#include <thread>

// Saturação suave Padé (usada apenas quando Drive > 0 ou no loop de feedback de alta ressonância)
static inline float fastTanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x > 3.0f) return 1.0f;
    float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Clipper de Feedback Linear-Core para o Filtro Ladder:
// Mantém 100% de linearidade matemática para sinais até +-1.2 (sem comprimir a onda),
// limitando suavemente apenas auto-oscilações de alta ressonância.
static inline float linearCoreClip(float x) {
    const float linearThreshold = 1.20f;
    float absX = fabsf(x);
    if (absX <= linearThreshold) {
        return x;
    }
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - linearThreshold) * 0.5f;
    return sign * (linearThreshold + 0.80f * fastTanh(excess));
}

// Proteção Transparente de Teto do DAC (True Peak Safety Ceiling):
// O sinal é 100% INTOCADO e LINEAR (out == in) até 0.95 (-0.4 dBFS).
// Apenas picos extremos acima de 0.95 são arredondados para evitar wrap digital no hardware.
static inline float pureMasterSafetyCeiling(float x) {
    const float ceiling = 0.95f;
    float absX = fabsf(x);
    if (absX <= ceiling) {
        return x;
    }
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - ceiling) / (1.0f - ceiling);
    return sign * (ceiling + (1.0f - ceiling) * fastTanh(excess));
}

// Bloqueador de DC de 1ª ordem ultra-transparente (corte sub-audível em ~1.5Hz)
class DCBlocker {
private:
    double x1 = 0.0;
    double y1 = 0.0;
public:
    void clear() { x1 = 0.0; y1 = 0.0; }
    float process(float x) {
        double in = static_cast<double>(x);
        double y = in - x1 + 0.99975 * y1;
        x1 = in;
        y1 = y;
        return static_cast<float>(y);
    }
};

// --- OSCILADOR DE PRECISÃO 64-BIT COM POLYBLEP & POLYBLAMP PURO ---
class Oscillator {
private:
    double sampleRate = 48000.0;
    double frequency = 440.0;
    double phase = 0.0;
    int waveType = 0;

    static inline double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return t * t + t + t + 1.0;
        }
        return 0.0;
    }

    static inline double polyBlamp(double t, double dt) {
        if (t < dt) {
            t = t / dt - 1.0;
            return -1.0 / 3.0 * t * t * t;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt + 1.0;
            return 1.0 / 3.0 * t * t * t;
        }
        return 0.0;
    }

public:
    void init(float sr, double initialPhase = 0.0) {
        sampleRate = static_cast<double>(std::max(8000.0f, sr));
        phase = initialPhase - std::floor(initialPhase);
    }

    void resetPhase(double newPhase = 0.0) {
        phase = newPhase - std::floor(newPhase);
    }

    void setFrequency(float f) {
        double maxFreq = sampleRate * 0.475;
        frequency = std::clamp(static_cast<double>(f), 0.05, maxFreq);
    }

    void setWaveType(int type) {
        waveType = std::clamp(type, 0, 3);
    }

    float nextSample() {
        double dt = frequency / sampleRate;
        phase += dt;
        if (phase >= 1.0) phase -= 1.0;

        double sample = 0.0;
        switch (waveType) {
            case 0: { // Sawtooth Pura com PolyBLEP
                sample = (2.0 * phase) - 1.0;
                sample -= polyBlep(phase, dt);
                break;
            }
            case 1: { // Square Pura (50% Duty) com PolyBLEP nas duas bordas
                sample = (phase < 0.5) ? 1.0 : -1.0;
                sample += polyBlep(phase, dt);
                double phase2 = phase + 0.5;
                if (phase2 >= 1.0) phase2 -= 1.0;
                sample -= polyBlep(phase2, dt);
                break;
            }
            case 2: { // Triangle Pura com correção de inflexão PolyBLAMP
                double p = phase;
                sample = 1.0 - 4.0 * std::fabs(p - 0.5);
                double p2 = p + 0.5;
                if (p2 >= 1.0) p2 -= 1.0;
                sample += 4.0 * dt * (polyBlamp(p, dt) - polyBlamp(p2, dt));
                break;
            }
            case 3: // Sine Matemática Pura
            default: {
                sample = std::sin(2.0 * M_PI * phase);
                break;
            }
        }
        return static_cast<float>(sample);
    }
};

// --- ENVELOPE ADSR ANALÓGICO RC ---
class Envelope {
private:
    float sampleRate = 48000.0f;
    enum State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE } state = IDLE;
    float currentLevel = 0.0f;

    float attackTime = 0.005f;
    float decayTime = 0.25f;
    float sustainLevel = 0.8f;
    float releaseTime = 0.25f;

    float attackCoeff = 0.01f;
    float decayCoeff = 0.005f;
    float releaseCoeff = 0.005f;

    void recalcCoeffs() {
        float sr = std::max(8000.0f, sampleRate);
        float atkSamples = std::max(1.0f, attackTime * sr);
        attackCoeff = 1.0f - expf(-2.6f / atkSamples);
        float decSamples = std::max(1.0f, decayTime * sr);
        decayCoeff = 1.0f - expf(-4.6f / decSamples);
        float relSamples = std::max(1.0f, releaseTime * sr);
        releaseCoeff = 1.0f - expf(-4.6f / relSamples);
    }

public:
    void init(float sr) {
        sampleRate = sr;
        recalcCoeffs();
    }

    void setADSR(float a, float d, float s, float r) {
        attackTime = std::max(0.001f, a);
        decayTime = std::max(0.004f, d);
        sustainLevel = std::clamp(s, 0.0f, 1.0f);
        releaseTime = std::max(0.004f, r);
        recalcCoeffs();
    }

    void triggerOn() { state = ATTACK; }
    void triggerOff() { if (state != IDLE) state = RELEASE; }
    void kill() { state = IDLE; currentLevel = 0.0f; }

    bool isIdle() const { return state == IDLE; }
    bool isReleasing() const { return state == RELEASE; }

    float nextLevel() {
        switch (state) {
            case IDLE:
                currentLevel = 0.0f;
                break;
            case ATTACK:
                currentLevel += (1.12f - currentLevel) * attackCoeff;
                if (currentLevel >= 1.0f) {
                    currentLevel = 1.0f;
                    state = DECAY;
                }
                break;
            case DECAY:
                currentLevel += (sustainLevel - currentLevel) * decayCoeff;
                if (fabsf(currentLevel - sustainLevel) < 0.0005f) {
                    currentLevel = sustainLevel;
                    state = SUSTAIN;
                }
                break;
            case SUSTAIN:
                currentLevel += (sustainLevel - currentLevel) * 0.025f;
                break;
            case RELEASE:
                currentLevel += (0.0f - currentLevel) * releaseCoeff;
                if (currentLevel <= 0.00015f) {
                    currentLevel = 0.0f;
                    state = IDLE;
                }
                break;
        }
        return currentLevel;
    }
};

// --- FILTRO MOOG LADDER 24dB TPT (ZERO-DELAY FEEDBACK LINEAR-CORE) ---
// Sem interpolação linear passa-baixas na entrada e sem compressão/achatamento forçado!
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    double s[4] = {0.0, 0.0, 0.0, 0.0};

public:
    void init(float sr) {
        sampleRate = sr;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) s[i] = 0.0;
    }

    float process(float input, float cutoffHz, float resonance, float driveNorm, int oversampleFactor) {
        int os = std::clamp(oversampleFactor, 1, 4);
        double effSr = static_cast<double>(sampleRate) * static_cast<double>(os);

        // Frequência de corte exata (preserva transparência total quando aberto)
        double fc = std::clamp(static_cast<double>(cutoffHz), 20.0, static_cast<double>(sampleRate) * 0.46);

        // Integrador Trapezoidal Zero-Delay Feedback (TPT)
        double g = std::tan(M_PI * fc / effSr);
        double G = g / (1.0 + g);
        double G2 = G * G;
        double G3 = G2 * G;
        double G4 = G2 * G2;

        double resNorm = std::clamp(static_cast<double>(resonance), 0.0, 1.0);
        double k = std::pow(resNorm, 1.25) * 3.85;

        // Entrada 100% PURA E LINEAR quando Drive está em 0.0!
        // Só aplica saturação se o usuário aumentar deliberadamente o controle de Filter Drive.
        double x = static_cast<double>(input);
        if (driveNorm > 0.005f) {
            float drv = 1.0f + (driveNorm * 2.0f);
            x = static_cast<double>(fastTanh(input * drv) / fastTanh(drv));
        }

        // Compensação limpa de passband em Zero-Delay Feedback:
        // No filtro Ladder ideal, o ganho DC cai por 1/(1 + k).
        // Multiplicar a entrada por (1 + k * 0.25) restaura o nível sem saturar a entrada!
        double cleanPassbandComp = 1.0 + (k * 0.25);
        double xComp = x * cleanPassbandComp;

        double out = 0.0;
        double invOnePlusKG4 = 1.0 / (1.0 + k * G4);

        for (int step = 0; step < os; ++step) {
            double S = (G3 * s[0]) + (G2 * s[1]) + (G * s[2]) + s[3];
            double fb = static_cast<double>(linearCoreClip(static_cast<float>(S)));
            double u = (xComp - k * fb) * invOnePlusKG4;

            double v0 = (u - s[0]) * G;
            double y0 = v0 + s[0];
            s[0] = y0 + v0;

            double v1 = (y0 - s[1]) * G;
            double y1 = v1 + s[1];
            s[1] = y1 + v1;

            double v2 = (y1 - s[2]) * G;
            double y2 = v2 + s[2];
            s[2] = y2 + v2;

            double v3 = (y2 - s[3]) * G;
            double y3 = v3 + s[3];
            s[3] = y3 + v3;

            out = y3;
        }

        return static_cast<float>(out);
    }
};

// --- MASTER STUDIO TAPE DELAY (PARALELO SEND/RETURN, SINAL DRY 100% INTOCADO) ---
class AnalogDelay {
private:
    std::vector<float> bufferL;
    std::vector<float> bufferR;
    int writeIdx = 0;
    float sampleRate = 48000.0f;
    float lpStateL = 0.0f, lpStateR = 0.0f;
    float hpStateL = 0.0f, hpStateR = 0.0f;
    float wowPhase = 0.0f, flutterPhase = 0.0f;

    float readFractional(const std::vector<float>& buf, float delaySamples) const {
        int size = static_cast<int>(buf.size());
        float readPos = static_cast<float>(writeIdx) - delaySamples;
        while (readPos < 0.0f) readPos += static_cast<float>(size);
        int idx0 = static_cast<int>(readPos) % size;
        int idx1 = (idx0 + 1) % size;
        float frac = readPos - std::floor(readPos);
        return buf[idx0] + frac * (buf[idx1] - buf[idx0]);
    }

public:
    void init(float sr) {
        sampleRate = sr;
        size_t maxSamples = static_cast<size_t>(sr * 1.6f);
        bufferL.assign(maxSamples, 0.0f);
        bufferR.assign(maxSamples, 0.0f);
        writeIdx = 0;
        clear();
    }

    void clear() {
        std::fill(bufferL.begin(), bufferL.end(), 0.0f);
        std::fill(bufferR.begin(), bufferR.end(), 0.0f);
        lpStateL = hpStateL = lpStateR = hpStateR = 0.0f;
    }

    void processStereo(float inL, float inR, float delayTimeSec, float feedback, float mix, float stereoSpread, float& outL, float& outR) {
        // Quando o Mix está zerado, retorna o sinal Dry 100% bit-a-bit puro!
        if (mix <= 0.001f || bufferL.empty()) {
            outL = inL;
            outR = inR;
            return;
        }

        wowPhase += 0.50f / sampleRate;
        if (wowPhase >= 1.0f) wowPhase -= 1.0f;
        flutterPhase += 5.2f / sampleRate;
        if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;

        float modDepth = 0.25f + (stereoSpread * 0.75f);
        float tapeModSec = ((sinf(2.0f * static_cast<float>(M_PI) * wowPhase) * 0.00035f) +
                            (sinf(2.0f * static_cast<float>(M_PI) * flutterPhase) * 0.00008f)) * modDepth;

        float maxDelaySamples = static_cast<float>(bufferL.size()) - 4.0f;
        float delaySamplesL = std::clamp((delayTimeSec + tapeModSec) * sampleRate, 2.0f, maxDelaySamples);
        float delaySamplesR = std::clamp((delayTimeSec - tapeModSec) * sampleRate, 2.0f, maxDelaySamples);

        float rawL = readFractional(bufferL, delaySamplesL);
        float rawR = readFractional(bufferR, delaySamplesR);

        // Filtro High-Pass/Low-Pass APENAS nas repetições (Wet) do eco
        hpStateL += 0.015f * (rawL - hpStateL);
        hpStateR += 0.015f * (rawR - hpStateR);
        float bandL = rawL - hpStateL;
        float bandR = rawR - hpStateR;

        lpStateL += 0.48f * (bandL - lpStateL);
        lpStateR += 0.48f * (bandR - lpStateR);

        float fb = std::clamp(feedback, 0.0f, 0.90f);
        float cross = stereoSpread * 0.18f;
        float fbSignalL = lpStateL * (1.0f - cross) + lpStateR * cross;
        float fbSignalR = lpStateR * (1.0f - cross) + lpStateL * cross;

        bufferL[writeIdx] = fastTanh(inL + fbSignalL * fb);
        bufferR[writeIdx] = fastTanh(inR + fbSignalR * fb);
        writeIdx = (writeIdx + 1) % static_cast<int>(bufferL.size());

        // Dry 100% íntegro + retorno paralelo Wet
        outL = inL + (lpStateL * mix * 0.75f);
        outR = inR + (lpStateR * mix * 0.75f);
    }
};

// --- VOZ POLIFÔNICA DE ALTA PUREZA (FASE COERENTE E CANAIS PUROS) ---
class Voice {
private:
    Oscillator osc[3];
    MoogLadderFilter vcf[3]; // 1 filtro TPT linear por canal (mesmo Cutoff = Zero rotação de fase diferencial!)
    Envelope ampEnv;
    Envelope filterEnv;
    float sampleRate = 48000.0f;

    int currentNote = -1;
    uint64_t age = 0;
    float noteVelocity = 1.0f;
    uint32_t rngState = 123456789u;
    int driftCounter = 0;
    float oscDriftSt[3] = {0.0f, 0.0f, 0.0f};

    float nextWhiteNoise() {
        rngState = rngState * 1664525u + 1013904223u;
        return (static_cast<float>(rngState >> 8) / 8388608.0f) - 1.0f;
    }

public:
    void init(float sr, int idx) {
        sampleRate = sr;
        rngState = 13579u + static_cast<uint32_t>(idx * 98765u);
        for (int c = 0; c < 3; ++c) {
            osc[c].init(sr, 0.0);
            oscDriftSt[c] = 0.0f;
            vcf[c].init(sr);
        }
        osc[0].setWaveType(0);
        osc[1].setWaveType(1);
        osc[2].setWaveType(2);

        ampEnv.init(sr);
        ampEnv.setADSR(0.008f, 0.3f, 0.8f, 0.25f);

        filterEnv.init(sr);
        filterEnv.setADSR(0.008f, 0.25f, 0.35f, 0.25f);
    }

    void updateFrequencies(const float* oscTunes, float driftAmount) {
        if (currentNote < 0) return;
        for (int c = 0; c < 3; ++c) {
            // Drift máximo limitado a +-3.5 cents (0.035 semitons) apenas se driftAmount > 0,
            // evitando desafinação grosseira ou batimento destrutivo entre os osciladores.
            float totalDetuneSt = oscTunes[c] + (oscDriftSt[c] * driftAmount * 0.35f);
            float freq = 440.0f * exp2f((static_cast<float>(currentNote) + totalDetuneSt - 69.0f) / 12.0f);
            osc[c].setFrequency(freq);
        }
    }

    void noteOn(int note, int velocity, const float* oscTunes, float driftAmount, uint64_t noteAge) {
        if (ampEnv.isIdle()) {
            for (int c = 0; c < 3; ++c) {
                vcf[c].reset();
                // Inicia os 3 osciladores em fase coerente (0.0) para ataque definido e zero cancelamento!
                osc[c].resetPhase(0.0);
            }
        }
        currentNote = note;
        noteVelocity = std::clamp(static_cast<float>(velocity) / 127.0f, 0.05f, 1.0f);
        age = noteAge;
        updateFrequencies(oscTunes, driftAmount);
        ampEnv.triggerOn();
        filterEnv.triggerOn();
    }

    void noteOff(int note) {
        if (currentNote == note) {
            ampEnv.triggerOff();
            filterEnv.triggerOff();
        }
    }

    void kill() {
        currentNote = -1;
        ampEnv.kill();
        filterEnv.kill();
        for (int c = 0; c < 3; ++c) {
            vcf[c].reset();
        }
    }

    void setWaveType(int index, int type) {
        if (index >= 0 && index < 3) osc[index].setWaveType(type);
    }
    void setAmpADSR(float a, float d, float s, float r) { ampEnv.setADSR(a, d, s, r); }
    void setFilterADSR(float a, float d, float s, float r) { filterEnv.setADSR(a, d, s, r); }
    bool isActive() const { return !ampEnv.isIdle(); }
    bool isReleasing() const { return ampEnv.isReleasing(); }
    int getNote() const { return currentNote; }
    uint64_t getAge() const { return age; }

    // Renderiza os 3 canais de Oscilador de forma 100% PURA, LINEAR e COERENTE EM FASE:
    // - Sem bassShelfState (+60% de grave artificial removido!)
    // - Sem pré-clipping fastTanh forçado no filtro!
    // - Mesmo Cutoff sincronizado nos 3 canais da voz (Zero cancelamento de fase na Master!)
    void renderChannels(
        const float* oscVols,
        const float* oscTunes,
        float baseCutoff,
        float resonance,
        float filterEnvAmtHz,
        float lfoModOctaves,
        float driftAmount,
        float driveNorm,
        float keyTrackAmount,
        float velSensAmount,
        int oversampleFactor,
        float* outCh
    ) {
        if (ampEnv.isIdle()) {
            outCh[0] = 0.0f;
            outCh[1] = 0.0f;
            outCh[2] = 0.0f;
            return;
        }

        if ((++driftCounter & 63) == 0) {
            for (int c = 0; c < 3; ++c) {
                oscDriftSt[c] = (oscDriftSt[c] * 0.995f) + (nextWhiteNoise() * 0.005f);
                oscDriftSt[c] = std::clamp(oscDriftSt[c], -0.10f, 0.10f);
            }
            updateFrequencies(oscTunes, driftAmount);
        }

        float fEnvVal = filterEnv.nextLevel();
        float aEnvVal = ampEnv.nextLevel();

        float velFactor = (1.0f - velSensAmount) + (noteVelocity * velSensAmount);
        float velFilterBoost = (1.0f - velSensAmount * 0.4f) + (noteVelocity * velSensAmount * 0.4f);
        float envOctaves = (filterEnvAmtHz / 5000.0f) * 4.5f * fEnvVal * velFilterBoost;

        // Keyboard Tracking 1V/Oct autêntico baseado na nota tocada (sincronizado para os 3 osciladores!)
        float noteOffset = static_cast<float>(currentNote) - 60.0f;
        float keyTrackOctaves = (noteOffset / 12.0f) * keyTrackAmount;

        float totalOctaves = envOctaves + lfoModOctaves + keyTrackOctaves;
        float voiceCutoff = baseCutoff * exp2f(totalOctaves);

        float vcaGain = aEnvVal * velFactor;

        for (int c = 0; c < 3; ++c) {
            float vol = oscVols[c];
            float rawOsc = osc[c].nextSample();

            if (vol <= 0.0005f) {
                // Mantém o estado do filtro atualizado em silêncio para evitar transientes ao abrir o volume
                vcf[c].process(0.0f, voiceCutoff, resonance, driveNorm, oversampleFactor);
                outCh[c] = 0.0f;
                continue;
            }

            // Cada oscilador entra com seu volume exato no seu filtro TPT linear sincronizado,
            // saindo direto para o VCA sem nenhum shelf de sub-grave ou compressor!
            float channelSignal = rawOsc * vol;
            float filteredCh = vcf[c].process(channelSignal, voiceCutoff, resonance, driveNorm, oversampleFactor);

            outCh[c] = filteredCh * vcaGain;
        }
    }
};

// --- MOTOR OBOE COM BARRAMENTO MASTER 100% PURO E LOCK-FREE PARAMS ---
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16;
    std::array<Voice, MAX_VOICES> voices;

    Oscillator lfo;
    AnalogDelay masterDelay;
    DCBlocker dcBlockerL;
    DCBlocker dcBlockerR;

    std::mutex voiceMutex;
    uint64_t voiceCounter = 0;

    // Parâmetros de Oscilador em std::atomic para nunca travar a thread de áudio ao girar knobs!
    std::atomic<float> oscVolumes[3] = { {0.75f}, {0.60f}, {0.50f} };
    std::atomic<float> oscTunes[3] = { {0.0f}, {0.0f}, {-12.0f} };
    std::atomic<int> oscWaves[3] = { {0}, {1}, {2} };

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{2800.0f};
    std::atomic<float> filterResonance{0.20f};
    std::atomic<float> filterEnvAmount{2500.0f};
    std::atomic<float> lfoRate{2.5f}, lfoDepth{0.0f};
    std::atomic<float> delayTime{0.32f}, delayFeedback{0.35f}, delayMix{0.0f};

    std::atomic<int> oversampleFactor{2};
    std::atomic<float> analogDrift{0.05f}, filterDrive{0.0f}, filterKeyTrack{0.50f}, stereoSpread{0.15f}, velocitySens{0.35f};
    std::atomic<int> maxPolyphony{16}, bufferBurstMult{2};
    std::atomic<bool> paramSmoothing{true};

    float smoothCutoff = 2800.0f, smoothRes = 0.20f, smoothMaster = 0.85f, smoothDelayTime = 0.32f;
    float smoothOscVol[3] = {0.75f, 0.60f, 0.50f};
    std::atomic<int> activeVoiceCount{0};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

public:
    AudioEngine() {
        for (int i = 0; i < MAX_VOICES; ++i) voices[i].init(48000.0f, i);
        lfo.init(48000.0f, 0.0);
        lfo.setWaveType(3); // LFO Senoidal puro para modulação suave
        masterDelay.init(48000.0f);
        scopeBuffer.fill(0.0f);
    }

    bool start() {
        std::lock_guard<std::mutex> lock(voiceMutex);
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setDataCallback(this)
               ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result == oboe::Result::OK) {
            float actualSr = static_cast<float>(stream->getSampleRate());
            for (int i = 0; i < MAX_VOICES; ++i) voices[i].init(actualSr, i);
            lfo.init(actualSr, 0.0);
            lfo.setWaveType(3);
            masterDelay.init(actualSr);
            dcBlockerL.clear();
            dcBlockerR.clear();

            int32_t burst = stream->getFramesPerBurst();
            int mult = std::clamp(bufferBurstMult.load(), 1, 8);
            if (burst > 0) stream->setBufferSizeInFrames(burst * mult);

            smoothCutoff = filterCutoff.load();
            smoothRes = filterResonance.load();
            smoothMaster = masterVolume.load();
            smoothDelayTime = delayTime.load();
            for (int c = 0; c < 3; ++c) smoothOscVol[c] = oscVolumes[c].load();

            stream->requestStart();
            return true;
        }
        return false;
    }

    void stop() {
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
    }

    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) {
            std::thread([this]() { this->start(); }).detach();
        }
    }

    void noteOn(int note, int velocity) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        int polyLimit = std::clamp(maxPolyphony.load(), 1, MAX_VOICES);
        float tunes[3] = { oscTunes[0].load(), oscTunes[1].load(), oscTunes[2].load() };

        Voice* targetVoice = nullptr;
        for (int i = 0; i < polyLimit; ++i) {
            if (voices[i].isActive() && voices[i].getNote() == note) {
                targetVoice = &voices[i];
                break;
            }
        }
        if (!targetVoice) {
            for (int i = 0; i < polyLimit; ++i) {
                if (!voices[i].isActive()) {
                    targetVoice = &voices[i];
                    break;
                }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (int i = 0; i < polyLimit; ++i) {
                if (voices[i].isReleasing() && voices[i].getAge() < oldestAge) {
                    oldestAge = voices[i].getAge();
                    targetVoice = &voices[i];
                }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (int i = 0; i < polyLimit; ++i) {
                if (voices[i].getAge() < oldestAge) {
                    oldestAge = voices[i].getAge();
                    targetVoice = &voices[i];
                }
            }
        }
        if (targetVoice) {
            for (int c = 0; c < 3; ++c) targetVoice->setWaveType(c, oscWaves[c].load());
            targetVoice->noteOn(note, velocity, tunes, analogDrift.load(), ++voiceCounter);
        }
    }

    void noteOff(int note) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) {
            if (v.isActive() && v.getNote() == note) v.noteOff(note);
        }
    }

    void allNotesOff() {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.kill();
        masterDelay.clear();
        dcBlockerL.clear();
        dcBlockerR.clear();
    }

    void setOscVolume(int index, float vol) {
        if (index >= 0 && index < 3) {
            oscVolumes[index].store(std::clamp(vol, 0.0f, 1.0f));
        }
    }

    void setOscTune(int index, float semitones) {
        if (index >= 0 && index < 3) {
            oscTunes[index].store(semitones);
            std::lock_guard<std::mutex> lock(voiceMutex);
            float tunes[3] = { oscTunes[0].load(), oscTunes[1].load(), oscTunes[2].load() };
            float drift = analogDrift.load();
            for (auto& v : voices) {
                if (v.isActive()) v.updateFrequencies(tunes, drift);
            }
        }
    }

    void setOscWave(int index, int waveType) {
        if (index >= 0 && index < 3) {
            oscWaves[index].store(waveType);
            std::lock_guard<std::mutex> lock(voiceMutex);
            for (auto& v : voices) v.setWaveType(index, waveType);
        }
    }

    void setMasterVolume(float vol) { masterVolume.store(vol); }
    void setFilterCutoff(float cutoffHz) { filterCutoff.store(cutoffHz); }
    void setFilterResonance(float res) { filterResonance.store(res); }
    void setFilterEnvAmount(float amtHz) { filterEnvAmount.store(amtHz); }
    void setLfoRate(float rateHz) { lfoRate.store(rateHz); }
    void setLfoDepth(float depthHz) { lfoDepth.store(depthHz); }
    void setDelayParams(float timeSec, float fb, float mix) {
        delayTime.store(timeSec);
        delayFeedback.store(fb);
        delayMix.store(mix);
    }

    void setAmpADSR(float a, float d, float s, float r) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setAmpADSR(a, d, s, r);
    }

    void setFilterADSR(float a, float d, float s, float r) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setFilterADSR(a, d, s, r);
    }

    void setOversampling(int factor) { oversampleFactor.store(std::clamp(factor, 1, 4)); }
    void setAnalogDrift(float drift) { analogDrift.store(std::clamp(drift, 0.0f, 1.0f)); }
    void setFilterDrive(float drive) { filterDrive.store(std::clamp(drive, 0.0f, 1.0f)); }
    void setFilterKeyTrack(float track) { filterKeyTrack.store(std::clamp(track, 0.0f, 1.0f)); }
    void setStereoSpread(float spread) { stereoSpread.store(std::clamp(spread, 0.0f, 1.0f)); }
    void setVelocitySensitivity(float sens) { velocitySens.store(std::clamp(sens, 0.0f, 1.0f)); }

    void setMaxPolyphony(int voicesLimit) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        int limit = std::clamp(voicesLimit, 1, MAX_VOICES);
        maxPolyphony.store(limit);
        for (int i = limit; i < MAX_VOICES; ++i) voices[i].kill();
    }

    void setBufferBurstMultiplier(int mult) {
        int cleanMult = std::clamp(mult, 1, 8);
        bufferBurstMult.store(cleanMult);
        if (stream) {
            int32_t burst = stream->getFramesPerBurst();
            if (burst > 0) stream->setBufferSizeInFrames(burst * cleanMult);
        }
    }

    void setParameterSmoothing(bool enabled) { paramSmoothing.store(enabled); }

    void getEngineStats(float* outStats, int len) {
        if (len < 7) return;
        if (stream) {
            outStats[0] = static_cast<float>(stream->getSampleRate());
            outStats[1] = static_cast<float>(stream->getFramesPerBurst());
            outStats[2] = static_cast<float>(stream->getBufferSizeInFrames());
            outStats[3] = (stream->getSampleRate() > 0)
                ? (static_cast<float>(stream->getBufferSizeInFrames()) * 1000.0f / static_cast<float>(stream->getSampleRate()))
                : 0.0f;
            auto xrunsRes = stream->getXRunCount();
            outStats[4] = static_cast<float>(xrunsRes ? xrunsRes.value() : 0);
            outStats[5] = static_cast<float>(activeVoiceCount.load());
            outStats[6] = static_cast<float>(oversampleFactor.load());
        } else {
            for (int i = 0; i < 7; ++i) outStats[i] = 0.0f;
        }
    }

    void copyScopeData(float* dest, int length) {
        int count = std::min(length, SCOPE_SIZE);
        int readIdx = scopeWriteIndex.load();
        for (int i = 0; i < count; ++i) {
            dest[i] = scopeBuffer[(readIdx + i) % SCOPE_SIZE];
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        float targetCutoff = filterCutoff.load();
        float targetRes = filterResonance.load();
        float envAmt = filterEnvAmount.load();
        float targetMaster = masterVolume.load();
        float currentLfoRate = lfoRate.load();
        float currentLfoDepthHz = lfoDepth.load();
        float targetDTime = delayTime.load();
        float dFb = delayFeedback.load();
        float dMix = delayMix.load();

        int osFactor = oversampleFactor.load();
        float drift = analogDrift.load();
        float drive = filterDrive.load();
        float keyTrack = filterKeyTrack.load();
        float spread = stereoSpread.load();
        float velSens = velocitySens.load();
        int polyLimit = std::clamp(maxPolyphony.load(), 1, MAX_VOICES);
        bool useSmoothing = paramSmoothing.load();

        float targetOscVols[3] = {
            oscVolumes[0].load(),
            oscVolumes[1].load(),
            oscVolumes[2].load()
        };
        float currentOscTunes[3] = {
            oscTunes[0].load(),
            oscTunes[1].load(),
            oscTunes[2].load()
        };

        std::unique_lock<std::mutex> lock(voiceMutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            std::fill(outputBuffer, outputBuffer + (numFrames * 2), 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

        lfo.setFrequency(currentLfoRate);

        int wIdx = scopeWriteIndex.load();
        float lfoDepthOctaves = (currentLfoDepthHz / 3000.0f) * 2.0f;
        float smoothCoeff = useSmoothing ? 0.005f : 1.0f;

        int currentlyActive = 0;
        for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
            if (voices[vIdx].isActive()) currentlyActive++;
        }
        activeVoiceCount.store(currentlyActive);

        // Ganho linear unitário por canal de oscilador (0.28f):
        // Permite que os 3 osciladores somados em 100% nunca saturem o barramento Master!
        const float channelUnityGain = 0.28f;

        // Panning sutil apenas de amplitude (Zero atraso de fase / Zero Efeito Haas!)
        const float chPanBias[3] = {0.0f, -0.15f, 0.15f};

        for (int i = 0; i < numFrames; ++i) {
            smoothCutoff += smoothCoeff * (targetCutoff - smoothCutoff);
            smoothRes += smoothCoeff * (targetRes - smoothRes);
            smoothMaster += smoothCoeff * (targetMaster - smoothMaster);
            smoothDelayTime += 0.001f * (targetDTime - smoothDelayTime);

            for (int c = 0; c < 3; ++c) {
                smoothOscVol[c] += smoothCoeff * (targetOscVols[c] - smoothOscVol[c]);
            }

            float lfoModOct = lfo.nextSample() * lfoDepthOctaves;

            // 3 Barramentos de Canal Independentes (Canal 1 = OSC 1, Canal 2 = OSC 2, Canal 3 = OSC 3)
            float chBusL[3] = {0.0f, 0.0f, 0.0f};
            float chBusR[3] = {0.0f, 0.0f, 0.0f};

            for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
                if (!voices[vIdx].isActive()) continue;

                float voiceChOut[3] = {0.0f, 0.0f, 0.0f};
                voices[vIdx].renderChannels(
                    smoothOscVol,
                    currentOscTunes,
                    smoothCutoff,
                    smoothRes,
                    envAmt,
                    lfoModOct,
                    drift,
                    drive,
                    keyTrack,
                    velSens,
                    osFactor,
                    voiceChOut
                );

                float voicePanDir = ((vIdx & 1) == 0) ? -1.0f : 1.0f;
                for (int c = 0; c < 3; ++c) {
                    float pan = std::clamp(spread * ((0.18f * voicePanDir) + chPanBias[c]), -0.85f, 0.85f);
                    // Lei de Pan Constant-Power trigonométrica exata (preserva energia constante L+R)
                    float panAngle = (pan + 1.0f) * 0.25f * static_cast<float>(M_PI);
                    float gainL = cosf(panAngle);
                    float gainR = sinf(panAngle);

                    chBusL[c] += voiceChOut[c] * gainL;
                    chBusR[c] += voiceChOut[c] * gainR;
                }
            }

            // SOMA 100% PURA E LINEAR DOS 3 CANAIS NO BARRAMENTO MASTER:
            // Cada canal de oscilador chega íntegro até a Master sem passar por compressores,
            // sem bass-shelf artificial e sem atrasos de fase entre si!
            float dryMasterL = (chBusL[0] + chBusL[1] + chBusL[2]) * channelUnityGain;
            float dryMasterR = (chBusR[0] + chBusR[1] + chBusR[2]) * channelUnityGain;

            // Delay Estéreo atua como Send/Return paralelo na Master (Dry passa 100% intocado!)
            float delayedL = 0.0f, delayedR = 0.0f;
            masterDelay.processStereo(dryMasterL, dryMasterR, smoothDelayTime, dFb, dMix, spread, delayedL, delayedR);

            float cleanL = dcBlockerL.process(delayedL) * smoothMaster;
            float cleanR = dcBlockerR.process(delayedR) * smoothMaster;

            // Saída Master 100% linear até 0.95 (protege apenas contra wrap digital acima de 1.0)
            float outL = pureMasterSafetyCeiling(cleanL);
            float outR = pureMasterSafetyCeiling(cleanR);

            outputBuffer[i * 2] = outL;
            outputBuffer[i * 2 + 1] = outR;

            if ((i & 3) == 0) {
                scopeBuffer[wIdx] = (outL + outR) * 0.5f;
                wIdx = (wIdx + 1) % SCOPE_SIZE;
            }
        }
        scopeWriteIndex.store(wIdx);
        return oboe::DataCallbackResult::Continue;
    }
};

static AudioEngine* engine = nullptr;

extern "C" {
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_startEngine(JNIEnv *env, jobject thiz) { if (!engine) { engine = new AudioEngine(); engine->start(); } }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_stopEngine(JNIEnv *env, jobject thiz) { if (engine) { engine->stop(); delete engine; engine = nullptr; } }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_noteOn(JNIEnv *env, jobject thiz, jint note, jint velocity) { if (engine) engine->noteOn(note, velocity); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_noteOff(JNIEnv *env, jobject thiz, jint note) { if (engine) engine->noteOff(note); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_allNotesOff(JNIEnv *env, jobject thiz) { if (engine) engine->allNotesOff(); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setOscVolume(JNIEnv *env, jobject thiz, jint index, jfloat volume) { if (engine) engine->setOscVolume(index, volume); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setOscTune(JNIEnv *env, jobject thiz, jint index, jfloat semitones) { if (engine) engine->setOscTune(index, semitones); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setOscWave(JNIEnv *env, jobject thiz, jint index, jint waveType) { if (engine) engine->setOscWave(index, waveType); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setMasterVolume(JNIEnv *env, jobject thiz, jfloat volume) { if (engine) engine->setMasterVolume(volume); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterCutoff(JNIEnv *env, jobject thiz, jfloat cutoffHz) { if (engine) engine->setFilterCutoff(cutoffHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterResonance(JNIEnv *env, jobject thiz, jfloat resonance) { if (engine) engine->setFilterResonance(resonance); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterEnvAmount(JNIEnv *env, jobject thiz, jfloat envAmountHz) { if (engine) engine->setFilterEnvAmount(envAmountHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setLfoRate(JNIEnv *env, jobject thiz, jfloat rateHz) { if (engine) engine->setLfoRate(rateHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setLfoDepth(JNIEnv *env, jobject thiz, jfloat depthHz) { if (engine) engine->setLfoDepth(depthHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setDelayParams(JNIEnv *env, jobject thiz, jfloat timeSec, jfloat feedback, jfloat mix) { if (engine) engine->setDelayParams(timeSec, feedback, mix); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setAmpADSR(JNIEnv *env, jobject thiz, jfloat a, jfloat d, jfloat s, jfloat r) { if (engine) engine->setAmpADSR(a, d, s, r); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterADSR(JNIEnv *env, jobject thiz, jfloat a, jfloat d, jfloat s, jfloat r) { if (engine) engine->setFilterADSR(a, d, s, r); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_getWaveform(JNIEnv *env, jobject thiz, jfloatArray outArray) { if (!engine) return; jsize len = env->GetArrayLength(outArray); jfloat* body = env->GetFloatArrayElements(outArray, nullptr); engine->copyScopeData(body, len); env->ReleaseFloatArrayElements(outArray, body, 0); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setOversampling(JNIEnv *env, jobject thiz, jint factor) { if (engine) engine->setOversampling(factor); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setAnalogDrift(JNIEnv *env, jobject thiz, jfloat drift) { if (engine) engine->setAnalogDrift(drift); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterDrive(JNIEnv *env, jobject thiz, jfloat drive) { if (engine) engine->setFilterDrive(drive); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterKeyTrack(JNIEnv *env, jobject thiz, jfloat track) { if (engine) engine->setFilterKeyTrack(track); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setStereoSpread(JNIEnv *env, jobject thiz, jfloat spread) { if (engine) engine->setStereoSpread(spread); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setVelocitySensitivity(JNIEnv *env, jobject thiz, jfloat sens) { if (engine) engine->setVelocitySensitivity(sens); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setMaxPolyphony(JNIEnv *env, jobject thiz, jint maxVoices) { if (engine) engine->setMaxPolyphony(maxVoices); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setBufferBurstMultiplier(JNIEnv *env, jobject thiz, jint mult) { if (engine) engine->setBufferBurstMultiplier(mult); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setParameterSmoothing(JNIEnv *env, jobject thiz, jboolean enabled) { if (engine) engine->setParameterSmoothing(enabled); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_getEngineStats(JNIEnv *env, jobject thiz, jfloatArray outStats) { if (!engine) return; jsize len = env->GetArrayLength(outStats); jfloat* body = env->GetFloatArrayElements(outStats, nullptr); engine->getEngineStats(body, len); env->ReleaseFloatArrayElements(outStats, body, 0); }
}
