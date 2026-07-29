#include "fft.h"

#include <cmath>
#include <utility>

namespace songnotes::dsp {

size_t nextPowerOfTwo(size_t n) {
    size_t p = 1;
    while (p < n) {
        p <<= 1;
    }
    return p;
}

void fft(std::vector<Complex> &a, bool inverse) {
    const size_t n = a.size();
    if (n <= 1) return;

    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(a[i], a[j]);
        }
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        const double angle = (inverse ? 2.0 : -2.0) * M_PI / static_cast<double>(len);
        const Complex wlen(static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle)));
        for (size_t i = 0; i < n; i += len) {
            Complex w(1.0f, 0.0f);
            for (size_t k = 0; k < len / 2; k++) {
                const Complex u = a[i + k];
                const Complex v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wlen;
            }
        }
    }

    if (inverse) {
        for (auto &x : a) {
            x /= static_cast<float>(n);
        }
    }
}

} // namespace songnotes::dsp
