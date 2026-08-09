#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "command.h"
#include "dsp/piano_voice.h"
#include "dsp/track_mixer.h"
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
    MultitrackPlaying = 6, // Phase 4: mixes Scene::multitrack chunk-at-a-time via dsp::mixTracksInto
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
    //
    // calibrationOffsetFrames (Rule C) is the measured round-trip latency
    // for the route this recording will use — from CalibrationStore, 0.0
    // if nothing's been measured for this route yet — folded into how much
    // the writer thread trims so the stored take's frame 0 lands where the
    // user's performance actually happened, not where the downbeat was
    // nominally scheduled. See the .cpp for the direction/sign reasoning.
    //
    // backingTracks (Phase 4 punch-in): if non-empty, mixed into the
    // output alongside the count-in/metronome click — real overdubbing,
    // hearing the existing song while recording a new take onto it.
    // backingTracksStartFrame is where, on the backing tracks' own project
    // timeline, this take's downbeat lands — the new take's file frame 0
    // (after headSkipFrames trimming) corresponds to exactly that project
    // frame, which is what a caller needs to know to build the Clip it
    // eventually punches into a track. Empty backingTracks (the default)
    // reproduces plain click-only recording exactly, byte-for-byte — this
    // is deliberately additive, not a new recording mode.
    bool armRecording(const std::string &filePath, double bpm, int32_t beatsPerBar,
                       int32_t countInBeats, double calibrationOffsetFrames = 0.0,
                       const std::vector<dsp::Track> &backingTracks = {},
                       int64_t backingTracksStartFrame = 0);
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

    // Phase 4: plays a multitrack mix in real time — dsp::mixTracksInto()
    // called chunk-at-a-time from onAudioReady using a persistent
    // pre-sized scratch buffer (mMultitrackScratch), never allocating on
    // the RT thread. Total length is the furthest clip end across all
    // tracks; auto-stops there, same as startPlaybackFromBuffer(). Shares
    // stopPlayback()/mPlaybackCursor with the single-buffer Playing mode
    // (mutually exclusive, so no separate cursor field is needed).
    bool startMultitrackPlayback(const std::vector<dsp::Track> &tracks);

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

    // Phase 9 piano: (re)publishes the decoded sample bank — `entries[N]`
    // must correspond to `dsp::kPianoSamples[N]` (PianoSampleLoader.kt's
    // contract, enforced on the JNI marshaling side, not here). Safe to
    // call more than once (e.g. a reload); old generations are kept alive
    // a few publishes past their replacement, same minimal RCU pattern
    // ScenePublisher uses, so the RT thread can never read a freed bank —
    // though in practice this is expected to be called exactly once, when
    // the piano UI first becomes visible.
    bool loadPianoBank(std::vector<dsp::PianoSampleBankEntry> entries);

    // Enqueues a note-on/note-off exactly like armRecording() enqueues Arm
    // — through the command queue, not a direct atomic write — because the
    // RT thread's own mStreamFrameCounter at the instant it drains the
    // command is what a voice's envelope age is measured from (see
    // command.h's doc comment). No-ops (returns false) if the queue is
    // full, which given 64 slots and this being a human tapping a key
    // would require an absurd flood to ever happen.
    bool pianoNoteOn(int32_t midiNote);
    bool pianoNoteOff(int32_t midiNote);

    // Plain atomic write, no command queue — see mPianoMasterGain's own
    // doc comment for why volume doesn't need frame-accurate scheduling.
    void setPianoVolume(float gain) { mPianoMasterGain.store(gain, std::memory_order_relaxed); }

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

    // The input stream's actual device ID (Oboe reports back whatever the
    // system actually opened it on, which may differ from what was
    // requested if that device disappeared) — 0 (oboe::kUnspecified) if no
    // input stream is open. Diagnostics-visible confirmation that
    // setPreferredInputDevice() below actually took effect, closing the
    // Phase 3 gap where only the output stream's capabilities were
    // reportable.
    int32_t inputDeviceId();

    // Pins the input stream to a specific Android AudioDeviceInfo.id (e.g.
    // the built-in mic's), overriding the system's default input routing —
    // used so a user can keep recording on the phone's own mic while a
    // metronome click plays out to a connected headset/Bluetooth device
    // that would otherwise also become the default input (stealing the mic
    // along with it). `oboe::kUnspecified` (0) restores default routing.
    // Rebuilds the duplex streams immediately if they're already open
    // (same closeStreamsLocked()/openStreamsLocked() pair
    // onErrorAfterClose() uses for a route-change recovery) — always, even
    // if deviceId didn't numerically change, since a caller may be reacting
    // to what oboe::kUnspecified now resolves to having changed underneath
    // (e.g. Bluetooth SCO connecting/disconnecting), not to this preference
    // itself. If streams aren't open yet, just remembers the preference for
    // the next open. The OUTPUT stream is never pinned — it's left on
    // default routing deliberately, since that already follows a connected
    // headset/headphones automatically, which is exactly where the click
    // should play.
    bool setPreferredInputDevice(int32_t deviceId);

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

    // RT-thread-only, called from onAudioReady's command-drain step while
    // handling a PianoNoteOn/PianoNoteOff command — `engineSampleRateHz`
    // is `stream->getSampleRate()`, threaded through explicitly rather than
    // re-queried, since these run on the same thread/callback that already
    // has it.
    void handlePianoNoteOn(int32_t midi, double engineSampleRateHz);
    void handlePianoNoteOff(int32_t midi);

    std::mutex mRebuildMutex; // guards stream open/close/rebuild; never touched by the RT thread
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::vector<float> mInputScratch; // sized once at open time; RT thread only touches, never resizes

    // See setPreferredInputDevice()'s doc comment. oboe::kUnspecified (0)
    // means "system default" — Oboe's own AudioStreamBuilder default, so
    // applying this unconditionally in openStreamsLocked() is a no-op
    // until a caller actually sets it. Guarded by mRebuildMutex, same as
    // the streams themselves.
    int32_t mPreferredInputDeviceId = oboe::kUnspecified;

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

    // Phase 4 multitrack playback: mMultitrackTotalFrames is set by
    // startMultitrackPlayback() before the mode store, same handoff
    // pattern as mPlaybackCursor above (which multitrack playback reuses
    // as its own cursor too). mMultitrackScratch is sized once — to
    // kMaxFramesPerCallback frames, mono — the first time it's needed and
    // never resized afterward; onAudioReady writes into it via
    // dsp::mixTracksInto() every callback, which performs no allocation of
    // its own, keeping this RT-safe.
    int64_t mMultitrackTotalFrames = 0;
    std::vector<float> mMultitrackScratch;

    // Phase 4 punch-in: backing tracks played during Armed/Recording,
    // alongside the count-in/metronome click. Both set by armRecording()
    // before mCommandQueue.write() — visible to the RT thread once it
    // observes the Arm command via mCommandQueue.read(), same
    // release-acquire handoff the Command payload itself already relies
    // on (see SpscRingBuffer's own memory_order_release/acquire pair), not
    // a new synchronization mechanism. mBackingTracksTotalFrames == 0 (the
    // default, and what an empty backingTracks list produces) means "no
    // backing tracks this take" — onAudioReady's Armed/Recording branch
    // skips the mix entirely in that case, reproducing plain click-only
    // recording exactly. Reuses mMultitrackScratch as its mix scratch
    // buffer (mutually exclusive in time with MultitrackPlaying).
    int64_t mBackingTracksStartFrame = 0;
    int64_t mBackingTracksTotalFrames = 0;

    // Phase 9 piano: additive voice layer, rendered every callback
    // regardless of `mode` (see onAudioReady's step 3b and
    // docs/handoff/PHASE-09.md for why this isn't its own EngineMode).
    //
    // The bank is loaded once (typically when the piano UI first opens) and
    // never mutated in place while the engine lives — mPianoBank is an
    // atomic raw pointer the RT thread reads with one acquire load, exactly
    // ScenePublisher's pattern, just not routed through Scene itself: every
    // existing Scene-publishing call site would otherwise have to
    // remember to carry the bank forward, and forgetting one would
    // silently kill the piano mid-session. mPianoBankRetired keeps old
    // generations alive a few loadPianoBank() calls past their
    // replacement — touched only by whichever thread calls
    // loadPianoBank() (never the RT thread).
    std::atomic<const std::vector<dsp::PianoSampleBankEntry> *> mPianoBank{nullptr};
    std::deque<std::shared_ptr<const std::vector<dsp::PianoSampleBankEntry>>> mPianoBankRetired;

    // Fixed-size voice pool — no RT allocation. `midi == -1` marks an
    // inactive slot. `noteOnFrame`/`releaseFrame` are mStreamFrameCounter
    // values (not seconds), matching every other piece of scheduling state
    // in this class; converted to the envelope's ageSeconds terms only at
    // render time, against whatever this callback's actual output sample
    // rate is. `releaseFrame < 0` means still held. `sampleBankIndex`
    // indexes directly into `*mPianoBank.load()` (see dsp::PianoSample
    // BankEntry's own doc comment for the ordering contract).
    struct PianoVoiceState {
        bool active = false;
        int32_t midi = -1;
        int32_t sampleBankIndex = -1;
        double readPos = 0.0;
        double rate = 1.0;
        int64_t noteOnFrame = 0;
        int64_t releaseFrame = -1;
    };
    static constexpr int32_t kMaxPianoVoices = 16; // the web app has no cap (the browser handles it) — a real Android-side constraint
    // Plain atomic, not the command queue — unlike note-on/off, volume has
    // no frame-accurate scheduling need; the RT thread just wants whatever
    // the most recent value is, exactly like every other atomic knob in
    // this class (e.g. mMode itself).
    std::atomic<float> mPianoMasterGain{1.0f};
    std::array<PianoVoiceState, kMaxPianoVoices> mPianoVoices{};
    // Sized once, to kMaxFramesPerCallback frames, mono — same "never
    // resized on the RT thread" contract as mMultitrackScratch, and
    // deliberately a separate buffer from it: multitrack playback and
    // piano voices can both be live in the same callback (piano is
    // additive over every mode), so sharing one scratch buffer between
    // them would mean one silently clobbering the other's in-progress mix.
    std::vector<float> mPianoScratch;

    EngineStateBlock mState;

    mutable std::mutex mErrorMutex; // guards mLastError; never touched by the RT thread
    std::string mLastError;
};

} // namespace songnotes
