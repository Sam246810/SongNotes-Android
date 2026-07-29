#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/fft.h"

using songnotes::dsp::Complex;
using songnotes::dsp::fft;
using songnotes::dsp::nextPowerOfTwo;

TEST(NextPowerOfTwo, RoundsUpCorrectly) {
    EXPECT_EQ(nextPowerOfTwo(1), 1u);
    EXPECT_EQ(nextPowerOfTwo(2), 2u);
    EXPECT_EQ(nextPowerOfTwo(3), 4u);
    EXPECT_EQ(nextPowerOfTwo(1000), 1024u);
    EXPECT_EQ(nextPowerOfTwo(1024), 1024u);
}

TEST(Fft, DcSignalConcentratesAllEnergyInBinZero) {
    std::vector<Complex> data(64, Complex(1.0f, 0.0f));
    fft(data, false);
    EXPECT_NEAR(std::abs(data[0]), 64.0f, 0.01f);
    for (size_t i = 1; i < data.size(); i++) {
        EXPECT_NEAR(std::abs(data[i]), 0.0f, 0.01f);
    }
}

TEST(Fft, PureSinusoidPeaksAtItsBin) {
    constexpr size_t n = 64;
    constexpr size_t targetBin = 5;
    std::vector<Complex> data(n);
    for (size_t i = 0; i < n; i++) {
        const double angle = 2.0 * M_PI * static_cast<double>(targetBin) * static_cast<double>(i) / n;
        data[i] = Complex(static_cast<float>(std::cos(angle)), 0.0f);
    }
    fft(data, false);

    size_t peakBin = 0;
    float peakMag = 0.0f;
    for (size_t i = 0; i < n / 2; i++) { // real input -> only need the first half (conjugate symmetric)
        if (std::abs(data[i]) > peakMag) {
            peakMag = std::abs(data[i]);
            peakBin = i;
        }
    }
    EXPECT_EQ(peakBin, targetBin);
}

TEST(Fft, RoundTripsBackToOriginal) {
    constexpr size_t n = 32;
    std::vector<Complex> original(n);
    for (size_t i = 0; i < n; i++) {
        original[i] = Complex(static_cast<float>(i) * 0.1f - 1.5f, static_cast<float>(i % 3) * 0.2f);
    }
    auto roundTripped = original;
    fft(roundTripped, false);
    fft(roundTripped, true);

    for (size_t i = 0; i < n; i++) {
        EXPECT_NEAR(roundTripped[i].real(), original[i].real(), 1e-4f);
        EXPECT_NEAR(roundTripped[i].imag(), original[i].imag(), 1e-4f);
    }
}

TEST(Fft, ConservesEnergyPerParseval) {
    constexpr size_t n = 128;
    std::vector<Complex> data(n);
    double timeDomainEnergy = 0.0;
    for (size_t i = 0; i < n; i++) {
        const float v = std::sin(static_cast<float>(i) * 0.37f) * 0.5f;
        data[i] = Complex(v, 0.0f);
        timeDomainEnergy += static_cast<double>(v) * v;
    }
    fft(data, false);
    double freqDomainEnergy = 0.0;
    for (const auto &c : data) {
        freqDomainEnergy += static_cast<double>(std::norm(c));
    }
    freqDomainEnergy /= static_cast<double>(n);

    EXPECT_NEAR(timeDomainEnergy, freqDomainEnergy, timeDomainEnergy * 0.01 + 1e-6);
}
