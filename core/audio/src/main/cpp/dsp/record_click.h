#pragma once

#include <cstdint>

namespace songnotes::dsp {

// Where a take's count-in and metronome clicks go, relative to the frame
// the Arm command is drained at, so the click stays on the PROJECT's beat
// grid (beat k at k * beatIntervalFrames from project frame 0) even when a
// take starts between beats — a voice note's next take starts wherever the
// last one ended. Without this the click restarted on the take's own grid,
// shifted off the bars of everything recorded before.
//
// The count-in counts up to the first grid beat at or after the take's
// start (takeStartFrame, a project frame); recording itself still lands at
// takeStartFrame, so a take starting mid-beat simply begins a little before
// that beat, the way the pre-roll already does.
struct RecordClickPlan {
    int64_t downbeatOffset;   // arm -> the take's first frame (>= preRollFrames)
    int64_t firstClickOffset; // arm -> the first click (>= 0)
    int64_t countInEndOffset; // arm -> the first grid beat at or after the take's start; clicks before it are count-in
    int32_t firstBeatIndexInBar; // the first click's beat in its bar (0 = downbeat accent)
};

RecordClickPlan planRecordClick(int64_t beatIntervalFrames, int32_t beatsPerBar, int32_t countInBeats,
                                int64_t preRollFrames, int64_t takeStartFrame);

} // namespace songnotes::dsp
