#include <gtest/gtest.h>

#include <cmath>

#include "dsp/onset_detection.h"

using songnotes::dsp::detectOnsets;
using songnotes::dsp::estimateLatencyFromOnsets;

namespace {
constexpr double kSampleRate = 48000.0;
}

// Ports the intent of the desktop web app's `detectOnsets`/
// `estimateLatencyFromOnsets` tests (src/test/latency.test.js), not the
// code — same scenarios (transient bursts separated by a refractory gap,
// pure silence, median-of-matched-deltas estimation, spurious-onset
// rejection, too-few-matches), reimplemented against this C++ port.

TEST(DetectOnsets, FindsTransientBurstsSeparatedByRefractoryGap) {
    std::vector<float> pcm(static_cast<size_t>(kSampleRate), 0.0f); // 1s of silence
    const std::vector<double> clickTimes = {0.1, 0.4, 0.7};
    for (const double t : clickTimes) {
        const auto start = static_cast<size_t>(std::llround(t * kSampleRate));
        for (int i = 0; i < 200; i++) {
            pcm[start + static_cast<size_t>(i)] = static_cast<float>(std::sin(i * 0.5) * 0.9);
        }
    }
    const auto onsets = detectOnsets(pcm, kSampleRate);
    ASSERT_EQ(onsets.size(), clickTimes.size());
    for (size_t i = 0; i < clickTimes.size(); i++) {
        EXPECT_LT(std::fabs(onsets[i] - clickTimes[i]), 0.006);
    }
}

TEST(DetectOnsets, ReturnsNothingForPureSilence) {
    const std::vector<float> pcm(1000, 0.0f);
    EXPECT_TRUE(detectOnsets(pcm, kSampleRate).empty());
}

TEST(DetectOnsets, ReturnsNothingForEmptyInput) {
    EXPECT_TRUE(detectOnsets({}, kSampleRate).empty());
}

TEST(EstimateLatencyFromOnsets, EstimatesMedianDelayBetweenScheduledAndDetected) {
    const std::vector<double> scheduled = {0.1, 0.4, 0.7, 1.0};
    constexpr double latency = 0.025;
    std::vector<double> detected;
    for (const double t : scheduled) detected.push_back(t + latency);

    const auto est = estimateLatencyFromOnsets(detected, scheduled);
    ASSERT_TRUE(est.has_value());
    EXPECT_NEAR(*est, 0.025, 1e-4);
}

TEST(EstimateLatencyFromOnsets, IgnoresOnsetsImplausiblyFarFromAnyClick) {
    const std::vector<double> scheduled = {0.1, 0.4, 0.7, 1.0};
    const std::vector<double> detected = {0.12, 0.42, 0.72, 1.02, 5.0}; // 5.0 is spurious
    const auto est = estimateLatencyFromOnsets(detected, scheduled);
    ASSERT_TRUE(est.has_value());
    EXPECT_NEAR(*est, 0.02, 1e-3);
}

TEST(EstimateLatencyFromOnsets, ReturnsNulloptWhenTooFewClicksMatch) {
    const auto est = estimateLatencyFromOnsets({0.11}, {0.1, 0.4, 0.7, 1.0});
    EXPECT_FALSE(est.has_value());
}

TEST(EstimateLatencyFromOnsets, ReturnsNulloptForEmptyInput) {
    EXPECT_FALSE(estimateLatencyFromOnsets({}, {0.1, 0.4}).has_value());
    EXPECT_FALSE(estimateLatencyFromOnsets({0.1}, {}).has_value());
}
