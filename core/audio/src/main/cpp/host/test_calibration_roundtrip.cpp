// The highest-value tests in the project, per the plan: prove the whole
// sweep -> record -> deconvolve -> find-peak pipeline actually recovers a
// KNOWN delay from a SYNTHESIZED recording, at a few SNR levels, before any
// of this ever touches a real device. If the sweep/inverse-filter formula in
// sweep.cpp has a sign or scaling bug, this is where it gets caught —
// immediately and automatically, rather than as a confusing wrong number
// deep into an on-device calibration attempt.
#include <gtest/gtest.h>

#include <cmath>
#include <random>
#include <vector>

#include "dsp/calibration_stats.h"
#include "dsp/matched_filter.h"
#include "dsp/sweep.h"

using namespace songnotes::dsp;

namespace {

double rms(const std::vector<float> &v) {
    double sumSq = 0.0;
    for (float x : v) sumSq += static_cast<double>(x) * x;
    return v.empty() ? 0.0 : std::sqrt(sumSq / static_cast<double>(v.size()));
}

// Builds a synthetic "recording": silence, then the sweep at a known delay,
// then more silence, with noise added everywhere at a chosen SNR relative
// to the sweep's own RMS level. Fixed seed -> deterministic, non-flaky.
std::vector<float> synthesizeRecording(const std::vector<float> &sweep, int delayFrames, double snrDb,
                                        size_t tailPadding) {
    const double signalRms = rms(sweep);
    const double noiseRms = signalRms / std::pow(10.0, snrDb / 20.0);

    std::mt19937 rng(12345);
    std::normal_distribution<double> noiseDist(0.0, noiseRms);

    const auto totalLength = static_cast<size_t>(delayFrames) + sweep.size() + tailPadding;
    std::vector<float> recording(totalLength);
    for (size_t i = 0; i < totalLength; i++) {
        recording[i] = static_cast<float>(noiseDist(rng));
    }
    for (size_t i = 0; i < sweep.size(); i++) {
        recording[static_cast<size_t>(delayFrames) + i] += sweep[i];
    }
    return recording;
}

struct RecoveredDelay {
    double frames = 0.0;
    double pnrDb = 0.0;
};

RecoveredDelay recoverDelay(const std::vector<float> &recording, const std::vector<float> &inverseFilter,
                            size_t sweepLength) {
    auto deconvolved = convolve(recording, inverseFilter);
    auto peak = findPeak(deconvolved);
    RecoveredDelay result;
    // A pure delay(sweep, N) convolved with the inverse filter peaks at
    // (sweepLength - 1) + N — see sweep.h's derivation.
    result.frames =
        static_cast<double>(peak.index) + peak.interpolatedOffset - static_cast<double>(sweepLength - 1);
    result.pnrDb = peakToNoiseRatioDb(peak.peakMagnitude, peak.noiseFloor);
    return result;
}

} // namespace

class CalibrationRoundTrip : public ::testing::Test {
protected:
    static constexpr double kSampleRate = 48000.0;
    SweepAndInverse sweepData = generateSweepAndInverse(kSampleRate, 200.0, 8000.0, 0.5, 0.7f);
};

TEST_F(CalibrationRoundTrip, RecoversKnownDelayWithinOneSampleAtHighSnr) {
    constexpr int kTrueDelayFrames = 2400; // 50ms at 48kHz — a plausible round trip
    auto recording = synthesizeRecording(sweepData.sweep, kTrueDelayFrames, /*snrDb=*/20.0, 4800);
    auto recovered = recoverDelay(recording, sweepData.inverseFilter, sweepData.sweep.size());

    EXPECT_NEAR(recovered.frames, kTrueDelayFrames, 1.0);
    EXPECT_GT(recovered.pnrDb, 20.0); // a clean, trustworthy measurement
}

TEST_F(CalibrationRoundTrip, DegradesGracefullyAtModerateSnr) {
    constexpr int kTrueDelayFrames = 2400;
    auto recording = synthesizeRecording(sweepData.sweep, kTrueDelayFrames, /*snrDb=*/10.0, 4800);
    auto recovered = recoverDelay(recording, sweepData.inverseFilter, sweepData.sweep.size());

    // Looser tolerance than the clean case, but still recognizably correct
    // — this is "graceful," not "perfect."
    EXPECT_NEAR(recovered.frames, kTrueDelayFrames, 5.0);
    EXPECT_GT(recovered.pnrDb, 10.0);
}

TEST_F(CalibrationRoundTrip, StillRecoversAtZeroDbInputSnrViaProcessingGain) {
    constexpr int kTrueDelayFrames = 2400;
    auto recording = synthesizeRecording(sweepData.sweep, kTrueDelayFrames, /*snrDb=*/0.0, 4800);
    auto recovered = recoverDelay(recording, sweepData.inverseFilter, sweepData.sweep.size());

    // At 0dB input SNR (noise power equal to signal power, before
    // deconvolution), the sweep's processing gain — the entire reason the
    // plan specifies a sweep instead of a click — should still recover the
    // delay well within a millisecond. If this test starts failing, treat
    // it as a real signal — either the sweep/inverse-filter formula has a
    // bug, or the processing-gain assumption elsewhere in the plan (that
    // this measurement tolerates a noisy room) needs revisiting. Don't
    // just loosen the tolerance to make it pass.
    EXPECT_NEAR(recovered.frames, kTrueDelayFrames, 20.0);
}

TEST_F(CalibrationRoundTrip, PnrCollapsesOnPureNoiseWithNoSweepAtAll) {
    // No signal whatsoever — the real failure-detection path (a PNR gate,
    // not implemented in this test) needs this case to read as clearly
    // untrustworthy rather than silently returning some plausible-looking
    // frame index.
    std::mt19937 rng(999);
    std::normal_distribution<double> noiseDist(0.0, 0.1);
    std::vector<float> pureNoise(sweepData.sweep.size() * 2);
    for (auto &s : pureNoise) s = static_cast<float>(noiseDist(rng));

    auto deconvolved = convolve(pureNoise, sweepData.inverseFilter);
    auto peak = findPeak(deconvolved);
    const double pnrDb = peakToNoiseRatioDb(peak.peakMagnitude, peak.noiseFloor);

    // Nowhere near the ~20dB+ PNR a real clean measurement produces — a
    // real gate (threshold TBD once run against actual device noise
    // floors) would reject this outright.
    EXPECT_LT(pnrDb, 20.0);
}
