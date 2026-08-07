#include <jni.h>

#include <algorithm>

#include "audio_engine.h"
#include "dsp/peak_pyramid.h"
#include "dsp/piano_voice.h"
#include "dsp/track_mixer.h"
#include "dsp/wav_encoder.h"

using songnotes::NativeAudioEngine;

namespace {

inline NativeAudioEngine *toEngine(jlong handle) {
    return reinterpret_cast<NativeAudioEngine *>(handle);
}

jstring toJString(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

// Shared by nativeStartMultitrackPlayback and nativeArmRecording (backing
// tracks) — both take the same flat, track-major clip marshaling (see the
// doc comment on nativeStartMultitrackPlayback below for the shape).
// Returns an empty vector if trackClipCounts has zero length, which is
// exactly what "no tracks" (no backing tracks for a plain recording, or a
// theoretical empty-playlist call) marshals to from Kotlin.
std::vector<songnotes::dsp::Track> parseFlatTracks(JNIEnv *env, jobjectArray clipBuffers,
                                                     jlongArray clipStartFrames,
                                                     jlongArray clipBufferOffsetFrames,
                                                     jlongArray clipLengthFrames,
                                                     jintArray trackClipCounts, jfloatArray trackGains,
                                                     jbooleanArray trackMuted, jbooleanArray trackSoloed) {
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
    return tracks;
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

// backingTracks* use the same flat, track-major marshaling as
// nativeStartMultitrackPlayback (see parseFlatTracks/its doc comment) — an
// empty trackClipCounts (length 0) means "no backing tracks," reproducing
// plain click-only recording exactly.
JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeArmRecording(
    JNIEnv *env, jobject, jlong handle, jstring filePath, jdouble bpm, jint beatsPerBar,
    jint countInBeats, jdouble calibrationOffsetFrames, jobjectArray backingClipBuffers,
    jlongArray backingClipStartFrames, jlongArray backingClipBufferOffsetFrames,
    jlongArray backingClipLengthFrames, jintArray backingTrackClipCounts, jfloatArray backingTrackGains,
    jbooleanArray backingTrackMuted, jbooleanArray backingTrackSoloed, jlong backingTracksStartFrame) {
    auto *engine = toEngine(handle);
    if (!engine || !filePath) return JNI_FALSE;
    const auto backingTracks = parseFlatTracks(
        env, backingClipBuffers, backingClipStartFrames, backingClipBufferOffsetFrames,
        backingClipLengthFrames, backingTrackClipCounts, backingTrackGains, backingTrackMuted,
        backingTrackSoloed);
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    const bool ok = engine->armRecording(std::string(path), bpm, beatsPerBar, countInBeats,
                                          calibrationOffsetFrames, backingTracks, backingTracksStartFrame);
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
    const auto tracks =
        parseFlatTracks(env, clipBuffers, clipStartFrames, clipBufferOffsetFrames, clipLengthFrames,
                         trackClipCounts, trackGains, trackMuted, trackSoloed);
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

// Phase 4: offline mixdown export. Stateless — no engine handle needed,
// same as nativePunchIn — since mixing (dsp::mixTracks, the ALLOCATING
// wrapper; this runs on a normal thread, never onAudioReady, so
// allocation is fine here) and WAV encoding are both pure, engine-
// independent operations. totalFrames is computed the same way
// startMultitrackPlayback's is (furthest clip end across all tracks).
// tracks* use the same flat, track-major marshaling as
// nativeStartMultitrackPlayback (see parseFlatTracks).
JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeExportMixdownToWav(
    JNIEnv *env, jobject, jstring filePath, jint sampleRate, jobjectArray clipBuffers,
    jlongArray clipStartFrames, jlongArray clipBufferOffsetFrames, jlongArray clipLengthFrames,
    jintArray trackClipCounts, jfloatArray trackGains, jbooleanArray trackMuted,
    jbooleanArray trackSoloed) {
    if (!filePath) return JNI_FALSE;
    const auto tracks =
        parseFlatTracks(env, clipBuffers, clipStartFrames, clipBufferOffsetFrames, clipLengthFrames,
                         trackClipCounts, trackGains, trackMuted, trackSoloed);

    int64_t totalFrames = 0;
    for (const auto &track : tracks) {
        for (const auto &clip : track.clips) {
            totalFrames = std::max(totalFrames, clip.startFrame + clip.lengthFrames);
        }
    }
    const std::vector<float> mixed = songnotes::dsp::mixTracks(tracks, 0, totalFrames);

    const char *path = env->GetStringUTFChars(filePath, nullptr);
    const bool ok = songnotes::dsp::writeWavFile(std::string(path), mixed, sampleRate, /*channelCount=*/1);
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Phase 4: returns the raw mixed samples (no WAV encoding) — exists
// specifically for cross-validating dsp::mixTracks against the
// independent JVM reference mixer in :core:domain (com.songnotes.core.
// domain.mixTracks), isolating "do the two mixing implementations agree"
// from "is the WAV encoding correct" (already covered by
// nativeExportMixdownToWav's own smoke test). Stateless, same reasoning
// as nativePunchIn/nativeExportMixdownToWav.
JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeMixTracks(
    JNIEnv *env, jobject, jobjectArray clipBuffers, jlongArray clipStartFrames,
    jlongArray clipBufferOffsetFrames, jlongArray clipLengthFrames, jintArray trackClipCounts,
    jfloatArray trackGains, jbooleanArray trackMuted, jbooleanArray trackSoloed) {
    const auto tracks =
        parseFlatTracks(env, clipBuffers, clipStartFrames, clipBufferOffsetFrames, clipLengthFrames,
                         trackClipCounts, trackGains, trackMuted, trackSoloed);

    int64_t totalFrames = 0;
    for (const auto &track : tracks) {
        for (const auto &clip : track.clips) {
            totalFrames = std::max(totalFrames, clip.startFrame + clip.lengthFrames);
        }
    }
    const std::vector<float> mixed = songnotes::dsp::mixTracks(tracks, 0, totalFrames);

    auto *result = env->NewFloatArray(static_cast<jsize>(mixed.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(mixed.size()), mixed.data());
    return result;
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

// Phase 9 piano. sampleBuffers[i]/sampleRates[i] must correspond to
// dsp::kPianoSamples[i] — PianoSampleLoader.kt loads PIANO_SAMPLES (its
// Kotlin mirror of that same table) in order and this is expected to be
// called with the result unreordered, same ordering contract
// dsp::PianoSampleBankEntry's own doc comment states.
JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeLoadPianoBank(JNIEnv *env, jobject, jlong handle,
                                                                jobjectArray sampleBuffers,
                                                                jdoubleArray sampleRates) {
    auto *engine = toEngine(handle);
    if (!engine || !sampleBuffers || !sampleRates) return JNI_FALSE;

    const jsize count = env->GetArrayLength(sampleBuffers);
    std::vector<jdouble> rates(static_cast<size_t>(count));
    env->GetDoubleArrayRegion(sampleRates, 0, count, rates.data());

    std::vector<songnotes::dsp::PianoSampleBankEntry> entries(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto *bufferArr = static_cast<jfloatArray>(env->GetObjectArrayElement(sampleBuffers, i));
        const jsize len = env->GetArrayLength(bufferArr);
        auto buffer = std::make_shared<std::vector<float>>(static_cast<size_t>(len));
        env->GetFloatArrayRegion(bufferArr, 0, len, buffer->data());
        env->DeleteLocalRef(bufferArr);

        entries[static_cast<size_t>(i)].buffer = buffer;
        entries[static_cast<size_t>(i)].sampleRateHz = rates[static_cast<size_t>(i)];
    }

    return engine->loadPianoBank(std::move(entries)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativePianoNoteOn(JNIEnv *, jobject, jlong handle, jint midiNote) {
    auto *engine = toEngine(handle);
    return (engine != nullptr && engine->pianoNoteOn(midiNote)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativePianoNoteOff(JNIEnv *, jobject, jlong handle, jint midiNote) {
    auto *engine = toEngine(handle);
    return (engine != nullptr && engine->pianoNoteOff(midiNote)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeSetPianoVolume(JNIEnv *, jobject, jlong handle, jfloat gain) {
    auto *engine = toEngine(handle);
    if (engine) engine->setPianoVolume(gain);
}

// Stateless — exists purely for cross-validating dsp::renderVoiceInto
// against the independent JVM reference (com.songnotes.core.domain's
// renderVoiceInto in PianoVoice.kt), same reasoning/pattern as
// nativeMixTracks. Returns the rendered `numFrames`-long output buffer
// (starting from silence, i.e. this call's own contribution only — the
// real RT engine sums voices into whatever the output already holds, but
// the cross-validation only needs to compare one voice's isolated output
// against the JVM side's own isolated output).
JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeRenderPianoVoice(
    JNIEnv *env, jobject, jint numFrames, jfloatArray buffer, jdouble startReadPos, jdouble rate,
    jdouble startAgeSeconds, jdouble releaseAgeSeconds, jdouble sampleRateHz, jfloat gain) {
    const jsize bufferLen = env->GetArrayLength(buffer);
    std::vector<float> bufferVec(static_cast<size_t>(bufferLen));
    env->GetFloatArrayRegion(buffer, 0, bufferLen, bufferVec.data());

    std::vector<float> out(static_cast<size_t>(numFrames), 0.0f);
    songnotes::dsp::renderVoiceInto(out.data(), numFrames, bufferVec.data(), bufferLen, startReadPos, rate,
                                     startAgeSeconds, releaseAgeSeconds, sampleRateHz, gain);

    auto *result = env->NewFloatArray(numFrames);
    env->SetFloatArrayRegion(result, 0, numFrames, out.data());
    return result;
}

// Phase 10 waveform. Stateless, same reasoning as nativeMixTracks/
// nativeRenderPianoVoice -- peak computation isn't RT-critical (done once
// when a clip loads, off the audio thread), so it doesn't touch the live
// engine at all.
//
// Returns one self-describing FloatArray rather than a real nested
// structure -- JNI has no cheap way to hand back "a list of levels, each
// with its own arrays" without constructing real Kotlin objects field by
// field from native code, which is a lot of extra JNI plumbing for what's
// fundamentally just numbers. Format:
//   [numLevels,
//    level0.samplesPerPeak, level0.peakCount, level0.min[0], level0.max[0], level0.min[1], level0.max[1], ...,
//    level1.samplesPerPeak, level1.peakCount, ...,
//    ...]
// Every value fits exactly in a float32 (small non-negative integers for
// the headers, real sample values for the peaks), so there's no precision
// concern packing the integer headers in as floats. AudioEngine.kt's
// buildPeakPyramid() is the decoder for this format -- keep the two in
// sync if this shape ever changes.
JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeBuildPeakPyramid(JNIEnv *env, jobject, jfloatArray buffer,
                                                                    jint baseSamplesPerPeak, jint minPeaksPerLevel) {
    const jsize len = env->GetArrayLength(buffer);
    std::vector<float> bufferVec(static_cast<size_t>(len));
    env->GetFloatArrayRegion(buffer, 0, len, bufferVec.data());

    auto pyramid = songnotes::dsp::buildPeakPyramid(bufferVec, baseSamplesPerPeak, minPeaksPerLevel);

    size_t totalFloats = 1; // numLevels header
    for (const auto &level : pyramid) {
        totalFloats += 2 + level.peaks.size() * 2; // samplesPerPeak + peakCount headers, then min/max pairs
    }

    std::vector<float> flat;
    flat.reserve(totalFloats);
    flat.push_back(static_cast<float>(pyramid.size()));
    for (const auto &level : pyramid) {
        flat.push_back(static_cast<float>(level.samplesPerPeak));
        flat.push_back(static_cast<float>(level.peaks.size()));
        for (const auto &peak : level.peaks) {
            flat.push_back(peak.min);
            flat.push_back(peak.max);
        }
    }

    auto *result = env->NewFloatArray(static_cast<jsize>(flat.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

} // extern "C"
