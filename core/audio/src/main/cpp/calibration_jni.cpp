// JNI wrapping for the pure-math calibration DSP in dsp/{sweep,matched_filter,
// calibration_stats}.{h,cpp}. Deliberately stateless and separate from
// jni_bridge.cpp/NativeAudioEngine: this is host-testable math (see
// core/audio/src/main/cpp/host/test_calibration_roundtrip.cpp) that doesn't
// touch the RT audio thread, the duplex engine, or any engine handle. Engine
// integration (running real sweep repetitions through the record/playback
// path and feeding the results here) is still ahead — see docs/handoff/
// PHASE-03.md.
#include <jni.h>

#include <vector>

#include "dsp/calibration_stats.h"
#include "dsp/click_track.h"
#include "dsp/matched_filter.h"
#include "dsp/mix.h"
#include "dsp/onset_detection.h"
#include "dsp/sweep.h"

using namespace songnotes::dsp;

namespace {

std::vector<float> toFloatVector(JNIEnv *env, jfloatArray arr) {
    const jsize len = env->GetArrayLength(arr);
    std::vector<float> result(static_cast<size_t>(len));
    env->GetFloatArrayRegion(arr, 0, len, result.data());
    return result;
}

std::vector<double> toDoubleVector(JNIEnv *env, jdoubleArray arr) {
    const jsize len = env->GetArrayLength(arr);
    std::vector<double> result(static_cast<size_t>(len));
    env->GetDoubleArrayRegion(arr, 0, len, result.data());
    return result;
}

jfloatArray toJFloatArray(JNIEnv *env, const std::vector<float> &v) {
    auto *arr = env->NewFloatArray(static_cast<jsize>(v.size()));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

jdoubleArray toJDoubleArray(JNIEnv *env, const std::vector<double> &v) {
    auto *arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
    env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

} // namespace

extern "C" {

// Returns a single float array of length 2*N: [0,N) is the sweep, [N,2N) is
// the inverse filter (same length per sweep.h). Packed into one array rather
// than two return values or a constructed Kotlin object — no existing
// JNI-side machinery in this codebase builds Java objects from native code,
// and a single primitive array keeps this file free of class/method-ID
// lookups. Calibration.kt unpacks it.
JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeGenerateSweepAndInverse(
    JNIEnv *env, jobject, jdouble sampleRate, jdouble f1Hz, jdouble f2Hz, jdouble lengthSeconds,
    jfloat amplitude) {
    const SweepAndInverse data = generateSweepAndInverse(sampleRate, f1Hz, f2Hz, lengthSeconds, amplitude);
    std::vector<float> packed;
    packed.reserve(data.sweep.size() + data.inverseFilter.size());
    packed.insert(packed.end(), data.sweep.begin(), data.sweep.end());
    packed.insert(packed.end(), data.inverseFilter.begin(), data.inverseFilter.end());
    return toJFloatArray(env, packed);
}

// Deconvolves `recording` against `inverseFilter`, finds the peak, and
// returns [recoveredDelayFrames, pnrDb]. convolve()+findPeak() are kept
// paired on the native side (mirrors host/test_calibration_roundtrip.cpp's
// own recoverDelay() helper exactly) so the potentially large intermediate
// convolved buffer — up to recording.size() + inverseFilter.size() - 1
// samples, padded to the next power of two internally — never crosses the
// JNI boundary; only this 2-element result does.
JNIEXPORT jdoubleArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeMeasureRoundTripDelay(
    JNIEnv *env, jobject, jfloatArray recording, jfloatArray inverseFilter, jint sweepLength) {
    const std::vector<float> recordingVec = toFloatVector(env, recording);
    const std::vector<float> inverseFilterVec = toFloatVector(env, inverseFilter);

    const std::vector<float> deconvolved = convolve(recordingVec, inverseFilterVec);
    const PeakResult peak = findPeak(deconvolved);

    // A pure delay(sweep, N) convolved with the inverse filter peaks at
    // (sweepLength - 1) + N — see sweep.h's derivation and
    // test_calibration_roundtrip.cpp's recoverDelay() for the same formula.
    const double delayFrames =
        static_cast<double>(peak.index) + peak.interpolatedOffset - static_cast<double>(sweepLength - 1);
    const double pnrDb = peakToNoiseRatioDb(peak.peakMagnitude, peak.noiseFloor);

    return toJDoubleArray(env, {delayFrames, pnrDb});
}

JNIEXPORT jdoubleArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeRejectOutliersMad(JNIEnv *env, jobject,
                                                                    jdoubleArray values,
                                                                    jdouble thresholdMads) {
    const std::vector<double> kept = rejectOutliersMad(toDoubleVector(env, values), thresholdMads);
    return toJDoubleArray(env, kept);
}

JNIEXPORT jdouble JNICALL
Java_com_songnotes_core_audio_Calibration_nativePeakToNoiseRatioDb(JNIEnv *, jobject,
                                                                     jfloat peakMagnitude,
                                                                     jfloat noiseFloor) {
    return peakToNoiseRatioDb(peakMagnitude, noiseFloor);
}

// Rule A ("verification playback renders one pre-mixed buffer... a flam is
// arithmetically impossible"): regenerates a reference click track exactly
// matching `take`'s own length and re-mixes it against `take` offline, into
// a single buffer. renderClickTrack()+mixAndNormalize() are kept paired
// here (mirrors nativeMeasureRoundTripDelay's convolve+findPeak pairing)
// so the intermediate click-track buffer — the same length as `take`,
// potentially several seconds — never crosses the JNI boundary on its own;
// only the final mixed result does.
JNIEXPORT jfloatArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeBuildPreMixedVerificationBuffer(
    JNIEnv *env, jobject, jfloatArray take, jdouble sampleRate, jdouble bpm, jint beatsPerBar,
    jdouble downbeatHz, jdouble regularHz, jdouble clickLengthSeconds, jfloat clickAmplitude) {
    const std::vector<float> takeVec = toFloatVector(env, take);
    const auto clickTrack = renderClickTrack(sampleRate, bpm, beatsPerBar,
                                              static_cast<int64_t>(takeVec.size()), downbeatHz, regularHz,
                                              clickLengthSeconds, clickAmplitude);
    const auto mixed = mixAndNormalize(takeVec, clickTrack);
    return toJFloatArray(env, mixed);
}

// Manual tap-along path (dsp/onset_detection.h) — see that header's own
// doc comment for why this specific detector is appropriate here despite
// being unsuitable for the automatic sweep path.
JNIEXPORT jdoubleArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeDetectOnsets(JNIEnv *env, jobject, jfloatArray pcm,
                                                                jdouble sampleRate, jdouble minGapSec,
                                                                jdouble thresholdRatio, jdouble windowSec) {
    const std::vector<float> pcmVec = toFloatVector(env, pcm);
    const auto onsets = detectOnsets(pcmVec, sampleRate, minGapSec, thresholdRatio, windowSec);
    return toJDoubleArray(env, onsets);
}

// Returns a length-0 array for "no estimate" (mirrors std::nullopt — no
// separate boolean/sentinel needed) or a length-1 array holding the
// estimate in seconds.
JNIEXPORT jdoubleArray JNICALL
Java_com_songnotes_core_audio_Calibration_nativeEstimateLatencyFromOnsets(JNIEnv *env, jobject,
                                                                            jdoubleArray detectedTimes,
                                                                            jdoubleArray scheduledTimes,
                                                                            jdouble maxMatchSec) {
    const auto detected = toDoubleVector(env, detectedTimes);
    const auto scheduled = toDoubleVector(env, scheduledTimes);
    const auto est = estimateLatencyFromOnsets(detected, scheduled, maxMatchSec);
    if (!est.has_value()) return toJDoubleArray(env, {});
    return toJDoubleArray(env, {*est});
}

} // extern "C"
