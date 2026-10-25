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
// 1. UTILITÁRIOS MATEMÁTICOS, SEMICONDUTORES & SATURAÇÃO FÍSICA
// ==============================================================================

static inline float fastTanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x > 3.0f) return 1.0f;
    float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

static inline double fastTanhDouble(double x) {
    if (x < -3.0) return -1.0;
    if (x > 3.0) return 1.0;
    double x2 = x * x;
    return x * (27.0 + x2) / (27.0 + 9.0 * x2);
}

static inline float transparentMasterLimiter(float x) {
    const float threshold = 0.75f;
    const float limit = 0.985f;
    float absX = std::fabs(x);
    if (absX <= threshold) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - threshold) / (limit - threshold);
    return sign * (threshold + (limit - threshold) * fastTanh(excess));
}

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

struct PhysicalCircuitConfig {
    float thermalVoltageVt = 0.026f;
    float transistorMismatch = 0.020f;
    float capacitorLeakage = 0.14f;
    float comparatorThreshold = 0.488f;
    float envSupplyVoltage = 1.12f;
    float powerSupplySag = 0.08f;
    float tapeMotorInertia = 0.50f;
    float tapeMotorFriction = 0.20f;
    float tapeMagneticBias = 0.25f;
};

// ==============================================================================
// 2. ESTRUTURA COMPARTILHADA DO JUNO-6
// ==============================================================================
struct JunoConfig {
    int synthType = 0;
    int range = 1;
    float pulseVol = 0.8f;
    float sawVol = 0.8f;
    float subVol = 0.5f;
    float noiseVol = 0.0f;
    float pwm = 0.5f;
    float hpfCutoff = 20.0f;
    float vcfCutoff = 1000.0f;
    float vcfRes = 0.2f;
    float envAmt = 0.5f;
    int chorusMode = 0;
};

// ==============================================================================
// 3. DECIMADOR HALF-BAND POLIFÁSICO IIR
// ==============================================================================
class HalfBandDecimator {
private:
    static constexpr double a0 = 0.07986642623635751;
    static constexpr double a1 = 0.5453536510711322;
    static constexpr double b0 = 0.28382934487410993;
    static constexpr double b1 = 0.8344118914807379;

    double x0 = 0.0, x1 = 0.0, y0 = 0.0, y1 = 0.0;
    double z0 = 0.0, z1 = 0.0, w0 = 0.0, w1 = 0.0;
    double prevB = 0.0;

public:
    void reset() {
        x0 = x1 = y0 = y1 = 0.0;
        z0 = z1 = w0 = w1 = 0.0;
        prevB = 0.0;
    }

    inline double downsample(double in0, double in1) {
        double o0_A = a0 * (in0 - y0) + x0; x0 = in0; y0 = o0_A;
        double o1_A = a1 * (o0_A - y1) + x1; x1 = o0_A; y1 = o1_A;
        double branchA = o1_A;
        double branchB = prevB;

        double o0_B = b0 * (in1 - w0) + z0; z0 = in1; w0 = o0_B;
        double o1_B = b1 * (o0_B - w1) + z1; z1 = o0_B; w1 = o1_B;
        prevB = o1_B;

        return 0.5 * (branchA + branchB);
    }
};

// ==============================================================================
// 4. FILA LOCK-FREE SPSC PARA MIDI
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
        if (nextW == readIdx.load(std::memory_order_acquire)) return false;
        buffer[w] = ev;
        writeIdx.store(nextW, std::memory_order_release);
        return true;
    }

    bool pop(MidiEvent& ev) {
        size_t r = readIdx.load(std::memory_order_relaxed);
        if (r == writeIdx.load(std::memory_order_acquire)) return false;
        ev = buffer[r];
        readIdx.store((r + 1) % CAPACITY, std::memory_order_release);
        return true;
    }
};

// ==============================================================================
// 5. OSCILADOR VCO ANALÓGICO COM POLYBLEP
// ==============================================================================
class Oscillator {
public:
    double sampleRate = 48000.0;
    double frequency = 440.0;
    double phase = 0.0;
    int waveType = 0;

    static inline double polyBlep2(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return t * t + t + t + 1.0;
        }
        return 0.0;
    }

    static inline double polyBlep4(double t, double dt) {
        if (dt > 0.24) return polyBlep2(t, dt);
        double twoDt = 2.0 * dt;
        if (t < twoDt) {
            double u = t / dt;
            if (u < 1.0) {
                double u2 = u * u;
                return 0.25 * u2 * u2 - (2.0 / 3.0) * u2 * u + (4.0 / 3.0) * u - 1.0;
            } else {
                double v = 2.0 - u;
                double v2 = v * v;
                return -(1.0 / 12.0) * v2 * v2;
            }
        } else if (t > 1.0 - twoDt) {
            double u = (t - 1.0) / dt;
            if (u >= -1.0) {
                double u2 = u * u;
                return -0.25 * u2 * u2 - (2.0 / 3.0) * u2 * u + (4.0 / 3.0) * u + 1.0;
            } else {
                double v = 2.0 + u;
                double v2 = v * v;
                return (1.0 / 12.0) * v2 * v2;
            }
        }
        return 0.0;
    }

    static inline double polyBlamp2(double t, double dt) {
        if (t < dt) {
            t = t / dt - 1.0;
            return -1.0 / 3.0 * t * t * t;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt + 1.0;
            return 1.0 / 3.0 * t * t * t;
        }
        return 0.0;
    }

    static inline double polyBlamp4(double t, double dt) {
        if (dt > 0.24) return polyBlamp2(t, dt);
        double twoDt = 2.0 * dt;
        if (t < twoDt) {
            double u = t / dt;
            if (u < 1.0) {
                double u2 = u * u;
                return 2.0 * (u2 * u2 * u * (1.0 / 40.0) - u2 * u2 * (1.0 / 12.0) + u2 * (1.0 / 3.0) - u * 0.5 + (7.0 / 30.0));
            } else {
                double v = 2.0 - u;
                double v2 = v * v;
                return (1.0 / 60.0) * v2 * v2 * v;
            }
        } else if (t > 1.0 - twoDt) {
            double u = (t - 1.0) / dt;
            if (u >= -1.0) {
                double u2 = u * u;
                return 2.0 * (-u2 * u2 * u * (1.0 / 40.0) - u2 * u2 * (1.0 / 12.0) + u2 * (1.0 / 3.0) + u * 0.5 + (7.0 / 30.0));
            } else {
                double v = 2.0 + u;
                double v2 = v * v;
                return -(1.0 / 60.0) * v2 * v2 * v;
            }
        }
        return 0.0;
    }

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

    inline float nextSample(int osFactor = 1, float capLeakage = 0.14f, float compThreshold = 0.488f) {
        double effSr = sampleRate * static_cast<double>(osFactor);
        double dt = frequency / effSr;
        phase += dt;
        if (phase >= 1.0) phase -= 1.0;

        double sample = 0.0;
        switch (waveType) {
            case 0: {
                double baseSaw = (2.0 * phase) - 1.0 - polyBlep4(phase, dt);
                double rcCurveAmt = std::clamp(static_cast<double>(capLeakage), 0.0, 0.50);
                double rcSag = 4.0 * (phase * (1.0 - phase) - (1.0 / 6.0) + dt * polyBlamp4(phase, dt));
                sample = baseSaw + (rcCurveAmt * rcSag);
                break;
            }
            case 1: {
                double pw = std::clamp(static_cast<double>(compThreshold), 0.15, 0.85);
                double dcComp = (2.0 * pw) - 1.0;
                sample = ((phase < pw) ? 1.0 : -1.0) - dcComp;
                sample += polyBlep4(phase, dt);
                double phase2 = phase + (1.0 - pw);
                if (phase2 >= 1.0) phase2 -= 1.0;
                sample -= polyBlep4(phase2, dt);
                break;
            }
            case 2: {
                double p = phase;
                sample = 1.0 - 4.0 * std::fabs(p - 0.5);
                double p2 = p + 0.5;
                if (p2 >= 1.0) p2 -= 1.0;
                sample += 4.0 * dt * (polyBlamp4(p, dt) - polyBlamp4(p2, dt));
                double diodeRound = 0.025 + 0.08 * std::clamp(static_cast<double>(capLeakage), 0.0, 0.5);
                sample = sample - diodeRound * (sample * sample * sample);
                break;
            }
            case 3:
            default: {
                sample = std::sin(2.0 * M_PI * phase);
                break;
            }
        }
        return static_cast<float>(sample);
    }
};

// ==============================================================================
// 6. CIRCUITOS FÍSICOS DO ROLAND JUNO-6 (DCO, HPF, BBD CHORUS)
// ==============================================================================
class JunoDCO {
public:
    double phase = 0.0;
    double frequency = 440.0;
    double sampleRate = 48000.0;

    void setFrequency(double freq, double sampleRateOS) {
        frequency = freq;
        sampleRate = sampleRateOS;
    }

    double process(int range, float pulseVol, float sawVol, float subVol, float noiseVol, float pwmMod) {
        double rangeMult = (range == 0) ? 0.5 : ((range == 1) ? 1.0 : 2.0);
        double actualFreq = frequency * rangeMult;
        double dt = actualFreq / sampleRate;

        phase += dt;
        if (phase >= 1.0) phase -= 1.0;

        double out = 0.0;

        if (sawVol > 0.0f) {
            double saw = (2.0 * phase) - 1.0 - Oscillator::polyBlep4(phase, dt);
            saw += 0.05 * (saw * saw - 1.0); 
            out += saw * sawVol;
        }

        if (pulseVol > 0.0f) {
            double pw = std::clamp(static_cast<double>(pwmMod), 0.05, 0.95);
            double dcComp = (2.0 * pw) - 1.0;
            double pulse = ((phase < pw) ? 1.0 : -1.0) - dcComp;
            pulse += Oscillator::polyBlep4(phase, dt);
            double phase2 = phase + (1.0 - pw);
            if (phase2 >= 1.0) phase2 -= 1.0;
            pulse -= Oscillator::polyBlep4(phase2, dt);
            out += pulse * pulseVol;
        }

        if (subVol > 0.0f) {
            double subPhase = phase * 0.5;
            double sub = (subPhase < 0.5) ? 1.0 : -1.0;
            sub += Oscillator::polyBlep4(subPhase, dt * 0.5);
            double subPhase2 = subPhase + 0.5;
            if (subPhase2 >= 1.0) subPhase2 -= 1.0;
            sub -= Oscillator::polyBlep4(subPhase2, dt * 0.5);
            out += sub * subVol;
        }

        if (noiseVol > 0.0f) {
            double noise = ((double)rand() / RAND_MAX) * 2.0 - 1.0;
            out += noise * noiseVol;
        }

        return out * 0.55; 
    }
};

class JunoHPF {
private:
    double z1 = 0.0;
public:
    inline double process(double in, double cutoff, double sr) {
        double r = exp(-2.0 * M_PI * cutoff / sr);
        double out = in - z1 + r * z1;
        z1 = out;
        return out;
    }
    void reset() { z1 = 0.0; }
};

class BBDChorus {
private:
    std::vector<float> bufferL;
    std::vector<float> bufferR;
    int writeIdx = 0;
    double lfoPhase = 0.0;
    
    inline float readFractional(const std::vector<float>& buf, double delaySamples) {
        int size = buf.size();
        double readPos = static_cast<double>(writeIdx) - delaySamples;
        while (readPos < 0.0) readPos += size;
        if (readPos >= size) readPos -= size;
        
        int idx1 = static_cast<int>(readPos);
        int idx2 = (idx1 + 1) % size;
        
        int idx0 = (idx1 - 1 + size) % size;
        int idx3 = (idx1 + 2) % size;
        
        float frac = readPos - idx1;
        float y0 = buf[idx0], y1 = buf[idx1], y2 = buf[idx2], y3 = buf[idx3];
        
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        
        return ((c3 * frac + c2) * frac + c1) * frac + c0;
    }
    
    inline double triWave(double p) {
        double pp = p - std::floor(p);
        return (pp < 0.5) ? (pp * 2.0) : (2.0 - pp * 2.0);
    }
    
public:
    BBDChorus() {
        bufferL.resize(48000, 0.0f);
        bufferR.resize(48000, 0.0f);
    }

    void clear() {
        std::fill(bufferL.begin(), bufferL.end(), 0.0f);
        std::fill(bufferR.begin(), bufferR.end(), 0.0f);
        writeIdx = 0;
        lfoPhase = 0.0;
    }
    
    void processStereo(float& outL, float& outR, int mode, double sr) {
        if (mode == 0) return;
        
        double rate = 0.5;
        double depth = 1.5; 
        double baseDelayMs = 2.5;
        
        if (mode == 1) { rate = 0.4; depth = 1.0; }
        else if (mode == 2) { rate = 0.83; depth = 1.5; }
        else if (mode == 3) { rate = 1.2; depth = 2.5; }
        
        lfoPhase += (rate / sr);
        if (lfoPhase >= 1.0) lfoPhase -= 1.0;
        
        double modL = triWave(lfoPhase) * 2.0 - 1.0; 
        double modR = triWave(lfoPhase + 0.5) * 2.0 - 1.0;
        
        if (mode == 3) {
            modL = sin(2.0 * M_PI * lfoPhase);
            modR = sin(2.0 * M_PI * lfoPhase + M_PI);
        }
        
        double delayTimeL_ms = baseDelayMs + modL * depth;
        double delayTimeR_ms = baseDelayMs + modR * depth;
        
        double delaySamplesL = delayTimeL_ms * sr / 1000.0;
        double delaySamplesR = delayTimeR_ms * sr / 1000.0;
        
        float delayedL = readFractional(bufferL, delaySamplesL);
        float delayedR = readFractional(bufferR, delaySamplesR);
        
        bufferL[writeIdx] = outL;
        bufferR[writeIdx] = outR;
        writeIdx = (writeIdx + 1) % bufferL.size();
        
        outL = outL * 0.707f + delayedL * 0.707f;
        outR = outR * 0.707f + delayedR * 0.707f;
    }
};

// ==============================================================================
// 7. ENVELOPE RC FÍSICO
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
    float supplyRailV = 1.12f;

    float attackCoeff = 0.01f;
    float decayCoeff = 0.005f;
    float releaseCoeff = 0.005f;

    void recalcCoeffs() {
        float sr = std::max(8000.0f, sampleRate);
        float atkSamples = std::max(1.0f, attackTime * sr);
        float railRatio = std::clamp(supplyRailV, 1.01f, 1.60f);
        float targetFactor = -std::log((railRatio - 1.0f) / railRatio);
        attackCoeff = 1.0f - expf(-targetFactor / atkSamples);

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

    void setSupplyRailVoltage(float vRail) {
        float clamped = std::clamp(vRail, 1.02f, 1.50f);
        if (std::fabs(clamped - supplyRailV) > 0.005f) {
            supplyRailV = clamped;
            recalcCoeffs();
        }
    }

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
                currentLevel += (supplyRailV - currentLevel) * attackCoeff;
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
// 8. ESCADA DE TRANSISTORES MOOG (BJT LADDER + ZDF)
// ==============================================================================
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    double s[4] = {0.0, 0.0, 0.0, 0.0};
    HalfBandDecimator decim4xTo2x;
    HalfBandDecimator decim2xTo1x;

    double G[4] = {0.0, 0.0, 0.0, 0.0};
    double G_prod = 0.0;
    double k = 0.0;
    double passbandComp = 1.0;
    double invOnePlusKGprod = 1.0;

    double thermalScale = 1.0;
    double invThermalScale = 1.0;
    double bjtAsymOffset = 0.0;
    double bjtAsymBiasComp = 0.0;
    double stageMismatchBase[4] = { -0.85, 0.65, -0.40, 0.70 };

    float drv = 1.0f;
    float invTanhDrv = 1.0f;
    bool useDrive = false;

    inline double bjtDiffPair(double v) const {
        return (fastTanhDouble(v * thermalScale + bjtAsymOffset) - bjtAsymBiasComp) * invThermalScale;
    }

public:
    void init(float sr, int voiceIdx = 0) {
        sampleRate = sr;
        double seed = static_cast<double>(voiceIdx + 1) * 0.37;
        stageMismatchBase[0] = std::sin(seed * 1.3);
        stageMismatchBase[1] = std::cos(seed * 2.1);
        stageMismatchBase[2] = std::sin(seed * 3.7);
        stageMismatchBase[3] = std::cos(seed * 5.3);
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) s[i] = 0.0;
        decim4xTo2x.reset();
        decim2xTo1x.reset();
    }

    inline void prepareFrame(
        float cutoffHz,
        float resonance,
        float driveNorm,
        int osFactor,
        float thermalVt,
        float mismatchAmt
    ) {
        double effSr = static_cast<double>(sampleRate) * static_cast<double>(osFactor);
        double baseFc = std::clamp(static_cast<double>(cutoffHz), 20.0, static_cast<double>(sampleRate) * 0.46);

        double vtClamped = std::clamp(static_cast<double>(thermalVt), 0.018, 0.040);
        thermalScale = 0.026 / vtClamped;
        invThermalScale = 1.0 / thermalScale;

        double mm = std::clamp(static_cast<double>(mismatchAmt), 0.0, 1.0);
        bjtAsymOffset = mm * 0.14;
        bjtAsymBiasComp = fastTanhDouble(bjtAsymOffset);

        for (int st = 0; st < 4; ++st) {
            double stageTolerance = 1.0 + (stageMismatchBase[st] * mm * 0.035);
            double fcStage = std::clamp(baseFc * stageTolerance, 20.0, effSr * 0.47);
            double g = std::tan(M_PI * fcStage / effSr);
            G[st] = g / (1.0 + g);
        }

        G_prod = G[0] * G[1] * G[2] * G[3];

        double resNorm = std::clamp(static_cast<double>(resonance), 0.0, 1.0);
        k = std::pow(resNorm, 1.25) * 3.92;

        passbandComp = 1.0 + (k * 0.85);
        invOnePlusKGprod = 1.0 / (1.0 + k * G_prod);

        useDrive = (driveNorm > 0.005f);
        if (useDrive) {
            drv = 1.0f + (driveNorm * 2.4f);
            invTanhDrv = 1.0f / fastTanh(drv);
        }
    }

    inline double stepSubSample(float rawInput) {
        double inSignal = static_cast<double>(rawInput);
        if (useDrive) {
            inSignal = static_cast<double>(fastTanh(rawInput * drv) * invTanhDrv);
        }
        double x = inSignal * passbandComp;
        double G0 = G[0], G1 = G[1], G2 = G[2], G3 = G[3];

        double S = (G1 * G2 * G3 * s[0]) + (G2 * G3 * s[1]) + (G3 * s[2]) + s[3];
        double fb = bjtDiffPair(S);
        double u = (x - k * fb) * invOnePlusKGprod;

        double v0 = (bjtDiffPair(u) - bjtDiffPair(s[0])) * G0;
        double y0 = v0 + s[0];
        s[0] = y0 + v0;

        double v1 = (bjtDiffPair(y0) - bjtDiffPair(s[1])) * G1;
        double y1 = v1 + s[1];
        s[1] = y1 + v1;

        double v2 = (bjtDiffPair(y1) - bjtDiffPair(s[2])) * G2;
        double y2 = v2 + s[2];
        s[2] = y2 + v2;

        double v3 = (bjtDiffPair(y2) - bjtDiffPair(s[3])) * G3;
        double y3 = v3 + s[3];
        s[3] = y3 + v3;

        return y3;
    }

    inline float decimate2x(double y0, double y1) {
        return static_cast<float>(decim2xTo1x.downsample(y0, y1));
    }

    inline float decimate4x(double y0, double y1, double y2, double y3) {
        double z0 = decim4xTo2x.downsample(y0, y1);
        double z1 = decim4xTo2x.downsample(y2, y3);
        return static_cast<float>(decim2xTo1x.downsample(z0, z1));
    }
};

// ==============================================================================
// 9. DELAY ANALÓGICO MASTER
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
    float capstanPhase = 0.0f;
    float lpfAlpha = 0.45f, hpfAlpha = 0.015f;

    float actualTapeDelaySec = 0.32f;
    float motorVelocity = 0.0f;
    float bearingNoiseFilter = 0.0f;
    uint32_t motorRng = 987654321u;

    inline float nextMotorNoise() {
        motorRng = motorRng * 1664525u + 1013904223u;
        return (static_cast<float>(motorRng >> 8) / 8388608.0f) - 1.0f;
    }

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

        lpfAlpha = 1.0f - expf(-2.0f * static_cast<float>(M_PI) * 4200.0f / sr);
        hpfAlpha = 1.0f - expf(-2.0f * static_cast<float>(M_PI) * 55.0f / sr);
        clear();
    }

    void clear() {
        std::fill(bufferL.begin(), bufferL.end(), 0.0f);
        std::fill(bufferR.begin(), bufferR.end(), 0.0f);
        lpStateL = hpStateL = lpStateR = hpStateR = 0.0f;
        motorVelocity = 0.0f;
        bearingNoiseFilter = 0.0f;
    }

    void processStereo(
        float inL,
        float inR,
        float targetDelayTimeSec,
        float feedback,
        float mix,
        float stereoSpread,
        float motorInertia,
        float motorFriction,
        float magneticBias,
        float& outL,
        float& outR
    ) {
        if (mix <= 0.001f || bufferL.empty()) {
            outL = inL;
            outR = inR;
            actualTapeDelaySec = targetDelayTimeSec;
            return;
        }

        float dt = 1.0f / sampleRate;
        float inertia = std::clamp(motorInertia, 0.05f, 1.0f);
        float omegaN = 35.0f / (0.25f + inertia * 2.5f);
        float zeta = 0.75f;

        float posError = targetDelayTimeSec - actualTapeDelaySec;
        float springAccel = (omegaN * omegaN) * posError;
        float dampingAccel = 2.0f * zeta * omegaN * motorVelocity;
        motorVelocity += (springAccel - dampingAccel) * dt;
        actualTapeDelaySec += motorVelocity * dt;
        actualTapeDelaySec = std::clamp(actualTapeDelaySec, 0.01f, 1.50f);

        wowPhase += 0.52f * dt;
        if (wowPhase >= 1.0f) wowPhase -= 1.0f;
        flutterPhase += 5.4f * dt;
        if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;
        capstanPhase += 13.7f * dt;
        if (capstanPhase >= 1.0f) capstanPhase -= 1.0f;

        bearingNoiseFilter = bearingNoiseFilter * 0.996f + nextMotorNoise() * 0.004f;

        float frictionAmt = std::clamp(motorFriction, 0.0f, 1.0f);
        float modDepth = (0.15f + frictionAmt * 1.35f) * (0.3f + stereoSpread * 0.7f);

        float tapeModSec = (
            (sinf(2.0f * static_cast<float>(M_PI) * wowPhase) * 0.00032f) +
            (sinf(2.0f * static_cast<float>(M_PI) * flutterPhase) * 0.00008f) +
            (sinf(2.0f * static_cast<float>(M_PI) * capstanPhase) * 0.000025f * frictionAmt) +
            (bearingNoiseFilter * 0.00045f * frictionAmt)
        ) * modDepth;

        float maxDelaySamples = static_cast<float>(bufferL.size()) - 4.0f;
        float delaySamplesL = std::clamp((actualTapeDelaySec + tapeModSec) * sampleRate, 3.0f, maxDelaySamples);
        float delaySamplesR = std::clamp((actualTapeDelaySec - tapeModSec * 0.92f) * sampleRate, 3.0f, maxDelaySamples);

        float rawL = readFractionalHermite(bufferL, delaySamplesL);
        float rawR = readFractionalHermite(bufferR, delaySamplesR);

        float biasClamped = std::clamp(magneticBias, 0.0f, 1.0f);
        float dynamicLpfAlpha = lpfAlpha * (1.15f - 0.55f * biasClamped);

        hpStateL += hpfAlpha * (rawL - hpStateL);
        hpStateR += hpfAlpha * (rawR - hpStateR);
        float bandL = rawL - hpStateL;
        float bandR = rawR - hpStateR;

        lpStateL += dynamicLpfAlpha * (bandL - lpStateL);
        lpStateR += dynamicLpfAlpha * (bandR - lpStateR);

        float fb = std::clamp(feedback, 0.0f, 0.92f);
        float cross = stereoSpread * 0.16f;
        float fbSignalL = lpStateL * (1.0f - cross) + lpStateR * cross;
        float fbSignalR = lpStateR * (1.0f - cross) + lpStateL * cross;

        float tapeDrive = 1.0f + biasClamped * 1.1f;
        float tapeAsym = biasClamped * 0.08f;
        float biasOffset = fastTanh(tapeAsym);

        float recL = inL + fbSignalL * fb;
        float recR = inR + fbSignalR * fb;

        bufferL[writeIdx] = fastTanh(recL * tapeDrive + tapeAsym) - biasOffset;
        bufferR[writeIdx] = fastTanh(recR * tapeDrive + tapeAsym) - biasOffset;
        writeIdx = (writeIdx + 1) % static_cast<int>(bufferL.size());

        outL = inL + (lpStateL * mix * 0.72f);
        outR = inR + (lpStateR * mix * 0.72f);
    }
};

// ==============================================================================
// 10. VOZ POLIFÔNICA COM DUPLO MOTOR (MOOG + JUNO-6)
// ==============================================================================
class Voice {
private:
    Oscillator osc[3];
    JunoDCO junoDco;
    JunoHPF junoHpfFilter;

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
    float voicePsuSagState = 0.0f;

    float nextWhiteNoise() {
        rngState = rngState * 1664525u + 1013904223u;
        return (static_cast<float>(rngState >> 8) / 8388608.0f) - 1.0f;
    }

public:
    void init(float sr, int idx) {
        sampleRate = sr;
        rngState = 13579u + static_cast<uint32_t>(idx * 98765u);

        double phOffsets[3] = { 0.13 * idx, 0.37 * idx + 0.25, 0.71 * idx + 0.5 };
        for (int c = 0; c < 3; ++c) {
            osc[c].init(sr, phOffsets[c]);
            oscDriftSt[c] = 0.0f;
        }
        osc[0].setWaveType(0);
        osc[1].setWaveType(1);
        osc[2].setWaveType(2);

        vcf.init(sr, idx);
        ampEnv.init(sr);
        ampEnv.setADSR(0.008f, 0.3f, 0.8f, 0.25f);
        filterEnv.init(sr);
        filterEnv.setADSR(0.008f, 0.25f, 0.35f, 0.25f);
        junoHpfFilter.reset();
        voicePsuSagState = 0.0f;
    }

    void updateFrequencies(const float* oscTunes, float driftAmount, float psuPitchSagSt = 0.0f) {
        if (currentNote < 0) return;
        float baseFreq = 440.0f * exp2f((static_cast<float>(currentNote) - 69.0f) / 12.0f);
        junoDco.setFrequency(baseFreq, sampleRate);

        for (int c = 0; c < 3; ++c) {
            float totalDetuneSt = oscTunes[c] + (oscDriftSt[c] * driftAmount * 0.35f) - psuPitchSagSt;
            float freq = 440.0f * exp2f((static_cast<float>(currentNote) + totalDetuneSt - 69.0f) / 12.0f);
            osc[c].setFrequency(freq);
        }
    }

    void noteOn(int note, int velocity, const float* oscTunes, float driftAmount, uint64_t noteAge) {
        currentNote = note;
        noteVelocity = std::clamp(static_cast<float>(velocity) / 127.0f, 0.05f, 1.0f);
        age = noteAge;
        updateFrequencies(oscTunes, driftAmount, 0.0f);
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
        junoHpfFilter.reset();
        voicePsuSagState = 0.0f;
    }

    void setWaveType(int index, int type) {
        if (index >= 0 && index < 3) osc[index].setWaveType(type);
    }
    void setAmpADSR(float a, float d, float s, float r) { ampEnv.setADSR(a, d, s, r); }
    void setFilterADSR(float a, float d, float s, float r) { filterEnv.setADSR(a, d, s, r); }
    void setEnvelopeSupplyVoltage(float vRail) {
        ampEnv.setSupplyRailVoltage(vRail);
        filterEnv.setSupplyRailVoltage(vRail);
    }

    bool isActive() const { return !ampEnv.isIdle(); }
    bool isReleasing() const { return ampEnv.isReleasing(); }
    int getNote() const { return currentNote; }
    uint64_t getAge() const { return age; }

    inline float sampleMixedOscillators(
        const float* oscVols,
        float totalMixGain,
        int os,
        float capLeakage,
        float compThreshold
    ) {
        float s0 = osc[0].nextSample(os, capLeakage, compThreshold) * oscVols[0];
        float s1 = osc[1].nextSample(os, capLeakage, compThreshold) * oscVols[1];
        float s2 = osc[2].nextSample(os, capLeakage, compThreshold) * oscVols[2];
        return (s0 + s1 + s2) * totalMixGain;
    }

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
        const PhysicalCircuitConfig& phys,
        const JunoConfig& juno,
        float& voiceOutL,
        float& voiceOutR
    ) {
        if (ampEnv.isIdle()) {
            voiceOutL = 0.0f;
            voiceOutR = 0.0f;
            return;
        }

        float fEnvVal = filterEnv.nextLevel();
        float aEnvVal = ampEnv.nextLevel();

        float instantLoad = aEnvVal * (0.5f + 0.5f * fEnvVal);
        voicePsuSagState = voicePsuSagState * 0.992f + instantLoad * 0.008f;
        float sagEffect = voicePsuSagState * phys.powerSupplySag;

        if ((++driftCounter & 63) == 0) {
            for (int c = 0; c < 3; ++c) {
                oscDriftSt[c] = (oscDriftSt[c] * 0.994f) + (nextWhiteNoise() * 0.006f);
                oscDriftSt[c] = std::clamp(oscDriftSt[c], -0.10f, 0.10f);
            }
            updateFrequencies(oscTunes, driftAmount, sagEffect * 0.12f);
        }

        float velFactor = (1.0f - velSensAmount) + (noteVelocity * velSensAmount);
        float velFilterBoost = (1.0f - velSensAmount * 0.35f) + (noteVelocity * velSensAmount * 0.35f);
        float envOctaves = (filterEnvAmtHz / 5000.0f) * 4.5f * fEnvVal * velFilterBoost;

        float noteOffset = static_cast<float>(currentNote) - 60.0f;
        float keyTrackOctaves = (noteOffset >= 0.0f)
            ? ((noteOffset / 12.0f) * keyTrackAmount)
            : ((noteOffset / 12.0f) * keyTrackAmount * 0.15f);

        float sagCutoffOctaves = -sagEffect * 0.25f;
        float totalOctaves = envOctaves + lfoModOctaves + keyTrackOctaves + sagCutoffOctaves;

        int os = (oversampleFactor >= 4) ? 4 : ((oversampleFactor >= 2) ? 2 : 1);

        // ==========================================
        // ROTEAMENTO 1: ROLAND JUNO-6
        // ==========================================
        if (juno.synthType == 1) {
            float jCutoffHz = std::max(juno.vcfCutoff * exp2f(envOctaves * juno.envAmt + lfoModOctaves + keyTrackOctaves), 40.0f);
            vcf.prepareFrame(jCutoffHz, juno.vcfRes, driveNorm, os, phys.thermalVoltageVt, phys.transistorMismatch);
            junoDco.setFrequency(440.0f * exp2f((static_cast<float>(currentNote) - 69.0f) / 12.0f), static_cast<double>(sampleRate * os));

            float filtered = 0.0f;
            double pwmEff = static_cast<double>(juno.pwm) + (lfoModOctaves * 0.15);

            if (os == 4) {
                double srOS = sampleRate * 4.0;
                double y0 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                double y1 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                double y2 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                double y3 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                filtered = vcf.decimate4x(y0, y1, y2, y3);
            } else if (os == 2) {
                double srOS = sampleRate * 2.0;
                double y0 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                double y1 = vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, srOS)));
                filtered = vcf.decimate2x(y0, y1);
            } else {
                filtered = static_cast<float>(vcf.stepSubSample(static_cast<float>(junoHpfFilter.process(junoDco.process(juno.range, juno.pulseVol, juno.sawVol, juno.subVol, juno.noiseVol, pwmEff), juno.hpfCutoff, sampleRate))));
            }

            float vcaOut = filtered * aEnvVal * velFactor;
            float panAngle = (voicePan + 1.0f) * 0.25f * static_cast<float>(M_PI);
            voiceOutL = vcaOut * cosf(panAngle);
            voiceOutR = vcaOut * sinf(panAngle);
            return;
        }

        // ==========================================
        // ROTEAMENTO 2: MOOG POLY-D
        // ==========================================
        float totalVol = oscVols[0] + oscVols[1] + oscVols[2];
        float voiceCutoff = std::max(baseCutoff * exp2f(totalOctaves), 40.0f);

        float mixScale = (totalVol > 0.001f) ? (1.0f / sqrtf(std::max(1.0f, totalVol))) : 0.0f;
        float totalMixGain = mixScale * 0.65f * (1.0f - sagEffect * 0.12f);

        vcf.prepareFrame(
            voiceCutoff,
            resonance,
            driveNorm,
            os,
            phys.thermalVoltageVt,
            phys.transistorMismatch
        );

        float filtered = 0.0f;
        float capLeak = phys.capacitorLeakage;
        float compTh = phys.comparatorThreshold;

        if (os == 4) {
            double y0 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 4, capLeak, compTh));
            double y1 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 4, capLeak, compTh));
            double y2 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 4, capLeak, compTh));
            double y3 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 4, capLeak, compTh));
            filtered = vcf.decimate4x(y0, y1, y2, y3);
        } else if (os == 2) {
            double y0 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 2, capLeak, compTh));
            double y1 = vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 2, capLeak, compTh));
            filtered = vcf.decimate2x(y0, y1);
        } else {
            filtered = static_cast<float>(vcf.stepSubSample(sampleMixedOscillators(oscVols, totalMixGain, 1, capLeak, compTh)));
        }

        float vcaOut = filtered * aEnvVal * velFactor;
        float panAngle = (voicePan + 1.0f) * 0.25f * static_cast<float>(M_PI);
        voiceOutL = vcaOut * cosf(panAngle);
        voiceOutR = vcaOut * sinf(panAngle);
    }
};

// ==============================================================================
// 11. MOTOR DE ÁUDIO PRINCIPAL COM SUPORTE DUAL-SYNTH & CHORUS BBD
// ==============================================================================
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16;
    std::array<Voice, MAX_VOICES> voices;

    LockFreeMidiQueue midiQueue;
    Oscillator lfo;
    AnalogDelay masterDelay;
    BBDChorus masterChorus;
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

    // Parâmetros do Roland Juno-6
    std::atomic<int> synthType{0};
    std::atomic<int> junoRange{1};
    std::atomic<float> junoPulseVol{0.8f};
    std::atomic<float> junoSawVol{0.8f};
    std::atomic<float> junoSubVol{0.5f};
    std::atomic<float> junoNoiseVol{0.0f};
    std::atomic<float> junoPwm{0.5f};
    std::atomic<float> junoHpfCutoff{20.0f};
    std::atomic<float> junoVcfCutoff{1000.0f};
    std::atomic<float> junoVcfRes{0.2f};
    std::atomic<float> junoEnvAmt{0.5f};
    std::atomic<int> junoChorusMode{0};

    std::atomic<bool> paramSmoothing{true};

    std::atomic<float> thermalVoltageVt{0.026f};
    std::atomic<float> transistorMismatch{0.020f};
    std::atomic<float> capacitorLeakage{0.14f};
    std::atomic<float> comparatorThreshold{0.488f};
    std::atomic<float> envSupplyVoltage{1.12f};
    std::atomic<float> powerSupplySag{0.08f};
    std::atomic<float> tapeMotorInertia{0.50f};
    std::atomic<float> tapeMotorFriction{0.20f};
    std::atomic<float> tapeMagneticBias{0.25f};

    std::atomic<float> ampA{0.008f}, ampD{0.3f}, ampS{0.8f}, ampR{0.25f};
    std::atomic<float> fltA{0.008f}, fltD{0.25f}, fltS{0.35f}, fltR{0.25f};
    std::atomic<bool> envParamsDirty{false};

    float smoothCutoff = 2500.0f, smoothRes = 0.20f, smoothMaster = 0.85f;
    float smoothOscVol[3] = {0.80f, 0.65f, 0.55f};
    std::atomic<int> activeVoiceCount{0};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

    void handleNoteOn(int note,
