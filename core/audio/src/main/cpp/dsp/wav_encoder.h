#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace songnotes::dsp {

// Encodes mono/interleaved f32 PCM as a standard 32-bit float WAV file
// (RIFF/WAVE, fmt tag 3 = WAVE_FORMAT_IEEE_FLOAT) — the same layout
// libsndfile/soundfile write for float32 output, so any standard tool can
// read it back. Chosen over 16-bit integer WAV specifically because
// Phase 4's Done criterion is "exported WAV is sample-identical to a JVM
// reference mixer" — int16 quantization would introduce a rounding step
// two independent implementations could disagree on for reasons that have
// nothing to do with whether the actual mixing math agrees; float32
// preserves exactly what mixTracks() produced, no lossy step in between.
//
// Pure and allocating (returns the encoded bytes) — no file I/O here, so
// it's directly host-testable. See writeWavFile() below for the
// file-writing convenience wrapper actually used by the engine.
std::vector<uint8_t> encodeWavFloat32(const std::vector<float> &samples, int32_t sampleRate,
                                       int32_t channelCount = 1);

// Encodes and writes to `path`. Returns false if the file couldn't be
// opened for writing. Not RT-safe (file I/O) — never call from
// onAudioReady; this is for offline mixdown export, called from a normal
// thread.
bool writeWavFile(const std::string &path, const std::vector<float> &samples, int32_t sampleRate,
                   int32_t channelCount = 1);

} // namespace songnotes::dsp
