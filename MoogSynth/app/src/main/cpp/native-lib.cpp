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

// Saturação suave usada apenas quando exigida no filtro/delay
static inline float fastTanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x > 3.0f) return 1.0f;
    float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Limitador de Masterização Transparente:
// Mantém o sinal 100% LINEAR (sem achatar a onda) até 0.82, arredondando apenas picos acima disso.
static inline float transparentMasterLimiter(float x) {
    const float threshold = 0.82f;
    float absX = fabsf(x);
    if (absX <= threshold) {
        return x;
    }
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - threshold) / (1.0f - threshold);
    return sign * (threshold + (1.0f - threshold) * fastTanh(excess));
}

// Bloqueador de DC Master ultra-transparente (2Hz)
class DCBlocker {
private:
    float x1 = 0.0f;
    float y1 = 0.0f;
public:
    float process(float x) {
        float y = x - x1 + 0.9996f * y1;
        x1 = x;
        y1 = y;
        return y;
    }
};

// --- OSCILADOR COM POLYBLEP DE ALTA DEFINIÇÃO ---
class Oscillator {
private:
    float sampleRate = 48000.0f;
    float frequency = 440.0f;
    float phase = 0.0f;
    int waveType = 0;

    float polyBlep(float t, float dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0f;
        } else if (t > 1.0f - dt) {
            t = (t - 1.0f) / dt;
            return t * t + t + t + 1.0f;
        }
        return 0.0f;
    }

public:
    void init(float sr, float initialPhase = 0.0f) {
        sampleRate = sr;
        phase = initialPhase - std::floor(initialPhase);
    }

    void setFrequency(float f) {
        frequency = std::clamp(f, 0.05f, sampleRate * 0.47f);
    }

    void setWaveType(int type) {
        waveType = std::clamp(type, 0, 3);
    }

    float nextSample() {
        float dt = frequency / sampleRate;
        phase += dt;
        if (phase >= 1.0f) phase -= 1.0f;

        float sample = 0.0f;
        switch (waveType) {
            case 0: // Sawtooth
                sample = (2.0f * phase) - 1.0f;
                sample -= polyBlep(phase, dt);
                break;
            case 1: // Square
                sample = (phase < 0.5f) ? 1.0f : -1.0f;
                sample += polyBlep(phase, dt);
                sample -= polyBlep(fmodf(phase + 0.5f, 1.0f), dt);
                break;
            case 2: // Triangle
                sample = 2.0f * fabsf(2.0f * phase - 1.0f) - 1.0f;
                break;
            case 3: // Sine
            default:
                sample = sinf(2.0f * static_cast<float>(M_PI) * phase);
                break;
        }
        return sample;
    }
};

// --- ENVELOPE ADSR ANALÓGICO RC ---
class Envelope {
private:
    float sampleRate = 48000.0f;
    enum State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE } state = IDLE;
    float currentLevel = 0.0f;

    float attackTime = 0.008f;
    float decayTime = 0.25f;
    float sustainLevel = 0.7f;
    float releaseTime = 0.3f;

    float attackCoeff = 0.01f;
    float decayCoeff = 0.005f;
    float releaseCoeff = 0.005f;

    void recalcCoeffs() {
        float sr = std::max(8000.0f, sampleRate);
        float atkSamples = std::max(1.0f, attackTime * sr);
        attackCoeff = 1.0f - expf(-2.2f / atkSamples);
        float decSamples = std::max(1.0f, decayTime * sr);
        decayCoeff = 1.0f - expf(-4.5f / decSamples);
        float relSamples = std::max(1.0f, releaseTime * sr);
        releaseCoeff = 1.0f - expf(-4.8f / relSamples);
    }

public:
    void init(float sr) {
        sampleRate = sr;
        recalcCoeffs();
    }

    void setADSR(float a, float d, float s, float r) {
        attackTime = std::max(0.0015f, a);
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
                currentLevel += (1.18f - currentLevel) * attackCoeff;
                if (currentLevel >= 1.0f) {
                    currentLevel = 1.0f;
                    state = DECAY;
                }
                break;
            case DECAY:
                currentLevel += (sustainLevel - currentLevel) * decayCoeff;
                if (fabsf(currentLevel - sustainLevel) < 0.0008f) {
                    currentLevel = sustainLevel;
                    state = SUSTAIN;
                }
                break;
            case SUSTAIN:
                currentLevel += (sustainLevel - currentLevel) * 0.02f;
                break;
            case RELEASE:
                currentLevel += (0.0f - currentLevel) * releaseCoeff;
                if (currentLevel <= 0.0002f) {
                    currentLevel = 0.0f;
                    state = IDLE;
                }
                break;
        }
        return currentLevel;
    }
};

// --- FILTRO MOOG LADDER 24dB TPT (ZERO-DELAY FEEDBACK) ---
// Preserva 100% da dinâmica da onda, brilho cristalino até 18kHz e peso real nos graves
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    float s[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float prevInput = 0.0f;

public:
    void init(float sr) {
        sampleRate = sr;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) s[i] = 0.0f;
        prevInput = 0.0f;
    }

    float process(float input, float cutoffHz, float resonance, float driveNorm, int oversampleFactor) {
        int os = std::clamp(oversampleFactor, 1, 4);
        float effSr = sampleRate * static_cast<float>(os);

        // Compensação do ponto de -3dB de 4 polos em cascata (1 / 0.643 = ~1.55x)
        // Isso garante que o filtro não abafe os médios-agudos prematuramente!
        float fc = std::clamp(cutoffHz * 1.35f, 22.0f, sampleRate * 0.45f);

        // Integrador Trapezoidal (TPT)
        float g = tanf(static_cast<float>(M_PI) * fc / effSr);
        float G = g / (1.0f + g);
        float G2 = G * G;
        float G3 = G2 * G;
        float G4 = G2 * G2;

        float resNorm = std::clamp(resonance, 0.0f, 1.0f);
        float resShaped = powf(resNorm, 1.35f);
        float k = resShaped * 3.82f;

        // Compensação matemática exata da perda de graves do feedback negativo Ladder:
        // No filtro Ladder, o ganho DC cai por (1 + k). Compensamos para que o grave nunca perca o peso!
        float bassGainComp = 1.0f + (k * 0.42f);

        // Drive só satura quando o usuário realmente sobe o parâmetro Filter Drive
        float driveGain = 1.0f + (driveNorm * 1.4f);

        float out = 0.0f;
        float invOs = 1.0f / static_cast<float>(os);

        for (int step = 1; step <= os; ++step) {
            float interpIn = prevInput + (input - prevInput) * (static_cast<float>(step) * invOs);
            float x = interpIn * bassGainComp * driveGain;
            if (driveNorm > 0.05f) {
                x = fastTanh(x) / driveGain;
            }

            // Solução instantânea de Zero-Delay Feedback (sem atraso de 1 sample no loop!)
            float S = (G3 * s[0]) + (G2 * s[1]) + (G * s[2]) + s[3];
            float u = (x - k * fastTanh(S)) / (1.0f + k * G4);

            // 4 estágios trapezoidais lineares de alta definição (não achatam a onda!)
            float v0 = (u - s[0]) * G;
            float y0 = v0 + s[0];
            s[0] = y0 + v0;

            float v1 = (y0 - s[1]) * G;
            float y1 = v1 + s[1];
            s[1] = y1 + v1;

            float v2 = (y1 - s[2]) * G;
            float y2 = v2 + s[2];
            s[2] = y2 + v2;

            float v3 = (y2 - s[3]) * G;
            float y3 = v3 + s[3];
            s[3] = y3 + v3;

            out = y3;
        }

        prevInput = input;
        return out;
    }
};

// --- DELAY ANALÓGICO ESTÉREO ---
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
        if (mix <= 0.001f || bufferL.empty()) {
            outL = inL; outR = inR; return;
        }

        wowPhase += 0.55f / sampleRate;
        if (wowPhase >= 1.0f) wowPhase -= 1.0f;
        flutterPhase += 5.8f / sampleRate;
        if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;

        float tapeModSec = (sinf(2.0f * static_cast<float>(M_PI) * wowPhase) * 0.0007f) +
                           (sinf(2.0f * static_cast<float>(M_PI) * flutterPhase) * 0.00018f);

        float maxDelaySamples = static_cast<float>(bufferL.size()) - 4.0f;
        float baseSamplesL = std::clamp((delayTimeSec + tapeModSec) * sampleRate, 2.0f, maxDelaySamples);
        float spreadOffset = stereoSpread * 0.011f * sampleRate;
        float baseSamplesR = std::clamp(baseSamplesL + spreadOffset, 2.0f, maxDelaySamples);

        float rawL = readFractional(bufferL, baseSamplesL);
        float rawR = readFractional(bufferR, baseSamplesR);

        // Limpa sub-grave apenas das repetições do eco para não embolar o fundo
        hpStateL += 0.022f * (rawL - hpStateL);
        hpStateR += 0.022f * (rawR - hpStateR);
        float bandL = rawL - hpStateL;
        float bandR = rawR - hpStateR;

        lpStateL += 0.32f * (bandL - lpStateL);
        lpStateR += 0.32f * (bandR - lpStateR);

        float fb = std::clamp(feedback, 0.0f, 0.92f);
        float cross = stereoSpread * 0.22f;
        float fbSignalL = lpStateL * (1.0f - cross) + lpStateR * cross;
        float fbSignalR = lpStateR * (1.0f - cross) + lpStateL * cross;

        bufferL[writeIdx] = fastTanh(inL + fbSignalL * fb);
        bufferR[writeIdx] = fastTanh(inR + fbSignalR * fb);
        writeIdx = (writeIdx + 1) % static_cast<int>(bufferL.size());

        // Aditivo: mantém o sinal Dry 100% íntegro e profundo!
        outL = inL + (lpStateL * mix * 0.85f);
        outR = inR + (lpStateR * mix * 0.85f);
    }
};

// --- VOZ POLIFÔNICA DE ALTA PROFUNDIDADE ---
class Voice {
private:
    Oscillator osc[3];
    Envelope ampEnv;
    Envelope filterEnv;
    MoogLadderFilter vcf;

    // Filtro one-pole para encorpar o sub-grave nas notas baixas (Low-End Shelf)
    float bassShelfState = 0.0f;
    float sampleRate = 48000.0f;

    int currentNote = -1;
    uint64_t age = 0;
    float noteVelocity = 1.0f;
    uint32_t rngState = 123456789u;
    int driftCounter = 0;
    float oscDriftSt[3] = {0.0f, 0.0f, 0.0f};
    float oscStaticCalibrationSt[3] = {0.0f, 0.0f, 0.0f};
    float filterCutoffCalibration = 1.0f;

    float nextWhiteNoise() {
        rngState = rngState * 1664525u + 1013904223u;
        return (static_cast<float>(rngState >> 8) / 8388608.0f) - 1.0f;
    }

public:
    void init(float sr, int idx) {
        sampleRate = sr;
        rngState = 13579u + static_cast<uint32_t>(idx * 98765u);
        for (int i = 0; i < 3; ++i) {
            float initPhase = (nextWhiteNoise() + 1.0f) * 0.5f;
            osc[i].init(sr, initPhase);
            oscStaticCalibrationSt[i] = nextWhiteNoise() * 0.035f;
            oscDriftSt[i] = 0.0f;
        }
        filterCutoffCalibration = 1.0f + (nextWhiteNoise() * 0.02f);
        osc[0].setWaveType(0); osc[1].setWaveType(1); osc[2].setWaveType(2);
        ampEnv.init(sr); ampEnv.setADSR(0.01f, 0.3f, 0.7f, 0.3f);
        filterEnv.init(sr); filterEnv.setADSR(0.01f, 0.25f, 0.2f, 0.3f);
        vcf.init(sr);
        bassShelfState = 0.0f;
    }

    void updateFrequencies(const float* oscTunes, float driftAmount) {
        if (currentNote < 0) return;
        for (int i = 0; i < 3; ++i) {
            float totalDetuneSt = oscTunes[i] + (oscStaticCalibrationSt[i] + oscDriftSt[i]) * driftAmount;
            float freq = 440.0f * exp2f((static_cast<float>(currentNote) + totalDetuneSt - 69.0f) / 12.0f);
            osc[i].setFrequency(freq);
        }
    }

    void noteOn(int note, int velocity, const float* oscTunes, float driftAmount, uint64_t noteAge) {
        if (ampEnv.isIdle()) {
            vcf.reset();
            bassShelfState = 0.0f;
        }
        currentNote = note;
        noteVelocity = std::clamp(static_cast<float>(velocity) / 127.0f, 0.05f, 1.0f);
        age = noteAge;
        updateFrequencies(oscTunes, driftAmount);
        ampEnv.triggerOn();
        filterEnv.triggerOn();
    }

    void noteOff(int note) {
        if (currentNote == note) { ampEnv.triggerOff(); filterEnv.triggerOff(); }
    }

    void kill() {
        currentNote = -1; ampEnv.kill(); filterEnv.kill(); vcf.reset(); bassShelfState = 0.0f;
    }

    void setWaveType(int index, int type) { if (index >= 0 && index < 3) osc[index].setWaveType(type); }
    void setAmpADSR(float a, float d, float s, float r) { ampEnv.setADSR(a, d, s, r); }
    void setFilterADSR(float a, float d, float s, float r) { filterEnv.setADSR(a, d, s, r); }
    bool isActive() const { return !ampEnv.isIdle(); }
    bool isReleasing() const { return ampEnv.isReleasing(); }
    int getNote() const { return currentNote; }
    uint64_t getAge() const { return age; }

    float renderSample(const float* oscVols, const float* oscTunes, float baseCutoff, float resonance, float filterEnvAmtHz, float lfoModOctaves, float driftAmount, float driveNorm, float keyTrackAmount, float velSensAmount, int oversampleFactor) {
        if (ampEnv.isIdle()) return 0.0f;

        if ((++driftCounter & 63) == 0) {
            for (int i = 0; i < 3; ++i) {
                oscDriftSt[i] = (oscDriftSt[i] * 0.996f) + (nextWhiteNoise() * 0.004f);
                oscDriftSt[i] = std::clamp(oscDriftSt[i], -0.12f, 0.12f);
            }
            updateFrequencies(oscTunes, driftAmount);
        }

        float rawMix = (osc[0].nextSample() * oscVols[0]) +
                       (osc[1].nextSample() * oscVols[1]) +
                       (osc[2].nextSample() * oscVols[2]);

        float fEnvVal = filterEnv.nextLevel();
        float velFactor = (1.0f - velSensAmount) + (noteVelocity * velSensAmount);
        float velFilterBoost = (1.0f - velSensAmount * 0.5f) + (noteVelocity * velSensAmount * 0.5f);

        float envOctaves = (filterEnvAmtHz / 5000.0f) * 4.5f * fEnvVal * velFilterBoost;

        // Nas notas graves (< MIDI 60), o Key Tracking é suavizado (apenas 25%) para NUNCA fechar
        // o filtro nas notas baixas, enquanto nas notas agudas (> 60) ele abre 100% para dar brilho!
        float noteOffset = static_cast<float>(currentNote) - 60.0f;
        float keyTrackOctaves = (noteOffset >= 0.0f)
            ? ((noteOffset / 12.0f) * keyTrackAmount)
            : ((noteOffset / 12.0f) * keyTrackAmount * 0.25f);

        float totalOctaves = envOctaves + lfoModOctaves + keyTrackOctaves;
        float voiceDriftFactor = 1.0f + ((filterCutoffCalibration - 1.0f) * driftAmount);
        float modulatedCutoff = baseCutoff * voiceDriftFactor * exp2f(totalOctaves);

        // Passa pelo novo Filtro Moog TPT linear sem esmagar os picos da onda
        float filteredSignal = vcf.process(rawMix * 0.50f, modulatedCutoff, resonance, driveNorm, oversampleFactor);

        // CONTORNO SUB-BASS MOOG: Dá peso, corpo e presença às notas graves (< C4)
        float lowNoteWeight = std::clamp((64.0f - static_cast<float>(currentNote)) / 28.0f, 0.0f, 1.0f);
        float shelfAlpha = std::clamp(1100.0f / sampleRate, 0.001f, 0.2f);
        bassShelfState += shelfAlpha * (filteredSignal - bassShelfState);
        float fullBodySignal = filteredSignal + (bassShelfState * lowNoteWeight * 0.65f);

        // Saída do VCA 100% linear
        return fullBodySignal * ampEnv.nextLevel() * velFactor;
    }
};

// --- MOTOR OBOE ---
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16;
    std::array<Voice, MAX_VOICES> voices;
    Oscillator lfo;
    AnalogDelay tapeDelay;
    DCBlocker dcBlockerL, dcBlockerR;
    std::mutex voiceMutex;
    uint64_t voiceCounter = 0;

    float oscVolumes[3] = {0.75f, 0.55f, 0.45f};
    float oscTunes[3] = {0.0f, -12.0f, 7.0f};

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{1800.0f};
    std::atomic<float> filterResonance{0.35f};
    std::atomic<float> filterEnvAmount{2500.0f};
    std::atomic<float> lfoRate{2.5f}, lfoDepth{0.0f};
    std::atomic<float> delayTime{0.32f}, delayFeedback{0.4f}, delayMix{0.2f};

    std::atomic<int> oversampleFactor{2};
    std::atomic<float> analogDrift{0.35f}, filterDrive{0.25f}, filterKeyTrack{0.50f}, stereoSpread{0.45f}, velocitySens{0.35f};
    std::atomic<int> maxPolyphony{16}, bufferBurstMult{2};
    std::atomic<bool> paramSmoothing{true};

    float smoothCutoff = 1800.0f, smoothRes = 0.35f, smoothMaster = 0.85f, smoothDelayTime = 0.32f;
    std::atomic<int> activeVoiceCount{0};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

public:
    AudioEngine() {
        for (int i = 0; i < MAX_VOICES; ++i) voices[i].init(48000.0f, i);
        lfo.init(48000.0f); lfo.setWaveType(2); tapeDelay.init(48000.0f);
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
            lfo.init(actualSr); tapeDelay.init(actualSr);
            int32_t burst = stream->getFramesPerBurst();
            int mult = std::clamp(bufferBurstMult.load(), 1, 8);
            if (burst > 0) stream->setBufferSizeInFrames(burst * mult);
            smoothCutoff = filterCutoff.load(); smoothRes = filterResonance.load();
            smoothMaster = masterVolume.load(); smoothDelayTime = delayTime.load();
            stream->requestStart();
            return true;
        }
        return false;
    }

    void stop() { if (stream) { stream->requestStop(); stream->close(); stream.reset(); } }
    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) std::thread([this]() { this->start(); }).detach();
    }

    void noteOn(int note, int velocity) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        int polyLimit = std::clamp(maxPolyphony.load(), 1, MAX_VOICES);
        Voice* targetVoice = nullptr;
        for (int i = 0; i < polyLimit; ++i) {
            if (voices[i].isActive() && voices[i].getNote() == note) { targetVoice = &voices[i]; break; }
        }
        if (!targetVoice) {
            for (int i = 0; i < polyLimit; ++i) {
                if (!voices[i].isActive()) { targetVoice = &voices[i]; break; }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (int i = 0; i < polyLimit; ++i) {
                if (voices[i].isReleasing() && voices[i].getAge() < oldestAge) { oldestAge = voices[i].getAge(); targetVoice = &voices[i]; }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (int i = 0; i < polyLimit; ++i) {
                if (voices[i].getAge() < oldestAge) { oldestAge = voices[i].getAge(); targetVoice = &voices[i]; }
            }
        }
        if (targetVoice) targetVoice->noteOn(note, velocity, oscTunes, analogDrift.load(), ++voiceCounter);
    }

    void noteOff(int note) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) { if (v.isActive() && v.getNote() == note) v.noteOff(note); }
    }
    void allNotesOff() { std::lock_guard<std::mutex> lock(voiceMutex); for (auto& v : voices) v.kill(); tapeDelay.clear(); }

    void setOscVolume(int index, float vol) { std::lock_guard<std::mutex> lock(voiceMutex); if (index >= 0 && index < 3) oscVolumes[index] = std::clamp(vol, 0.0f, 1.0f); }
    void setOscTune(int index, float semitones) { std::lock_guard<std::mutex> lock(voiceMutex); if (index >= 0 && index < 3) { oscTunes[index] = semitones; float drift = analogDrift.load(); for (auto& v : voices) { if (v.isActive()) v.updateFrequencies(oscTunes, drift); } } }
    void setOscWave(int index, int waveType) { std::lock_guard<std::mutex> lock(voiceMutex); for (auto& v : voices) v.setWaveType(index, waveType); }
    void setMasterVolume(float vol) { masterVolume = vol; }
    void setFilterCutoff(float cutoffHz) { filterCutoff = cutoffHz; }
    void setFilterResonance(float res) { filterResonance = res; }
    void setFilterEnvAmount(float amtHz) { filterEnvAmount = amtHz; }
    void setLfoRate(float rateHz) { lfoRate = rateHz; }
    void setLfoDepth(float depthHz) { lfoDepth = depthHz; }
    void setDelayParams(float timeSec, float fb, float mix) { delayTime = timeSec; delayFeedback = fb; delayMix = mix; }
    void setAmpADSR(float a, float d, float s, float r) { std::lock_guard<std::mutex> lock(voiceMutex); for (auto& v : voices) v.setAmpADSR(a, d, s, r); }
    void setFilterADSR(float a, float d, float s, float r) { std::lock_guard<std::mutex> lock(voiceMutex); for (auto& v : voices) v.setFilterADSR(a, d, s, r); }

    void setOversampling(int factor) { oversampleFactor = std::clamp(factor, 1, 4); }
    void setAnalogDrift(float drift) { analogDrift = std::clamp(drift, 0.0f, 1.0f); }
    void setFilterDrive(float drive) { filterDrive = std::clamp(drive, 0.0f, 1.0f); }
    void setFilterKeyTrack(float track) { filterKeyTrack = std::clamp(track, 0.0f, 1.0f); }
    void setStereoSpread(float spread) { stereoSpread = std::clamp(spread, 0.0f, 1.0f); }
    void setVelocitySensitivity(float sens) { velocitySens = std::clamp(sens, 0.0f, 1.0f); }
    void setMaxPolyphony(int voicesLimit) { std::lock_guard<std::mutex> lock(voiceMutex); int limit = std::clamp(voicesLimit, 1, MAX_VOICES); maxPolyphony = limit; for (int i = limit; i < MAX_VOICES; ++i) voices[i].kill(); }
    void setBufferBurstMultiplier(int mult) { int cleanMult = std::clamp(mult, 1, 8); bufferBurstMult = cleanMult; if (stream) { int32_t burst = stream->getFramesPerBurst(); if (burst > 0) stream->setBufferSizeInFrames(burst * cleanMult); } }
    void setParameterSmoothing(bool enabled) { paramSmoothing = enabled; }

    void getEngineStats(float* outStats, int len) {
        if (len < 7) return;
        if (stream) {
            outStats[0] = static_cast<float>(stream->getSampleRate());
            outStats[1] = static_cast<float>(stream->getFramesPerBurst());
            outStats[2] = static_cast<float>(stream->getBufferSizeInFrames());
            outStats[3] = (stream->getSampleRate() > 0) ? (static_cast<float>(stream->getBufferSizeInFrames()) * 1000.0f / static_cast<float>(stream->getSampleRate())) : 0.0f;
            auto xrunsRes = stream->getXRunCount();
            outStats[4] = static_cast<float>(xrunsRes ? xrunsRes.value() : 0);
            outStats[5] = static_cast<float>(activeVoiceCount.load());
            outStats[6] = static_cast<float>(oversampleFactor.load());
        } else { for (int i = 0; i < 7; ++i) outStats[i] = 0.0f; }
    }

    void copyScopeData(float* dest, int length) {
        int count = std::min(length, SCOPE_SIZE);
        int readIdx = scopeWriteIndex.load();
        for (int i = 0; i < count; ++i) dest[i] = scopeBuffer[(readIdx + i) % SCOPE_SIZE];
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        float targetCutoff = filterCutoff.load(), targetRes = filterResonance.load(), envAmt = filterEnvAmount.load(), targetMaster = masterVolume.load();
        float currentLfoRate = lfoRate.load(), currentLfoDepthHz = lfoDepth.load(), targetDTime = delayTime.load(), dFb = delayFeedback.load(), dMix = delayMix.load();
        int osFactor = oversampleFactor.load(); float drift = analogDrift.load(), drive = filterDrive.load(), keyTrack = filterKeyTrack.load(), spread = stereoSpread.load(), velSens = velocitySens.load();
        int polyLimit = std::clamp(maxPolyphony.load(), 1, MAX_VOICES); bool useSmoothing = paramSmoothing.load();

        std::unique_lock<std::mutex> lock(voiceMutex, std::try_to_lock);
        if (!lock.owns_lock()) { std::fill(outputBuffer, outputBuffer + (numFrames * 2), 0.0f); return oboe::DataCallbackResult::Continue; }

        lfo.setFrequency(currentLfoRate);
        int wIdx = scopeWriteIndex.load();
        float lfoDepthOctaves = (currentLfoDepthHz / 3000.0f) * 2.2f;
        float smoothCoeff = useSmoothing ? 0.0045f : 1.0f;

        int currentlyActive = 0;
        for (int vIdx = 0; vIdx < polyLimit; ++vIdx) if (voices[vIdx].isActive()) currentlyActive++;
        activeVoiceCount.store(currentlyActive);

        // GANHO FIXO ESTÁVEL (0.42f):
        // Nunca varia quando notas entram ou saem do Release -> Elimina 100% o "soquinho"
        // e nunca esmaga o grave quando você toca acordes!
        const float fixedVoiceHeadroom = 0.42f;

        for (int i = 0; i < numFrames; ++i) {
            smoothCutoff += smoothCoeff * (targetCutoff - smoothCutoff);
            smoothRes += smoothCoeff * (targetRes - smoothRes);
            smoothMaster += smoothCoeff * (targetMaster - smoothMaster);
            smoothDelayTime += 0.0008f * (targetDTime - smoothDelayTime);

            float lfoModOct = lfo.nextSample() * lfoDepthOctaves;
            float sumL = 0.0f, sumR = 0.0f;

            for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
                if (!voices[vIdx].isActive()) continue;
                float voiceSample = voices[vIdx].renderSample(oscVolumes, oscTunes, smoothCutoff, smoothRes, envAmt, lfoModOct, drift, drive, keyTrack, velSens, osFactor);
                float panDir = ((vIdx & 1) == 0) ? -1.0f : 1.0f;
                float voiceSpread = spread * 0.35f * panDir;
                float gainL = 0.707f * (1.0f - voiceSpread);
                float gainR = 0.707f * (1.0f + voiceSpread);
                sumL += voiceSample * gainL;
                sumR += voiceSample * gainR;
            }

            sumL *= fixedVoiceHeadroom;
            sumR *= fixedVoiceHeadroom;

            float delayedL = 0.0f, delayedR = 0.0f;
            tapeDelay.processStereo(sumL, sumR, smoothDelayTime, dFb, dMix, spread, delayedL, delayedR);

            float cleanL = dcBlockerL.process(delayedL);
            float cleanR = dcBlockerR.process(delayedR);

            // Saída linear pura com proteção transparente apenas nos picos extremos
            float outL = transparentMasterLimiter(cleanL * smoothMaster);
            float outR = transparentMasterLimiter(cleanR * smoothMaster);

            outputBuffer[i * 2] = outL;
            outputBuffer[i * 2 + 1] = outR;

            if ((i & 3) == 0) { scopeBuffer[wIdx] = (outL + outR) * 0.5f; wIdx = (wIdx + 1) % SCOPE_SIZE; }
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
