#include "audio_engine.h"

#include <android/log.h>
#include <cmath>

#include "dsp/sine_wave.h"

#define LOG_TAG "SongNotesAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace songnotes {

namespace {
constexpr double kTestToneHz = 440.0;
constexpr float kTestToneAmplitude = 0.2f;
} // namespace

NativeAudioEngine::~NativeAudioEngine() {
    closeStream();
}

bool NativeAudioEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    auto result = builder.openStream();
    if (result != oboe::Result::OK) {
        LOGW("Exclusive+LowLatency open failed (%s), retrying with Shared",
             oboe::convertToText(result.error()));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream();
    }

    if (result != oboe::Result::OK) {
        mLastError = std::string("openStream failed: ") + oboe::convertToText(result.error());
        LOGW("%s", mLastError.c_str());
        return false;
    }

    mStream = result.value();
    LOGI("Stream opened: api=%d sharing=%d perf=%d sampleRate=%d framesPerBurst=%d mmap=%d",
         static_cast<int>(mStream->getAudioApi()), static_cast<int>(mStream->getSharingMode()),
         static_cast<int>(mStream->getPerformanceMode()), mStream->getSampleRate(),
         mStream->getFramesPerBurst(), mStream->isMMapUsed());
    return true;
}

void NativeAudioEngine::closeStream() {
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

bool NativeAudioEngine::startTestTone() {
    if (!mStream && !openStream()) {
        return false;
    }
    mPhase = 0.0;
    auto result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        mLastError = std::string("requestStart failed: ") + oboe::convertToText(result);
        LOGW("%s", mLastError.c_str());
        return false;
    }
    mLastError.clear();
    return true;
}

void NativeAudioEngine::stop() {
    if (mStream) {
        mStream->requestStop();
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *stream,
                                                          void *audioData, int32_t numFrames) {
    // Real-time thread: no allocation, no locks, no JNI, no logging below this line.
    dsp::renderSine(static_cast<float *>(audioData), numFrames, stream->getChannelCount(),
                     stream->getSampleRate(), kTestToneHz, kTestToneAmplitude, &mPhase);
    return oboe::DataCallbackResult::Continue;
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Phase 0: report and drop the stream. Automatic rebuild (needed once a
    // take is mid-flight) arrives in Phase 1 alongside the writer thread.
    mLastError = std::string("stream error after close: ") + oboe::convertToText(error);
    LOGW("%s", mLastError.c_str());
    mStream.reset();
}

std::string NativeAudioEngine::audioApi() const {
    if (!mStream) return "-";
    switch (mStream->getAudioApi()) {
        case oboe::AudioApi::AAudio:
            return "AAudio";
        case oboe::AudioApi::OpenSLES:
            return "OpenSL ES";
        default:
            return "Unknown";
    }
}

int32_t NativeAudioEngine::sampleRate() const {
    return mStream ? mStream->getSampleRate() : 0;
}

int32_t NativeAudioEngine::framesPerBurst() const {
    return mStream ? mStream->getFramesPerBurst() : 0;
}

int32_t NativeAudioEngine::channelCount() const {
    return mStream ? mStream->getChannelCount() : 0;
}

std::string NativeAudioEngine::format() const {
    if (!mStream) return "-";
    switch (mStream->getFormat()) {
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
    if (!mStream) return "-";
    return mStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";
}

std::string NativeAudioEngine::performanceMode() const {
    if (!mStream) return "-";
    switch (mStream->getPerformanceMode()) {
        case oboe::PerformanceMode::LowLatency:
            return "LowLatency";
        case oboe::PerformanceMode::PowerSaving:
            return "PowerSaving";
        default:
            return "None";
    }
}

bool NativeAudioEngine::isMMapUsed() const {
    return mStream && mStream->isMMapUsed();
}

int32_t NativeAudioEngine::xRunCount() const {
    if (!mStream) return 0;
    auto result = mStream->getXRunCount();
    return result ? result.value() : 0;
}

std::string NativeAudioEngine::lastError() const {
    return mLastError;
}

} // namespace songnotes
