#pragma once

#include <vector>

namespace songnotes::dsp {

// An exponential sine sweep (ESS) from f1Hz to f2Hz over lengthSeconds,
// plus its Farina inverse filter. Convolving `sweep` with `inverseFilter`
// yields (up to floating-point precision) a single sharp impulse at index
// sweep.size()-1 — see Farina, "Simultaneous measurement of impulse
// response and distortion...", AES 2000.
//
// The point of all this: convolving a RECORDING of the sweep (played
// through a real acoustic/electrical loopback, so really
// sweep-convolved-with-whatever-that-path's-impulse-response-is, plus
// noise) with the same inverse filter reveals that path's impulse
// response, positioned however much later than sweep.size()-1 the round
// trip actually took. That extra shift, in samples, IS the round-trip
// latency this whole calibration phase measures. See matched_filter.h for
// the convolution + peak-finding step that actually extracts it.
struct SweepAndInverse {
    std::vector<float> sweep;
    std::vector<float> inverseFilter; // same length as sweep
};

SweepAndInverse generateSweepAndInverse(double sampleRate, double f1Hz, double f2Hz,
                                          double lengthSeconds, float amplitude);

} // namespace songnotes::dsp
