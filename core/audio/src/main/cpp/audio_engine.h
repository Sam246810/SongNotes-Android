#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "command.h"
#include "engine_state_block.h"
#include "scene.h"
#include "spsc_ring_buffer.h"

namespace songnotes {

enum class EngineMode : int32_t {
    Idle = 0,
    Armed = 1,
    Recording = 2,
    Playing = 3,
    TestTone = 4,
    Calibrating = 5, // Phase 3: plays a sweep from mScenePublisher while capturing the loopback into mRecordRing
};

// Output-master duplex engine: one Oboe output stream owns the data
// callback; a second input stream is opened alongside it with no callback
// of its own and is read non-blockingly from inside the output callback
// (Oboe's FullDuplexStream pattern). The test tone, recording (with its
// count-in and metronome), and playback are all just different things this
// same callback does with the same two streams, per the plan's "same engine
// path for calibration and real recording" principle.
class NativeAudioEngine : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    NativeAudioEngine() = default;
    ~NativeAudioEngine() override;

    NativeAudioEngine(const NativeAudioEngine &) = delete;
    NativeAudioEngine &operator=(const NativeAudioEngine &) = delete;

    // Opens the duplex streams if not already open, without starting any
    // particular mode. Lets a caller query inputSessionId() — and set up
    // AEC/NS/AGC on it — before startCalibrationCapture() begins, rather
    // than racing the very first captured frames against effect setup.
    bool ensureReady();

    bool startTestTone();
    void stopTestTone();

    // Begins count-in (countInBeats beats at bpm/beatsPerBar), then
    // transitions to actually recording at the downbeat. Capture into the
    // take actually starts kPreRollSeconds before the downbeat (not at Arm
    // time, and not at the downbeat) — see the .cpp for why.
    bool armRecording(const std::string &filePath, double bpm, int32_t beatsPerBar,
                       int32_t countInBeats);
    void stopRecording();

    bool startPlayback(const std::string &filePath);

    // Plays an in-memory buffer directly — no file I/O, so unlike
    // startPlayback() this runs synchronously on the calling thread rather
    // than spawning a loader thread (there's nothing to wait on). Reuses
    // Playing mode's existing onAudioReady logic entirely: publishing a
    // Scene and storing EngineMode::Playing is all loaderThreadLoop does
    // once its file read finishes anyway. Exists for Rule A's pre-mixed
    // verification playback (Calibration.buildPreMixedVerificationBuffer)
    // — no new RT-thread code needed for it.
    bool startPlaybackFromBuffer(const std::vector<float> &buffer);

    void stopPlayback();

    // Phase 3 calibration: plays `sweep` out through the same duplex engine
    // used for everything else, capturing the acoustic/electrical loopback
    // into mRecordRing for `sweep.size() + tailPaddingFrames` frames total
    // (the tail beyond the sweep's own length is what leaves room to
    // actually capture the round-trip delay + reverb tail, not just the
    // sweep's dry image). Mirrors Playing's Scene-based output path and
    // Recording's ring-buffer input path — no new RT-thread machinery, only
    // a new combination of the two existing ones.
    bool startCalibrationCapture(const std::vector<float> &sweep, int32_t tailPaddingFrames);
    void stopCalibration();

    // Call only after polling state() shows isCalibrating has dropped back
    // to false following a natural completion (not an abort via
    // stopCalibration(), which discards the in-flight capture instead) —
    // mirrors mRecordRing.clear()'s own "producer and consumer both
    // quiescent" precondition. One bulk read off mRecordRing, safe from any
    // non-RT thread once the RT-thread producer side is confirmed done.
    std::vector<float> takeCalibrationCapture();

    EngineStateBlock *stateBlock() { return &mState; }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                           int32_t numFrames) override;
    // oboe::AudioStreamErrorCallback — called on a normal (non-RT) callback
    // thread, safe to log/allocate/reopen streams here.
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

    // Capability getters — report the output stream. All lock mRebuildMutex
    // internally: mOutputStream can be reset concurrently by error recovery,
    // and these are called from the UI thread, never the RT thread.
    std::string audioApi();
    int32_t sampleRate();
    int32_t framesPerBurst();
    int32_t channelCount();
    std::string format();
    std::string sharingMode();
    std::string performanceMode();
    bool isMMapUsed();
    int32_t xRunCount();
    std::string lastError();

    // The input stream's allocated session ID (see setSessionId in
    // openStreamsLocked), for attaching android.media.audiofx.* effects
    // from Kotlin. -1 (matches oboe::SessionId::None / AudioManager's
    // AUDIO_SESSION_ID_NONE) if no input stream is open.
    int32_t inputSessionId();

private:
    // Must be called with mRebuildMutex already held.
    bool openStreamsLocked();
    void closeStreamsLocked();
    bool ensureStreamsOpen();
    bool ensureOutputStarted();
    int32_t sampleRateLocked(); // caller must hold mRebuildMutex

    void setLastError(const std::string &message);

    void writerThreadLoop(std::string filePath, int64_t headSkipFrames);
    void loaderThreadLoop(std::string filePath);

    void stopRecordingInternal();
    void stopPlaybackInternal();
    void stopCalibrationInternal();

    std::mutex mRebuildMutex; // guards stream open/close/rebuild; never touched by the RT thread
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::vector<float> mInputScratch; // sized once at open time; RT thread only touches, never resizes

    double mTestTonePhase = 0.0;
    std::atomic<int32_t> mMode{static_cast<int32_t>(EngineMode::Idle)};

    // Transport: master clock is output frames rendered since stream open.
    // Touched ONLY by the RT thread — never reset, monotonically increasing
    // for the life of the engine (wraps at ~2^63 frames, i.e. never).
    int64_t mStreamFrameCounter = 0;

    // UI/JNI thread -> RT thread. 64 slots is far more than one Arm command
    // could ever need to queue up, but costs nothing extra.
    SpscRingBuffer<Command> mCommandQueue{64};

    // Arm/count-in/metronome scheduling — all RT-thread-owned, set only
    // while draining an Arm command, read every callback thereafter.
    static constexpr double kPreRollSeconds = 0.75;
    static constexpr double kClickLengthSeconds = 0.01; // 10ms
    static constexpr double kDownbeatClickHz = 1800.0;
    static constexpr double kRegularClickHz = 1200.0;
    int64_t mBeatIntervalFrames = 0;
    int32_t mBeatsPerBar = 4;
    int64_t mArmFrame = 0;
    int64_t mDownbeatFrame = 0;
    int64_t mPreRollStartFrame = 0;
    int64_t mNextClickFrame = 0;
    int32_t mBeatIndexInBar = 0;
    std::vector<float> mClickTemplateDownbeat;
    std::vector<float> mClickTemplateRegular;
    const std::vector<float> *mActiveClickTemplate = nullptr;
    int32_t mActiveClickReadPos = 0;

    // Recording path: RT thread -> ring buffer -> writer thread -> file.
    // A permanent member (not allocated per-take) deliberately — see
    // docs/handoff/PHASE-02.md for why a per-take unique_ptr was a latent
    // use-after-free risk against the RT thread.
    static constexpr size_t kRingCapacityFrames = 1 << 20; // ~21.8s mono @48kHz of slack
    SpscRingBuffer<float> mRecordRing{kRingCapacityFrames};
    std::thread mWriterThread;
    std::atomic<bool> mWriterShouldRun{false};

    // Playback path: loader thread -> Scene -> RT thread reads it directly.
    ScenePublisher mScenePublisher;
    std::thread mLoaderThread;
    int64_t mPlaybackCursor = 0; // written by the loader thread only before the mode
                                   // transition to Playing is published (release-ordered);
                                   // read/advanced by the RT thread only afterward.
                                   // Reused as the sweep read cursor during Calibrating too
                                   // — Playing and Calibrating are mutually exclusive modes,
                                   // so this never needs to be two separate fields.

    // Phase 3 calibration capture: both set by startCalibrationCapture()
    // before the mode store (release-ordered, same handoff pattern as
    // mPlaybackCursor above), then owned by the RT thread for the duration
    // of the capture.
    int64_t mCalibrationCaptureTargetFrames = 0;
    int64_t mCalibrationFramesCaptured = 0;

    EngineStateBlock mState;

    mutable std::mutex mErrorMutex; // guards mLastError; never touched by the RT thread
    std::string mLastError;
};

} // namespace songnotes
