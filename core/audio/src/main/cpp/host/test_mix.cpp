#include <gtest/gtest.h>

#include <cmath>

#include "dsp/mix.h"

using songnotes::dsp::mixAndNormalize;

TEST(Mix, SumsSampleBySample) {
    std::vector<float> a = {0.1f, 0.2f, 0.3f};
    std::vector<float> b = {0.05f, 0.05f, 0.05f};
    auto out = mixAndNormalize(a, b);
    ASSERT_EQ(out.size(), 3u);
    EXPECT_NEAR(out[0], 0.15f, 1e-6f);
    EXPECT_NEAR(out[1], 0.25f, 1e-6f);
    EXPECT_NEAR(out[2], 0.35f, 1e-6f);
}

TEST(Mix, ZeroPadsTheShorterBuffer) {
    std::vector<float> a = {0.1f, 0.2f, 0.3f, 0.4f};
    std::vector<float> b = {0.1f};
    auto out = mixAndNormalize(a, b);
    ASSERT_EQ(out.size(), 4u);
    EXPECT_NEAR(out[0], 0.2f, 1e-6f);
    EXPECT_NEAR(out[1], 0.2f, 1e-6f);
    EXPECT_NEAR(out[3], 0.4f, 1e-6f);
}

TEST(Mix, ScalesDownOnlyWhenSumWouldClip) {
    std::vector<float> a = {0.8f, -0.8f};
    std::vector<float> b = {0.6f, -0.6f};
    // Peak of the raw sum is 1.4 -- must be scaled to exactly 1.0 at the peak.
    auto out = mixAndNormalize(a, b);
    float peak = 0.0f;
    for (float s : out) peak = std::max(peak, std::fabs(s));
    EXPECT_NEAR(peak, 1.0f, 1e-5f);
    // Scaling must be uniform: the ratio between the two samples is preserved.
    EXPECT_NEAR(out[0] / out[1], (0.8f + 0.6f) / (-0.8f - 0.6f), 1e-4f);
}

TEST(Mix, NeverScalesUpAQuietMix) {
    std::vector<float> a = {0.1f, 0.05f};
    std::vector<float> b = {0.05f, 0.05f};
    auto out = mixAndNormalize(a, b);
    EXPECT_NEAR(out[0], 0.15f, 1e-6f);
    EXPECT_NEAR(out[1], 0.10f, 1e-6f);
}

TEST(Mix, HandlesBothEmpty) {
    auto out = mixAndNormalize({}, {});
    EXPECT_TRUE(out.empty());
}
