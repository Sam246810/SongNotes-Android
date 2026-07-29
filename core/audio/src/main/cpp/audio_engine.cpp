#include "audio_engine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>

#include "dsp/sine_wave.h"

#define LOG_TAG "SongNotesAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace songnotes {

namespace {
constexpr double kTestToneHz = 440.0;
constexpr float kTestToneAmplitude = 0.2f;
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

    auto outResult = outBuilder.openStream();
    if (outResult != oboe::Result::OK) {
        LOGW("Exclusive output open failed (%s), retrying Shared",
             oboe::convertToText(outResult.error()));
        outBuilder.setSharingMode(oboe::SharingMode::Shared);
        outResult = outBuilder.openStream();
    }
    if (outResult != oboe::Result::OK) {
        setLastError(std::string("output openStream failed: ") +
                      oboe::convertToText(outResult.error()));
        return false;
    }
    mOutputStream = outResult.value();

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

    auto inResult = inBuilder.openStream();
    if (inResult != oboe::Result::OK) {
        LOGW("Exclusive+Unprocessed input open failed (%s), retrying Shared",
             oboe::convertToText(inResult.error()));
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        inResult = inBuilder.openStream();
    }
    if (inResult != oboe::Result::OK) {
        LOGW("Shared+Unprocessed input open failed (%s), retrying VoiceRecognition",
             oboe::convertToText(inResult.error()));
        inBuilder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        inResult = inBuilder.openStream();
    }
    if (inResult != oboe::Result::OK) {
        setLastError(std::string("input openStream failed: ") +
                      oboe::convertToText(inResult.error()));
        mOutputStream->close();
        mOutputStream.reset();
        return false;
    }
    mInputStream = inResult.value();

    mInputScratch.assign(
        static_cast<size_t>(kMaxFramesPerCallback) * static_cast<size_t>(mInputStream->getChannelCount()),
        0.0f);

    auto startResult = mInputStream->requestStart();
    if (startResult != oboe::Result::OK) {
        setLastError(std::string("input requestStart failed: ") + oboe::convertToText(startResult));
        return false;
    }

    LOGI("Duplex streams opened: out api=%d sharing=%d sampleRate=%d framesPerBurst=%d mmap=%d | "
         "in sharing=%d",
         static_cast<int>(mOutputStream->getAudioApi()), static_cast<int>(mOutputStream->getSharingMode()),
         mOutputStream->getSampleRate(), mOutputStream->getFramesPerBurst(), mOutputStream->isMMapUsed(),
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

bool NativeAudioEngine::startRecording(const std::string &filePath) {
    if (!ensureStreamsOpen()) return false;
    stopRecordingInternal(); // in case one was already in flight

    mRecordRing = std::make_unique<SpscRingBuffer<float>>(kRingCapacityFrames);
    mState.framesRecorded.store(0, std::memory_order_relaxed);
    mState.framesDropped.store(0, std::memory_order_relaxed);
    mState.isRecording.store(1, std::memory_order_relaxed);
    mWriterShouldRun.store(true, std::memory_order_relaxed);
    mWriterThread = std::thread(&NativeAudioEngine::writerThreadLoop, this, filePath);

    mMode.store(static_cast<int32_t>(EngineMode::Recording), std::memory_order_release);
    return ensureOutputStarted();
}

void NativeAudioEngine::stopRecording() { stopRecordingInternal(); }

void NativeAudioEngine::stopRecordingInternal() {
    auto expected = static_cast<int32_t>(EngineMode::Recording);
    mMode.compare_exchange_strong(expected, static_cast<int32_t>(EngineMode::Idle));
    mState.isRecording.store(0, std::memory_order_relaxed);
    mWriterShouldRun.store(false, std::memory_order_relaxed);
    if (mWriterThread.joinable()) {
        mWriterThread.join();
    }
    mRecordRing.reset();
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

void NativeAudioEngine::writerThreadLoop(std::string filePath) {
    std::ofstream file(filePath, std::ios::binary | std::ios::trunc);
    if (!file) {
        setLastError("writerThreadLoop: failed to open " + filePath + " for writing");
        mWriterShouldRun.store(false, std::memory_order_relaxed);
        return;
    }

    std::vector<float> scratch(4096);
    while (mWriterShouldRun.load(std::memory_order_relaxed) ||
           (mRecordRing && mRecordRing->availableToRead() > 0)) {
        size_t got = mRecordRing ? mRecordRing->read(scratch.data(), scratch.size()) : 0;
        if (got > 0) {
            file.write(reinterpret_cast<const char *>(scratch.data()),
                       static_cast<std::streamsize>(got * sizeof(float)));
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
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
    const auto mode = static_cast<EngineMode>(mMode.load(std::memory_order_acquire));

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

    // 2. Recording: push captured input into the ring buffer for the writer thread.
    if (mode == EngineMode::Recording && mRecordRing) {
        const size_t written = mRecordRing->write(mInputScratch.data(), static_cast<size_t>(framesWanted));
        if (written < static_cast<size_t>(framesWanted)) {
            mState.framesDropped.fetch_add(static_cast<int32_t>(framesWanted - written),
                                            std::memory_order_relaxed);
        }
        mState.framesRecorded.fetch_add(numFrames, std::memory_order_relaxed);
    }

    // 3. Fill the output buffer.
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
            std::fill(out + static_cast<size_t>(toCopy) * outChannels, out + static_cast<size_t>(numFrames) * outChannels,
                      0.0f);
            mPlaybackCursor += toCopy;
            mState.playbackFrame.store(static_cast<int32_t>(mPlaybackCursor), std::memory_order_relaxed);
            if (mPlaybackCursor >= static_cast<int64_t>(buffer->size())) {
                mMode.store(static_cast<int32_t>(EngineMode::Idle), std::memory_order_release);
                mState.isPlaying.store(0, std::memory_order_relaxed);
            }
        } else {
            std::fill(out, out + static_cast<size_t>(numFrames) * outChannels, 0.0f);
        }
    } else {
        // Recording or Idle: output carries silence. No live input
        // monitoring — see the plan's "don't build software input
        // monitoring" decision (round trip is far too long for it to be
        // usable, and wired headphones already give acoustic monitoring).
        std::fill(out, out + static_cast<size_t>(numFrames) * outChannels, 0.0f);
    }

    // 4. xrun bookkeeping — one non-blocking query, no allocation.
    auto xrunResult = stream->getXRunCount();
    if (xrunResult) {
        mState.xRunCount.store(xrunResult.value(), std::memory_order_relaxed);
    }

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
        // a failed rebuild would otherwise leak the writer thread forever:
        // with no more onAudioReady callbacks coming, the ring buffer never
        // fills again, but mWriterShouldRun stays true and nothing else
        // would ever clear it.
        stopRecordingInternal();
        stopPlaybackInternal();
        return;
    }

    // Reopened, presumably on a new route (e.g. headphones were unplugged
    // mid-take). The ring buffer, writer thread, and file are untouched by
    // close/open — nothing already captured is lost. There is an
    // unavoidable gap in the take across the physical disconnect+reopen
    // window, since no audio hardware existed to capture anything during it.
    mMode.store(static_cast<int32_t>(modeBeforeError), std::memory_order_release);
    if (mOutputStream) {
        auto result = mOutputStream->requestStart();
        if (result != oboe::Result::OK) {
            setLastError(std::string("post-rebuild requestStart failed: ") + oboe::convertToText(result));
        }
    }
}

std::string NativeAudioEngine::audioApi() const {
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

int32_t NativeAudioEngine::sampleRate() const {
    return mOutputStream ? mOutputStream->getSampleRate() : 0;
}

int32_t NativeAudioEngine::framesPerBurst() const {
    return mOutputStream ? mOutputStream->getFramesPerBurst() : 0;
}

int32_t NativeAudioEngine::channelCount() const {
    return mOutputStream ? mOutputStream->getChannelCount() : 0;
}

std::string NativeAudioEngine::format() const {
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

std::string NativeAudioEngine::sharingMode() const {
    if (!mOutputStream) return "-";
    return mOutputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";
}

std::string NativeAudioEngine::performanceMode() const {
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

bool NativeAudioEngine::isMMapUsed() const {
    return mOutputStream && mOutputStream->isMMapUsed();
}

int32_t NativeAudioEngine::xRunCount() const {
    if (!mOutputStream) return 0;
    auto result = mOutputStream->getXRunCount();
    return result ? result.value() : 0;
}

std::string NativeAudioEngine::lastError() const {
    std::lock_guard<std::mutex> lock(mErrorMutex);
    return mLastError;
}

} // namespace songnotes
