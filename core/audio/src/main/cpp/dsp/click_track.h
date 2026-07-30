#pragma once

#include <cstdint>
#include <vector>

namespace songnotes::dsp {

// Renders a full click track into a buffer of exactly totalFrames length: a
// downbeat click at frame 0, beatsPerBar-1 regular clicks per bar
// thereafter, repeating every round(sampleRate * 60 / bpm) frames, for as
// many beats as fit within totalFrames — silence elsewhere. This is the
// pure/offline equivalent of the click-scheduling logic
// audio_engine.cpp's onAudioReady runs live during Armed/Recording, needed
// because Rule A's verification playback mixes a reference click against
// an already-recorded take *offline*, into one buffer, rather than
// replaying two independently-scheduled live sources (a live click replayed
// against pre-recorded audio is exactly the two-source drift the rule
// exists to make arithmetically impossible).
//
// bpm <= 0 renders silence rather than dividing by zero or spinning.
std::vector<float> renderClickTrack(double sampleRate, double bpm, int32_t beatsPerBar, int64_t totalFrames,
                                     double downbeatHz, double regularHz, double clickLengthSeconds,
                                     float amplitude);

} // namespace songnotes::dsp
