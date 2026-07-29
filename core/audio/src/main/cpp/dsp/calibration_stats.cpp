#include "calibration_stats.h"

#include <algorithm>
#include <cmath>

namespace songnotes::dsp {

namespace {
double median(std::vector<double> values) { // by value — sorts its own local copy
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const size_t mid = values.size() / 2;
    if (values.size() % 2 == 0) {
        return (values[mid - 1] + values[mid]) / 2.0;
    }
    return values[mid];
}
} // namespace

std::vector<double> rejectOutliersMad(const std::vector<double> &values, double thresholdMads) {
    if (values.size() < 3) return values;

    const double med = median(values);
    std::vector<double> deviations;
    deviations.reserve(values.size());
    for (double v : values) deviations.push_back(std::fabs(v - med));
    const double mad = median(deviations);

    if (mad < 1e-9) {
        return values; // effectively identical — nothing meaningful to reject
    }

    // 1.4826 makes MAD a consistent estimator of standard deviation under
    // normality — the standard scaling constant, not a tuned one.
    const double scaledMad = mad * 1.4826;
    std::vector<double> kept;
    kept.reserve(values.size());
    for (double v : values) {
        if (std::fabs(v - med) <= thresholdMads * scaledMad) {
            kept.push_back(v);
        }
    }
    return kept;
}

double peakToNoiseRatioDb(float peakMagnitude, float noiseFloor) {
    constexpr float kFloor = 1e-9f; // avoids log(0)/div-by-0 on a digital-silence noise floor
    const float safeNoise = std::max(noiseFloor, kFloor);
    const float safePeak = std::max(peakMagnitude, kFloor);
    return 20.0 * std::log10(static_cast<double>(safePeak) / static_cast<double>(safeNoise));
}

} // namespace songnotes::dsp
