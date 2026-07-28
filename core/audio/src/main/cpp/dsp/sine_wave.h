#pragma once

#include <cstdint>

namespace songnotes::dsp {

// Renders `numFrames` of a sine wave into `out` (interleaved, `channelCount`
// channels, same sample duplicated across channels), continuing from
// `*phase` (radians) and advancing it in place.
//
// Pure function: no I/O, no allocation, no Android/Oboe/JNI dependency.
// Safe to call from a real-time audio thread, and safe to build + unit test
// on a desktop host — see cpp/host/. This is the first, smallest instance of
// the pattern Phase 3's calibration DSP (sweep generation, matched filter)
// will follow at much greater scale: shared pure C++ that both the device
// build and the host GoogleTest build compile unmodified.
void renderSine(float *out, int32_t numFrames, int32_t channelCount, double sampleRate,
                 double frequencyHz, float amplitude, double *phase);

} // namespace songnotes::dsp
