#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <cstdint>
#include <array>
#include <vector>
#include <atomic>
#include <algorithm>
#include <thread>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ==============================================================================
// 1. UTILITﾃヽIOS MATEMﾃゝICOS & SATURAﾃ�ﾃグ SUAVE
// ==============================================================================

// Aproximaﾃｧﾃ｣o Padﾃｩ de alta velocidade e continuidade para tanh(x)
static inline float fastTanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x > 3.0f) return 1.0f;
    float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Limitador de estﾃｺdio transparente (Soft-Knee Master Limiter)
// 100% linear atﾃｩ threshold (0.75 / -2.5 dBFS); curva suave acima disso sem hard-clipping
static inline float transparentMasterLimiter(float x) {
    const float threshold = 0.75f;
    const float limit = 0.985f;
    float absX = std::fabs(x);
    if (absX <= threshold) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - threshold) / (limit - threshold);
    return sign * (threshold + (limit - threshold) * fastTanh(excess));
}

// Bloqueador de DC de 1ﾂｪ ordem adaptativo com corte estﾃ｡vel ~2.0Hz em qualquer sample rate
class DCBlocker {
private:
    double x1 = 0.0;
    double y1 = 0.0;
    double r = 0.9998;
public:
    void init(float sr) {
        double f0 = 2.0;
        r = 1.0 - (2.0 * M_PI * f0 / static_cast<double>(sr));
        r = std::clamp(r, 0.99, 0.99999);
        clear();
    }
    void clear() { x1 = 0.0; y1 = 0.0; }
    inline float process(float x) {
        double in = static_cast<double>(x);
        double y = in - x1 + r * y1;
        x1 = in;
        y1 = y;
        return static_cast<float>(y);
    }
};

// ==============================================================================
// 2. FILTRO HALF-BAND POLIFﾃヾICO IIR PARA OVERSAMPLING REAL 2X
// ==============================================================================
// Topologia All-Pass Polyphase (Laurent de Soras / HIIR)
// Elimina o aliasing no upsampling e decima sem dobrar ruﾃｭdo acima de Nyquist.
class HalfBand2x {
private:
    const double a0 = 0.07986642623635751;
    const double a1 = 0.5453536510711322;
    const double b0 = 0.28382934487410993;
    const double b1 = 0.8344118914807379;

    double up_x0 = 0.0, up_x1 = 0.0, up_y0 = 0.0, up_y1 = 0.0;
    double up_z0 = 0.0, up_z1 = 0.0, up_w0 = 0.0, up_w1 = 0.0;
    double up_prevB = 0.0;

    double down_x0 = 0.0, down_x1 = 0.0, down_y0 = 0.0, down_y1 = 0.0;
    double down_z0 = 0.0, down_z1 = 0.0, down_w0 = 0.0, down_w1 = 0.0;
    double down_prevB = 0.0;

public:
    void reset() {
        up_x0 = up_x1 = up_y0 = up_y1 = 0.0;
        up_z0 = up_z1 = up_w0 = up_w1 = 0.0;
        up_prevB = 0.0;

        down_x0 = down_x1 = down_y0 = down_y1 = 0.0;
        down_z0 = down_z1 = down_w0 = down_w1 = 0.0;
        down_prevB = 0.0;
    }

    // Upsample 1x -> 2x com filtro passa-baixas anti-imaging
    inline void upsample(double in, double& out0, double& out1) {
        // Ramo A
        double o0_A = a0 * (in - up_y0) + up_x0; up_x0 = in; up_y0 = o0_A;
        double o1_A = a1 * (o0_A - up_y1) + up_x1; up_x1 = o0_A; up_y1 = o1_A;

        out0 = o1_A;
        out1 = up_prevB;

        // Ramo B
        double o0_B = b0 * (in - up_w0) + up_z0; up_z0 = in; up_w0 = o0_B;
        double o1_B = b1 * (o0_B - up_w1) + up_z1; up_z1 = o0_B; up_w1 = o1_B;
        up_prevB = o1_B;
    }

    // Downsample 2x -> 1x com filtro anti-aliasing (decimaﾃｧﾃ｣o pura)
    inline double downsample(double in0, double in1) {
        // Ramo A em in0
        double o0_A = a0 * (in0 - down_y0) + down_x0; down_x0 = in0; down_y0 = o0_A;
        double o1_A = a1 * (o0_A - down_y1) + down_x1; down_x1 = o0_A; down_y1 = o1_A;

        double branchA = o1_A;
        double branchB = down_prevB;

        // Ramo B em in1
        double o0_B = b0 * (in1 - down_w0) + down_z0; down_z0 = in1; down_w0 = o0_B;
        double o1_B = b1 * (o0_B - down_w1) + down_z1; down_z1 = o0_B; down_w1 = o1_B;
        down_prevB = o1_B;

        return 0.5 * (branchA + branchB);
    }
};

// ==============================================================================
// 3. FILA LOCK-FREE SPSC PARA EVENTOS MIDI (ZERO BLOQUEIO DE THREAD DE ﾃゞDIO)
// ==============================================================================
struct MidiEvent {
    enum Type : uint8_t { NOTE_ON = 1, NOTE_OFF = 2, ALL_NOTES_OFF = 3 } type;
    int note;
    int velocity;
};

class LockFreeMidiQueue {
private:
    static constexpr size_t CAPACITY = 256;
    std::array<MidiEvent, CAPACITY> buffer{};
    std::atomic<size_t> writeIdx{0};
    std::atomic<size_t> readIdx{0};

public:
    bool push(const MidiEvent& ev) {
        size_t w = writeIdx.load(std::memory_order_relaxed);
        size_t nextW = (w + 1) % CAPACITY;
        if (nextW == readIdx.load(std::memory_order_acquire)) return false; // Buffer cheio
        buffer[w] = ev;
        writeIdx.store(nextW, std::memory_order_release);
        return true;
    }

    bool pop(MidiEvent& ev) {
        size_t r = readIdx.load(std::memory_order_relaxed);
        if (r == writeIdx.load(std::memory_order_acquire)) return false; // Buffer vazio
        ev = buffer[r];
        readIdx.store((r + 1) % CAPACITY, std::memory_order_release);
        return true;
    }
};

// ==============================================================================
// 4. OSCILADOR ANALﾃ敵ICO FREE-RUNNING COM POLYBLEP / POLYBLAMP
// ==============================================================================
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

    void setFrequency(float f) {
        double maxFreq = sampleRate * 0.475;
        frequency = std::clamp(static_cast<double>(f), 0.05, maxFreq);
    }

    void setWaveType(int type) {
        waveType = std::clamp(type, 0, 3);
    }

    // Osciladores rodam continuamente (free-running analﾃｳgico autﾃｪntico)
    inline float nextSample() {
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
            case 1: { // Square Pura (50% Duty) com PolyBLEP em ambas as bordas
                sample = (phase < 0.5) ? 1.0 : -1.0;
                sample += polyBlep(phase, dt);
                double phase2 = phase + 0.5;
                if (phase2 >= 1.0) phase2 -= 1.0;
                sample -= polyBlep(phase2, dt);
                break;
            }
            case 2: { // Triangle Pura com correﾃｧﾃ｣o PolyBLAMP
                double p = phase;
                sample = 1.0 - 4.0 * std::fabs(p - 0.5);
                double p2 = p + 0.5;
                if (p2 >= 1.0) p2 -= 1.0;
                sample += 4.0 * dt * (polyBlamp(p, dt) - polyBlamp(p2, dt));
                break;
            }
            case 3: // Sine Analﾃｳgica Pura
            default: {
                sample = std::sin(2.0 * M_PI * phase);
                break;
            }
        }
        return static_cast<float>(sample);
    }
};

// ==============================================================================
// 5. ENVELOPE RC EXPONENCIAL (MOOG ADSR COM TRANSIENTES CONTﾃ康UOS)
// ==============================================================================
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

    // O ataque de uma nova nota parte do nﾃｭvel instantﾃ｢neo anterior (Zero-Click Stealing)
    void triggerOn() { state = ATTACK; }
    void triggerOff() { if (state != IDLE) state = RELEASE; }
    void kill() { state = IDLE; currentLevel = 0.0f; }

    bool isIdle() const { return state == IDLE; }
    bool isReleasing() const { return state == RELEASE; }

    inline float nextLevel() {
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
                if (std::fabs(currentLevel - sustainLevel) < 0.0005f) {
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

// ==============================================================================
// 6. FILTRO MOOG LADDER 24dB TPT/ZDF COM OVERSAMPLING REAL & PRESERVAﾃ�ﾃグ DE GRAVES
// ==============================================================================
// Topologia Zero-Delay Feedback (Mystran / PNR Non-linear approximation)
// Garante estabilidade matemﾃ｡tica da auto-oscilaﾃｧﾃ｣o e graves sem corte em ressonﾃ｢ncia.
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    double s[4] = {0.0, 0.0, 0.0, 0.0};
    HalfBand2x halfBand;

    inline double processCore(double inSample, double G, double G4, double k, double invOnePlusKG4) {
        double S = (G * G * G * s[0]) + (G * G * s[1]) + (G * s[2]) + s[3];
        // Saturaﾃｧﾃ｣o Padﾃｩ no loop de realimentaﾃｧﾃ｣o
        double fb = static_cast<double>(fastTanh(static_cast<float>(S)));
        double u = (inSample - k * fb) * invOnePlusKG4;

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

        return y3;
    }

public:
    void init(float sr) {
        sampleRate = sr;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) s[i] = 0.0;
        halfBand.reset();
    }

    float process(float input, float cutoffHz, float resonance, float driveNorm, int oversampleFactor) {
        double fc = std::clamp(static_cast<double>(cutoffHz), 20.0, static_cast<double>(sampleRate) * 0.46);
        double resNorm = std::clamp(static_cast<double>(resonance), 0.0, 1.0);
        double k = std::pow(resNorm, 1.25) * 3.85;

        // Compensaﾃｧﾃ｣o DC na banda passante do Moog: 1 + k * 0.85
        double passbandComp = 1.0 + (k * 0.85);

        if (oversampleFactor >= 2) {
            // OVERSAMPLING 2X REAL:
            // 1. Upsampling com filtro polifﾃ｡sico
            double up0 = 0.0, up1 = 0.0;
            halfBand.upsample(static_cast<double>(input), up0, up1);

            double effSr = static_cast<double>(sampleRate) * 2.0;
            double g = std::tan(M_PI * fc / effSr);
            double G = g / (1.0 + g);
            double G4 = G * G * G * G;
            double invOnePlusKG4 = 1.0 / (1.0 + k * G4);

            // 2. Drive e saturaﾃｧﾃ｣o acontecem DENTRO da taxa expandida (sem aliasing)
            float drv = 1.0f + (driveNorm * 2.2f);
            double in0 = (driveNorm > 0.005f) ? (static_cast<double>(fastTanh(static_cast<float>(up0) * drv) / fastTanh(drv))) : up0;
            double in1 = (driveNorm > 0.005f) ? (static_cast<double>(fastTanh(static_cast<float>(up1) * drv) / fastTanh(drv))) : up1;

            in0 *= passbandComp;
            in1 *= passbandComp;

            double y0 = processCore(in0, G, G4, k, invOnePlusKG4);
            double y1 = processCore(in1, G, G4, k, invOnePlusKG4);

            // 3. Decimaﾃｧﾃ｣o com filtro anti-aliasing
            return static_cast<float>(halfBand.downsample(y0, y1));
        } else {
            // MODO 1X DIRETO
            double g = std::tan(M_PI * fc / static_cast<double>(sampleRate));
            double G = g / (1.0 + g);
            double G4 = G * G * G * G;
            double invOnePlusKG4 = 1.0 / (1.0 + k * G4);

            double inSignal = static_cast<double>(input);
            if (driveNorm > 0.005f) {
                float drv = 1.0f + (driveNorm * 2.2f);
                inSignal = static_cast<double>(fastTanh(input * drv) / fastTanh(drv));
            }
            double x = inSignal * passbandComp;
            return static_cast<float>(processCore(x, G, G4, k, invOnePlusKG4));
        }
    }
};

// ==============================================================================
// 7. STEREO TAPE DELAY COM INTERPOLAﾃ�ﾃグ Cﾃ咤ICA DE HERMITE (TRANSPARﾃ劾CIA TOTAL)
// ==============================================================================
class AnalogDelay {
private:
    std::vector<float> bufferL;
    std::vector<float> bufferR;
    int writeIdx = 0;
    float sampleRate = 48000.0f;
    float lpStateL = 0.0f, lpStateR = 0.0f;
    float hpStateL = 0.0f, hpStateR = 0.0f;
    float wowPhase = 0.0f, flutterPhase = 0.0f;
    float lpfAlpha = 0.45f, hpfAlpha = 0.015f;

    // Interpolaﾃｧﾃ｣o Cﾃｺbica de Hermite (4 pontos) - Preserva agudos nas modulaﾃｧﾃｵes
    inline float readFractionalHermite(const std::vector<float>& buf, float delaySamples) const {
        int size = static_cast<int>(buf.size());
        float readPos = static_cast<float>(writeIdx) - delaySamples;
        while (readPos < 0.0f) readPos += static_cast<float>(size);

        int idx1 = static_cast<int>(readPos) % size;
        int idx0 = (idx1 - 1 + size) % size;
        int idx2 = (idx1 + 1) % size;
        int idx3 = (idx1 + 2) % size;
        float frac = readPos - std::floor(readPos);

        float y0 = buf[idx0], y1 = buf[idx1], y2 = buf[idx2], y3 = buf[idx3];
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);

        return ((c3 * frac + c2) * frac + c1) * frac + c0;
    }

public:
    void init(float sr) {
        sampleRate = sr;
        size_t maxSamples = static_cast<size_t>(sr * 1.6f);
        bufferL.assign(maxSamples, 0.0f);
        bufferR.assign(maxSamples, 0.0f);
        writeIdx = 0;

        // Coeficientes dos filtros de fita calibrados independentes da taxa de amostragem
        lpfAlpha = 1.0f - expf(-2.0f * static_cast<float>(M_PI) * 4500.0f / sr);
        hpfAlpha = 1.0f - expf(-2.0f * static_cast<float>(M_PI) * 45.0f / sr);
        clear();
    }

    void clear() {
        std::fill(bufferL.begin(), bufferL.end(), 0.0f);
        std::fill(bufferR.begin(), bufferR.end(), 0.0f);
        lpStateL = hpStateL = lpStateR = hpStateR = 0.0f;
    }

    void processStereo(float inL, float inR, float delayTimeSec, float feedback, float mix, float stereoSpread, float& outL, float& outR) {
        if (mix <= 0.001f || bufferL.empty()) {
            outL = inL;
            outR = inR;
            return;
        }

        wowPhase += 0.50f / sampleRate;
        if (wowPhase >= 1.0f) wowPhase -= 1.0f;
        flutterPhase += 5.2f / sampleRate;
        if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;

        float modDepth = 0.20f + (stereoSpread * 0.80f);
        float tapeModSec = ((sinf(2.0f * static_cast<float>(M_PI) * wowPhase) * 0.00030f) +
                            (sinf(2.0f * static_cast<float>(M_PI) * flutterPhase) * 0.00007f)) * modDepth;

        float maxDelaySamples = static_cast<float>(bufferL.size()) - 4.0f;
        float delaySamplesL = std::clamp((delayTimeSec + tapeModSec) * sampleRate, 3.0f, maxDelaySamples);
        float delaySamplesR = std::clamp((delayTimeSec - tapeModSec) * sampleRate, 3.0f, maxDelaySamples);

        float rawL = readFractionalHermite(bufferL, delaySamplesL);
        float rawR = readFractionalHermite(bufferR, delaySamplesR);

        hpStateL += hpfAlpha * (rawL - hpStateL);
        hpStateR += hpfAlpha * (rawR - hpStateR);
        float bandL = rawL - hpStateL;
        float bandR = rawR - hpStateR;

        lpStateL += lpfAlpha * (bandL - lpStateL);
        lpStateR += lpfAlpha * (bandR - lpStateR);

        float fb = std::clamp(feedback, 0.0f, 0.90f);
        float cross = stereoSpread * 0.15f;
        float fbSignalL = lpStateL * (1.0f - cross) + lpStateR * cross;
        float fbSignalR = lpStateR * (1.0f - cross) + lpStateL * cross;

        bufferL[writeIdx] = fastTanh(inL + fbSignalL * fb);
        bufferR[writeIdx] = fastTanh(inR + fbSignalR * fb);
        writeIdx = (writeIdx + 1) % static_cast<int>(bufferL.size());

        outL = inL + (lpStateL * mix * 0.70f);
        outR = inR + (lpStateR * mix * 0.70f);
    }
};

// ==============================================================================
// 8. VOZ POLIFﾃ年ICA COM OSCILADORES FREE-RUNNING E SINERGIA MOOG
// ==============================================================================
class Voice {
private:
    Oscillator osc[3];
    MoogLadderFilter vcf;
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

        // Fases iniciais pseudo-aleatﾃｳrias para oscilaﾃｧﾃ｣o livre natural (free-running)
        double phOffsets[3] = { 0.13 * idx, 0.37 * idx + 0.25, 0.71 * idx + 0.5 };
        for (int c = 0; c < 3; ++c) {
            osc[c].init(sr, phOffsets[c]);
            oscDriftSt[c] = 0.0f;
        }
        osc[0].setWaveType(0);
        osc[1].setWaveType(1);
        osc[2].setWaveType(2);

        vcf.init(sr);
        ampEnv.init(sr);
        ampEnv.setADSR(0.008f, 0.3f, 0.8f, 0.25f);
        filterEnv.init(sr);
        filterEnv.setADSR(0.008f, 0.25f, 0.35f, 0.25f);
    }

    void updateFrequencies(const float* oscTunes, float driftAmount) {
        if (currentNote < 0) return;
        for (int c = 0; c < 3; ++c) {
            float totalDetuneSt = oscTunes[c] + (oscDriftSt[c] * driftAmount * 0.30f);
            float freq = 440.0f * exp2f((static_cast<float>(currentNote) + totalDetuneSt - 69.0f) / 12.0f);
            osc[c].setFrequency(freq);
        }
    }

    void noteOn(int note, int velocity, const float* oscTunes, float driftAmount, uint64_t noteAge) {
        // Nﾃグ zera a fase nem o filtro abruptamente: preserva a continuidade fﾃｭsica analﾃｳgica
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
        vcf.reset();
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

    // RENDERIZAﾃ�ﾃグ ESTﾃ嘘EO POR VOZ
    // Os osciladores 1, 2 e 3 geram o som somado no mixer da voz -> VCF Moog -> VCA
    // e sﾃ｣o espacializados de forma coerente no barramento L/R.
    void renderStereo(
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
        float voicePan,
        float& voiceOutL,
        float& voiceOutR
    ) {
        if (ampEnv.isIdle()) {
            voiceOutL = 0.0f;
            voiceOutR = 0.0f;
            return;
        }

        if ((++driftCounter & 63) == 0) {
            for (int c = 0; c < 3; ++c) {
                oscDriftSt[c] = (oscDriftSt[c] * 0.995f) + (nextWhiteNoise() * 0.005f);
                oscDriftSt[c] = std::clamp(oscDriftSt[c], -0.08f, 0.08f);
            }
            updateFrequencies(oscTunes, driftAmount);
        }

        float fEnvVal = filterEnv.nextLevel();
        float aEnvVal = ampEnv.nextLevel();

        float velFactor = (1.0f - velSensAmount) + (noteVelocity * velSensAmount);
        float velFilterBoost = (1.0f - velSensAmount * 0.35f) + (noteVelocity * velSensAmount * 0.35f);
        float envOctaves = (filterEnvAmtHz / 5000.0f) * 4.5f * fEnvVal * velFilterBoost;

        // Key Tracking assimﾃｩtrico autﾃｪntico: abre nos agudos, mas preserva a fundamental dos graves
        float noteOffset = static_cast<float>(currentNote) - 60.0f;
        float keyTrackOctaves = (noteOffset >= 0.0f)
            ? ((noteOffset / 12.0f) * keyTrackAmount)
            : ((noteOffset / 12.0f) * keyTrackAmount * 0.15f);

        float totalOctaves = envOctaves + lfoModOctaves + keyTrackOctaves;
        float voiceCutoff = std::max(baseCutoff * exp2f(totalOctaves), 40.0f);

        // 1. Osciladores
        float s0 = osc[0].nextSample() * oscVols[0];
        float s1 = osc[1].nextSample() * oscVols[1];
        float s2 = osc[2].nextSample() * oscVols[2];

        // 2. Mixer de entrada do filtro (soma com folga dinﾃ｢mica de som analﾃｳgico)
        float totalVol = oscVols[0] + oscVols[1] + oscVols[2];
        float mixScale = (totalVol > 0.001f) ? (1.0f / sqrtf(std::max(1.0f, totalVol))) : 0.0f;
        float mixedInput = (s0 + s1 + s2) * (mixScale * 0.65f);

        // 3. Filtro Moog Ladder 24dB
        float filtered = vcf.process(mixedInput, voiceCutoff, resonance, driveNorm, oversampleFactor);

        // 4. VCA
        float vcaOut = filtered * aEnvVal * velFactor;

        // 5. Constant-power Panning por voz
        float panAngle = (voicePan + 1.0f) * 0.25f * static_cast<float>(M_PI);
        voiceOutL = vcaOut * cosf(panAngle);
        voiceOutR = vcaOut * sinf(panAngle);
    }
};

// ==============================================================================
// 9. MOTOR DE ﾃゞDIO PRINCIPAL COM FILA LOCK-FREE E HEADROOM CONSTANTE
// ==============================================================================
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16;
    std::array<Voice, MAX_VOICES> voices;

    LockFreeMidiQueue midiQueue;
    Oscillator lfo;
    AnalogDelay masterDelay;
    DCBlocker dcBlockerL;
    DCBlocker dcBlockerR;

    uint64_t voiceCounter = 0;

    std::atomic<float> oscVolumes[3] = { {0.80f}, {0.65f}, {0.55f} };
    std::atomic<float> oscTunes[3] = { {0.0f}, {0.0f}, {-12.0f} };
    std::atomic<int> oscWaves[3] = { {0}, {1}, {2} };

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{2500.0f};
    std::atomic<float> filterResonance{0.20f};
    std::atomic<float> filterEnvAmount{2200.0f};
    std::atomic<float> lfoRate{2.5f}, lfoDepth{0.0f};
    std::atomic<float> delayTime{0.32f}, delayFeedback{0.35f}, delayMix{0.0f};

    std::atomic<int> oversampleFactor{2};
    std::atomic<float> analogDrift{0.05f}, filterDrive{0.0f}, filterKeyTrack{0.50f}, stereoSpread{0.15f}, velocitySens{0.35f};
    std::atomic<int> maxPolyphony{16}, bufferBurstMult{2};
    std::atomic<bool> paramSmoothing{true};

    // Parﾃ｢metros de Envelope em atﾃｴmicos (sem necessidade de mutex)
    std::atomic<float> ampA{0.008f}, ampD{0.3f}, ampS{0.8f}, ampR{0.25f};
    std::atomic<float> fltA{0.008f}, fltD{0.25f}, fltS{0.35f}, fltR{0.25f};
    std::atomic<bool> envParamsDirty{false};

    float smoothCutoff = 2500.0f, smoothRes = 0.20f, smoothMaster = 0.85f, smoothDelayTime = 0.32f;
    float smoothOscVol[3] = {0.80f, 0.65f, 0.55f};
    std::atomic<int> activeVoiceCount{0};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

    // Processamento de voz executado EXCLUSIVAMENTE na thread de ﾃ｡udio (Lock-Free)
    void handleNoteOn(int note, int velocity) {
        int polyLimit = std::clamp(maxPolyphony.load(std::memory_order_relaxed), 1, MAX_VOICES);
        float tunes[3] = { oscTunes[0].load(), oscTunes[1].load(), oscTunes[2].load() };

        Voice* targetVoice = nullptr;
        // 1. Mesma nota jﾃ｡ ativa (re-trigger legato sem estalo)
        for (int i = 0; i < polyLimit; ++i) {
            if (voices[i].isActive() && voices[i].getNote() == note) {
                targetVoice = &voices[i];
                break;
            }
        }
        // 2. Voz livre
        if (!targetVoice) {
            for (int i = 0; i < polyLimit; ++i) {
                if (!voices[i].isActive()) {
                    targetVoice = &voices[i];
                    break;
                }
            }
        }
        // 3. Voz em fase de release mais antiga
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (int i = 0; i < polyLimit; ++i) {
                if (voices[i].isReleasing() && voices[i].getAge() < oldestAge) {
                    oldestAge = voices[i].getAge();
                    targetVoice = &voices[i];
                }
            }
        }
        // 4. Voz mais antiga em geral (Voice Stealing suave sem zerar fase)
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

    void handleNoteOff(int note) {
        for (auto& v : voices) {
            if (v.isActive() && v.getNote() == note) v.noteOff(note);
        }
    }

    void handleAllNotesOff() {
        for (auto& v : voices) v.kill();
        masterDelay.clear();
        dcBlockerL.clear();
        dcBlockerR.clear();
    }

public:
    AudioEngine() {
        for (int i = 0; i < MAX_VOICES; ++i) voices[i].init(48000.0f, i);
        lfo.init(48000.0f, 0.0);
        lfo.setWaveType(3);
        masterDelay.init(48000.0f);
        dcBlockerL.init(48000.0f);
        dcBlockerR.init(48000.0f);
        scopeBuffer.fill(0.0f);
    }

    bool start() {
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
            dcBlockerL.init(actualSr);
            dcBlockerR.init(actualSr);

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

    // Mﾃｩtodos chamados pela JNI/UI - 100% Lock-Free (enfileiram sem travar)
    void noteOn(int note, int velocity) {
        midiQueue.push({MidiEvent::NOTE_ON, note, velocity});
    }

    void noteOff(int note) {
        midiQueue.push({MidiEvent::NOTE_OFF, note, 0});
    }

    void allNotesOff() {
        midiQueue.push({MidiEvent::ALL_NOTES_OFF, 0, 0});
    }

    void setOscVolume(int index, float vol) {
        if (index >= 0 && index < 3) oscVolumes[index].store(std::clamp(vol, 0.0f, 1.0f));
    }
    void setOscTune(int index, float semitones) {
        if (index >= 0 && index < 3) oscTunes[index].store(semitones);
    }
    void setOscWave(int index, int waveType) {
        if (index >= 0 && index < 3) oscWaves[index].store(waveType);
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
        ampA.store(a); ampD.store(d); ampS.store(s); ampR.store(r);
        envParamsDirty.store(true);
    }
    void setFilterADSR(float a, float d, float s, float r) {
        fltA.store(a); fltD.store(d); fltS.store(s); fltR.store(r);
        envParamsDirty.store(true);
    }

    void setOversampling(int factor) { oversampleFactor.store(std::clamp(factor, 1, 4)); }
    void setAnalogDrift(float drift) { analogDrift.store(std::clamp(drift, 0.0f, 1.0f)); }
    void setFilterDrive(float drive) { filterDrive.store(std::clamp(drive, 0.0f, 1.0f)); }
    void setFilterKeyTrack(float track) { filterKeyTrack.store(std::clamp(track, 0.0f, 1.0f)); }
    void setStereoSpread(float spread) { stereoSpread.store(std::clamp(spread, 0.0f, 1.0f)); }
    void setVelocitySensitivity(float sens) { velocitySens.store(std::clamp(sens, 0.0f, 1.0f)); }

    void setMaxPolyphony(int voicesLimit) {
        int limit = std::clamp(voicesLimit, 1, MAX_VOICES);
        maxPolyphony.store(limit);
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

    // ==============================================================================
    // CALLBACK DE TEMPO REAL DE ﾃゞDIO (100% LOCK-FREE E ZERO-ALLOCATION)
    // ==============================================================================
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        // 1. Esvazia a fila MIDI Lock-Free diretamente na thread de ﾃ｡udio
        MidiEvent ev;
        while (midiQueue.pop(ev)) {
            switch (ev.type) {
                case MidiEvent::NOTE_ON:  handleNoteOn(ev.note, ev.velocity); break;
                case MidiEvent::NOTE_OFF: handleNoteOff(ev.note); break;
                case MidiEvent::ALL_NOTES_OFF: handleAllNotesOff(); break;
            }
        }

        // 2. Atualiza parﾃ｢metros de ADSR se alterados pela UI
        if (envParamsDirty.load(std::memory_order_relaxed)) {
            envParamsDirty.store(false);
            float aA = ampA.load(), aD = ampD.load(), aS = ampS.load(), aR = ampR.load();
            float fA = fltA.load(), fD = fltD.load(), fS = fltS.load(), fR = fltR.load();
            for (auto& v : voices) {
                v.setAmpADSR(aA, aD, aS, aR);
                v.setFilterADSR(fA, fD, fS, fR);
            }
        }

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

        float targetOscVols[3] = { oscVolumes[0].load(), oscVolumes[1].load(), oscVolumes[2].load() };
        float currentOscTunes[3] = { oscTunes[0].load(), oscTunes[1].load(), oscTunes[2].load() };

        lfo.setFrequency(currentLfoRate);

        int wIdx = scopeWriteIndex.load();
        float lfoDepthOctaves = (currentLfoDepthHz / 3000.0f) * 2.0f;
        float smoothCoeff = useSmoothing ? 0.005f : 1.0f;

        int currentlyActive = 0;
        for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
            if (voices[vIdx].isActive()) currentlyActive++;
        }
        activeVoiceCount.store(currentlyActive);

        // HEADROOM FIXO CALIBRADO DE HARDWARE (Elimina o bombeamento de volume dinﾃ｢mico)
        // Permite que notas graves isoladas soem com peso total e acordes se somem organicamente
        const float HEADROOM_GAIN = 0.42f;

        for (int i = 0; i < numFrames; ++i) {
            smoothCutoff += smoothCoeff * (targetCutoff - smoothCutoff);
            smoothRes += smoothCoeff * (targetRes - smoothRes);
            smoothMaster += smoothCoeff * (targetMaster - smoothMaster);
            smoothDelayTime += 0.001f * (targetDTime - smoothDelayTime);

            for (int c = 0; c < 3; ++c) {
                smoothOscVol[c] += smoothCoeff * (targetOscVols[c] - smoothOscVol[c]);
            }

            float lfoModOct = lfo.nextSample() * lfoDepthOctaves;

            float mixSumL = 0.0f;
            float mixSumR = 0.0f;

            for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
                if (!voices[vIdx].isActive()) continue;

                // Abertura estﾃｩreo distribuﾃｭda por voz (Voice Spreading analﾃｳgico verdadeiro)
                float panNorm = (polyLimit > 1) ? ((static_cast<float>(vIdx) / static_cast<float>(polyLimit - 1)) * 2.0f - 1.0f) : 0.0f;
                float voicePan = panNorm * spread;

                float vL = 0.0f, vR = 0.0f;
                voices[vIdx].renderStereo(
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
                    voicePan,
                    vL,
                    vR
                );

                mixSumL += vL;
                mixSumR += vR;
            }

            // Ganho mestre com headroom constante
            float voiceSumL = mixSumL * HEADROOM_GAIN;
            float voiceSumR = mixSumR * HEADROOM_GAIN;

            // Stereo Tape Delay paralelo (sinal Dry 100% puro e transparente)
            float delayedL = 0.0f, delayedR = 0.0f;
            masterDelay.processStereo(voiceSumL, voiceSumR, smoothDelayTime, dFb, dMix, spread, delayedL, delayedR);

            // Bloqueador de DC transparente e ganho master
            float cleanL = dcBlockerL.process(delayedL) * smoothMaster;
            float cleanR = dcBlockerR.process(delayedR) * smoothMaster;

            // Limitador de joelho suave (Soft-Knee Master Limiter) - Zero estalo ou achatamento mecﾃ｢nico
            float outL = transparentMasterLimiter(cleanL);
            float outR = transparentMasterLimiter(cleanR);

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

// ==============================================================================
// 10. JNI INTERFACE (PONTE KOTLIN <-> C++)
// ==============================================================================
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

