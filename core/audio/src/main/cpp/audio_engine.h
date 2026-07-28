#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <string>

namespace songnotes {

// Phase 0 scope: an output-only stream that plays a 440 Hz test tone and
// reports what Oboe actually negotiated. No input, no duplex, no ring
// buffer, no error-recovery rebuild — those arrive in Phase 1 once there's
// a writer thread and a Scene to rebuild against. Deliberately small.
class NativeAudioEngine : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    NativeAudioEngine() = default;
    ~NativeAudioEngine() override;

    NativeAudioEngine(const NativeAudioEngine &) = delete;
    NativeAudioEngine &operator=(const NativeAudioEngine &) = delete;

    bool startTestTone();
    void stop();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                           int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

    std::string audioApi() const;
    int32_t sampleRate() const;
    int32_t framesPerBurst() const;
    int32_t channelCount() const;
    std::string format() const;
    std::string sharingMode() const;
    std::string performanceMode() const;
    bool isMMapUsed() const;
    int32_t xRunCount() const;
    std::string lastError() const;

private:
    bool openStream();
    void closeStream();

    std::shared_ptr<oboe::AudioStream> mStream;
    double mPhase = 0.0;
    std::string mLastError;
};

} // namespace songnotes
