#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "engine_state_block.h"
#include "scene.h"
#include "spsc_ring_buffer.h"

namespace songnotes {

enum class EngineMode : int32_t { Idle = 0, Recording = 1, Playing = 2, TestTone = 3 };

// Output-master duplex engine: one Oboe output stream owns the data
// callback; a second input stream is opened alongside it with no callback
// of its own and is read non-blockingly from inside the output callback
// (Oboe's FullDuplexStream pattern). This is the one and only audio path —
// the test tone, recording, and playback are all just different things this
// same callback does with the same two streams, per the plan's "same engine
// path for calibration and real recording" principle.
class NativeAudioEngine : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    NativeAudioEngine() = default;
    ~NativeAudioEngine() override;

    NativeAudioEngine(const NativeAudioEngine &) = delete;
    NativeAudioEngine &operator=(const NativeAudioEngine &) = delete;

    bool startTestTone();
    void stopTestTone();

    bool startRecording(const std::string &filePath);
    void stopRecording();

    bool startPlayback(const std::string &filePath);
    void stopPlayback();

    EngineStateBlock *stateBlock() { return &mState; }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                           int32_t numFrames) override;
    // oboe::AudioStreamErrorCallback — called on a normal (non-RT) callback
    // thread, safe to log/allocate/reopen streams here.
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

    // Capability getters — report the output stream.
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
    // Must be called with mRebuildMutex already held.
    bool openStreamsLocked();
    void closeStreamsLocked();
    bool ensureStreamsOpen();
    bool ensureOutputStarted();

    void setLastError(const std::string &message);

    void writerThreadLoop(std::string filePath);
    void loaderThreadLoop(std::string filePath);

    void stopRecordingInternal();
    void stopPlaybackInternal();

    std::mutex mRebuildMutex; // guards stream open/close/rebuild; never touched by the RT thread
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::vector<float> mInputScratch; // sized once at open time; RT thread only touches, never resizes

    double mTestTonePhase = 0.0;
    std::atomic<int32_t> mMode{static_cast<int32_t>(EngineMode::Idle)};

    // Recording path: RT thread -> ring buffer -> writer thread -> file.
    static constexpr size_t kRingCapacityFrames = 1 << 20; // ~21.8s mono @48kHz of slack
    std::unique_ptr<SpscRingBuffer<float>> mRecordRing;
    std::thread mWriterThread;
    std::atomic<bool> mWriterShouldRun{false};

    // Playback path: loader thread -> Scene -> RT thread reads it directly.
    ScenePublisher mScenePublisher;
    std::thread mLoaderThread;
    int64_t mPlaybackCursor = 0; // written by the loader thread only before the mode
                                   // transition to Playing is published (release-ordered);
                                   // read/advanced by the RT thread only afterward.

    EngineStateBlock mState;

    mutable std::mutex mErrorMutex; // guards mLastError; never touched by the RT thread
    std::string mLastError;
};

} // namespace songnotes
