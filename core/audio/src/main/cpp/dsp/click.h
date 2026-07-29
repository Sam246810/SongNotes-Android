#pragma once

#include <cstdint>
#include <vector>

namespace songnotes::dsp {

// Generates a short, percussive metronome click as an exponentially-decaying
// sine burst: click(t) = amplitude * sin(2*pi*frequencyHz*t) * exp(-t/decay).
// Pure function, no I/O, host-testable — the downbeat/regular click
// templates differ only in the frequencyHz passed in (downbeat is pitched
// higher, matching how most metronomes distinguish beat 1).
//
// lengthFrames should be short relative to the beat interval (a few
// milliseconds) — this is a click, not a tone.
std::vector<float> generateClick(double sampleRate, double frequencyHz, int32_t lengthFrames,
                                  float amplitude);

} // namespace songnotes::dsp
