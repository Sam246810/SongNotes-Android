#pragma once

#include <cstdint>
#include <vector>

namespace songnotes::dsp {

// Full linear convolution of `a` and `b` via zero-padded FFT (fast
// convolution) — result has length a.size() + b.size() - 1. This is how
// the calibration sweep's deconvolution actually happens: convolving a
// recording with the Farina inverse filter (see sweep.h).
std::vector<float> convolve(const std::vector<float> &a, const std::vector<float> &b);

struct PeakResult {
    int32_t index = -1;               // integer sample index of the peak, -1 if input was empty
    double interpolatedOffset = 0.0;  // sub-sample correction via parabolic interpolation, in [-0.5, 0.5]
    float peakMagnitude = 0.0f;
    float noiseFloor = 0.0f;          // median magnitude away from the peak — feeds peakToNoiseRatioDb()
};

// Finds the single largest-magnitude sample in `signal` and refines its
// position via parabolic interpolation over the peak and its two
// neighbors. One sample at 48kHz is ~20.8us — nowhere near precise enough
// on its own for a <3ms spread target, let alone the tighter tolerance
// real calibration needs; the interpolation is what gets sub-sample
// precision out of a discretely-sampled peak.
PeakResult findPeak(const std::vector<float> &signal);

} // namespace songnotes::dsp
