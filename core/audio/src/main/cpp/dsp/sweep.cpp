#include "sweep.h"

#include <cmath>

namespace songnotes::dsp {

SweepAndInverse generateSweepAndInverse(double sampleRate, double f1Hz, double f2Hz,
                                          double lengthSeconds, float amplitude) {
    SweepAndInverse result;
    const auto n = static_cast<size_t>(std::llround(sampleRate * lengthSeconds));
    result.sweep.resize(n);
    result.inverseFilter.resize(n);
    if (n == 0) return result;

    const double T = lengthSeconds;
    const double w1 = 2.0 * M_PI * f1Hz;
    const double r = std::log(f2Hz / f1Hz); // spectral growth rate over the sweep's duration

    for (size_t i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / sampleRate;
        // Instantaneous-frequency integral for an exponential sweep —
        // the standard ESS phase formula.
        const double phase = (w1 * T / r) * (std::exp(t * r / T) - 1.0);
        result.sweep[i] = static_cast<float>(std::sin(phase)) * amplitude;
    }

    // Farina inverse filter: time-reversed sweep, amplitude-modulated by
    // f1/f(T-t) to compensate the sweep's inherent -6dB/octave spectral
    // tilt (higher frequencies are swept through faster and so carry less
    // energy per unit time unless corrected). Expressed directly via r and
    // T rather than recomputing f(t) — f1/f(T-t) = exp(r*(t/T - 1)).
    for (size_t i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / sampleRate;
        const double envelope = std::exp(r * (t / T - 1.0));
        const size_t reversedIndex = n - 1 - i;
        result.inverseFilter[i] = result.sweep[reversedIndex] * static_cast<float>(envelope);
    }

    return result;
}

} // namespace songnotes::dsp
