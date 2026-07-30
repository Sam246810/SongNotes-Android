#include <jni.h>

#include "audio_engine.h"
#include "dsp/track_mixer.h"

using songnotes::NativeAudioEngine;

namespace {

inline NativeAudioEngine *toEngine(jlong handle) {
    return reinterpret_cast<NativeAudioEngine *>(handle);
}

jstring toJString(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new NativeAudioEngine());
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeEnsureReady(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine != nullptr && engine->ensureReady();
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartTestTone(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine != nullptr && engine->startTestTone();
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStopTestTone(JNIEnv *, jobject, jlong handle) {
    if (auto *engine = toEngine(handle)) {
        engine->stopTestTone();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeArmRecording(JNIEnv *env, jobject, jlong handle,
                                                              jstring filePath, jdouble bpm,
                                                              jint beatsPerBar, jint countInBeats,
                                                              jdouble calibrationOffsetFrames) {
    auto *engine = toEngine(handle);
    if (!engine || !filePath) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    const bool ok =
        engine->armRecording(std::string(path), bpm, beatsPerBar, countInBeats, calibrationOffsetFrames);
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStopRecording(JNIEnv *, jobject, jlong handle) {
    if (auto *engine = toEngine(handle)) {
        engine->stopRecording();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartPlayback(JNIEnv *env, jobject, jlong handle,
                                                               jstring filePath) {
    auto *engine = toEngine(handle);
    if (!engine || !filePath) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    const bool ok = engine->startPlayback(std::string(path));
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStopPlayback(JNIEnv *, jobject, jlong handle) {
    if (auto *engine = toEngine(handle)) {
        engine->stopPlayback();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartPlaybackFromBuffer(JNIEnv *env, jobject,
                                                                          jlong handle,
                                                                          jfloatArray buffer) {
    auto *engine = toEngine(handle);
    if (!engine || !buffer) return JNI_FALSE;
    const jsize len = env->GetArrayLength(buffer);
    std::vector<float> bufferVec(static_cast<size_t>(len));
    env->GetFloatArrayRegion(buffer, 0, len, bufferVec.data());
    return engine->startPlaybackFromBuffer(bufferVec) ? JNI_TRUE : JNI_FALSE;
}

// Phase 4, first slice: exactly one clip per track. Parallel arrays, one
// entry per track — clipBuffers[i]/clipStartFrames[i] describe track i's
// single clip; trackGains/trackMuted/trackSoloed describe the track
// itself. Multiple clips per track (needed for real punch-in / multi-take
// tracks) isn't wired through JNI yet — dsp::Track already supports it
// natively, this marshaling just doesn't expose it yet. See
// docs/handoff/PHASE-04.md.
JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartMultitrackPlayback(
    JNIEnv *env, jobject, jlong handle, jobjectArray clipBuffers, jlongArray clipStartFrames,
    jfloatArray trackGains, jbooleanArray trackMuted, jbooleanArray trackSoloed) {
    auto *engine = toEngine(handle);
    if (!engine || !clipBuffers) return JNI_FALSE;

    const jsize trackCount = env->GetArrayLength(clipBuffers);
    std::vector<jlong> startFrames(static_cast<size_t>(trackCount));
    env->GetLongArrayRegion(clipStartFrames, 0, trackCount, startFrames.data());
    std::vector<jfloat> gains(static_cast<size_t>(trackCount));
    env->GetFloatArrayRegion(trackGains, 0, trackCount, gains.data());
    std::vector<jboolean> muted(static_cast<size_t>(trackCount));
    env->GetBooleanArrayRegion(trackMuted, 0, trackCount, muted.data());
    std::vector<jboolean> soloed(static_cast<size_t>(trackCount));
    env->GetBooleanArrayRegion(trackSoloed, 0, trackCount, soloed.data());

    std::vector<songnotes::dsp::Track> tracks(static_cast<size_t>(trackCount));
    for (jsize i = 0; i < trackCount; i++) {
        auto *bufferArr = static_cast<jfloatArray>(env->GetObjectArrayElement(clipBuffers, i));
        const jsize len = env->GetArrayLength(bufferArr);
        auto buffer = std::make_shared<std::vector<float>>(static_cast<size_t>(len));
        env->GetFloatArrayRegion(bufferArr, 0, len, buffer->data());
        env->DeleteLocalRef(bufferArr);

        songnotes::dsp::Clip clip;
        clip.buffer = buffer;
        clip.startFrame = startFrames[static_cast<size_t>(i)];
        clip.bufferOffsetFrames = 0;
        clip.lengthFrames = len;

        auto &track = tracks[static_cast<size_t>(i)];
        track.clips.push_back(clip);
        track.gain = gains[static_cast<size_t>(i)];
        track.muted = muted[static_cast<size_t>(i)] != JNI_FALSE;
        track.soloed = soloed[static_cast<size_t>(i)] != JNI_FALSE;
    }

    return engine->startMultitrackPlayback(tracks) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartCalibrationCapture(JNIEnv *env, jobject,
                                                                          jlong handle, jfloatArray sweep,
                                                                          jint tailPaddingFrames) {
    auto *engine = toEngine(handle);
    if (!engine || !sweep) return JNI_FALSE;
    const jsize len = env->GetArrayLength(sweep);
    std::vector<float> sweepVec(static_cast<size_t>(len));
    env->GetFloatArrayRegion(sweep, 0, len, sweepVec.data());
    return engine->startCalibrationCapture(sweepVec, tailPaddingFrames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStopCalibration(JNIEnv *, jobject, jlong handle) {
    if (auto *engine = toEngine(handle)) {
        engine->stopCalibration();
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeTakeCalibrationCapture(JNIEnv *env, jobject,
                                                                          jlong handle) {
    auto *engine = toEngine(handle);
    if (!engine) return env->NewFloatArray(0);
    const std::vector<float> captured = engine->takeCalibrationCapture();
    auto *result = env->NewFloatArray(static_cast<jsize>(captured.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(captured.size()), captured.data());
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetStateBuffer(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    if (!engine) return nullptr;
    auto *state = engine->stateBlock();
    return env->NewDirectByteBuffer(state, sizeof(*state));
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete toEngine(handle);
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetAudioApi(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->audioApi() : "-");
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetSampleRate(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->sampleRate() : 0;
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetFramesPerBurst(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->framesPerBurst() : 0;
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetChannelCount(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->channelCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetFormat(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->format() : "-");
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetSharingMode(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->sharingMode() : "-");
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetPerformanceMode(JNIEnv *env, jobject,
                                                                     jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->performanceMode() : "-");
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeIsMMapUsed(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine != nullptr && engine->isMMapUsed();
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetXRunCount(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->xRunCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetLastError(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->lastError() : "");
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetInputSessionId(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->inputSessionId() : -1;
}

} // extern "C"
