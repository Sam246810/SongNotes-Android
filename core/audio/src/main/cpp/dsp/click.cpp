#include "click.h"

#include <algorithm>
#include <cmath>

namespace songnotes::dsp {

std::vector<float> generateClick(double sampleRate, double frequencyHz, int32_t lengthFrames,
                                  float amplitude) {
    std::vector<float> out(static_cast<size_t>(std::max(lengthFrames, 0)));
    if (out.empty()) return out;

    const double lengthSeconds = static_cast<double>(lengthFrames) / sampleRate;
    // 5 time constants brings the envelope to ~0.7% of peak by the end of
    // the buffer — small enough that the explicit fade-out below (not this
    // decay alone) is what actually guarantees a click-free truncation.
    const double tau = lengthSeconds / 5.0;
    const double phaseIncrement = 2.0 * M_PI * frequencyHz / sampleRate;

    // Linear fade over the final 10% of the buffer (or up to 64 frames,
    // whichever is smaller) so the very last sample is exactly zero — the
    // exponential decay alone gets close but never reaches it, and a
    // nonzero sample at a buffer boundary is itself a tiny click.
    const int32_t fadeFrames = std::min<int32_t>(lengthFrames / 10, 64);
    const int32_t fadeStart = lengthFrames - fadeFrames;

    for (int32_t i = 0; i < lengthFrames; i++) {
        const double t = static_cast<double>(i) / sampleRate;
        const double envelope = std::exp(-t / tau);
        double sample = std::sin(phaseIncrement * i) * envelope;
        if (fadeFrames > 0 && i >= fadeStart) {
            const double fadeProgress = static_cast<double>(i - fadeStart) / static_cast<double>(fadeFrames);
            sample *= (1.0 - fadeProgress);
        }
        out[static_cast<size_t>(i)] = static_cast<float>(sample) * amplitude;
    }
    out.back() = 0.0f;

    return out;
}

} // namespace songnotes::dsp
