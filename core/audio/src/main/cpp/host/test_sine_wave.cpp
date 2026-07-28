#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "dsp/sine_wave.h"

using songnotes::dsp::renderSine;

TEST(SineWave, ProducesRequestedFrequency) {
    constexpr double sampleRate = 48000.0;
    constexpr double frequencyHz = 440.0;
    constexpr int32_t numFrames = 4800; // 100 ms
    std::vector<float> buffer(numFrames);
    double phase = 0.0;

    renderSine(buffer.data(), numFrames, /*channelCount=*/1, sampleRate, frequencyHz, 1.0f,
               &phase);

    int zeroCrossings = 0;
    for (int i = 1; i < numFrames; i++) {
        if ((buffer[i - 1] < 0.0f) != (buffer[i] < 0.0f)) {
            zeroCrossings++;
        }
    }
    // 440 Hz over 100 ms = 44 full cycles = 88 zero crossings, +/- a bit for
    // wherever the window happens to start/end relative to a cycle boundary.
    EXPECT_NEAR(zeroCrossings, 88, 2);
}

TEST(SineWave, RespectsAmplitude) {
    constexpr int32_t numFrames = 4800;
    std::vector<float> buffer(numFrames);
    double phase = 0.0;
    renderSine(buffer.data(), numFrames, 1, 48000.0, 440.0, 0.2f, &phase);

    float peak = 0.0f;
    for (float sample : buffer) {
        peak = std::max(peak, std::fabs(sample));
    }
    EXPECT_NEAR(peak, 0.2f, 0.01f);
}

TEST(SineWave, DuplicatesAcrossChannels) {
    constexpr int32_t numFrames = 100;
    constexpr int32_t channels = 2;
    std::vector<float> buffer(numFrames * channels);
    double phase = 0.0;
    renderSine(buffer.data(), numFrames, channels, 48000.0, 440.0, 1.0f, &phase);

    for (int32_t frame = 0; frame < numFrames; frame++) {
        EXPECT_FLOAT_EQ(buffer[frame * channels], buffer[frame * channels + 1]);
    }
}

TEST(SineWave, PhaseIsContinuousAcrossCalls) {
    // Rendering one big call vs. two consecutive smaller calls — with phase
    // carried between them, exactly as it is across successive real
    // onAudioReady invocations — must produce identical output. This is the
    // property that keeps the test tone click-free at audio-callback
    // buffer boundaries.
    constexpr int32_t total = 200;
    constexpr int32_t split = 77;

    std::vector<float> oneShot(total);
    double phaseA = 0.0;
    renderSine(oneShot.data(), total, 1, 48000.0, 440.0, 1.0f, &phaseA);

    std::vector<float> twoShot(total);
    double phaseB = 0.0;
    renderSine(twoShot.data(), split, 1, 48000.0, 440.0, 1.0f, &phaseB);
    renderSine(twoShot.data() + split, total - split, 1, 48000.0, 440.0, 1.0f, &phaseB);

    for (int32_t i = 0; i < total; i++) {
        EXPECT_NEAR(oneShot[i], twoShot[i], 1e-6f);
    }
}

TEST(SineWave, HandlesZeroFrames) {
    double phase = 0.0;
    // Must not touch `out` when numFrames == 0 — nullptr is deliberately
    // passed here to prove that.
    renderSine(nullptr, 0, 1, 48000.0, 440.0, 1.0f, &phase);
    EXPECT_EQ(phase, 0.0);
}
