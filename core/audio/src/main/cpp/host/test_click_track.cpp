#include <gtest/gtest.h>

#include <cmath>

#include "dsp/click_track.h"

using songnotes::dsp::renderClickTrack;

namespace {
float peakInRange(const std::vector<float> &buf, size_t start, size_t end) {
    float peak = 0.0f;
    for (size_t i = start; i < std::min(end, buf.size()); i++) peak = std::max(peak, std::fabs(buf[i]));
    return peak;
}
} // namespace

TEST(ClickTrack, HasRequestedLength) {
    auto track = renderClickTrack(48000.0, 80.0, 4, 96000, 1800.0, 1200.0, 0.01, 0.5f);
    EXPECT_EQ(track.size(), 96000u);
}

TEST(ClickTrack, PlacesClicksAtExpectedBeatFrames) {
    constexpr double sampleRate = 48000.0;
    constexpr double bpm = 80.0;
    const auto beatIntervalFrames = static_cast<int64_t>(std::llround(sampleRate * 60.0 / bpm));
    auto track = renderClickTrack(sampleRate, bpm, 4, beatIntervalFrames * 4, 1800.0, 1200.0, 0.01, 0.5f);

    // A click's energy is front-loaded (see dsp/click.h) — check for
    // meaningful signal in a small window right at each expected beat, and
    // near-silence well before/after it.
    for (int beat = 0; beat < 4; beat++) {
        const auto beatFrame = static_cast<size_t>(beat * beatIntervalFrames);
        EXPECT_GT(peakInRange(track, beatFrame, beatFrame + 50), 0.1f) << "beat " << beat;
    }
    const size_t midpoint = static_cast<size_t>(beatIntervalFrames / 2);
    EXPECT_LT(peakInRange(track, midpoint, midpoint + 50), 0.01f);
}

TEST(ClickTrack, SilentWhenBpmIsDegenerate) {
    auto zero = renderClickTrack(48000.0, 0.0, 4, 48000, 1800.0, 1200.0, 0.01, 0.5f);
    EXPECT_EQ(zero.size(), 48000u);
    EXPECT_FLOAT_EQ(peakInRange(zero, 0, zero.size()), 0.0f);

    auto negative = renderClickTrack(48000.0, -10.0, 4, 48000, 1800.0, 1200.0, 0.01, 0.5f);
    EXPECT_FLOAT_EQ(peakInRange(negative, 0, negative.size()), 0.0f);
}

TEST(ClickTrack, HandlesZeroLength) {
    auto track = renderClickTrack(48000.0, 80.0, 4, 0, 1800.0, 1200.0, 0.01, 0.5f);
    EXPECT_TRUE(track.empty());
}

TEST(ClickTrack, DoesNotOverrunWhenLastClickIsTruncatedByBufferEnd) {
    // Deliberately end the buffer mid-click for the last beat — this
    // exercises the framesAvailable clamp in renderClickTrack, which would
    // otherwise write past the end of `out`.
    constexpr double sampleRate = 48000.0;
    constexpr double bpm = 80.0;
    const auto beatIntervalFrames = static_cast<int64_t>(std::llround(sampleRate * 60.0 / bpm));
    const int64_t totalFrames = beatIntervalFrames * 2 + 10; // 10 frames into the 3rd beat's click
    auto track = renderClickTrack(sampleRate, bpm, 4, totalFrames, 1800.0, 1200.0, 0.01, 0.5f);
    EXPECT_EQ(track.size(), static_cast<size_t>(totalFrames));
}
