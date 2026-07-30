#pragma once

#include <vector>

namespace songnotes::dsp {

// Sums two buffers sample-by-sample (the shorter is implicitly zero-padded
// to the longer's length), then scales down only if the result would
// clip past 1.0 — never scales up a quiet mix. This is Rule A's "one
// pre-mixed buffer": the recorded take and a regenerated reference click
// (see click_track.h) summed offline into a single source, so a flam (two
// independently-scheduled sources drifting apart) is arithmetically
// impossible, not just avoided by discipline.
std::vector<float> mixAndNormalize(const std::vector<float> &a, const std::vector<float> &b);

} // namespace songnotes::dsp
