#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/piano_voice.h"

using songnotes::dsp::envelopeAt;
using songnotes::dsp::interpolatedSample;
using songnotes::dsp::kPianoSampleCount;
using songnotes::dsp::kPianoSamples;
using songnotes::dsp::nearestSampleIndexFor;
using songnotes::dsp::playbackRateFor;
using songnotes::dsp::renderVoiceInto;

TEST(PianoVoice, SampleTableHas29EntriesSpacedAMinorThirdApart) {
    EXPECT_EQ(kPianoSampleCount, 29);
    EXPECT_EQ(kPianoSamples.front().midi, 24);
    EXPECT_EQ(kPianoSamples.back().midi, 108);
    for (size_t i = 1; i < kPianoSamples.size(); i++) {
        EXPECT_EQ(kPianoSamples[i].midi - kPianoSamples[i - 1].midi, 3);
    }
}

TEST(PianoVoice, NearestSampleIndexFindsAnExactHit) {
    // C4 (midi 60) is index 12 in the table above.
    EXPECT_EQ(kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(60))].midi, 60);
}

TEST(PianoVoice, NearestSampleIndexBreaksTiesTowardTheLowerSample) {
    EXPECT_EQ(kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(25))].midi, 24);
    EXPECT_EQ(kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(26))].midi, 27);
}

TEST(PianoVoice, NearestSampleIndexClampsOutOfRangeMidiToTheEdge) {
    EXPECT_EQ(kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(0))].midi, 24);
    EXPECT_EQ(kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(127))].midi, 108);
}

TEST(PianoVoice, NearestSampleIndexNeverFartherThanOneSemitoneWithinTheRecordedRange) {
    for (int32_t midi = 24; midi <= 108; midi++) {
        const int32_t sampleMidi = kPianoSamples[static_cast<size_t>(nearestSampleIndexFor(midi))].midi;
        EXPECT_LE(std::abs(sampleMidi - midi), 1) << "midi=" << midi;
    }
}

TEST(PianoVoice, PlaybackRateIsOneForAnExactHitAtMatchingSampleRates) {
    EXPECT_NEAR(playbackRateFor(60, 60, 44100.0, 44100.0), 1.0, 1e-9);
}

TEST(PianoVoice, PlaybackRateAppliesTheSemitoneRatio) {
    const double expected = std::pow(2.0, 1.0 / 12.0);
    EXPECT_NEAR(playbackRateFor(61, 60, 44100.0, 44100.0), expected, 1e-9);
}

TEST(PianoVoice, PlaybackRateFoldsInTheSampleRateCorrection) {
    // Sample recorded at 44.1kHz played on a 48kHz engine -- omitting this
    // factor is exactly the silent "every note ~9% sharp" bug this exists
    // to prevent.
    const double expected = 44100.0 / 48000.0;
    EXPECT_NEAR(playbackRateFor(60, 60, 44100.0, 48000.0), expected, 1e-9);
}

TEST(PianoVoice, EnvelopeStartsAtZeroAndReachesOneAtTheEndOfTheAttack) {
    EXPECT_NEAR(envelopeAt(0.0), 0.0, 1e-9);
    EXPECT_NEAR(envelopeAt(0.005), 1.0, 1e-6);
}

TEST(PianoVoice, EnvelopeDecaysToTheDocumentedCheckpoints) {
    EXPECT_NEAR(envelopeAt(0.8), 0.25, 1e-6);
    EXPECT_NEAR(envelopeAt(4.0), 0.001, 1e-6);
    EXPECT_NEAR(envelopeAt(10.0), 0.001, 1e-9); // holds the floor forever after
}

TEST(PianoVoice, EnvelopeIsMonotonicallyNonIncreasingAfterTheAttackWhileHeld) {
    double previous = envelopeAt(0.005);
    for (double t = 0.055; t < 5.0; t += 0.05) {
        const double current = envelopeAt(t);
        EXPECT_LE(current, previous + 1e-9) << "rose at t=" << t;
        previous = current;
    }
}

TEST(PianoVoice, ReleaseRampsFromTheTrueValueAtReleaseTimeNotFromOne) {
    const double releaseAge = 1.0;
    const double heldValueAtRelease = envelopeAt(releaseAge);
    EXPECT_NEAR(envelopeAt(releaseAge, releaseAge), heldValueAtRelease, 1e-9);

    const double justAfter = envelopeAt(releaseAge + 0.05, releaseAge);
    EXPECT_LT(justAfter, heldValueAtRelease);
    EXPECT_GT(justAfter, 0.001);

    EXPECT_NEAR(envelopeAt(releaseAge + 0.4, releaseAge), 0.001, 1e-6);
    EXPECT_NEAR(envelopeAt(releaseAge + 5.0, releaseAge), 0.001, 1e-9);
}

TEST(PianoVoice, EnvelopeBeforeReleaseTimeIsUnaffectedByALaterRelease) {
    const double releaseAge = 2.0;
    EXPECT_NEAR(envelopeAt(0.5), envelopeAt(0.5, releaseAge), 1e-9);
}

TEST(PianoVoice, ReleaseAtTheVeryFirstSampleDoesNotThrowOrProduceNan) {
    const double value = envelopeAt(0.0, 0.0);
    EXPECT_GE(value, 0.0);
    EXPECT_LE(value, 1.0);
    EXPECT_FALSE(std::isnan(value));
    EXPECT_NEAR(envelopeAt(0.4, 0.0), 0.001, 1e-6);
}

TEST(PianoVoice, InterpolatedSampleMatchesHandComputedLinearInterpolation) {
    const std::vector<float> buffer = {0.0f, 10.0f, 20.0f, 30.0f};
    float out = -1.0f;

    ASSERT_TRUE(interpolatedSample(buffer.data(), static_cast<int32_t>(buffer.size()), 0.0, &out));
    EXPECT_NEAR(out, 0.0f, 1e-6f);

    ASSERT_TRUE(interpolatedSample(buffer.data(), static_cast<int32_t>(buffer.size()), 0.5, &out));
    EXPECT_NEAR(out, 5.0f, 1e-6f);

    ASSERT_TRUE(interpolatedSample(buffer.data(), static_cast<int32_t>(buffer.size()), 2.3, &out));
    EXPECT_NEAR(out, 23.0f, 1e-6f);
}

TEST(PianoVoice, InterpolatedSampleFailsOnceItCanNoLongerFormAPair) {
    const std::vector<float> buffer = {1.0f, 2.0f, 3.0f};
    float out = 0.0f;
    EXPECT_TRUE(interpolatedSample(buffer.data(), 3, 1.9, &out));
    EXPECT_FALSE(interpolatedSample(buffer.data(), 3, 2.0, &out)); // idx=2 has no idx+1
    EXPECT_FALSE(interpolatedSample(buffer.data(), 3, 5.0, &out));
    EXPECT_FALSE(interpolatedSample(buffer.data(), 3, -1.0, &out));
}

TEST(PianoVoice, RenderVoiceIntoSumsRatherThanOverwrites) {
    std::vector<float> buffer(1000, 1.0f); // constant source, so envelope is the only shaping factor
    std::vector<float> out(4, 5.0f); // pre-filled, as if another voice already rendered here

    renderVoiceInto(out.data(), 4, buffer.data(), static_cast<int32_t>(buffer.size()), 0.0, 1.0, 10.0, -1.0,
                     48000.0, 1.0f);

    // Age 10s is well past the decay floor (0.001), so each frame added ~0.001.
    for (float v : out) {
        EXPECT_GT(v, 5.0f);
        EXPECT_LT(v, 5.01f);
    }
}

TEST(PianoVoice, RenderVoiceIntoReportsExhaustedAndStopsAdvancing) {
    std::vector<float> buffer(10, 1.0f);
    std::vector<float> out(20, 0.0f);

    auto result = renderVoiceInto(out.data(), 20, buffer.data(), static_cast<int32_t>(buffer.size()), 0.0, 1.0, 1.0,
                                   -1.0, 48000.0, 1.0f);

    EXPECT_TRUE(result.exhausted);
    EXPECT_LT(result.nextReadPos, 10.0);
}

TEST(PianoVoice, RenderVoiceIntoAdvancesReadPosByRatePerFrame) {
    std::vector<float> buffer(1000);
    for (size_t i = 0; i < buffer.size(); i++) buffer[i] = static_cast<float>(i);
    std::vector<float> out(10, 0.0f);

    auto result = renderVoiceInto(out.data(), 10, buffer.data(), static_cast<int32_t>(buffer.size()), 5.0, 2.0,
                                   100.0, -1.0, 48000.0, 1.0f);

    EXPECT_NEAR(result.nextReadPos, 25.0, 1e-9);
    EXPECT_FALSE(result.exhausted);
}
