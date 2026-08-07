#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

namespace songnotes::dsp {

// How long, in seconds, a released voice keeps sounding before the engine
// forcibly deactivates it — matches the web app's `triggerNoteOff`, which
// calls `source.stop()` in a `setTimeout` 450ms after release regardless of
// the sample's own natural decay length (the extra 50ms there is JS timer
// slop for the scheduled ramp to actually finish; the C++ engine drives this
// from its own precise frame counter instead, so it doesn't need that
// margin). Exported here — not just a private detail of envelopeAt()'s
// release-ramp math — because `audio_engine.cpp`'s voice pool needs the
// exact same value to know when a released voice is done, and two
// independently-hardcoded 0.4s constants is exactly the kind of thing that
// silently drifts apart during a future edit.
inline constexpr double kPianoReleaseSeconds = 0.4;

// Salamander Grand Piano, velocity 13 — 29 recorded notes spaced a minor
// third (3 semitones) apart from C1 (MIDI 24) to C8 (MIDI 108). Everything
// off this grid is played by pitch-shifting the nearest recorded sample, so
// the maximum stretch is +/-1 semitone. Single source of truth mirrored
// exactly in `:core:domain`'s `PianoVoice.kt` (`PIANO_SAMPLES`) — the JVM
// side is the pure, host-testable-with-no-NDK reference this file is
// verified against (see docs/handoff/PHASE-09.md), so this table and that
// one must never drift apart.
struct PianoSample {
    const char *note;
    int32_t midi;
};

inline constexpr int32_t kPianoSampleCount = 29;
inline constexpr std::array<PianoSample, kPianoSampleCount> kPianoSamples = {{
    {"C1", 24}, {"D#1", 27}, {"F#1", 30}, {"A1", 33},
    {"C2", 36}, {"D#2", 39}, {"F#2", 42}, {"A2", 45},
    {"C3", 48}, {"D#3", 51}, {"F#3", 54}, {"A3", 57},
    {"C4", 60}, {"D#4", 63}, {"F#4", 66}, {"A4", 69},
    {"C5", 72}, {"D#5", 75}, {"F#5", 78}, {"A5", 81},
    {"C6", 84}, {"D#6", 87}, {"F#6", 90}, {"A6", 93},
    {"C7", 96}, {"D#7", 99}, {"F#7", 102}, {"A7", 105},
    {"C8", 108},
}};

// Index into kPianoSamples of the recorded sample closest to `midi` — ties
// broken toward the lower sample (first-found-wins linear scan), matching
// the web app's `dist < minDist` behavior. See PianoVoice.kt's own doc
// comment: this table's odd 3-semitone spacing means an exact tie can never
// actually occur in practice, but the rule is still real, tested behavior.
int32_t nearestSampleIndexFor(int32_t midi);

// The combined rate to read a sample recorded at `sampleFileHz` so `midi`
// sounds at the right pitch on an engine running at `engineSampleRateHz`,
// given the nearest recorded sample's own MIDI number `sampleMidi`. The
// semitone term matches the web app's `Math.pow(2, diff / 12)`; the
// sample-rate term has no web equivalent (the browser always resamples for
// you) and is what keeps a 44.1kHz sample in tune on a 48kHz engine — see
// PianoVoice.kt's doc comment for the full reasoning.
double playbackRateFor(int32_t midi, int32_t sampleMidi, double sampleFileHz, double engineSampleRateHz);

// The voice's gain multiplier at `ageSeconds` since note-on.
// `releaseAgeSeconds < 0` means the note is still held; otherwise it's the
// age at which note-off happened. Ported from the web app's Web Audio gain
// schedule (`PianoPanel.jsx`'s `triggerNoteOn`/`triggerNoteOff`) — see
// PianoVoice.kt's `envelopeAt` doc comment for the exact envelope shape and
// the release-from-true-current-value reasoning. This is the one function
// in this file most worth cross-checking against PianoVoice.kt's own
// GoldenFixture-free but property-tested version, since a wrong constant
// here is a silent "sounds a bit off" bug, not a crash.
double envelopeAt(double ageSeconds, double releaseAgeSeconds = -1.0);

// Linearly-interpolated sample at fractional index `readPos` into
// `buffer[0, bufferLength)`. Returns false (leaving `*outSample`
// untouched) once `readPos` runs past the last interpolatable pair —
// mirrors PianoVoice.kt's `interpolatedSample` returning null for the same
// condition, just without an allocation-implying Optional/nullable return
// on this side.
bool interpolatedSample(const float *buffer, int32_t bufferLength, double readPos, float *outSample);

// Result of renderVoiceInto(): where the voice's read cursor ended up, and
// whether it ran off the end of its sample buffer (the caller deactivates
// the voice when true).
struct VoiceRenderResult {
    double nextReadPos;
    bool exhausted;
};

// Renders one voice's next `numFrames` into `out` (mono), summing — never
// overwriting — so multiple voices layer via repeated calls against the
// same buffer, same contract as PianoVoice.kt's `renderVoiceInto` (this
// function's parameter list mirrors it exactly, on purpose — it's what
// lets a Diagnostics screen call both with identical inputs and assert
// bit-identical output, per docs/handoff/PHASE-09.md's cross-validation
// step). Pure, allocation-free, RT-safe: no I/O, no locks, no logging.
VoiceRenderResult renderVoiceInto(float *out, int32_t numFrames, const float *buffer, int32_t bufferLength,
                                   double startReadPos, double rate, double startAgeSeconds,
                                   double releaseAgeSeconds, double sampleRateHz, float gain);

// One decoded, ready-to-play sample: the audio (owned by the engine's own
// piano bank — see NativeAudioEngine::loadPianoBank in audio_engine.h) plus
// the rate it was recorded/decoded at, which playbackRateFor() needs to
// compute a voice's true combined rate. A bank is a
// `std::vector<PianoSampleBankEntry>` whose index N corresponds to
// `kPianoSamples[N]` — the loader (PianoSampleLoader.kt, via JNI) is
// responsible for keeping that ordering, not this struct.
struct PianoSampleBankEntry {
    std::shared_ptr<const std::vector<float>> buffer;
    double sampleRateHz = 44100.0;
};

} // namespace songnotes::dsp
