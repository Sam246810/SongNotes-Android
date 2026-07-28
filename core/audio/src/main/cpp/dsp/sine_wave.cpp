#include "sine_wave.h"

#include <cmath>

namespace songnotes::dsp {

void renderSine(float *out, int32_t numFrames, int32_t channelCount, double sampleRate,
                 double frequencyHz, float amplitude, double *phase) {
    const double phaseIncrement = 2.0 * M_PI * frequencyHz / sampleRate;
    double p = *phase;
    for (int32_t frame = 0; frame < numFrames; frame++) {
        const auto sample = static_cast<float>(std::sin(p)) * amplitude;
        for (int32_t ch = 0; ch < channelCount; ch++) {
            out[frame * channelCount + ch] = sample;
        }
        p += phaseIncrement;
        if (p >= 2.0 * M_PI) {
            p -= 2.0 * M_PI;
        }
    }
    *phase = p;
}

} // namespace songnotes::dsp
