#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/peak_pyramid.h"

using songnotes::dsp::buildPeakPyramid;
using songnotes::dsp::selectLevelForZoom;

TEST(PeakPyramid, EmptyBufferProducesNoLevels) {
    std::vector<float> buffer;
    auto pyramid = buildPeakPyramid(buffer, 4, 2);
    EXPECT_TRUE(pyramid.empty());
}

TEST(PeakPyramid, BaseLevelChunksExactMultipleCorrectly) {
    // 8 samples, chunk size 4 -> exactly 2 chunks.
    std::vector<float> buffer = {1.0f, -2.0f, 3.0f, 0.0f, 5.0f, 5.0f, -9.0f, 1.0f};
    auto pyramid = buildPeakPyramid(buffer, /*baseSamplesPerPeak=*/4, /*minPeaksPerLevel=*/2);
    ASSERT_FALSE(pyramid.empty());
    const auto &base = pyramid.front();
    EXPECT_EQ(base.samplesPerPeak, 4);
    ASSERT_EQ(base.peaks.size(), 2u);
    EXPECT_FLOAT_EQ(base.peaks[0].min, -2.0f);
    EXPECT_FLOAT_EQ(base.peaks[0].max, 3.0f);
    EXPECT_FLOAT_EQ(base.peaks[1].min, -9.0f);
    EXPECT_FLOAT_EQ(base.peaks[1].max, 5.0f);
}

TEST(PeakPyramid, BaseLevelHandlesAShorterFinalChunk) {
    // 5 samples, chunk size 4 -> chunk 0 has 4 samples, chunk 1 has 1.
    std::vector<float> buffer = {0.0f, 1.0f, 2.0f, 3.0f, 100.0f};
    auto pyramid = buildPeakPyramid(buffer, 4, 2);
    const auto &base = pyramid.front();
    ASSERT_EQ(base.peaks.size(), 2u);
    EXPECT_FLOAT_EQ(base.peaks[0].min, 0.0f);
    EXPECT_FLOAT_EQ(base.peaks[0].max, 3.0f);
    EXPECT_FLOAT_EQ(base.peaks[1].min, 100.0f);
    EXPECT_FLOAT_EQ(base.peaks[1].max, 100.0f);
}

TEST(PeakPyramid, HigherLevelsCombineAdjacentPairsAndDoubleSamplesPerPeak) {
    // 16 samples, base chunk size 2 -> 8 base peaks; next level combines
    // pairs of those 8 into 4, doubling samplesPerPeak to 4 each time.
    std::vector<float> buffer(16);
    for (size_t i = 0; i < buffer.size(); i++) buffer[i] = static_cast<float>(i) - 8.0f; // -8..7
    auto pyramid = buildPeakPyramid(buffer, /*baseSamplesPerPeak=*/2, /*minPeaksPerLevel=*/2);

    ASSERT_GE(pyramid.size(), 2u);
    EXPECT_EQ(pyramid[0].samplesPerPeak, 2);
    EXPECT_EQ(pyramid[0].peaks.size(), 8u);
    EXPECT_EQ(pyramid[1].samplesPerPeak, 4);
    EXPECT_EQ(pyramid[1].peaks.size(), 4u);
    // Level 1's first peak combines level 0's peaks[0] and peaks[1].
    EXPECT_FLOAT_EQ(pyramid[1].peaks[0].min, std::min(pyramid[0].peaks[0].min, pyramid[0].peaks[1].min));
    EXPECT_FLOAT_EQ(pyramid[1].peaks[0].max, std::max(pyramid[0].peaks[0].max, pyramid[0].peaks[1].max));
}

TEST(PeakPyramid, HigherLevelHandlesAnOddNumberOfPairsFromTheLevelBelow) {
    // Base level with an odd peak count (5) -- the next level's last pair
    // has no partner and should just carry that single peak through
    // unchanged, not crash or drop it.
    std::vector<float> buffer(10); // 10 samples, chunk size 2 -> 5 base peaks
    for (size_t i = 0; i < buffer.size(); i++) buffer[i] = static_cast<float>(i);
    auto pyramid = buildPeakPyramid(buffer, 2, 2);
    ASSERT_GE(pyramid.size(), 2u);
    ASSERT_EQ(pyramid[0].peaks.size(), 5u);
    ASSERT_EQ(pyramid[1].peaks.size(), 3u); // ceil(5/2)
    EXPECT_FLOAT_EQ(pyramid[1].peaks[2].min, pyramid[0].peaks[4].min);
    EXPECT_FLOAT_EQ(pyramid[1].peaks[2].max, pyramid[0].peaks[4].max);
}

TEST(PeakPyramid, StopsAddingLevelsOnceBelowMinPeaksPerLevel) {
    // 64 samples, base chunk 2 -> 32 base peaks. With minPeaksPerLevel=8:
    // 32 -> 16 -> 8 -> 4 (4 < 8, stop after adding it).
    std::vector<float> buffer(64, 0.0f);
    auto pyramid = buildPeakPyramid(buffer, 2, 8);
    std::vector<size_t> peakCounts;
    for (const auto &level : pyramid) peakCounts.push_back(level.peaks.size());
    ASSERT_EQ(peakCounts.size(), 4u);
    EXPECT_EQ(peakCounts, (std::vector<size_t>{32, 16, 8, 4}));
}

TEST(PeakPyramid, ShortBufferProducesOnlyTheBaseLevel) {
    // Fewer chunks than minPeaksPerLevel to begin with -- no refinement possible.
    std::vector<float> buffer(6, 1.0f); // chunk size 2 -> 3 base peaks
    auto pyramid = buildPeakPyramid(buffer, 2, 8);
    EXPECT_EQ(pyramid.size(), 1u);
    EXPECT_EQ(pyramid.front().peaks.size(), 3u);
}

TEST(PeakPyramid, SelectLevelForZoomPicksTheCoarsestLevelThatStillFitsThePixelBudget) {
    std::vector<float> buffer(1024, 0.0f);
    auto pyramid = buildPeakPyramid(buffer, 2, 2); // samplesPerPeak: 2, 4, 8, 16, ...

    EXPECT_EQ(selectLevelForZoom(pyramid, 1.0).samplesPerPeak, 2); // very zoomed in -- finest level
    EXPECT_EQ(selectLevelForZoom(pyramid, 4.0).samplesPerPeak, 4);
    EXPECT_EQ(selectLevelForZoom(pyramid, 4.5).samplesPerPeak, 4); // between two levels -- picks the finer of the two that still fits
    EXPECT_EQ(selectLevelForZoom(pyramid, 15.0).samplesPerPeak, 8);
}

TEST(PeakPyramid, SelectLevelForZoomFallsBackToFinestLevelWhenNothingQualifies) {
    std::vector<float> buffer(64, 0.0f);
    auto pyramid = buildPeakPyramid(buffer, 16, 2); // finest level already coarser than a tiny pixel budget
    EXPECT_EQ(&selectLevelForZoom(pyramid, 0.1), &pyramid.front());
}
