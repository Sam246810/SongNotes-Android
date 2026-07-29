#include "audio_engine.h"

#include <android/log.h>
#include <oboe/OboeExtensions.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>

#include "dsp/click.h"
#include "dsp/sine_wave.h"

#define LOG_TAG "SongNotesAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace songnotes {

namespace {
constexpr double kTestToneHz = 440.0;
constexpr float kTestToneAmplitude = 0.2f;
constexpr float kClickAmplitude = 0.5f;
// Generous ceiling on frames-per-callback so mInputScratch is sized once,
// up front, and never touched by the RT thread except to read/write within
// bounds. Real devices sit far below this even at the largest Shared-mode
// fallback buffer sizes.
constexpr int32_t kMaxFramesPerCallback = 8192;
} // namespace

NativeAudioEngine::~NativeAudioEngine() {
    stopRecordingInternal();
    stopPlaybackInternal();
    if (mLoaderThread.joinable()) {
        mLoaderThread.join();
    }
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    closeStreamsLocked();
}

void NativeAudioEngine::setLastError(const std::string &message) {
    std::lock_guard<std::mutex> lock(mErrorMutex);
    mLastError = message;
    LOGW("%s", message.c_str());
}

bool NativeAudioEngine::openStreamsLocked() {
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result outResult = outBuilder.openStream(mOutputStream);
    if (outResult != oboe::Result::OK) {
        LOGW("Exclusive output open failed (%s), retrying Shared",
             oboe::convertToText(outResult));
        outBuilder.setSharingMode(oboe::SharingMode::Shared);
        outResult = outBuilder.openStream(mOutputStream);
    }
    if (outResult != oboe::Result::OK) {
        setLastError(std::string("output openStream failed: ") +
                      oboe::convertToText(outResult));
        return false;
    }

    // Input: sample rate matched to what the output stream actually
    // negotiated, so the duplex read never needs to resample.
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(mOutputStream->getSampleRate())
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setErrorCallback(this);

    oboe::Result inResult = inBuilder.openStream(mInputStream);
    if (inResult != oboe::Result::OK) {
        LOGW("Exclusive+Unprocessed input open failed (%s), retrying Shared",
             oboe::convertToText(inResult));
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        inResult = inBuilder.openStream(mInputStream);
    }
    if (inResult != oboe::Result::OK) {
        LOGW("Shared+Unprocessed input open failed (%s), retrying VoiceRecognition",
             oboe::convertToText(inResult));
        inBuilder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        inResult = inBuilder.openStream(mInputStream);
    }
    if (inResult != oboe::Result::OK) {
        setLastError(std::string("input openStream failed: ") +
                      oboe::convertToText(inResult));
        mOutputStream->close();
        mOutputStream.reset();
        return false;
    }

    mInputScratch.assign(
        static_cast<size_t>(kMaxFramesPerCallback) * static_cast<size_t>(mInputStream->getChannelCount()),
        0.0f);

    auto startResult = mInputStream->requestStart();
    if (startResult != oboe::Result::OK) {
        setLastError(std::string("input requestStart failed: ") + oboe::convertToText(startResult));
        return false;
    }

    // Click templates depend on the negotiated sample rate, so (re)build
    // them here rather than once at construction — they must stay valid
    // across a route-change rebuild too, where the rate could change.
    const auto sr = static_cast<double>(mOutputStream->getSampleRate());
    const auto clickLengthFrames = static_cast<int32_t>(std::llround(sr * kClickLengthSeconds));
    mClickTemplateDownbeat = dsp::generateClick(sr, kDownbeatClickHz, clickLengthFrames, kClickAmplitude);
    mClickTemplateRegular = dsp::generateClick(sr, kRegularClickHz, clickLengthFrames, kClickAmplitude);
    mActiveClickTemplate = nullptr;
    mActiveClickReadPos = 0;

    LOGI("Duplex streams opened: out api=%d sharing=%d sampleRate=%d framesPerBurst=%d mmap=%d | "
         "in sharing=%d",
         static_cast<int>(mOutputStream->getAudioApi()), static_cast<int>(mOutputStream->getSharingMode()),
         mOutputStream->getSampleRate(), mOutputStream->getFramesPerBurst(),
         static_cast<int>(oboe::OboeExtensions::isMMapUsed(mOutputStream.get())),
         static_cast<int>(mInputStream->getSharingMode()));
    return true;
}

void NativeAudioEngine::closeStreamsLocked() {
    if (mInputStream) {
        mInputStream->stop();
        mInputStream->close();
        mInputStream.reset();
    }
    if (mOutputStream) {
        mOutputStream->stop();
        mOutputStream->close();
        mOutputStream.reset();
    }
}

bool NativeAudioEngine::ensureStreamsOpen() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (mOutputStream && mInputStream) return true;
    return openStreamsLocked();
}

bool NativeAudioEngine::ensureOutputStarted() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return false;
    if (mOutputStream->getState() == oboe::StreamState::Started) return true;
    auto result = mOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        setLastError(std::string("output requestStart failed: ") + oboe::convertToText(result));
        return false;
    }
    return true;
}

int32_t NativeAudioEngine::sampleRateLocked() {
    return mOutputStream ? mOutputStream->getSampleRate() : 48000;
}

bool NativeAudioEngine::startTestTone() {
    if (!ensureStreamsOpen()) return false;
    mTestTonePhase = 0.0;
    mMode.store(static_cast<int32_t>(EngineMode::TestTone), std::memory_order_release);
    return ensureOutputStarted();
}

void NativeAudioEngine::stopTestTone() {
    auto expected = static_cast<int32_t>(EngineMode::TestTone);
    mMode.compare_exchange_strong(expected, static_cast<int32_t>(EngineMode::Idle));
}

bool NativeAudioEngine::armRecording(const std::string &filePath, double bpm, int32_t beatsPerBar,
                                      int32_t countInBeats) {
    if (!ensureStreamsOpen()) return false;
    stopRecordingInternal(); // in case one was already in flight

    int32_t sr;
    {
        std::lock_guard<std::mutex> lock(mRebuildMutex);
        sr = sampleRateLocked();
    }
    const auto preRollFrames = static_cast<int64_t>(std::llround(static_cast<double>(sr) * kPreRollSeconds));

    mState.framesRecorded.store(0, std::memory_order_relaxed);
    mState.framesDropped.store(0, std::memory_order_relaxed);
    mState.isArmed.store(1, std::memory_order_relaxed);
    mState.isRecording.store(0, std::memory_order_relaxed);
    mState.countInBeatsRemaining.store(countInBeats, std::memory_order_relaxed);
    mWriterShouldRun.store(true, std::memory_order_relaxed);
    mWriterThread = std::thread(&NativeAudioEngine::writerThreadLoop, this, filePath, preRollFrames);

    Command cmd;
    cmd.type = CommandType::Arm;
    cmd.bpm = bpm;
    cmd.beatsPerBar = beatsPerBar;
    cmd.countInBeats = countInBeats;
    cmd.preRollFrames = preRollFrames;
    mCommandQueue.write(&cmd, 1);

    return ensureOutputStarted();
}

void NativeAudioEngine::stopRecording() { stopRecordingInternal(); }

void NativeAudioEngine::stopRecordingInternal() {
    const auto current = static_cast<EngineMode>(mMode.load(std::memory_order_acquire));
    if (current == EngineMode::Armed || current == EngineMode::Recording) {
        mMode.store(static_cast<int32_t>(EngineMode::Idle), std::memory_order_release);
    }
    mState.isArmed.store(0, std::memory_order_relaxed);
    mState.isRecording.store(0, std::memory_order_relaxed);
    mState.countInBeatsRemaining.store(0, std::memory_order_relaxed);
    mWriterShouldRun.store(false, std::memory_order_relaxed);
    if (mWriterThread.joinable()) {
        mWriterThread.join();
    }
    mRecordRing.clear(); // safe: producer (RT) and consumer (writer thread) are both quiescent here
}

bool NativeAudioEngine::startPlayback(const std::string &filePath) {
    if (!ensureStreamsOpen()) return false;
    stopPlaybackInternal();
    if (mLoaderThread.joinable()) {
        mLoaderThread.join(); // in case a previous load is still finishing up
    }
    mState.isPlaying.store(1, std::memory_order_relaxed);
    mLoaderThread = std::thread(&NativeAudioEngine::loaderThreadLoop, this, filePath);
    return ensureOutputStarted();
}

void NativeAudioEngine::stopPlayback() { stopPlaybackInternal(); }

void NativeAudioEngine::stopPlaybackInternal() {
    auto expected = static_cast<int32_t>(EngineMode::Playing);
    mMode.compare_exchange_strong(expected, static_cast<int32_t>(EngineMode::Idle));
    mState.isPlaying.store(0, std::memory_order_relaxed);
}

void NativeAudioEngine::writerThreadLoop(std::string filePath, int64_t headSkipFrames) {
    std::ofstream file(filePath, std::ios::binary | std::ios::trunc);
    if (!file) {
        setLastError("writerThreadLoop: failed to open " + filePath + " for writing");
        mWriterShouldRun.store(false, std::memory_order_relaxed);
        return;
    }

    std::vector<float> scratch(4096);
    int64_t framesSeenSoFar = 0;
    while (mWriterShouldRun.load(std::memory_order_relaxed) || mRecordRing.availableToRead() > 0) {
        size_t got = mRecordRing.read(scratch.data(), scratch.size());
        if (got == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        const int64_t framesBefore = framesSeenSoFar;
        framesSeenSoFar += static_cast<int64_t>(got);
        if (framesBefore >= headSkipFrames) {
            // Already past the pre-roll — the whole read belongs in the file.
            file.write(reinterpret_cast<const char *>(scratch.data()),
                       static_cast<std::streamsize>(got * sizeof(float)));
        } else if (framesSeenSoFar > headSkipFrames) {
            // This read straddles the pre-roll boundary — write only the tail past it,
            // so the file's frame 0 is exactly the downbeat, not the pre-roll start.
            const auto skipHere = static_cast<size_t>(headSkipFrames - framesBefore);
            file.write(reinterpret_cast<const char *>(scratch.data() + skipHere),
                       static_cast<std::streamsize>((got - skipHere) * sizeof(float)));
        }
        // else: this read is still entirely within the pre-roll — discard.
    }
    file.flush();
}

void NativeAudioEngine::loaderThreadLoop(std::string filePath) {
    auto scene = std::make_shared<Scene>();
    auto buffer = std::make_shared<std::vector<float>>();

    std::ifstream file(filePath, std::ios::binary | std::ios::ate);
    if (!file) {
        setLastError("loaderThreadLoop: failed to open " + filePath);
        mState.isPlaying.store(0, std::memory_order_relaxed);
        return;
    }
    const std::streamsize byteSize = file.tellg();
    file.seekg(0, std::ios::beg);
    const auto frameCount = static_cast<size_t>(byteSize) / sizeof(float);
    buffer->resize(frameCount);
    if (frameCount > 0 &&
        !file.read(reinterpret_cast<char *>(buffer->data()), static_cast<std::streamsize>(frameCount * sizeof(float)))) {
        setLastError("loaderThreadLoop: short read on " + filePath);
        mState.isPlaying.store(0, std::memory_order_relaxed);
        return;
    }

    scene->playbackBuffer = buffer;
    mPlaybackCursor = 0; // safe: RT thread only reads this once the mode below is observed
    mState.playbackTotalFrames.store(static_cast<int32_t>(frameCount), std::memory_order_relaxed);
    mState.playbackFrame.store(0, std::memory_order_relaxed);
    mScenePublisher.publish(scene);
    // Release-ordered: guarantees the RT thread's acquire-load of mMode
    // below also sees the Scene publish and mPlaybackCursor reset above.
    mMode.store(static_cast<int32_t>(EngineMode::Playing), std::memory_order_release);
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *stream, void *audioData,
                                                          int32_t numFrames) {
    // Real-time thread: no allocation, no locks, no JNI, no logging below this line.
    auto *out = static_cast<float *>(audioData);
    const int32_t outChannels = stream->getChannelCount();

    // 0. Drain any pending Arm command. This is where count-in/pre-roll
    // scheduling is computed, using THIS thread's own frame counter — the
    // whole reason a command queue exists instead of the UI thread just
    // guessing "now" and racing with us.
    Command cmd;
    while (mCommandQueue.read(&cmd, 1) == 1) {
        if (cmd.type == CommandType::Arm) {
            // Clamp to at least 1 frame so a degenerate bpm (0, negative,
            // absurdly high) can never produce a zero/negative beat
            // interval — that would make the click-scheduling loop below
            // spin without mNextClickFrame ever advancing past the current
            // callback.
            mBeatIntervalFrames = std::max<int64_t>(
                1, static_cast<int64_t>(std::llround(static_cast<double>(stream->getSampleRate()) * 60.0 / cmd.bpm)));
            mBeatsPerBar = cmd.beatsPerBar;
            mArmFrame = mStreamFrameCounter;
            mDownbeatFrame = mArmFrame + static_cast<int64_t>(cmd.countInBeats) * mBeatIntervalFrames;
            mPreRollStartFrame = mDownbeatFrame - cmd.preRollFrames;
            mNextClickFrame = mArmFrame;
            mBeatIndexInBar = 0;
            mActiveClickTemplate = nullptr;
            mActiveClickReadPos = 0;
            mMode.store(static_cast<int32_t>(EngineMode::Armed), std::memory_order_release);
        }
    }

    auto mode = static_cast<EngineMode>(mMode.load(std::memory_order_acquire));

    // 0b. Autonomous Armed -> Recording transition at the downbeat. Purely a
    // state label for the UI/ring-buffer-accounting below — click rendering
    // and pre-roll capture already treat Armed and Recording identically,
    // so callback-granularity timing here has no acoustic consequence.
    if (mode == EngineMode::Armed && mStreamFrameCounter >= mDownbeatFrame) {
        mMode.store(static_cast<int32_t>(EngineMode::Recording), std::memory_order_release);
        mState.isArmed.store(0, std::memory_order_relaxed);
        mState.isRecording.store(1, std::memory_order_relaxed);
        mState.countInBeatsRemaining.store(0, std::memory_order_relaxed);
        mode = EngineMode::Recording;
    }

    // 1. Drain input (non-blocking) into the pre-sized scratch buffer.
    const int32_t inChannels = mInputStream ? mInputStream->getChannelCount() : 1;
    const int32_t maxScratchFrames =
        inChannels > 0 ? static_cast<int32_t>(mInputScratch.size() / static_cast<size_t>(inChannels)) : 0;
    const int32_t framesWanted = std::min(numFrames, maxScratchFrames);

    int32_t framesRead = 0;
    if (mInputStream && framesWanted > 0) {
        auto result = mInputStream->read(mInputScratch.data(), framesWanted, 0 /* non-blocking */);
        if (result) {
            framesRead = result.value();
        }
    }
    if (framesRead < framesWanted) {
        std::fill(mInputScratch.begin() + static_cast<size_t>(framesRead) * inChannels,
                  mInputScratch.begin() + static_cast<size_t>(framesWanted) * inChannels, 0.0f);
    }
    const int32_t framesShort = numFrames - framesRead;
    if (framesShort > 0) {
        mState.framesDropped.fetch_add(framesShort, std::memory_order_relaxed);
    }

    // 2. Recording (Armed or Recording): push captured input into the ring
    // buffer once we've reached the pre-roll start — sample-accurate, not
    // just callback-accurate, since this boundary determines exactly how
    // much of the file the writer thread later discards as pre-roll.
    if (mode == EngineMode::Armed || mode == EngineMode::Recording) {
        const int64_t callbackEndFrame = mStreamFrameCounter + numFrames;
        if (callbackEndFrame > mPreRollStartFrame) {
            const int64_t skipWithinCallback = std::max<int64_t>(0, mPreRollStartFrame - mStreamFrameCounter);
            const auto startOffset = static_cast<int32_t>(std::min<int64_t>(skipWithinCallback, framesWanted));
            const int32_t framesToWrite = framesWanted - startOffset;
            if (framesToWrite > 0) {
                const size_t written = mRecordRing.write(
                    mInputScratch.data() + static_cast<size_t>(startOffset) * inChannels,
                    static_cast<size_t>(framesToWrite));
                if (written < static_cast<size_t>(framesToWrite)) {
                    mState.framesDropped.fetch_add(static_cast<int32_t>(framesToWrite - written),
                                                    std::memory_order_relaxed);
                }
                mState.framesRecorded.fetch_add(framesToWrite, std::memory_order_relaxed);
            }
        }
    }

    // 3. Fill the output buffer.
    std::fill(out, out + static_cast<size_t>(numFrames) * outChannels, 0.0f);
    if (mode == EngineMode::TestTone) {
        dsp::renderSine(out, numFrames, outChannels, stream->getSampleRate(), kTestToneHz,
                         kTestToneAmplitude, &mTestTonePhase);
    } else if (mode == EngineMode::Playing) {
        const Scene *scene = mScenePublisher.current();
        const auto &buffer = scene->playbackBuffer;
        if (buffer && mPlaybackCursor < static_cast<int64_t>(buffer->size())) {
            const int64_t remaining = static_cast<int64_t>(buffer->size()) - mPlaybackCursor;
            const auto toCopy = static_cast<int32_t>(remaining < numFrames ? remaining : numFrames);
            for (int32_t frame = 0; frame < toCopy; frame++) {
                const float sample = (*buffer)[static_cast<size_t>(mPlaybackCursor + frame)];
                for (int32_t ch = 0; ch < outChannels; ch++) {
                    out[frame * outChannels + ch] = sample;
                }
            }
            mPlaybackCursor += toCopy;
            mState.playbackFrame.store(static_cast<int32_t>(mPlaybackCursor), std::memory_order_relaxed);
            if (mPlaybackCursor >= static_cast<int64_t>(buffer->size())) {
                mMode.store(static_cast<int32_t>(EngineMode::Idle), std::memory_order_release);
                mState.isPlaying.store(0, std::memory_order_relaxed);
            }
        }
    } else if (mode == EngineMode::Armed || mode == EngineMode::Recording) {
        // Metronome: finish any click already in progress, then start any
        // click(s) scheduled within the remainder of this callback. No live
        // input monitoring is mixed in here — see the plan's "don't build
        // software input monitoring" decision.
        if (mActiveClickTemplate != nullptr) {
            const auto &tmpl = *mActiveClickTemplate;
            const auto remaining = static_cast<int32_t>(tmpl.size()) - mActiveClickReadPos;
            const int32_t toCopy = std::min(remaining, numFrames);
            for (int32_t frame = 0; frame < toCopy; frame++) {
                const float sample = tmpl[static_cast<size_t>(mActiveClickReadPos + frame)];
                for (int32_t ch = 0; ch < outChannels; ch++) {
                    out[frame * outChannels + ch] += sample;
                }
            }
            mActiveClickReadPos += toCopy;
            if (mActiveClickReadPos >= static_cast<int32_t>(tmpl.size())) {
                mActiveClickTemplate = nullptr;
                mActiveClickReadPos = 0;
            }
        }

        while (mActiveClickTemplate == nullptr && mNextClickFrame < mStreamFrameCounter + numFrames) {
            const auto &tmpl = (mBeatIndexInBar == 0) ? mClickTemplateDownbeat : mClickTemplateRegular;
            // Clamped to >=0 rather than requiring mNextClickFrame >=
            // mStreamFrameCounter: if a previous click's tail delayed this
            // one (pathological — click length is ~10ms, beats are hundreds
            // of ms apart, so this shouldn't happen in practice), render it
            // immediately rather than silently dropping the beat.
            const auto offsetInCallback =
                static_cast<int32_t>(std::max<int64_t>(0, mNextClickFrame - mStreamFrameCounter));
            const int32_t framesAvailableInCallback = numFrames - offsetInCallback;
            const auto toCopy =
                static_cast<int32_t>(std::min<size_t>(tmpl.size(), static_cast<size_t>(framesAvailableInCallback)));
            for (int32_t frame = 0; frame < toCopy; frame++) {
                const float sample = tmpl[static_cast<size_t>(frame)];
                for (int32_t ch = 0; ch < outChannels; ch++) {
                    out[(offsetInCallback + frame) * outChannels + ch] += sample;
                }
            }
            if (toCopy < static_cast<int32_t>(tmpl.size())) {
                mActiveClickTemplate = &tmpl;
                mActiveClickReadPos = toCopy;
            }

            if (mode == EngineMode::Armed) {
                const int32_t remaining = mState.countInBeatsRemaining.load(std::memory_order_relaxed);
                mState.countInBeatsRemaining.store(std::max(0, remaining - 1), std::memory_order_relaxed);
            }
            mBeatIndexInBar = (mBeatIndexInBar + 1) % std::max(1, mBeatsPerBar);
            mNextClickFrame += mBeatIntervalFrames;
        }
    }

    // 4. xrun bookkeeping — one non-blocking query, no allocation.
    auto xrunResult = stream->getXRunCount();
    if (xrunResult) {
        mState.xRunCount.store(xrunResult.value(), std::memory_order_relaxed);
    }

    mStreamFrameCounter += numFrames;
    return oboe::DataCallbackResult::Continue;
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    // Called on Oboe's internal callback/binder thread — NOT the RT audio
    // thread — so logging, allocation, and reopening streams here is safe
    // and is Oboe's own documented recovery pattern.
    setLastError(std::string("stream error after close: ") + oboe::convertToText(error));
    LOGW("recovering (stream=%p)", static_cast<void *>(stream));

    const auto modeBeforeError = static_cast<EngineMode>(mMode.load(std::memory_order_acquire));

    std::lock_guard<std::mutex> lock(mRebuildMutex);
    closeStreamsLocked();

    if (!openStreamsLocked()) {
        LOGW("Stream rebuild failed after disconnect — stopping any active take.");
        mMode.store(static_cast<int32_t>(EngineMode::Idle), std::memory_order_release);
        // Neither of these touches mRebuildMutex, so calling them while we
        // still hold it here is safe (no re-entrant lock). Skipping this on
        // a failed rebuild would otherwise leak the writer thread forever.
        stopRecordingInternal();
        stopPlaybackInternal();
        return;
    }

    // Reopened, presumably on a new route (e.g. headphones were unplugged
    // mid-take). The ring buffer, writer thread, and file are untouched by
    // close/open — nothing already captured is lost. There is an
    // unavoidable gap in the take across the physical disconnect+reopen
    // window, since no audio hardware existed to capture anything during it.
    // Count-in/click scheduling (mArmFrame etc.) is NOT recomputed here —
    // it's expressed in mStreamFrameCounter terms, which keeps counting
    // through the rebuild, so the beat grid doesn't shift.
    mMode.store(static_cast<int32_t>(modeBeforeError), std::memory_order_release);
    if (mOutputStream) {
        auto result = mOutputStream->requestStart();
        if (result != oboe::Result::OK) {
            setLastError(std::string("post-rebuild requestStart failed: ") + oboe::convertToText(result));
        }
    }
}

std::string NativeAudioEngine::audioApi() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return "-";
    switch (mOutputStream->getAudioApi()) {
        case oboe::AudioApi::AAudio:
            return "AAudio";
        case oboe::AudioApi::OpenSLES:
            return "OpenSL ES";
        default:
            return "Unknown";
    }
}

int32_t NativeAudioEngine::sampleRate() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    return sampleRateLocked();
}

int32_t NativeAudioEngine::framesPerBurst() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    return mOutputStream ? mOutputStream->getFramesPerBurst() : 0;
}

int32_t NativeAudioEngine::channelCount() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    return mOutputStream ? mOutputStream->getChannelCount() : 0;
}

std::string NativeAudioEngine::format() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return "-";
    switch (mOutputStream->getFormat()) {
        case oboe::AudioFormat::Float:
            return "Float";
        case oboe::AudioFormat::I16:
            return "I16";
        case oboe::AudioFormat::I24:
            return "I24";
        case oboe::AudioFormat::I32:
            return "I32";
        default:
            return "Unknown";
    }
}

std::string NativeAudioEngine::sharingMode() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return "-";
    return mOutputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";
}

std::string NativeAudioEngine::performanceMode() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return "-";
    switch (mOutputStream->getPerformanceMode()) {
        case oboe::PerformanceMode::LowLatency:
            return "LowLatency";
        case oboe::PerformanceMode::PowerSaving:
            return "PowerSaving";
        default:
            return "None";
    }
}

bool NativeAudioEngine::isMMapUsed() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    return mOutputStream && oboe::OboeExtensions::isMMapUsed(mOutputStream.get());
}

int32_t NativeAudioEngine::xRunCount() {
    std::lock_guard<std::mutex> lock(mRebuildMutex);
    if (!mOutputStream) return 0;
    auto result = mOutputStream->getXRunCount();
    return result ? result.value() : 0;
}

std::string NativeAudioEngine::lastError() {
    std::lock_guard<std::mutex> lock(mErrorMutex);
    return mLastError;
}

} // namespace songnotes
