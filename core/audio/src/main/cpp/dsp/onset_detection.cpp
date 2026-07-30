#include "onset_detection.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace songnotes::dsp {

std::vector<double> detectOnsets(const std::vector<float> &pcm, double sampleRate, double minGapSec,
                                  double thresholdRatio, double windowSec) {
    if (pcm.empty()) return {};

    const int64_t win = std::max<int64_t>(1, std::llround(windowSec * sampleRate));
    const int64_t minGap = std::max<int64_t>(1, std::llround(minGapSec * sampleRate));
    const auto n = static_cast<int64_t>(pcm.size());

    // Short-window RMS energy envelope via a running sum of squares —
    // same running-accumulator shape as the JS version, avoiding an O(N*win)
    // recompute per sample.
    std::vector<float> env(pcm.size());
    double acc = 0.0;
    for (int64_t i = 0; i < n; i++) {
        const double v = pcm[static_cast<size_t>(i)];
        acc += v * v;
        if (i >= win) {
            const double old = pcm[static_cast<size_t>(i - win)];
            acc -= old * old;
        }
        env[static_cast<size_t>(i)] = static_cast<float>(std::sqrt(acc / static_cast<double>(win)));
    }

    float peak = 0.0f;
    for (const float e : env) peak = std::max(peak, e);
    if (peak <= 0.0f) return {};

    const float threshold = peak * static_cast<float>(thresholdRatio);
    std::vector<double> onsets;
    int64_t lastOnset = std::numeric_limits<int64_t>::min() / 2; // avoids overflow in `i - lastOnset`
    for (int64_t i = 0; i < n; i++) {
        if (env[static_cast<size_t>(i)] >= threshold && i - lastOnset >= minGap) {
            onsets.push_back(static_cast<double>(i) / sampleRate);
            lastOnset = i;
        }
    }
    return onsets;
}

std::optional<double> estimateLatencyFromOnsets(const std::vector<double> &detectedTimes,
                                                 const std::vector<double> &scheduledTimes,
                                                 double maxMatchSec) {
    if (detectedTimes.empty() || scheduledTimes.empty()) return std::nullopt;

    std::vector<double> deltas;
    for (const double scheduled : scheduledTimes) {
        std::optional<double> best;
        for (const double onset : detectedTimes) {
            const double d = onset - scheduled;
            if (d >= -0.005 && d <= maxMatchSec) {
                if (!best.has_value() || d < *best) best = d;
            }
        }
        if (best.has_value()) deltas.push_back(std::max(0.0, *best));
    }

    const auto required = static_cast<size_t>(
        std::max<int64_t>(2, (static_cast<int64_t>(scheduledTimes.size()) + 1) / 2));
    if (deltas.size() < required) return std::nullopt;

    std::sort(deltas.begin(), deltas.end());
    const size_t mid = deltas.size() / 2;
    if (deltas.size() % 2 == 0) {
        return (deltas[mid - 1] + deltas[mid]) / 2.0;
    }
    return deltas[mid];
}

} // namespace songnotes::dsp
