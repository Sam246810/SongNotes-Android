#include <gtest/gtest.h>

#include <cmath>

#include "dsp/calibration_stats.h"

using songnotes::dsp::peakToNoiseRatioDb;
using songnotes::dsp::rejectOutliersMad;

TEST(RejectOutliersMad, KeepsAllValuesWhenFewerThanThree) {
    std::vector<double> values = {1.0, 100.0};
    auto result = rejectOutliersMad(values);
    EXPECT_EQ(result.size(), 2u);
}

TEST(RejectOutliersMad, KeepsAllValuesWhenIdentical) {
    std::vector<double> values = {5.0, 5.0, 5.0, 5.0};
    auto result = rejectOutliersMad(values);
    EXPECT_EQ(result.size(), 4u);
}

TEST(RejectOutliersMad, DropsAClearOutlierAmongTightMeasurements) {
    // Five sweep reps clustered tightly around 42ms, one wildly off —
    // exactly the "a noise burst corrupted one rep" scenario this exists
    // for.
    std::vector<double> values = {42.0, 42.1, 41.9, 42.2, 41.8, 90.0};
    auto result = rejectOutliersMad(values);
    EXPECT_EQ(result.size(), 5u);
    for (double v : result) {
        EXPECT_NEAR(v, 42.0, 1.0);
    }
}

TEST(RejectOutliersMad, KeepsEverythingWhenGenuinelySpreadOut) {
    // No tight cluster to define an "outlier" against — MAD rejection
    // shouldn't invent one.
    std::vector<double> values = {10.0, 20.0, 30.0, 40.0, 50.0};
    auto result = rejectOutliersMad(values, 3.0);
    EXPECT_EQ(result.size(), values.size());
}

TEST(PeakToNoiseRatioDb, ReturnsZeroWhenPeakEqualsNoise) {
    EXPECT_NEAR(peakToNoiseRatioDb(1.0f, 1.0f), 0.0, 1e-4);
}

TEST(PeakToNoiseRatioDb, TenXPeakIsTwentyDb) {
    EXPECT_NEAR(peakToNoiseRatioDb(10.0f, 1.0f), 20.0, 1e-3);
}

TEST(PeakToNoiseRatioDb, HandlesZeroNoiseFloorWithoutDivByZero) {
    const double db = peakToNoiseRatioDb(1.0f, 0.0f);
    EXPECT_GT(db, 100.0); // effectively "infinite" — just must not be NaN/inf
    EXPECT_FALSE(std::isnan(db));
    EXPECT_FALSE(std::isinf(db));
}
