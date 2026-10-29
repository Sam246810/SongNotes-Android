#include <gtest/gtest.h>

#include "dsp/record_click.h"

using songnotes::dsp::planRecordClick;

namespace {
constexpr int64_t kBeat = 28800; // 100 bpm at 48 kHz
constexpr int64_t kPreRoll = 36000;
} // namespace

TEST(RecordClick, FromZeroMatchesTheOldTakeGrid) {
    const auto plan = planRecordClick(kBeat, 4, 4, kPreRoll, 0);
    EXPECT_EQ(plan.downbeatOffset, 4 * kBeat);
    EXPECT_EQ(plan.firstClickOffset, 0);
    EXPECT_EQ(plan.countInEndOffset, 4 * kBeat);
    EXPECT_EQ(plan.firstBeatIndexInBar, 0);
}

TEST(RecordClick, MidBeatStartCountsInToTheNextGridBeat) {
    // A voice note's next take starting 0.3 beats after beat 6 (bar 2, beat 3).
    const int64_t start = 6 * kBeat + kBeat * 3 / 10;
    const auto plan = planRecordClick(kBeat, 4, 4, kPreRoll, start);
    const int64_t gap = 7 * kBeat - start;
    // The count-in ends on grid beat 7 (bar 2, beat 4), gap frames after the take starts.
    EXPECT_EQ(plan.countInEndOffset - plan.downbeatOffset, gap);
    EXPECT_EQ(plan.countInEndOffset - plan.firstClickOffset, 4 * kBeat);
    EXPECT_GE(plan.firstClickOffset, 0);
    // Four count-in beats before beat 7 start on beat 3: the bar's downbeat accent lands on the 2nd click.
    EXPECT_EQ(plan.firstBeatIndexInBar, 3);
}

TEST(RecordClick, OnBeatPunchInKeepsTheBarAccent) {
    // Punching in on beat 3 of bar 2 with a one-bar count-in.
    const auto plan = planRecordClick(kBeat, 4, 4, kPreRoll, 6 * kBeat);
    EXPECT_EQ(plan.countInEndOffset, plan.downbeatOffset);
    EXPECT_EQ(plan.firstBeatIndexInBar, 2);
}

TEST(RecordClick, NoCountInStillClicksOnTheGrid) {
    const int64_t start = 10 * kBeat + 100;
    const auto plan = planRecordClick(kBeat, 4, 0, kPreRoll, start);
    EXPECT_GE(plan.downbeatOffset, kPreRoll);
    // First click is grid beat 11, not the take's own first frame.
    EXPECT_EQ(plan.firstClickOffset - plan.downbeatOffset, 11 * kBeat - start);
    EXPECT_EQ(plan.firstBeatIndexInBar, 3);
}

TEST(RecordClick, PreRollDominatesAShortCountIn) {
    const auto plan = planRecordClick(kBeat, 4, 1, kPreRoll, 0);
    EXPECT_EQ(plan.downbeatOffset, kPreRoll);
    EXPECT_EQ(plan.firstClickOffset, kPreRoll - kBeat);
}

TEST(RecordClick, DegenerateInputsStaySane) {
    const auto plan = planRecordClick(0, 0, -2, kPreRoll, -50);
    EXPECT_GE(plan.firstClickOffset, 0);
    EXPECT_GE(plan.downbeatOffset, kPreRoll);
    EXPECT_EQ(plan.firstBeatIndexInBar, 0);
}
