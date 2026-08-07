#include "peak_pyramid.h"

#include <algorithm>
#include <limits>

namespace songnotes::dsp {

namespace {

PeakLevel buildBaseLevel(const std::vector<float> &buffer, int32_t samplesPerPeak) {
    PeakLevel level;
    level.samplesPerPeak = samplesPerPeak;
    if (buffer.empty()) return level;

    const auto chunkCount =
        static_cast<size_t>((buffer.size() + static_cast<size_t>(samplesPerPeak) - 1) / static_cast<size_t>(samplesPerPeak));
    level.peaks.reserve(chunkCount);

    for (size_t chunkStart = 0; chunkStart < buffer.size(); chunkStart += static_cast<size_t>(samplesPerPeak)) {
        const size_t chunkEnd = std::min(buffer.size(), chunkStart + static_cast<size_t>(samplesPerPeak));
        float chunkMin = std::numeric_limits<float>::max();
        float chunkMax = std::numeric_limits<float>::lowest();
        for (size_t i = chunkStart; i < chunkEnd; i++) {
            chunkMin = std::min(chunkMin, buffer[i]);
            chunkMax = std::max(chunkMax, buffer[i]);
        }
        level.peaks.push_back(PeakPair{chunkMin, chunkMax});
    }
    return level;
}

// Combines adjacent pairs from `prev` — cheap, since it walks the
// already-reduced peak array, not the raw audio again.
PeakLevel buildNextLevel(const PeakLevel &prev) {
    PeakLevel level;
    level.samplesPerPeak = prev.samplesPerPeak * 2;
    level.peaks.reserve((prev.peaks.size() + 1) / 2);

    for (size_t i = 0; i < prev.peaks.size(); i += 2) {
        float combinedMin = prev.peaks[i].min;
        float combinedMax = prev.peaks[i].max;
        if (i + 1 < prev.peaks.size()) {
            combinedMin = std::min(combinedMin, prev.peaks[i + 1].min);
            combinedMax = std::max(combinedMax, prev.peaks[i + 1].max);
        }
        level.peaks.push_back(PeakPair{combinedMin, combinedMax});
    }
    return level;
}

} // namespace

std::vector<PeakLevel> buildPeakPyramid(const std::vector<float> &buffer, int32_t baseSamplesPerPeak,
                                         int32_t minPeaksPerLevel) {
    std::vector<PeakLevel> pyramid;
    if (buffer.empty() || baseSamplesPerPeak < 1) return pyramid;

    pyramid.push_back(buildBaseLevel(buffer, baseSamplesPerPeak));
    while (pyramid.back().peaks.size() >= static_cast<size_t>(std::max(2, minPeaksPerLevel))) {
        pyramid.push_back(buildNextLevel(pyramid.back()));
    }
    return pyramid;
}

const PeakLevel &selectLevelForZoom(const std::vector<PeakLevel> &pyramid, double samplesPerPixel) {
    size_t best = 0;
    for (size_t i = 0; i < pyramid.size(); i++) {
        if (static_cast<double>(pyramid[i].samplesPerPeak) <= samplesPerPixel) best = i;
    }
    return pyramid[best];
}

} // namespace songnotes::dsp
