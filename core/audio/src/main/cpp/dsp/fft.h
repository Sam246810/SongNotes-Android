#pragma once

#include <complex>
#include <cstddef>
#include <vector>

namespace songnotes::dsp {

using Complex = std::complex<float>;

// Smallest power of two >= n (n=0 returns 1).
size_t nextPowerOfTwo(size_t n);

// In-place iterative radix-2 Cooley-Tukey FFT. `data.size()` MUST already
// be a power of two (callers pad with zeros — see convolve() in
// matched_filter.h for the pattern). inverse=true computes the inverse
// transform, with the customary 1/N scaling applied so fft(fft(x, false),
// true) round-trips back to x.
void fft(std::vector<Complex> &data, bool inverse);

} // namespace songnotes::dsp
