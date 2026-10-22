#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <array>
#include <atomic>
#include <algorithm>
#include <mutex>
#include <thread>

// --- OSCILADOR COM POLYBLEP (ANTI-ALIASING) ---
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
    float sampleRate = 48000.0f;
    enum State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE } state = IDLE;
    float currentLevel = 0.0f;

    float attackTime = 0.008f;
    float decayTime = 0.2f;
    float sustainLevel = 0.7f;
    float releaseTime = 0.25f;

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
        // Não zeramos currentLevel se a voz estiver sendo re-disparada (evita cliques!)
    }

    void triggerOff() {
        if (state != IDLE) {
            state = RELEASE;
        }
    }

    void kill() {
        state = IDLE;
        currentLevel = 0.0f;
    }

    bool isIdle() const { return state == IDLE; }
    bool isReleasing() const { return state == RELEASE; }

    float nextLevel() {
        float dt = 1.0f / sampleRate;

        switch (state) {
            case IDLE:
                currentLevel = 0.0f;
                break;
            case ATTACK:
                currentLevel += dt / attackTime;
                if (currentLevel >= 1.0f) {
                    currentLevel = 1.0f;
                    state = DECAY;
                }
                break;
            case DECAY:
                currentLevel -= (1.0f - sustainLevel) * (dt / decayTime);
                if (currentLevel <= sustainLevel) {
                    currentLevel = sustainLevel;
                    state = SUSTAIN;
                }
                break;
            case SUSTAIN:
                currentLevel = sustainLevel;
                break;
            case RELEASE:
                currentLevel -= currentLevel * (dt / releaseTime) * 3.0f;
                if (currentLevel <= 0.0005f) {
                    currentLevel = 0.0f;
                    state = IDLE;
                }
                break;
        }
        return currentLevel;
    }
};

// --- FILTRO MOOG LADDER 24dB/OCT ---
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
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
        float fc = std::clamp(cutoffHz, 20.0f, sampleRate * 0.42f);
        float f = (2.0f * fc) / sampleRate;

        float p = f * (1.8f - 0.8f * f);
        float k = 2.0f * sinf(f * static_cast<float>(M_PI) * 0.5f) - 1.0f;

        float t1 = (1.0f - p) * 1.386249f;
        float t2 = 12.0f + t1 * t1;
        float resScaled = std::clamp(resonance, 0.0f, 0.98f) * 4.0f;
        float r = resScaled * (t2 + 6.0f * t1) / (t2 - 6.0f * t1);

        float x = input - r * stage[3];

        stage[0] = x * p + delay[0] * p - k * stage[0];
        stage[1] = stage[0] * p + delay[1] * p - k * stage[1];
        stage[2] = stage[1] * p + delay[2] * p - k * stage[2];
        stage[3] = stage[2] * p + delay[3] * p - k * stage[3];

        stage[3] = tanhf(stage[3]);

        delay[0] = x;
        delay[1] = stage[0];
        delay[2] = stage[1];
        delay[3] = stage[2];

        return stage[3];
    }
};

// --- VOZ POLIFÔNICA ---
class Voice {
private:
    Oscillator osc1, osc2, osc3;
    Envelope ampEnv;
    Envelope filterEnv;
    MoogLadderFilter vcf;
    int currentNote = -1;
    uint64_t age = 0; // Usado para saber qual é a voz mais antiga no roubo de vozes

public:
    void init(float sr) {
        osc1.init(sr); osc1.setWaveType(0);
        osc2.init(sr); osc2.setWaveType(1);
        osc3.init(sr); osc3.setWaveType(2);

        ampEnv.init(sr);
        ampEnv.setADSR(0.005f, 0.25f, 0.7f, 0.25f);

        filterEnv.init(sr);
        filterEnv.setADSR(0.008f, 0.25f, 0.3f, 0.3f);

        vcf.init(sr);
    }

//    void noteOn(int note, float freq, uint64_t noteAge) {
//        if (!ampEnv.isActive()) {
//            vcf.reset();
//        }
//        currentNote = note;
//        age = noteAge;
//        osc1.setFrequency(freq);
//        osc2.setFrequency(freq * 0.5f);
//        osc3.setFrequency(freq * 1.4983f);
//        ampEnv.triggerOn();
//        filterEnv.triggerOn();
//    }


    void noteOn(int note, float freq, uint64_t noteAge) {
        if (ampEnv.isIdle()) {
            vcf.reset();
        }
        currentNote = note;
        age = noteAge;
        osc1.setFrequency(freq);
        osc2.setFrequency(freq * 0.5f);
        osc3.setFrequency(freq * 1.4983f);
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

    void setFilterADSR(float a, float d, float s, float r) {
        filterEnv.setADSR(a, d, s, r);
    }

    bool isActive() const { return !ampEnv.isIdle(); }
    bool isReleasing() const { return ampEnv.isReleasing(); }
    int getNote() const { return currentNote; }
    uint64_t getAge() const { return age; }

    float renderSample(float baseCutoff, float resonance, float filterEnvAmt) {
        if (ampEnv.isIdle()) return 0.0f;

        float rawMix = (osc1.nextSample() * 0.55f) +
                       (osc2.nextSample() * 0.35f) +
                       (osc3.nextSample() * 0.30f);

        float fEnvVal = filterEnv.nextLevel();
        float modulatedCutoff = baseCutoff + (fEnvVal * filterEnvAmt);

        float filteredSignal = vcf.process(rawMix, modulatedCutoff, resonance);
        return filteredSignal * ampEnv.nextLevel();
    }
};

// --- MOTOR DE ÁUDIO OBOE COM HOT-SWAP DE DISPOSITIVOS DE ÁUDIO ---
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16; // Polifonia ampliada para 16 vozes para glissandos
    std::array<Voice, MAX_VOICES> voices;
    std::mutex voiceMutex;
    uint64_t voiceCounter = 0;

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{1800.0f};
    std::atomic<float> filterResonance{0.35f};
    std::atomic<float> filterEnvAmount{2500.0f};

public:
    AudioEngine() {
        for (auto& v : voices) v.init(48000.0f);
    }

    bool start() {
        std::lock_guard<std::mutex> lock(voiceMutex);
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setDataCallback(this)
               ->setErrorCallback(this); // Monitora desconexão de fone USB/Bluetooth!

        oboe::Result result = builder.openStream(stream);
        if (result == oboe::Result::OK) {
            float actualSampleRate = static_cast<float>(stream->getSampleRate());
            for (auto& v : voices) v.init(actualSampleRate);
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

    // CHAMADO AUTOMATICAMENTE QUANDO O FONE USB É DESCONECTADO!
    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) {
            // Reinicia o motor na nova saída de áudio padrão do Android em uma thread separada
            std::thread([this]() {
                this->start();
            }).detach();
        }
    }

    void noteOn(int note, int velocity) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        Voice* targetVoice = nullptr;

        // 1. Se a mesma nota já está tocando, reutiliza a mesma voz (evita empilhar a mesma nota!)
        for (auto& v : voices) {
            if (v.isActive() && v.getNote() == note) {
                targetVoice = &v;
                break;
            }
        }

        // 2. Se não, busca uma voz totalmente livre
        if (!targetVoice) {
            for (auto& v : voices) {
                if (!v.isActive()) {
                    targetVoice = &v;
                    break;
                }
            }
        }

        // 3. Se não houver voz livre, rouba a voz mais antiga que já está em fase de Release
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (auto& v : voices) {
                if (v.isReleasing() && v.getAge() < oldestAge) {
                    oldestAge = v.getAge();
                    targetVoice = &v;
                }
            }
        }

        // 4. Em último caso, rouba a voz mais antiga de todas
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (auto& v : voices) {
                if (v.getAge() < oldestAge) {
                    oldestAge = v.getAge();
                    targetVoice = &v;
                }
            }
        }

        float freq = 440.0f * powf(2.0f, (note - 69.0f) / 12.0f);
        targetVoice->noteOn(note, freq, ++voiceCounter);
    }

    void noteOff(int note) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        // Desliga TODAS as vozes que estejam associadas a esta nota
        for (auto& v : voices) {
            if (v.isActive() && v.getNote() == note) {
                v.noteOff(note);
            }
        }
    }

    void allNotesOff() {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) {
            v.kill();
        }
    }

    void setMasterVolume(float vol) { masterVolume = vol; }
    void setFilterCutoff(float cutoffHz) { filterCutoff = cutoffHz; }
    void setFilterResonance(float res) { filterResonance = res; }
    void setFilterEnvAmount(float amtHz) { filterEnvAmount = amtHz; }

    void setFilterADSR(float a, float d, float s, float r) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setFilterADSR(a, d, s, r);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        float cutoff = filterCutoff.load();
        float res = filterResonance.load();
        float envAmt = filterEnvAmount.load();
        float masterVol = masterVolume.load();

        std::unique_lock<std::mutex> lock(voiceMutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            // Se a thread MIDI estiver alterando nota neste exato microssegundo, preenche com zero sem travar
            std::fill(outputBuffer, outputBuffer + numFrames, 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

        for (int i = 0; i < numFrames; ++i) {
            float sampleSum = 0.0f;
            for (auto& v : voices) {
                sampleSum += v.renderSample(cutoff, res, envAmt);
            }
            outputBuffer[i] = tanhf(sampleSum * masterVol * 0.65f);
        }

        return oboe::DataCallbackResult::Continue;
    }
};

static AudioEngine* engine = nullptr;

// --- PONTE JNI ---
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
Java_com_moogsynth_app_audio_NativeAudioEngine_allNotesOff(JNIEnv *env, jobject thiz) {
    if (engine) engine->allNotesOff();
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
