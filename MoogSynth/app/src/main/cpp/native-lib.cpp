#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <array>
#include <atomic>
#include <algorithm>

// --- OSCILADOR COM POLYBLEP (ANTI-ALIASING) ---
class Oscillator {
private:
    float sampleRate = 44100.0f;
    float frequency = 440.0f;
    float phase = 0.0f;
    int waveType = 0; // 0: Saw, 1: Square, 2: Triangle, 3: Sine

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
    void init(float sr) { sampleRate = sr; }
    void setFrequency(float f) { frequency = f; }
    void setWaveType(int type) { waveType = type; }

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

// --- ENVELOPE ADSR ---
class Envelope {
private:
    float sampleRate = 44100.0f;
    enum State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE } state = IDLE;
    float currentLevel = 0.0f;
    float timeInState = 0.0f;

    float attackTime = 0.01f;
    float decayTime = 0.2f;
    float sustainLevel = 0.7f;
    float releaseTime = 0.3f;

public:
    void init(float sr) { sampleRate = sr; }

    void setADSR(float a, float d, float s, float r) {
        attackTime = std::max(0.001f, a);
        decayTime = std::max(0.005f, d);
        sustainLevel = std::clamp(s, 0.0f, 1.0f);
        releaseTime = std::max(0.005f, r);
    }

    void triggerOn() {
        state = ATTACK;
        timeInState = 0.0f;
    }

    void triggerOff() {
        if (state != IDLE) {
            state = RELEASE;
            timeInState = 0.0f;
        }
    }

    bool isIdle() const { return state == IDLE; }

    float nextLevel() {
        float dt = 1.0f / sampleRate;
        timeInState += dt;

        switch (state) {
            case IDLE:
                currentLevel = 0.0f;
                break;
            case ATTACK:
                currentLevel = timeInState / attackTime;
                if (currentLevel >= 1.0f) {
                    currentLevel = 1.0f;
                    state = DECAY;
                    timeInState = 0.0f;
                }
                break;
            case DECAY:
                currentLevel = 1.0f - (1.0f - sustainLevel) * (timeInState / decayTime);
                if (timeInState >= decayTime) {
                    currentLevel = sustainLevel;
                    state = SUSTAIN;
                }
                break;
            case SUSTAIN:
                currentLevel = sustainLevel;
                break;
            case RELEASE:
                currentLevel *= (1.0f - (dt / releaseTime));
                if (currentLevel <= 0.001f) {
                    currentLevel = 0.0f;
                    state = IDLE;
                }
                break;
        }
        return currentLevel;
    }
};

// --- FILTRO MOOG LADDER 24dB/OCT (4 POLOS COM SATURAÇÃO NÃO-LINEAR) ---
class MoogLadderFilter {
private:
    float sampleRate = 44100.0f;
    float stage[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float delay[4] = {0.0f, 0.0f, 0.0f, 0.0f};

public:
    void init(float sr) {
        sampleRate = sr;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) {
            stage[i] = 0.0f;
            delay[i] = 0.0f;
        }
    }

    float process(float input, float cutoffHz, float resonance) {
        // Limita o Cutoff por segurança até próximo de Nyquist
        float fc = std::clamp(cutoffHz, 20.0f, sampleRate * 0.42f);
        float f = (2.0f * fc) / sampleRate;

        // Curva de sintonia empírica para resposta de frequência precisa em 4 polos
        float p = f * (1.8f - 0.8f * f);
        float k = 2.0f * sinf(f * static_cast<float>(M_PI) * 0.5f) - 1.0f;

        // Compensação de realimentação (Resonance de 0.0 a 1.0 mapeado para auto-oscilação até ~4.0)
        float t1 = (1.0f - p) * 1.386249f;
        float t2 = 12.0f + t1 * t1;
        float resScaled = std::clamp(resonance, 0.0f, 0.98f) * 4.0f;
        float r = resScaled * (t2 + 6.0f * t1) / (t2 - 6.0f * t1);

        // Realimentação negativa do 4º polo com saturação térmica (tanhf)
        float x = input - r * stage[3];

        // 4 estágios em cascata de 6dB/oct = 24dB/oct (4 polos)
        stage[0] = x * p + delay[0] * p - k * stage[0];
        stage[1] = stage[0] * p + delay[1] * p - k * stage[1];
        stage[2] = stage[1] * p + delay[2] * p - k * stage[2];
        stage[3] = stage[2] * p + delay[3] * p - k * stage[3];

        // Saturação não-linear do transistor no último estágio para evitar explosão do filtro
        stage[3] = tanhf(stage[3]);

        delay[0] = x;
        delay[1] = stage[0];
        delay[2] = stage[1];
        delay[3] = stage[2];

        return stage[3];
    }
};

// --- VOZ POLIFÔNICA COMPLETA (3 OSC + MOOG VCF + 2 ENVELOPES) ---
class Voice {
private:
    Oscillator osc1, osc2, osc3;
    Envelope ampEnv;
    Envelope filterEnv;
    MoogLadderFilter vcf;
    int currentNote = -1;

public:
    void init(float sr) {
        osc1.init(sr); osc1.setWaveType(0); // Sawtooth
        osc2.init(sr); osc2.setWaveType(1); // Square
        osc3.init(sr); osc3.setWaveType(2); // Triangle

        ampEnv.init(sr);
        ampEnv.setADSR(0.005f, 0.3f, 0.7f, 0.3f);

        filterEnv.init(sr);
        filterEnv.setADSR(0.01f, 0.25f, 0.3f, 0.4f); // Envelope clássico percussivo de filtro Moog

        vcf.init(sr);
    }

    void noteOn(int note, float freq) {
        currentNote = note;
        osc1.setFrequency(freq);
        osc2.setFrequency(freq * 0.5f); // Sub-oitava (-12 semitons)
        osc3.setFrequency(freq * 1.4983f); // Quinta justa (+7 semitons)
        ampEnv.triggerOn();
        filterEnv.triggerOn();
    }

    void noteOff(int note) {
        if (currentNote == note) {
            ampEnv.triggerOff();
            filterEnv.triggerOff();
        }
    }

    void setFilterADSR(float a, float d, float s, float r) {
        filterEnv.setADSR(a, d, s, r);
    }

    bool isActive() const { return !ampEnv.isIdle(); }
    int getNote() const { return currentNote; }

    float renderSample(float baseCutoff, float resonance, float filterEnvAmt) {
        if (ampEnv.isIdle()) return 0.0f;

        // Soma os 3 osciladores
        float rawMix = (osc1.nextSample() * 0.55f) +
                       (osc2.nextSample() * 0.35f) +
                       (osc3.nextSample() * 0.30f);

        // Modula a frequência de corte pelo envelope dedicado do filtro
        float fEnvVal = filterEnv.nextLevel();
        float modulatedCutoff = baseCutoff + (fEnvVal * filterEnvAmt);

        // Passa pelo Filtro Moog Ladder de 24dB/oct
        float filteredSignal = vcf.process(rawMix, modulatedCutoff, resonance);

        // Aplica o VCA (Envelope de Amplitude)
        return filteredSignal * ampEnv.nextLevel();
    }
};

// --- MOTOR DE ÁUDIO OBOE (ULTRA-BAIXA LATÊNCIA) ---
class AudioEngine : public oboe::AudioStreamDataCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 8;
    std::array<Voice, MAX_VOICES> voices;

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{1800.0f};   // Cutoff inicial em Hz
    std::atomic<float> filterResonance{0.35f};  // Ressonância inicial (0.0 a 1.0)
    std::atomic<float> filterEnvAmount{2500.0f}; // Intensidade do Envelope em Hz

public:
    AudioEngine() {
        for (auto& v : voices) v.init(44100.0f);
    }

    bool start() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setSampleRate(44100)
               ->setDataCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result == oboe::Result::OK) {
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

    void noteOn(int note, int velocity) {
        Voice* targetVoice = nullptr;
        for (auto& v : voices) {
            if (!v.isActive()) { targetVoice = &v; break; }
        }
        if (!targetVoice) targetVoice = &voices[0];

        float freq = 440.0f * powf(2.0f, (note - 69.0f) / 12.0f);
        targetVoice->noteOn(note, freq);
    }

    void noteOff(int note) {
        for (auto& v : voices) {
            if (v.getNote() == note) v.noteOff(note);
        }
    }

    void setMasterVolume(float vol) { masterVolume = vol; }
    void setFilterCutoff(float cutoffHz) { filterCutoff = cutoffHz; }
    void setFilterResonance(float res) { filterResonance = res; }
    void setFilterEnvAmount(float amtHz) { filterEnvAmount = amtHz; }

    void setFilterADSR(float a, float d, float s, float r) {
        for (auto& v : voices) v.setFilterADSR(a, d, s, r);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        float cutoff = filterCutoff.load();
        float res = filterResonance.load();
        float envAmt = filterEnvAmount.load();
        float masterVol = masterVolume.load();

        for (int i = 0; i < numFrames; ++i) {
            float sampleSum = 0.0f;
            for (auto& v : voices) {
                sampleSum += v.renderSample(cutoff, res, envAmt);
            }

            // Saturação final de saída (Soft Clipping Analógico)
            outputBuffer[i] = tanhf(sampleSum * masterVol * 0.8f);
        }

        return oboe::DataCallbackResult::Continue;
    }
};

static AudioEngine* engine = nullptr;

// --- PONTE JNI (COMUNICAÇÃO COM O KOTLIN) ---
extern "C" {

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_startEngine(JNIEnv *env, jobject thiz) {
    if (!engine) {
        engine = new AudioEngine();
        engine->start();
    }
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_stopEngine(JNIEnv *env, jobject thiz) {
    if (engine) {
        engine->stop();
        delete engine;
        engine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_noteOn(JNIEnv *env, jobject thiz, jint note, jint velocity) {
    if (engine) engine->noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_noteOff(JNIEnv *env, jobject thiz, jint note) {
    if (engine) engine->noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setMasterVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    if (engine) engine->setMasterVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterCutoff(JNIEnv *env, jobject thiz, jfloat cutoffHz) {
    if (engine) engine->setFilterCutoff(cutoffHz);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterResonance(JNIEnv *env, jobject thiz, jfloat resonance) {
    if (engine) engine->setFilterResonance(resonance);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterEnvAmount(JNIEnv *env, jobject thiz, jfloat envAmountHz) {
    if (engine) engine->setFilterEnvAmount(envAmountHz);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterADSR(JNIEnv *env, jobject thiz, jfloat a, jfloat d, jfloat s, jfloat r) {
    if (engine) engine->setFilterADSR(a, d, s, r);
}

}
