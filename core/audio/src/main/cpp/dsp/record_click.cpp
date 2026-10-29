#include "dsp/record_click.h"

#include <algorithm>

namespace songnotes::dsp {

RecordClickPlan planRecordClick(int64_t beatIntervalFrames, int32_t beatsPerBar, int32_t countInBeats,
                                int64_t preRollFrames, int64_t takeStartFrame) {
    const int64_t interval = std::max<int64_t>(1, beatIntervalFrames);
    const int32_t bar = std::max(1, beatsPerBar);
    const int64_t start = std::max<int64_t>(0, takeStartFrame);
    const int64_t countIn = std::max(0, countInBeats);

    // First grid beat at or after the take's start, and how far past the start it is.
    const int64_t gridBeat = (start + interval - 1) / interval;
    const int64_t gap = gridBeat * interval - start;

    const int64_t countInFrames = countIn * interval;
    // Far enough out that every count-in click is still ahead of us and
    // the capture start (preRoll before the downbeat) isn't in the past.
    const int64_t downbeat = std::max(countInFrames - gap, preRollFrames);
    const int64_t countInEnd = downbeat + gap;

    RecordClickPlan plan{};
    plan.downbeatOffset = downbeat;
    plan.countInEndOffset = countInEnd;
    plan.firstClickOffset = countInEnd - countInFrames;
    plan.firstBeatIndexInBar = static_cast<int32_t>(((gridBeat - countIn) % bar + bar) % bar);
    return plan;
}

} // namespace songnotes::dsp
