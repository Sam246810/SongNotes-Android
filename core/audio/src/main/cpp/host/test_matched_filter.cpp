#include <gtest/gtest.h>

#include <cmath>

#include "dsp/matched_filter.h"

using songnotes::dsp::convolve;
using songnotes::dsp::findPeak;

TEST(Convolve, MatchesHandComputedResult) {
    // [1,1] * [1,1] = [1,2,1] — textbook example, cheap sanity check that
    // the FFT-based fast convolution agrees with direct convolution before
    // trusting it on anything bigger.
    auto result = convolve({1.0f, 1.0f}, {1.0f, 1.0f});
    ASSERT_EQ(result.size(), 3u);
    EXPECT_NEAR(result[0], 1.0f, 1e-4f);
    EXPECT_NEAR(result[1], 2.0f, 1e-4f);
    EXPECT_NEAR(result[2], 1.0f, 1e-4f);
}

TEST(Convolve, ConvolvingWithAnImpulseIsIdentity) {
    std::vector<float> signal = {0.3f, -0.7f, 1.2f, -0.4f, 0.05f};
    std::vector<float> impulse = {1.0f};
    auto result = convolve(signal, impulse);
    ASSERT_EQ(result.size(), signal.size());
    for (size_t i = 0; i < signal.size(); i++) {
        EXPECT_NEAR(result[i], signal[i], 1e-4f);
    }
}

TEST(Convolve, OutputLengthIsSumMinusOne) {
    std::vector<float> a(100, 0.1f);
    std::vector<float> b(37, 0.2f);
    auto result = convolve(a, b);
    EXPECT_EQ(result.size(), 100u + 37u - 1u);
}

TEST(FindPeak, LocatesTheLargestMagnitudeSample) {
    std::vector<float> signal = {0.1f, -0.2f, 0.9f, -0.05f, 0.3f, -0.85f};
    auto result = findPeak(signal);
    EXPECT_EQ(result.index, 2); // 0.9 is the largest magnitude
}

TEST(FindPeak, ParabolicInterpolationIsZeroForASymmetricPeak) {
    // y = -(x-3)^2 sampled at integers 0..6 — perfectly symmetric around
    // index 3, so the true peak sits exactly on a sample and the
    // interpolated correction should be ~0.
    std::vector<float> signal;
    for (int x = 0; x <= 6; x++) {
        const double v = -std::pow(x - 3, 2) + 100.0;
        signal.push_back(static_cast<float>(v));
    }
    auto result = findPeak(signal);
    EXPECT_EQ(result.index, 3);
    EXPECT_NEAR(result.interpolatedOffset, 0.0, 1e-6);
}

TEST(FindPeak, ParabolicInterpolationShiftsTowardTheTallerNeighbor) {
    // An asymmetric peak: neighbor to the right is taller than the left,
    // so the true (continuous) peak lies slightly to the right of index 2.
    std::vector<float> signal = {0.0f, 5.0f, 10.0f, 9.0f, 0.0f};
    auto result = findPeak(signal);
    EXPECT_EQ(result.index, 2);
    EXPECT_GT(result.interpolatedOffset, 0.0);
}

TEST(FindPeak, NoiseFloorReflectsTheQuietRegionNotThePeak) {
    std::vector<float> signal(200, 0.01f); // uniform low-level "noise"
    signal[100] = 5.0f;                    // one big spike
    auto result = findPeak(signal);
    EXPECT_EQ(result.index, 100);
    EXPECT_NEAR(result.noiseFloor, 0.01f, 1e-4f);
    EXPECT_GT(result.peakMagnitude, result.noiseFloor * 100.0f);
}

TEST(FindPeak, HandlesEmptyInput) {
    auto result = findPeak({});
    EXPECT_EQ(result.index, -1);
}
