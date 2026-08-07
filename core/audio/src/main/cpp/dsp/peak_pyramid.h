#pragma once

#include <cstdint>
#include <vector>

namespace songnotes::dsp {

// One min/max pair over a chunk of raw samples.
struct PeakPair {
    float min;
    float max;
};

// One level of the pyramid: peaks computed over consecutive chunks of
// `samplesPerPeak` raw samples each (the buffer's last chunk may be
// shorter, if `buffer.size()` isn't an exact multiple). Level 0 (the
// finest) is built directly from the raw audio; each subsequent level is
// built by combining adjacent pairs from the level before it (min of the
// two mins, max of the two maxes) — cheap, since it operates on the
// already-reduced peak arrays, not the raw audio again.
struct PeakLevel {
    int32_t samplesPerPeak;
    std::vector<PeakPair> peaks;
};

// Builds a full pyramid from `buffer`. `baseSamplesPerPeak` is level 0's
// chunk size; each following level doubles it. Stops adding levels once a
// level would have fewer than `minPeaksPerLevel` peaks (no point pre-
// computing a level coarser than what a screen could ever usefully show) —
// always returns at least one level (even a single-peak one), unless
// `buffer` is empty, which returns no levels at all.
std::vector<PeakLevel> buildPeakPyramid(const std::vector<float> &buffer, int32_t baseSamplesPerPeak = 256,
                                         int32_t minPeaksPerLevel = 8);

// Picks the coarsest level whose samplesPerPeak is <= samplesPerPixel —
// the least detail that still gives at least one peak per pixel, so
// rendering never scans more data than the screen can actually show.
// Falls back to the finest (first) level if none qualify (samplesPerPixel
// smaller than even level 0's chunk size — a very zoomed-in view, where
// each peak chunk spans multiple pixels and that's the correct look).
// `pyramid` must be non-empty.
const PeakLevel &selectLevelForZoom(const std::vector<PeakLevel> &pyramid, double samplesPerPixel);

} // namespace songnotes::dsp
