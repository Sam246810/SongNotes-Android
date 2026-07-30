#include <jni.h>

#include <algorithm>

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

// Phase 4, second slice: N clips per track. clipBuffers/clipStartFrames/
// clipBufferOffsetFrames/clipLengthFrames are FLAT — one entry per clip,
// track-major order (all of track 0's clips, then all of track 1's, ...).
// trackClipCounts[i] says how many consecutive flat entries belong to
// track i, so the native side can regroup them; trackGains/trackMuted/
// trackSoloed remain one entry per track. This is what lets a punched-in
// track (several clip fragments) actually reach real-time playback — see
// docs/handoff/PHASE-04.md.
JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartMultitrackPlayback(
    JNIEnv *env, jobject, jlong handle, jobjectArray clipBuffers, jlongArray clipStartFrames,
    jlongArray clipBufferOffsetFrames, jlongArray clipLengthFrames, jintArray trackClipCounts,
    jfloatArray trackGains, jbooleanArray trackMuted, jbooleanArray trackSoloed) {
    auto *engine = toEngine(handle);
    if (!engine || !clipBuffers || !trackClipCounts) return JNI_FALSE;

    const jsize totalClips = env->GetArrayLength(clipBuffers);
    std::vector<jlong> startFrames(static_cast<size_t>(totalClips));
    env->GetLongArrayRegion(clipStartFrames, 0, totalClips, startFrames.data());
    std::vector<jlong> bufferOffsets(static_cast<size_t>(totalClips));
    env->GetLongArrayRegion(clipBufferOffsetFrames, 0, totalClips, bufferOffsets.data());
    std::vector<jlong> lengths(static_cast<size_t>(totalClips));
    env->GetLongArrayRegion(clipLengthFrames, 0, totalClips, lengths.data());

    const jsize trackCount = env->GetArrayLength(trackClipCounts);
    std::vector<jint> clipCounts(static_cast<size_t>(trackCount));
    env->GetIntArrayRegion(trackClipCounts, 0, trackCount, clipCounts.data());
    std::vector<jfloat> gains(static_cast<size_t>(trackCount));
    env->GetFloatArrayRegion(trackGains, 0, trackCount, gains.data());
    std::vector<jboolean> muted(static_cast<size_t>(trackCount));
    env->GetBooleanArrayRegion(trackMuted, 0, trackCount, muted.data());
    std::vector<jboolean> soloed(static_cast<size_t>(trackCount));
    env->GetBooleanArrayRegion(trackSoloed, 0, trackCount, soloed.data());

    std::vector<songnotes::dsp::Track> tracks(static_cast<size_t>(trackCount));
    jsize flatIndex = 0;
    for (jsize t = 0; t < trackCount; t++) {
        auto &track = tracks[static_cast<size_t>(t)];
        track.gain = gains[static_cast<size_t>(t)];
        track.muted = muted[static_cast<size_t>(t)] != JNI_FALSE;
        track.soloed = soloed[static_cast<size_t>(t)] != JNI_FALSE;

        const jint clipCount = clipCounts[static_cast<size_t>(t)];
        for (jint c = 0; c < clipCount && flatIndex < totalClips; c++, flatIndex++) {
            auto *bufferArr = static_cast<jfloatArray>(env->GetObjectArrayElement(clipBuffers, flatIndex));
            const jsize len = env->GetArrayLength(bufferArr);
            auto buffer = std::make_shared<std::vector<float>>(static_cast<size_t>(len));
            env->GetFloatArrayRegion(bufferArr, 0, len, buffer->data());
            env->DeleteLocalRef(bufferArr);

            songnotes::dsp::Clip clip;
            clip.buffer = buffer;
            clip.startFrame = startFrames[static_cast<size_t>(flatIndex)];
            clip.bufferOffsetFrames = bufferOffsets[static_cast<size_t>(flatIndex)];
            clip.lengthFrames = lengths[static_cast<size_t>(flatIndex)];
            track.clips.push_back(clip);
        }
    }

    return engine->startMultitrackPlayback(tracks) ? JNI_TRUE : JNI_FALSE;
}

// Phase 4: stateless wrapper around dsp::punchIn — lets Kotlin splice a new
// take into a track's existing clip list without duplicating the
// trim/split logic in Kotlin. existingClip*/out* are all a single track's
// worth of clips (flat, no per-track grouping needed — this always
// operates on exactly one track at a time). Returns the number of clips
// actually written into the out* arrays (NOT necessarily the true result
// size — see the clamp below); caller must size the out* arrays to at
// least existingClips.size() * 2 + 1, the worst case where EVERY existing
// clip straddles both edges of the insert (each becomes a head + tail
// fragment, 2 outputs per input), plus the insert clip itself (always
// exactly 1 more). A single existing clip splitting into head+tail is 2
// outputs from 1 input, not 1 — an earlier version of this comment/the
// Kotlin-side capacity formula got this wrong and undersized the output
// arrays, which crashed with an ArrayIndexOutOfBoundsException the first
// time a real straddling-punch-in was exercised on device.
JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativePunchIn(
    JNIEnv *env, jobject, jobjectArray existingClipBuffers, jlongArray existingClipStartFrames,
    jlongArray existingClipBufferOffsetFrames, jlongArray existingClipLengthFrames,
    jfloatArray insertClipBuffer, jlong insertStartFrame, jlong insertBufferOffsetFrames,
    jlong insertLengthFrames, jobjectArray outClipBuffers, jlongArray outClipStartFrames,
    jlongArray outClipBufferOffsetFrames, jlongArray outClipLengthFrames) {
    const jsize existingCount = env->GetArrayLength(existingClipBuffers);
    std::vector<jlong> existingStart(static_cast<size_t>(existingCount));
    env->GetLongArrayRegion(existingClipStartFrames, 0, existingCount, existingStart.data());
    std::vector<jlong> existingOffset(static_cast<size_t>(existingCount));
    env->GetLongArrayRegion(existingClipBufferOffsetFrames, 0, existingCount, existingOffset.data());
    std::vector<jlong> existingLength(static_cast<size_t>(existingCount));
    env->GetLongArrayRegion(existingClipLengthFrames, 0, existingCount, existingLength.data());

    std::vector<songnotes::dsp::Clip> existingClips(static_cast<size_t>(existingCount));
    for (jsize i = 0; i < existingCount; i++) {
        auto *bufferArr = static_cast<jfloatArray>(env->GetObjectArrayElement(existingClipBuffers, i));
        const jsize len = env->GetArrayLength(bufferArr);
        auto buffer = std::make_shared<std::vector<float>>(static_cast<size_t>(len));
        env->GetFloatArrayRegion(bufferArr, 0, len, buffer->data());
        env->DeleteLocalRef(bufferArr);

        auto &clip = existingClips[static_cast<size_t>(i)];
        clip.buffer = buffer;
        clip.startFrame = existingStart[static_cast<size_t>(i)];
        clip.bufferOffsetFrames = existingOffset[static_cast<size_t>(i)];
        clip.lengthFrames = existingLength[static_cast<size_t>(i)];
    }

    songnotes::dsp::Clip insertClip;
    {
        const jsize len = env->GetArrayLength(insertClipBuffer);
        auto buffer = std::make_shared<std::vector<float>>(static_cast<size_t>(len));
        env->GetFloatArrayRegion(insertClipBuffer, 0, len, buffer->data());
        insertClip.buffer = buffer;
        insertClip.startFrame = insertStartFrame;
        insertClip.bufferOffsetFrames = insertBufferOffsetFrames;
        insertClip.lengthFrames = insertLengthFrames;
    }

    const std::vector<songnotes::dsp::Clip> result = songnotes::dsp::punchIn(existingClips, insertClip);

    const jsize outCapacity = env->GetArrayLength(outClipBuffers);
    const jsize resultCount = std::min(static_cast<jsize>(result.size()), outCapacity);
    for (jsize i = 0; i < resultCount; i++) {
        const auto &clip = result[static_cast<size_t>(i)];
        auto *bufferArr = env->NewFloatArray(static_cast<jsize>(clip.buffer->size()));
        env->SetFloatArrayRegion(bufferArr, 0, static_cast<jsize>(clip.buffer->size()), clip.buffer->data());
        env->SetObjectArrayElement(outClipBuffers, i, bufferArr);
        env->DeleteLocalRef(bufferArr);
    }
    std::vector<jlong> outStart(static_cast<size_t>(resultCount));
    std::vector<jlong> outOffset(static_cast<size_t>(resultCount));
    std::vector<jlong> outLength(static_cast<size_t>(resultCount));
    for (jsize i = 0; i < resultCount; i++) {
        outStart[static_cast<size_t>(i)] = result[static_cast<size_t>(i)].startFrame;
        outOffset[static_cast<size_t>(i)] = result[static_cast<size_t>(i)].bufferOffsetFrames;
        outLength[static_cast<size_t>(i)] = result[static_cast<size_t>(i)].lengthFrames;
    }
    env->SetLongArrayRegion(outClipStartFrames, 0, resultCount, outStart.data());
    env->SetLongArrayRegion(outClipBufferOffsetFrames, 0, resultCount, outOffset.data());
    env->SetLongArrayRegion(outClipLengthFrames, 0, resultCount, outLength.data());

    // Return what was actually WRITTEN (resultCount), not result.size() —
    // if the caller under-sized the out arrays, returning the true (larger)
    // count would tell it to read indices that were never written, an
    // out-of-bounds read on the Kotlin side. This is defense in depth: the
    // Kotlin-side capacity formula should already be sized correctly (see
    // AudioEngine.punchIn's doc comment), so this clamp should never
    // actually trigger — but if it ever does, a silently-truncated result
    // is far better than a crash.
    return static_cast<jint>(resultCount);
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
