#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <array>
#include <vector>
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
    void setWaveType(int type) { waveType = std::clamp(type, 0, 3); }

    float nextSample() {
        float dt = frequency / sampleRate;
        phase += dt;
        if (phase >= 1.0f) phase -= 1.0f;

        float sample = 0.0f;
        switch (waveType) {
            case 0:
                sample = (2.0f * phase) - 1.0f;
                sample -= polyBlep(phase, dt);
                break;
            case 1:
                sample = (phase < 0.5f) ? 1.0f : -1.0f;
                sample += polyBlep(phase, dt);
                sample -= polyBlep(fmodf(phase + 0.5f, 1.0f), dt);
                break;
            case 2:
                sample = 2.0f * fabsf(2.0f * phase - 1.0f) - 1.0f;
                break;
            case 3:
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
    float decayTime = 0.25f;
    float sustainLevel = 0.7f;
    float releaseTime = 0.3f;

public:
    void init(float sr) { sampleRate = sr; }

    void setADSR(float a, float d, float s, float r) {
        attackTime = std::max(0.002f, a);
        decayTime = std::max(0.005f, d);
        sustainLevel = std::clamp(s, 0.0f, 1.0f);
        releaseTime = std::max(0.005f, r);
    }

    void triggerOn() { state = ATTACK; }
    void triggerOff() { if (state != IDLE) state = RELEASE; }
    void kill() { state = IDLE; currentLevel = 0.0f; }

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

// --- FILTRO MOOG LADDER 24dB/OCT CALIBRADO (SEM APITO PREMATURO + COMPENSAÇÃO DE GRAVES) ---
class MoogLadderFilter {
private:
    float sampleRate = 48000.0f;
    float stage[4] = {0.0f, 0.0f, 0.0f, 0.0f};

public:
    void init(float sr) {
        sampleRate = sr;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; ++i) {
            stage[i] = 0.0f;
        }
    }

    float process(float input, float cutoffHz, float resonance) {
        // Limita a frequência de corte na faixa musical segura
        float fc = std::clamp(cutoffHz, 20.0f, sampleRate * 0.38f);

        // Coeficiente de corte unipolar exato (g = 1 - exp(-2*pi*fc/sr))
        float g = 1.0f - expf(-2.0f * static_cast<float>(M_PI) * fc / sampleRate);
        g = std::clamp(g, 0.0001f, 0.82f);

        // Curva de ressonância progressiva:
        // De 0% a 75% o controle é super musical e cremoso; apenas perto de 95-100% chega perto da auto-oscilação
        float resNorm = std::clamp(resonance, 0.0f, 1.0f);
        float resShaped = powf(resNorm, 1.5f);

        // Atenua levemente o excesso de Q em frequências muito agudas para nunca apitar estridente
        float highFreqDamp = 1.0f - (g * 0.28f);
        float k = resShaped * 3.65f * highFreqDamp;

        // Compensação de volume do sinal dos osciladores (evita que os osciladores sumam quando sobe a ressonância)
        float compensatedInput = input * (1.0f + resShaped * 0.55f);

        // Feedback negativo com saturação suave no caminho de retorno
        float feedback = k * tanhf(stage[3] * 0.85f);
        float u = tanhf(compensatedInput - feedback);

        // 4 estágios de 1 polo em cascata com saturação térmica analógica entre os transistores
        stage[0] += g * (u - stage[0]);
        stage[1] += g * (tanhf(stage[0]) - stage[1]);
        stage[2] += g * (tanhf(stage[1]) - stage[2]);
        stage[3] += g * (tanhf(stage[2]) - stage[3]);

        return stage[3];
    }
};

// --- DELAY ANALÓGICO (TAPE ECHO) ---
class AnalogDelay {
private:
    std::vector<float> buffer;
    int writeIdx = 0;
    float sampleRate = 48000.0f;
    float lpState = 0.0f;

public:
    void init(float sr) {
        sampleRate = sr;
        buffer.assign(static_cast<size_t>(sr * 1.5f), 0.0f);
        writeIdx = 0;
        lpState = 0.0f;
    }

    void clear() {
        std::fill(buffer.begin(), buffer.end(), 0.0f);
        lpState = 0.0f;
    }

    float process(float input, float delayTimeSec, float feedback, float mix) {
        if (mix <= 0.001f || buffer.empty()) return input;

        int delaySamples = std::clamp(
            static_cast<int>(delayTimeSec * sampleRate),
            1,
            static_cast<int>(buffer.size()) - 1
        );

        int readIdx = writeIdx - delaySamples;
        if (readIdx < 0) readIdx += static_cast<int>(buffer.size());

        float delayed = buffer[readIdx];
        lpState += 0.25f * (delayed - lpState);

        buffer[writeIdx] = tanhf(input + lpState * std::clamp(feedback, 0.0f, 0.92f));
        writeIdx = (writeIdx + 1) % static_cast<int>(buffer.size());

        return input * (1.0f - mix * 0.5f) + lpState * mix;
    }
};

// --- VOZ POLIFÔNICA ---
class Voice {
private:
    Oscillator osc[3];
    Envelope ampEnv;
    Envelope filterEnv;
    MoogLadderFilter vcf;
    int currentNote = -1;
    uint64_t age = 0;

public:
    void init(float sr) {
        for (int i = 0; i < 3; ++i) osc[i].init(sr);
        osc[0].setWaveType(0);
        osc[1].setWaveType(1);
        osc[2].setWaveType(2);

        ampEnv.init(sr);
        ampEnv.setADSR(0.01f, 0.3f, 0.7f, 0.3f);

        filterEnv.init(sr);
        filterEnv.setADSR(0.01f, 0.25f, 0.2f, 0.3f);

        vcf.init(sr);
    }

    void updateFrequencies(const float* oscTunes) {
        if (currentNote < 0) return;
        for (int i = 0; i < 3; ++i) {
            float freq = 440.0f * powf(2.0f, (static_cast<float>(currentNote) + oscTunes[i] - 69.0f) / 12.0f);
            osc[i].setFrequency(freq);
        }
    }

    void noteOn(int note, const float* oscTunes, uint64_t noteAge) {
        if (ampEnv.isIdle()) {
            vcf.reset();
        }
        currentNote = note;
        age = noteAge;
        updateFrequencies(oscTunes);
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

    void setAmpADSR(float a, float d, float s, float r) {
        ampEnv.setADSR(a, d, s, r);
    }

    void setFilterADSR(float a, float d, float s, float r) {
        filterEnv.setADSR(a, d, s, r);
    }

    bool isActive() const { return !ampEnv.isIdle(); }
    bool isReleasing() const { return ampEnv.isReleasing(); }
    int getNote() const { return currentNote; }
    uint64_t getAge() const { return age; }

    float renderSample(const float* oscVols, float baseCutoff, float resonance, float filterEnvAmt, float lfoModHz) {
        if (ampEnv.isIdle()) return 0.0f;

        float rawMix = (osc[0].nextSample() * oscVols[0]) +
                       (osc[1].nextSample() * oscVols[1]) +
                       (osc[2].nextSample() * oscVols[2]);

        float fEnvVal = filterEnv.nextLevel();
        float modulatedCutoff = baseCutoff + (fEnvVal * filterEnvAmt) + lfoModHz;

        // Passa o mix com headroom controlado pelo novo MoogLadderFilter
        float filteredSignal = vcf.process(rawMix * 0.55f, modulatedCutoff, resonance);
        return filteredSignal * ampEnv.nextLevel();
    }
};

// --- MOTOR DE ÁUDIO OBOE ---
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 16;
    std::array<Voice, MAX_VOICES> voices;
    Oscillator lfo;
    AnalogDelay tapeDelay;
    std::mutex voiceMutex;
    uint64_t voiceCounter = 0;

    float oscVolumes[3] = {0.75f, 0.55f, 0.45f};
    float oscTunes[3] = {0.0f, -12.0f, 7.0f};

    std::atomic<float> masterVolume{0.85f};
    std::atomic<float> filterCutoff{1800.0f};
    std::atomic<float> filterResonance{0.35f};
    std::atomic<float> filterEnvAmount{2500.0f};

    std::atomic<float> lfoRate{2.5f};
    std::atomic<float> lfoDepth{0.0f};
    std::atomic<float> delayTime{0.32f};
    std::atomic<float> delayFeedback{0.4f};
    std::atomic<float> delayMix{0.2f};

    static const int SCOPE_SIZE = 256;
    std::array<float, SCOPE_SIZE> scopeBuffer{};
    std::atomic<int> scopeWriteIndex{0};

public:
    AudioEngine() {
        for (auto& v : voices) v.init(48000.0f);
        lfo.init(48000.0f);
        lfo.setWaveType(2);
        tapeDelay.init(48000.0f);
        scopeBuffer.fill(0.0f);
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
               ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result == oboe::Result::OK) {
            float actualSampleRate = static_cast<float>(stream->getSampleRate());
            for (auto& v : voices) v.init(actualSampleRate);
            lfo.init(actualSampleRate);
            tapeDelay.init(actualSampleRate);
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
            std::thread([this]() {
                this->start();
            }).detach();
        }
    }

    void noteOn(int note, int velocity) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        Voice* targetVoice = nullptr;

        for (auto& v : voices) {
            if (v.isActive() && v.getNote() == note) { targetVoice = &v; break; }
        }
        if (!targetVoice) {
            for (auto& v : voices) {
                if (!v.isActive()) { targetVoice = &v; break; }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (auto& v : voices) {
                if (v.isReleasing() && v.getAge() < oldestAge) {
                    oldestAge = v.getAge();
                    targetVoice = &v;
                }
            }
        }
        if (!targetVoice) {
            uint64_t oldestAge = UINT64_MAX;
            for (auto& v : voices) {
                if (v.getAge() < oldestAge) {
                    oldestAge = v.getAge();
                    targetVoice = &v;
                }
            }
        }

        targetVoice->noteOn(note, oscTunes, ++voiceCounter);
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
        tapeDelay.clear();
    }

    void setOscVolume(int index, float vol) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        if (index >= 0 && index < 3) oscVolumes[index] = std::clamp(vol, 0.0f, 1.0f);
    }

    void setOscTune(int index, float semitones) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        if (index >= 0 && index < 3) {
            oscTunes[index] = semitones;
            for (auto& v : voices) {
                if (v.isActive()) v.updateFrequencies(oscTunes);
            }
        }
    }

    void setOscWave(int index, int waveType) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setWaveType(index, waveType);
    }

    void setMasterVolume(float vol) { masterVolume = vol; }
    void setFilterCutoff(float cutoffHz) { filterCutoff = cutoffHz; }
    void setFilterResonance(float res) { filterResonance = res; }
    void setFilterEnvAmount(float amtHz) { filterEnvAmount = amtHz; }

    void setLfoRate(float rateHz) { lfoRate = rateHz; }
    void setLfoDepth(float depthHz) { lfoDepth = depthHz; }
    void setDelayParams(float timeSec, float fb, float mix) {
        delayTime = timeSec;
        delayFeedback = fb;
        delayMix = mix;
    }

    void setAmpADSR(float a, float d, float s, float r) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setAmpADSR(a, d, s, r);
    }

    void setFilterADSR(float a, float d, float s, float r) {
        std::lock_guard<std::mutex> lock(voiceMutex);
        for (auto& v : voices) v.setFilterADSR(a, d, s, r);
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

        float cutoff = filterCutoff.load();
        float res = filterResonance.load();
        float envAmt = filterEnvAmount.load();
        float masterVol = masterVolume.load();
        float currentLfoRate = lfoRate.load();
        float currentLfoDepth = lfoDepth.load();
        float dTime = delayTime.load();
        float dFb = delayFeedback.load();
        float dMix = delayMix.load();

        std::unique_lock<std::mutex> lock(voiceMutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            std::fill(outputBuffer, outputBuffer + numFrames, 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

        lfo.setFrequency(currentLfoRate);
        int wIdx = scopeWriteIndex.load();

        for (int i = 0; i < numFrames; ++i) {
            float lfoMod = lfo.nextSample() * currentLfoDepth;
            float sampleSum = 0.0f;

            for (auto& v : voices) {
                sampleSum += v.renderSample(oscVolumes, cutoff, res, envAmt, lfoMod);
            }

            float withDelay = tapeDelay.process(sampleSum * 0.7f, dTime, dFb, dMix);
            float out = tanhf(withDelay * masterVol);
            outputBuffer[i] = out;

            if ((i & 3) == 0) {
                scopeBuffer[wIdx] = out;
                wIdx = (wIdx + 1) % SCOPE_SIZE;
            }
        }
        scopeWriteIndex.store(wIdx);

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
Java_com_moogsynth_app_audio_NativeAudioEngine_setOscVolume(JNIEnv *env, jobject thiz, jint index, jfloat volume) {
    if (engine) engine->setOscVolume(index, volume);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setOscTune(JNIEnv *env, jobject thiz, jint index, jfloat semitones) {
    if (engine) engine->setOscTune(index, semitones);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setOscWave(JNIEnv *env, jobject thiz, jint index, jint waveType) {
    if (engine) engine->setOscWave(index, waveType);
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
Java_com_moogsynth_app_audio_NativeAudioEngine_setLfoRate(JNIEnv *env, jobject thiz, jfloat rateHz) {
    if (engine) engine->setLfoRate(rateHz);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setLfoDepth(JNIEnv *env, jobject thiz, jfloat depthHz) {
    if (engine) engine->setLfoDepth(depthHz);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setDelayParams(JNIEnv *env, jobject thiz, jfloat timeSec, jfloat feedback, jfloat mix) {
    if (engine) engine->setDelayParams(timeSec, feedback, mix);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setAmpADSR(JNIEnv *env, jobject thiz, jfloat a, jfloat d, jfloat s, jfloat r) {
    if (engine) engine->setAmpADSR(a, d, s, r);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_setFilterADSR(JNIEnv *env, jobject thiz, jfloat a, jfloat d, jfloat s, jfloat r) {
    if (engine) engine->setFilterADSR(a, d, s, r);
}

JNIEXPORT void JNICALL
Java_com_moogsynth_app_audio_NativeAudioEngine_getWaveform(JNIEnv *env, jobject thiz, jfloatArray outArray) {
    if (!engine) return;
    jsize len = env->GetArrayLength(outArray);
    jfloat* body = env->GetFloatArrayElements(outArray, nullptr);
    engine->copyScopeData(body, len);
    env->ReleaseFloatArrayElements(outArray, body, 0);
}

}
