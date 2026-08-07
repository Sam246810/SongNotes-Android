#pragma once

#include <cstdint>

namespace songnotes {

// UI/JNI thread -> RT audio thread control messages. Deliberately POD (no
// strings, no containers) so pushing/popping is RT-safe on the consumer
// side. Carried over a 64-slot SpscRingBuffer<Command>.
//
// Arm needs the RT thread's own authoritative frame counter to schedule
// count-in/downbeat against — stopping a take, starting the test tone, etc.
// don't need frame-accurate timing and are handled by direct atomic mode
// stores instead (see NativeAudioEngine's stop*/start* methods).
//
// PianoNoteOn/PianoNoteOff (Phase 9) need the same thing for a different
// reason: a voice's envelope age is measured from the RT thread's own
// mStreamFrameCounter at the instant the note actually starts/releases, not
// from whenever the JNI/UI thread happened to call in — for a fast trill,
// that gap is audible. This is exactly the "add more CommandTypes here"
// case this comment used to just gesture at.
enum class CommandType : int32_t {
    None = 0,
    Arm = 1,
    PianoNoteOn = 2,
    PianoNoteOff = 3,
};

struct Command {
    CommandType type = CommandType::None;
    double bpm = 100.0;
    int32_t beatsPerBar = 4;
    int32_t countInBeats = 4;
    int64_t preRollFrames = 0;
    int32_t midiNote = -1; // PianoNoteOn/PianoNoteOff only
};

} // namespace songnotes
