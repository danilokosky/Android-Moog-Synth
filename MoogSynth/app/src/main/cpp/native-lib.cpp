#include <jni.h>
#include <oboe/Oboe.h>
#include <cmath>
#include <array>
#include <atomic>

// --- OSCILADOR COM POLYBLEP (ANTI-ALIASING) ---
class Oscillator {
private:
    float sampleRate;
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
                sample = sinf(2.0f * M_PI * phase);
                break;
        }
        return sample;
    }
};

// --- ENVELOPE ADSR ---
class Envelope {
private:
    float sampleRate;
    enum State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE } state = IDLE;
    float currentLevel = 0.0f;
    float timeInState = 0.0f;

    float attackTime = 0.01f;
    float decayTime = 0.2f;
    float sustainLevel = 0.7f;
    float releaseTime = 0.3f;

public:
    void init(float sr) { sampleRate = sr; }

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

    bool isIdle() { return state == IDLE; }

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

// --- VOZ POLIFÓNICA ---
class Voice {
private:
    Oscillator osc1, osc2, osc3;
    Envelope envelope;
    int currentNote = -1;

public:
    void init(float sr) {
        osc1.init(sr); osc1.setWaveType(0); // Saw
        osc2.init(sr); osc2.setWaveType(1); // Square
        osc3.init(sr); osc3.setWaveType(2); // Triangle
        envelope.init(sr);
    }

    void noteOn(int note, float freq) {
        currentNote = note;
        osc1.setFrequency(freq);
        osc2.setFrequency(freq * 0.5f); // Sub-octava
        osc3.setFrequency(freq * 1.5f); // Quinta
        envelope.triggerOn();
    }

    void noteOff(int note) {
        if (currentNote == note) {
            envelope.triggerOff();
        }
    }

    bool isActive() { return !envelope.isIdle(); }
    int getNote() { return currentNote; }

    float renderSample() {
        if (envelope.isIdle()) return 0.0f;
        float mix = (osc1.nextSample() * 0.6f) + (osc2.nextSample() * 0.3f) + (osc3.nextSample() * 0.3f);
        return mix * envelope.nextLevel();
    }
};

// --- MOTOR DE ÁUDIO OBOE (ULTRA-BAIXA LATÊNCIA) ---
class AudioEngine : public oboe::AudioStreamDataCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static const int MAX_VOICES = 8;
    std::array<Voice, MAX_VOICES> voices;
    std::atomic<float> masterVolume{0.8f};

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
        // Encontra voz livre ou reutiliza
        Voice* targetVoice = nullptr;
        for (auto& v : voices) {
            if (!v.isActive()) { targetVoice = &v; break; }
        }
        if (!targetVoice) targetVoice = &voices[0]; // Voice stealing

        float freq = 440.0f * powf(2.0f, (note - 69.0f) / 12.0f);
        targetVoice->noteOn(note, freq);
    }

    void noteOff(int note) {
        for (auto& v : voices) {
            if (v.getNote() == note) v.noteOff(note);
        }
    }

    void setMasterVolume(float vol) { masterVolume = vol; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *outputBuffer = static_cast<float*>(audioData);

        for (int i = 0; i < numFrames; ++i) {
            float sampleSum = 0.0f;
            for (auto& v : voices) {
                sampleSum += v.renderSample();
            }

            // Soft Clipping Analógico (Tanh) para volume rico e sem estourar
            float saturated = tanhf(sampleSum * masterVolume * 0.7f);
            outputBuffer[i] = saturated;
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
Java_com_moogsynth_app_audio_NativeAudioEngine_setMasterVolume(JNIEnv *env, jobject thiz, jint oscIndex, jfloat volume) {
    if (engine) engine->setMasterVolume(volume);
}

}
