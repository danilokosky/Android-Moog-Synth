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

// Aproximação Padé de alta velocidade e continuidade C2 para tanh(x)
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

// Limitador de estúdio transparente (Soft-Knee Master Limiter)
static inline float transparentMasterLimiter(float x) {
    const float threshold = 0.75f;
    const float limit = 0.985f;
    float absX = std::fabs(x);
    if (absX <= threshold) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float excess = (absX - threshold) / (limit - threshold);
    return sign * (threshold + (limit - threshold) * fastTanh(excess));
}

// Bloqueador de DC de 1ª ordem adaptativo com corte estável ~2.0Hz em qualquer sample rate
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

// Estrutura de Parâmetros Físicos de Semicondutores e Circuitos (Reguláveis em Tempo Real)
struct PhysicalCircuitConfig {
    float thermalVoltageVt = 0.026f;    // Tensão térmica BJT Vt = kT/q (~0.026V a 25°C; faixa: 0.018V a 0.040V)
    float transistorMismatch = 0.020f;  // Tolerância/Assimetria entre pares de transistores NPN CA3046 (0.0 a 1.0)
    float capacitorLeakage = 0.14f;     // Descarga/Fuga RC do capacitor integrador no VCO (0.0 a 0.5)
    float comparatorThreshold = 0.488f; // Ponto de disparo do comparador analógico (Duty Cycle 0.35 a 0.65)
    float envSupplyVoltage = 1.12f;     // Tensão de sobrecarga do trilho RC do Envelope (1.02V a 1.50V)
    float powerSupplySag = 0.08f;       // Queda de tensão da fonte de alimentação sob alta corrente (0.0 a 1.0)
    float tapeMotorInertia = 0.50f;     // Momento de inércia mecânica do motor/volante de fita (0.05 a 1.0)
    float tapeMotorFriction = 0.20f;    // Atrito mecânico estocástico dos rolamentos e rolo pressor (0.0 a 1.0)
    float tapeMagneticBias = 0.25f;     // Histerese e saturação magnética da fita de óxido férrico (0.0 a 1.0)
};

// ==============================================================================
// 2. DECIMADOR HALF-BAND POLIFÁSICO IIR (ANTI-ALIASING DE ESTÚDIO 2X / 4X)
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
// 3. FILA LOCK-FREE SPSC PARA EVENTOS MIDI (ZERO BLOQUEIO DE THREAD DE ÁUDIO)
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
// 4. OSCILADOR VCO FÍSICO (INTEGRADOR DE CAPACITOR + COMPARADOR + SHAPER DE DIODO)
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
                return (1.0 / 60.0) * v2 * v2 * v;
            }
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

    // Gera amostra com parâmetros físicos do capacitor integrador e comparador analógico
    inline float nextSample(int osFactor = 1, float capLeakage = 0.14f, float compThreshold = 0.488f) {
        double effSr = sampleRate * static_cast<double>(osFactor);
        double dt = frequency / effSr;
        phase += dt;
        if (phase >= 1.0) phase -= 1.0;

        double sample = 0.0;
        switch (waveType) {
            case 0: {
                // SAWTOOTH FÍSICA: Carga de capacitor com fuga resistiva (capLeakage) + transistor de descarga
                double baseSaw = (2.0 * phase) - 1.0 - polyBlep4(phase, dt);
                double rcCurveAmt = std::clamp(static_cast<double>(capLeakage), 0.0, 0.50);
                double rcSag = 4.0 * (phase * (1.0 - phase) - (1.0 / 6.0) + dt * polyBlamp4(phase, dt));
                sample = baseSaw + (rcCurveAmt * rcSag);
                break;
            }
            case 1: {
                // SQUARE FÍSICA: Comparador analógico com limiar de tensão regulável (compThreshold)
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
                // TRIANGLE FÍSICA: Conformador a diodo com saturação de pico dependente da fuga RC
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
// 5. ENVELOPE RC FÍSICO (CAPACITOR REAL COM TRILHO DE TENSÃO E CHAVEAMENTO A DIODO)
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
    float supplyRailV = 1.12f; // Tensão física do trilho que carrega o capacitor no Attack

    float attackCoeff = 0.01f;
    float decayCoeff = 0.005f;
    float releaseCoeff = 0.005f;

    void recalcCoeffs() {
        float sr = std::max(8000.0f, sampleRate);
        float atkSamples = std::max(1.0f, attackTime * sr);
        // Ajusta a constante RC com base na tensão de sobrecarga física (supplyRailV)
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
// 6. ESCADA DE TRANSISTORES BJT FÍSICA (4 PARES DIFERENCIAIS NPN CA3046 + ZDF)
//    Baseado no modelo físico não-linear de D'Angelo & Välimäki
// ==============================================================================
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    double s[4] = {0.0, 0.0, 0.0, 0.0};
    HalfBandDecimator decim4xTo2x;
    HalfBandDecimator decim2xTo1x;

    // Ganhos individuais dos 4 estágios (com tolerância física de capacitores/transistores)
    double G[4] = {0.0, 0.0, 0.0, 0.0};
    double G_prod = 0.0;
    double k = 0.0;
    double passbandComp = 1.0;
    double invOnePlusKGprod = 1.0;

    // Parâmetros físicos dos semicondutores BJT
    double thermalScale = 1.0;      // Escala baseada em Vt (Tensão Térmica kT/q)
    double invThermalScale = 1.0;
    double bjtAsymOffset = 0.0;     // Descasamento DC entre transistores do par diferencial
    double bjtAsymBiasComp = 0.0;   // Ponto de operação quiescente tanh(offset)
    double stageMismatchBase[4] = { -0.85, 0.65, -0.40, 0.70 }; // Perfil único de fábrica da voz

    float drv = 1.0f;
    float invTanhDrv = 1.0f;
    bool useDrive = false;

    // Equação física Ebers-Moll do Par Diferencial Bipolar (Q_left / Q_right)
    // I_diff = I_ctl * tanh(V_diff / (2 * Vt) + offset) - I_offset
    inline double bjtDiffPair(double v) const {
        return (fastTanhDouble(v * thermalScale + bjtAsymOffset) - bjtAsymBiasComp) * invThermalScale;
    }

public:
    void init(float sr, int voiceIdx = 0) {
        sampleRate = sr;
        // Gera micro-tolerâncias únicas para os 4 capacitores/transistores de cada voz
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

    // Prepara os parâmetros físicos dos 4 pares de transistores e capacitores por amostra
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

        // 1. Tensão Térmica Física do Semicondutor (Vt nominal = 0.026V a 25°C)
        // Quanto menor Vt (frio), mais abrupta é a saturação; quanto maior Vt (quente), mais suave e aberta.
        double vtClamped = std::clamp(static_cast<double>(thermalVt), 0.018, 0.040);
        thermalScale = 0.026 / vtClamped;
        invThermalScale = 1.0 / thermalScale;

        // 2. Assimetria do Par Diferencial NPN (gera 2º harmônico quente nos transistores)
        double mm = std::clamp(static_cast<double>(mismatchAmt), 0.0, 1.0);
        bjtAsymOffset = mm * 0.14;
        bjtAsymBiasComp = fastTanhDouble(bjtAsymOffset);

        // 3. Tolerância Física de cada um dos 4 estágios RC/Transistor da escada
        for (int st = 0; st < 4; ++st) {
            double stageTolerance = 1.0 + (stageMismatchBase[st] * mm * 0.035);
            double fcStage = std::clamp(baseFc * stageTolerance, 20.0, effSr * 0.47);
            double g = std::tan(M_PI * fcStage / effSr);
            G[st] = g / (1.0 + g);
        }

        G_prod = G[0] * G[1] * G[2] * G[3];

        double resNorm = std::clamp(static_cast<double>(resonance), 0.0, 1.0);
        k = std::pow(resNorm, 1.25) * 3.92;

        // Compensação de ganho na banda passante (DC Passband Compensation)
        passbandComp = 1.0 + (k * 0.85);
        invOnePlusKGprod = 1.0 / (1.0 + k * G_prod);

        useDrive = (driveNorm > 0.005f);
        if (useDrive) {
            drv = 1.0f + (driveNorm * 2.4f);
            invTanhDrv = 1.0f / fastTanh(drv);
        }
    }

    // Executa 1 sub-passo resolvendo a escada de 4 transistores não-lineares acoplados
    inline double stepSubSample(float rawInput) {
        double inSignal = static_cast<double>(rawInput);
        if (useDrive) {
            inSignal = static_cast<double>(fastTanh(rawInput * drv) * invTanhDrv);
        }
        double x = inSignal * passbandComp;

        double G0 = G[0], G1 = G[1], G2 = G[2], G3 = G[3];

        // Soma ponderada dos estados dos 4 capacitores para malha Zero-Delay Feedback (ZDF)
        double S = (G1 * G2 * G3 * s[0]) + (G2 * G3 * s[1]) + (G3 * s[2]) + s[3];
        double fb = bjtDiffPair(S);
        double u = (x - k * fb) * invOnePlusKGprod;

        // Estágio 1: Par Diferencial Q1/Q2 carregando Capacitor C1
        double v0 = (bjtDiffPair(u) - bjtDiffPair(s[0])) * G0;
        double y0 = v0 + s[0];
        s[0] = y0 + v0;

        // Estágio 2: Par Diferencial Q3/Q4 carregando Capacitor C2
        double v1 = (bjtDiffPair(y0) - bjtDiffPair(s[1])) * G1;
        double y1 = v1 + s[1];
        s[1] = y1 + v1;

        // Estágio 3: Par Diferencial Q5/Q6 carregando Capacitor C3
        double v2 = (bjtDiffPair(y1) - bjtDiffPair(s[2])) * G2;
        double y2 = v2 + s[2];
        s[2] = y2 + v2;

        // Estágio 4: Par Diferencial Q7/Q8 carregando Capacitor C4
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
// 7. MOTOR ELETROMECÂNICO DE FITA MAGNÉTICA (CAPSTAN SERVO + HISTERESE MAGNÉTICA)
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

    // Estado físico do motor DC (Capstan) e atrito dos rolamentos
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

        // 1. Dinâmica Eletromecânica de 2ª Ordem do Motor DC com Volante de Inércia (Capstan)
        float dt = 1.0f / sampleRate;
        float inertia = std::clamp(motorInertia, 0.05f, 1.0f);
        // Frequência natural do servo-motor inversamente proporcional à inércia mecânica
        float omegaN = 35.0f / (0.25f + inertia * 2.5f);
        float zeta = 0.75f; // Coeficiente de amortecimento mecânico (permite leve overshoot analógico)

        float posError = targetDelayTimeSec - actualTapeDelaySec;
        float springAccel = (omegaN * omegaN) * posError;
        float dampingAccel = 2.0f * zeta * omegaN * motorVelocity;
        motorVelocity += (springAccel - dampingAccel) * dt;
        actualTapeDelaySec += motorVelocity * dt;
        actualTapeDelaySec = std::clamp(actualTapeDelaySec, 0.01f, 1.50f);

        // 2. Simulação Física de Rolamentos, Excentricidade do Eixo e Escorregamento do Pinch Roller
        wowPhase += 0.52f * dt;
        if (wowPhase >= 1.0f) wowPhase -= 1.0f;
        flutterPhase += 5.4f * dt;
        if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;
        capstanPhase += 13.7f * dt;
        if (capstanPhase >= 1.0f) capstanPhase -= 1.0f;

        // Ruído estocástico mecânico dos rolamentos filtrado pela massa do motor
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

        // 3. Perda Magnética de Cabeçote e Head Bump (Passa-Altas + Passa-Baixas dependente do Bias)
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

        // 4. Saturação Magnética da Fita com Assimetria de Histerese (Magnetic Bias)
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
// 8. VOZ POLIFÔNICA FÍSICA (VCOs + MIXER + LADDER TRANSISTORIZADA + PSU SAG)
// ==============================================================================
// ==============================================================================
// ROLAND JUNO-6 COMPONENTS (DCO, HPF, BBD CHORUS)
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

        // DCO SAW: True discharge capacitor model approximation (sounds thicker than pure saw)
        if (sawVol > 0.0f) {
            double saw = (2.0 * phase) - 1.0 - Oscillator::polyBlep4(phase, dt);
            // Add a very subtle non-linear curve to simulate the Juno capacitor charging
            saw += 0.05 * (saw * saw - 1.0); 
            out += saw * sawVol;
        }

        // DCO PULSE (PWM)
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

        // SUB-OSCILLATOR: Perfectly phase-locked (it's a flip-flop triggered by the main DCO clock)
        if (subVol > 0.0f) {
            double subPhase = phase * 0.5; // Exactly half the frequency
            // The Juno Sub is a perfect square wave 
            double sub = (subPhase < 0.5) ? 1.0 : -1.0;
            sub += Oscillator::polyBlep4(subPhase, dt * 0.5);
            double subPhase2 = subPhase + 0.5;
            if (subPhase2 >= 1.0) subPhase2 -= 1.0;
            sub -= Oscillator::polyBlep4(subPhase2, dt * 0.5);
            out += sub * subVol;
        }

        // NOISE (Analog White Noise Generator)
        if (noiseVol > 0.0f) {
            double noise = ((double)rand() / RAND_MAX) * 2.0 - 1.0;
            out += noise * noiseVol;
        }

        // Output with slight analog DC removal and gain staging (Juno DCOs are hot)
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
        
        // Linear Interpolation (sufficient for Chorus, but let's use Hermite for less high-end loss)
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
    
    // Triangle wave generator: 0 to 1 to 0
    inline double triWave(double phase) {
        double p = phase - std::floor(phase);
        return (p < 0.5) ? (p * 2.0) : (2.0 - p * 2.0);
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
        if (mode == 0) return; // Chorus OFF
        
        // Juno-6 Chorus characteristics
        double rate = 0.5;
        double depth = 1.5; 
        double baseDelayMs = 2.5;
        
        if (mode == 1) { rate = 0.4; depth = 1.0; }
        else if (mode == 2) { rate = 0.83; depth = 1.5; }
        else if (mode == 3) { rate = 1.2; depth = 2.5; } // I+II is faster and deeper
        
        lfoPhase += (rate / sr);
        if (lfoPhase >= 1.0) lfoPhase -= 1.0;
        
        // Use triangle wave for authentic Juno chorus modulation
        double modL = triWave(lfoPhase) * 2.0 - 1.0; 
        double modR = triWave(lfoPhase + 0.5) * 2.0 - 1.0; // 180 degrees offset
        
        if (mode == 3) {
            // Mode I+II has a more chaotic Sine/Tri mix in analog realm
            modL = sin(2.0 * M_PI * lfoPhase);
            modR = sin(2.0 * M_PI * lfoPhase + M_PI);
        }
        
        double delayTimeL_ms = baseDelayMs + modL * depth;
        double delayTimeR_ms = baseDelayMs + modR * depth;
        
        double delaySamplesL = delayTimeL_ms * sr / 1000.0;
        double delaySamplesR = delayTimeR_ms * sr / 1000.0;
        
        float delayedL = readFractional(bufferL, delaySamplesL);
        float delayedR = readFractional(bufferR, delaySamplesR);
        
        bufferL[writeIdx] = outL; // Store dry signal
        bufferR[writeIdx] = outR;
        
        writeIdx = (writeIdx + 1) % bufferL.size();
        
        // BBD circuits naturally roll off high frequencies and add warmth
        // A simple one-pole LPF at ~6kHz would simulate the BBD loss, but let's keep it clean and lush first.
        outL = outL * 0.707f + delayedL * 0.707f;
        outR = outR * 0.707f + delayedR * 0.707f;
    }
};

class Voice {
private:
    JunoDCO junoDco;
    JunoHPF junoHpf;

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
    float voicePsuSagState = 0.0f; // Estado do capacitor da fonte de alimentação sob carga

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
        voicePsuSagState = 0.0f;
    }

    void updateFrequencies(const float* oscTunes, float driftAmount, float psuPitchSagSt = 0.0f) {
        if (currentNote < 0) return;
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

        // Simulação de Power Supply Sag (queda de tensão da fonte sob picos de envelope e graves)
        float instantLoad = aEnvVal * (0.5f + 0.5f * fEnvVal);
        voicePsuSagState = voicePsuSagState * 0.992f + instantLoad * 0.008f;
        float sagEffect = voicePsuSagState * phys.powerSupplySag;

        if ((++driftCounter & 63) == 0) {
            for (int c = 0; c < 3; ++c) {
                oscDriftSt[c] = (oscDriftSt[c] * 0.994f) + (nextWhiteNoise() * 0.006f);
                oscDriftSt[c] = std::clamp(oscDriftSt[c], -0.10f, 0.10f);
            }
            // A queda da fonte (PSU Sag) afeta sutilmente a afinação dos VCOs (até ~0.12 semitons em sag máximo)
            updateFrequencies(oscTunes, driftAmount, sagEffect * 0.12f);
        }

        float velFactor = (1.0f - velSensAmount) + (noteVelocity * velSensAmount);
        float velFilterBoost = (1.0f - velSensAmount * 0.35f) + (noteVelocity * velSensAmount * 0.35f);
        float envOctaves = (filterEnvAmtHz / 5000.0f) * 4.5f * fEnvVal * velFilterBoost;

        float noteOffset = static_cast<float>(currentNote) - 60.0f;
        float keyTrackOctaves = (noteOffset >= 0.0f)
            ? ((noteOffset / 12.0f) * keyTrackAmount)
            : ((noteOffset / 12.0f) * keyTrackAmount * 0.15f);

        // O Sag da fonte também comprime ligeiramente o pico de tensão do cutoff do filtro
        float sagCutoffOctaves = -sagEffect * 0.25f;
        float totalOctaves = envOctaves + lfoModOctaves + keyTrackOctaves + sagCutoffOctaves;
        float voiceCutoff = std::max(baseCutoff * exp2f(totalOctaves), 40.0f);

        
        float jRange = junoRange.load();
        float jPulse = junoPulseVol.load();
        float jSaw = junoSawVol.load();
        float jSub = junoSubVol.load();
        float jNoise = junoNoiseVol.load();
        float jPwmMod = junoPwm.load();
        float jHpfCut = junoHpf.load();
        float jVcfCut = junoVcfCutoff.load();
        float jVcfRes = junoVcfRes.load();
        float jEnvAmt = junoEnvAmt.load();
        int sType = synthType.load();

        if (sType == 1) {
            // JUNO ROUTING
            float jCutoffHz = std::max(jVcfCut * exp2f(envOctaves * jEnvAmt + lfoModOctaves + keyTrackOctaves), 40.0f);
            
            int os = (oversampleFactor >= 4) ? 4 : ((oversampleFactor >= 2) ? 2 : 1);
            
            // Apply Analog Drift, PSU Sag, and LFO Vibrato to the Juno DCO Master Clock
            float totalJunoDetuneSt = (lfoModOctaves * 0.4f) + (oscDriftSt[0] * driftAmount * 0.35f) - (sagEffect * 0.12f);
            float baseJunoFreq = 440.0f * exp2f((static_cast<float>(currentNote) + totalJunoDetuneSt - 69.0f) / 12.0f);
            junoDco.setFrequency(baseJunoFreq, 48000.0 * os);
            
            // Adjust the filter for Roland IR3109 character (slightly less drive distortion natively)
            vcf.prepareFrame(jCutoffHz, jVcfRes, driveNorm * 0.5f, os, phys.thermalVoltageVt, phys.transistorMismatch * 0.5f);
            
            // Juno PWM Modulation (Base + LFO)
            float pwmEff = jPwmMod + (lfoModOctaves * 0.35f);

            float filtered = 0.0f;
            if (os == 4) {
                double y0 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*4));
                double y1 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*4));
                double y2 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*4));
                double y3 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*4));
                filtered = vcf.decimate4x(y0, y1, y2, y3);
            } else if (os == 2) {
                double y0 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*2));
                double y1 = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0*2));
                filtered = vcf.decimate2x(y0, y1);
            } else {
                filtered = vcf.stepSubSample(junoHpf.process(junoDco.process(jRange, jPulse, jSaw, jSub, jNoise, pwmEff), jHpfCut, 48000.0));
            }

            float vcaOut = filtered * aEnvVal * velFactor;
            float panAngle = (voicePan + 1.0f) * 0.25f * static_cast<float>(M_PI);
            voiceOutL = vcaOut * cosf(panAngle);
            voiceOutR = vcaOut * sinf(panAngle);
            return;
        }
        
        // MOOG ROUTING
        float totalVol = oscVols[0] + oscVols[1] + oscVols[2];

        float mixScale = (totalVol > 0.001f) ? (1.0f / sqrtf(std::max(1.0f, totalVol))) : 0.0f;
        float totalMixGain = mixScale * 0.65f * (1.0f - sagEffect * 0.12f);

        int os = (oversampleFactor >= 4) ? 4 : ((oversampleFactor >= 2) ? 2 : 1);
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
// 9. MOTOR DE ÁUDIO PRINCIPAL COM MODELAGEM DE COMPONENTES FÍSICOS
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

// ==========================================
// VARIÁVEIS GLOBAIS DO JUNO-6
// ==========================================
std::atomic<int> synthType{0}; // 0 = Moog, 1 = Juno
std::atomic<int> junoRange{1}; // 0=16', 1=8', 2=4'
std::atomic<float> junoPulseVol{0.8f};
std::atomic<float> junoSawVol{0.8f};
std::atomic<float> junoSubVol{0.5f};
std::atomic<float> junoNoiseVol{0.0f};
std::atomic<float> junoPwm{0.5f};
std::atomic<float> junoHpf{20.0f};
std::atomic<float> junoVcfCutoff{1000.0f};
std::atomic<float> junoVcfRes{0.2f};
std::atomic<float> junoEnvAmt{0.5f};
std::atomic<int> junoChorus{0}; // 0=Off, 1=I, 2=II, 3=I+II

    std::atomic<bool> paramSmoothing{true};

    // Parâmetros Físicos de Nível de Componente (Transistores, Capacitores, Fonte e Motor de Fita)
    std::atomic<float> thermalVoltageVt{0.026f};    // ~26mV em 25°C (0.018V a 0.040V)
    std::atomic<float> transistorMismatch{0.020f};  // Assimetria BJT CA3046 (0.0 a 1.0)
    std::atomic<float> capacitorLeakage{0.14f};     // Fuga RC do capacitor do VCO (0.0 a 0.5)
    std::atomic<float> comparatorThreshold{0.488f}; // Limiar do comparador da onda quadrada (0.20 a 0.80)
    std::atomic<float> envSupplyVoltage{1.12f};     // Tensão de sobrecarga RC do ADSR (1.02V a 1.50V)
    std::atomic<float> powerSupplySag{0.08f};       // Queda da fonte de alimentação sob carga (0.0 a 1.0)
    std::atomic<float> tapeMotorInertia{0.50f};     // Inércia mecânica do motor Capstan (0.05 a 1.0)
    std::atomic<float> tapeMotorFriction{0.20f};    // Atrito mecânico dos rolamentos (0.0 a 1.0)
    std::atomic<float> tapeMagneticBias{0.25f};     // Bias magnético e histerese da fita (0.0 a 1.0)

    std::atomic<float> ampA{0.008f}, ampD{0.3f}, ampS{0.8f}, ampR{0.25f};
    std::atomic<float> fltA{0.008f}, fltD{0.25f}, fltS{0.35f}, fltR{0.25f};
    std::atomic<bool> envParamsDirty{false};

    float smoothCutoff = 2500.0f, smoothRes = 0.20f, smoothMaster = 0.85f;
    float smoothOscVol[3] = {0.80f, 0.65f, 0.55f};
    std::atomic<int> activeVoiceCount{0};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

    void handleNoteOn(int note, int velocity) {
        int polyLimit = std::clamp(maxPolyphony.load(std::memory_order_relaxed), 1, MAX_VOICES);
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

    // Setters dos Parâmetros Físicos de Semicondutores e Motores
    void setTransistorTemperatureVt(float vtVolts) {
        thermalVoltageVt.store(std::clamp(vtVolts, 0.018f, 0.040f));
    }
    void setTransistorMismatch(float mismatch) {
        transistorMismatch.store(std::clamp(mismatch, 0.0f, 1.0f));
    }
    void setCapacitorLeakage(float leakage) {
        capacitorLeakage.store(std::clamp(leakage, 0.0f, 0.50f));
    }
    void setComparatorThreshold(float threshold) {
        comparatorThreshold.store(std::clamp(threshold, 0.20f, 0.80f));
    }
    void setEnvelopeSupplyVoltage(float railVolts) {
        envSupplyVoltage.store(std::clamp(railVolts, 1.02f, 1.50f));
        envParamsDirty.store(true);
    }
    void setPowerSupplySag(float sag) {
        powerSupplySag.store(std::clamp(sag, 0.0f, 1.0f));
    }
    void setTapeMotorInertia(float inertia) {
        tapeMotorInertia.store(std::clamp(inertia, 0.05f, 1.0f));
    }
    void setTapeMotorFriction(float friction) {
        tapeMotorFriction.store(std::clamp(friction, 0.0f, 1.0f));
    }
    void setTapeMagneticBias(float bias) {
        tapeMagneticBias.store(std::clamp(bias, 0.0f, 1.0f));
    }

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

        MidiEvent ev;
        while (midiQueue.pop(ev)) {
            switch (ev.type) {
                case MidiEvent::NOTE_ON:  handleNoteOn(ev.note, ev.velocity); break;
                case MidiEvent::NOTE_OFF: handleNoteOff(ev.note); break;
                case MidiEvent::ALL_NOTES_OFF: handleAllNotesOff(); break;
            }
        }

        if (envParamsDirty.load(std::memory_order_relaxed)) {
            envParamsDirty.store(false);
            float aA = ampA.load(), aD = ampD.load(), aS = ampS.load(), aR = ampR.load();
            float fA = fltA.load(), fD = fltD.load(), fS = fltS.load(), fR = fltR.load();
            float vRail = envSupplyVoltage.load();
            for (auto& v : voices) {
                v.setEnvelopeSupplyVoltage(vRail);
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

        // Carrega o snapshot dos parâmetros físicos dos componentes para o bloco de áudio
        PhysicalCircuitConfig phys;
        phys.thermalVoltageVt = thermalVoltageVt.load(std::memory_order_relaxed);
        phys.transistorMismatch = transistorMismatch.load(std::memory_order_relaxed);
        phys.capacitorLeakage = capacitorLeakage.load(std::memory_order_relaxed);
        phys.comparatorThreshold = comparatorThreshold.load(std::memory_order_relaxed);
        phys.envSupplyVoltage = envSupplyVoltage.load(std::memory_order_relaxed);
        phys.powerSupplySag = powerSupplySag.load(std::memory_order_relaxed);
        phys.tapeMotorInertia = tapeMotorInertia.load(std::memory_order_relaxed);
        phys.tapeMotorFriction = tapeMotorFriction.load(std::memory_order_relaxed);
        phys.tapeMagneticBias = tapeMagneticBias.load(std::memory_order_relaxed);

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

        const float HEADROOM_GAIN = 0.42f;

        for (int i = 0; i < numFrames; ++i) {
            smoothCutoff += smoothCoeff * (targetCutoff - smoothCutoff);
            smoothRes += smoothCoeff * (targetRes - smoothRes);
            smoothMaster += smoothCoeff * (targetMaster - smoothMaster);

            for (int c = 0; c < 3; ++c) {
                smoothOscVol[c] += smoothCoeff * (targetOscVols[c] - smoothOscVol[c]);
            }

            float lfoModOct = lfo.nextSample(1) * lfoDepthOctaves;

            float mixSumL = 0.0f;
            float mixSumR = 0.0f;

            for (int vIdx = 0; vIdx < polyLimit; ++vIdx) {
                if (!voices[vIdx].isActive()) continue;

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
                    phys,
                    vL,
                    vR
                );

                mixSumL += vL;
                mixSumR += vR;
            }

            float voiceSumL = mixSumL * HEADROOM_GAIN;
            float voiceSumR = mixSumR * HEADROOM_GAIN;

            float delayedL = 0.0f, delayedR = 0.0f;
            masterDelay.processStereo(
                voiceSumL,
                voiceSumR,
                targetDTime,
                dFb,
                dMix,
                spread,
                phys.tapeMotorInertia,
                phys.tapeMotorFriction,
                phys.tapeMagneticBias,
                delayedL,
                delayedR
            );
            
            if (synthType.load() == 1) {
                masterChorus.processStereo(delayedL, delayedR, junoChorus.load(), 48000.0);
            }

            float cleanL = dcBlockerL.process(delayedL) * smoothMaster;
            float cleanR = dcBlockerR.process(delayedR) * smoothMaster;

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
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setSynthType(JNIEnv *, jobject, jint type) { synthType.store(type); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoRange(JNIEnv *, jobject, jint range) { junoRange.store(range); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoPulseVol(JNIEnv *, jobject, jfloat vol) { junoPulseVol.store(vol); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoSawVol(JNIEnv *, jobject, jfloat vol) { junoSawVol.store(vol); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoSubVol(JNIEnv *, jobject, jfloat vol) { junoSubVol.store(vol); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoNoiseVol(JNIEnv *, jobject, jfloat vol) { junoNoiseVol.store(vol); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoPwm(JNIEnv *, jobject, jfloat pwm) { junoPwm.store(pwm); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoHpf(JNIEnv *, jobject, jfloat cutoffHz) { junoHpf.store(cutoffHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoVcfCutoff(JNIEnv *, jobject, jfloat cutoffHz) { junoVcfCutoff.store(cutoffHz); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoVcfRes(JNIEnv *, jobject, jfloat res) { junoVcfRes.store(res); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoEnvAmt(JNIEnv *, jobject, jfloat amt) { junoEnvAmt.store(amt); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setJunoChorus(JNIEnv *, jobject, jint mode) { junoChorus.store(mode); }
}


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

// Novos Métodos JNI de Simulação Física de Componentes (Transistores, Capacitores, Fonte e Motor de Fita)
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setTransistorTemperatureVt(JNIEnv *env, jobject thiz, jfloat vtVolts) { if (engine) engine->setTransistorTemperatureVt(vtVolts); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setTransistorMismatch(JNIEnv *env, jobject thiz, jfloat mismatch) { if (engine) engine->setTransistorMismatch(mismatch); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setCapacitorLeakage(JNIEnv *env, jobject thiz, jfloat leakage) { if (engine) engine->setCapacitorLeakage(leakage); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setComparatorThreshold(JNIEnv *env, jobject thiz, jfloat threshold) { if (engine) engine->setComparatorThreshold(threshold); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setEnvelopeSupplyVoltage(JNIEnv *env, jobject thiz, jfloat railVolts) { if (engine) engine->setEnvelopeSupplyVoltage(railVolts); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setPowerSupplySag(JNIEnv *env, jobject thiz, jfloat sag) { if (engine) engine->setPowerSupplySag(sag); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setTapeMotorInertia(JNIEnv *env, jobject thiz, jfloat inertia) { if (engine) engine->setTapeMotorInertia(inertia); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setTapeMotorFriction(JNIEnv *env, jobject thiz, jfloat friction) { if (engine) engine->setTapeMotorFriction(friction); }
JNIEXPORT void JNICALL Java_com_moogsynth_app_audio_NativeAudioEngine_setTapeMagneticBias(JNIEnv *env, jobject thiz, jfloat bias) { if (engine) engine->setTapeMagneticBias(bias); }

}


