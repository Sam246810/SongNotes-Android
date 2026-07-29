#include "matched_filter.h"

#include <algorithm>
#include <cmath>

#include "fft.h"

namespace songnotes::dsp {

std::vector<float> convolve(const std::vector<float> &a, const std::vector<float> &b) {
    if (a.empty() || b.empty()) return {};

    const size_t resultLength = a.size() + b.size() - 1;
    const size_t fftSize = nextPowerOfTwo(resultLength);

    std::vector<Complex> fa(fftSize, Complex(0.0f, 0.0f));
    std::vector<Complex> fb(fftSize, Complex(0.0f, 0.0f));
    for (size_t i = 0; i < a.size(); i++) fa[i] = Complex(a[i], 0.0f);
    for (size_t i = 0; i < b.size(); i++) fb[i] = Complex(b[i], 0.0f);

    fft(fa, false);
    fft(fb, false);
    for (size_t i = 0; i < fftSize; i++) fa[i] *= fb[i];
    fft(fa, true);

    std::vector<float> result(resultLength);
    for (size_t i = 0; i < resultLength; i++) result[i] = fa[i].real();
    return result;
}

PeakResult findPeak(const std::vector<float> &signal) {
    PeakResult result;
    if (signal.empty()) return result;

    size_t peakIdx = 0;
    float peakMag = std::fabs(signal[0]);
    for (size_t i = 1; i < signal.size(); i++) {
        const float mag = std::fabs(signal[i]);
        if (mag > peakMag) {
            peakMag = mag;
            peakIdx = i;
        }
    }
    result.index = static_cast<int32_t>(peakIdx);
    result.peakMagnitude = peakMag;

    // Parabolic interpolation over the peak and its immediate neighbors —
    // skipped at either boundary, where there's no neighbor to fit.
    if (peakIdx > 0 && peakIdx + 1 < signal.size()) {
        const double left = std::fabs(signal[peakIdx - 1]);
        const double center = std::fabs(signal[peakIdx]);
        const double right = std::fabs(signal[peakIdx + 1]);
        const double denom = left - 2.0 * center + right;
        if (std::fabs(denom) > 1e-12) {
            result.interpolatedOffset = 0.5 * (left - right) / denom;
        }
    }

    // Noise floor: median magnitude of everything at least 5% of the
    // signal's length away from the peak, so the peak's own energy (and
    // its immediate skirt) never contaminates the estimate.
    const size_t exclusionRadius = std::max<size_t>(1, signal.size() / 20);
    std::vector<float> awayFromPeak;
    awayFromPeak.reserve(signal.size());
    for (size_t i = 0; i < signal.size(); i++) {
        const size_t distance = i > peakIdx ? i - peakIdx : peakIdx - i;
        if (distance > exclusionRadius) {
            awayFromPeak.push_back(std::fabs(signal[i]));
        }
    }
    if (!awayFromPeak.empty()) {
        std::sort(awayFromPeak.begin(), awayFromPeak.end());
        result.noiseFloor = awayFromPeak[awayFromPeak.size() / 2];
    }

    return result;
}

} // namespace songnotes::dsp
