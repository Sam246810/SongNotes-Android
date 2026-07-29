#include <gtest/gtest.h>

#include <cmath>

#include "dsp/click.h"

using songnotes::dsp::generateClick;

TEST(Click, HasRequestedLength) {
    auto click = generateClick(48000.0, 1800.0, 480, 0.5f);
    EXPECT_EQ(click.size(), 480u);
}

TEST(Click, EndsAtExactlyZero) {
    // A nonzero sample at the buffer boundary is itself an audible click —
    // this is the property that keeps the metronome from clicking twice
    // per beat (once for the intended click, once for its own truncation).
    auto click = generateClick(48000.0, 1800.0, 480, 0.5f);
    EXPECT_FLOAT_EQ(click.back(), 0.0f);
}

TEST(Click, PeakIsNearTheStartAndRoughlyMatchesAmplitude) {
    auto click = generateClick(48000.0, 1800.0, 480, 0.5f);
    float peak = 0.0f;
    size_t peakIndex = 0;
    for (size_t i = 0; i < click.size(); i++) {
        if (std::fabs(click[i]) > peak) {
            peak = std::fabs(click[i]);
            peakIndex = i;
        }
    }
    EXPECT_NEAR(peak, 0.5f, 0.05f);
    // Within the first ~1ms at 48kHz (first ~48 frames) — a percussive
    // click front-loads its energy, it doesn't build up to a peak.
    EXPECT_LT(peakIndex, 48u);
}

TEST(Click, EnvelopeDecaysOverTime) {
    auto click = generateClick(48000.0, 1800.0, 480, 1.0f);
    // Compare RMS of the first quarter vs the last quarter — the back end
    // should be much quieter than the front.
    auto rms = [&](size_t start, size_t end) {
        double sumSq = 0.0;
        for (size_t i = start; i < end; i++) sumSq += static_cast<double>(click[i]) * click[i];
        return std::sqrt(sumSq / static_cast<double>(end - start));
    };
    const double frontRms = rms(0, click.size() / 4);
    const double backRms = rms(click.size() * 3 / 4, click.size());
    EXPECT_GT(frontRms, backRms * 4.0);
}

TEST(Click, HigherFrequencyProducesMoreZeroCrossings) {
    auto low = generateClick(48000.0, 900.0, 480, 0.5f);
    auto high = generateClick(48000.0, 1800.0, 480, 0.5f);

    auto countZeroCrossings = [](const std::vector<float> &buf) {
        int count = 0;
        for (size_t i = 1; i < buf.size(); i++) {
            if ((buf[i - 1] < 0.0f) != (buf[i] < 0.0f)) count++;
        }
        return count;
    };
    // Roughly double the frequency should give roughly double the crossings
    // — loose bounds since the decay envelope suppresses crossings in the
    // quiet tail either way.
    EXPECT_GT(countZeroCrossings(high), countZeroCrossings(low));
}

TEST(Click, HandlesZeroLength) {
    auto click = generateClick(48000.0, 1800.0, 0, 0.5f);
    EXPECT_TRUE(click.empty());
}
