#include <gtest/gtest.h>

#include <cmath>

#include "dsp/sweep.h"

using songnotes::dsp::generateSweepAndInverse;

namespace {
// Counts zero crossings in [start, end) — a cheap proxy for instantaneous
// frequency over a short window, good enough to confirm the sweep moves in
// the right direction without needing a full spectral estimate.
double estimateFrequencyHz(const std::vector<float> &signal, size_t start, size_t end, double sampleRate) {
    int crossings = 0;
    for (size_t i = start + 1; i < end; i++) {
        if ((signal[i - 1] < 0.0f) != (signal[i] < 0.0f)) crossings++;
    }
    const double windowSeconds = static_cast<double>(end - start) / sampleRate;
    return (crossings / 2.0) / windowSeconds; // 2 crossings per cycle
}
} // namespace

TEST(Sweep, ProducesRequestedLength) {
    auto result = generateSweepAndInverse(48000.0, 200.0, 8000.0, 1.0, 0.5f);
    EXPECT_EQ(result.sweep.size(), 48000u);
    EXPECT_EQ(result.inverseFilter.size(), 48000u);
}

TEST(Sweep, StartsNearF1AndEndsNearF2) {
    constexpr double sampleRate = 48000.0;
    constexpr double f1 = 200.0;
    constexpr double f2 = 8000.0;
    auto result = generateSweepAndInverse(sampleRate, f1, f2, 2.0, 0.8f);

    // A short window right at the start / end — long enough to see several
    // cycles even at f1, short enough to stay a reasonable "instantaneous"
    // estimate rather than averaging over the whole sweep.
    const auto windowFrames = static_cast<size_t>(sampleRate * 0.02); // 20ms
    const double startFreq = estimateFrequencyHz(result.sweep, 0, windowFrames, sampleRate);
    const double endFreq = estimateFrequencyHz(
        result.sweep, result.sweep.size() - windowFrames, result.sweep.size(), sampleRate);

    EXPECT_NEAR(startFreq, f1, f1 * 0.15);
    EXPECT_NEAR(endFreq, f2, f2 * 0.15);
}

TEST(Sweep, StaysWithinRequestedAmplitude) {
    auto result = generateSweepAndInverse(48000.0, 200.0, 8000.0, 1.0, 0.5f);
    for (float s : result.sweep) {
        EXPECT_LE(std::fabs(s), 0.5f + 1e-5f);
    }
}

TEST(Sweep, InverseFilterIsTheSweepReversedAndEnvelopeScaled) {
    constexpr double f1 = 200.0;
    constexpr double f2 = 8000.0;
    auto result = generateSweepAndInverse(48000.0, f1, f2, 1.0, 0.5f);
    const size_t n = result.sweep.size();

    // inverseFilter[i] == sweep[n-1-i] * exp(r*(t/T - 1)), exactly, per the
    // documented derivation — check it at both ends where the envelope
    // takes its extreme values (f1/f2 at i=0, 1.0 at i=n-1) rather than
    // trusting a vague "looks reversed" check.
    const double expectedEnvelopeAtStart = f1 / f2;
    EXPECT_NEAR(result.inverseFilter[0], result.sweep[n - 1] * static_cast<float>(expectedEnvelopeAtStart),
                1e-5f);
    EXPECT_NEAR(result.inverseFilter[n - 1], result.sweep[0], 1e-5f);
}
