#pragma once

#include <cstdint>

namespace songnotes {

// UI/JNI thread -> RT audio thread control messages. Deliberately POD (no
// strings, no containers) so pushing/popping is RT-safe on the consumer
// side. Carried over a 64-slot SpscRingBuffer<Command>.
//
// Only Arm exists today. It's the one control action that genuinely needs
// the RT thread's own authoritative frame counter to schedule against —
// stopping a take, starting the test tone, etc. don't need frame-accurate
// timing and are handled by direct atomic mode stores instead (see
// NativeAudioEngine's stop*/start* methods). Add more CommandTypes here if
// a future phase needs another RT-scheduled action (e.g. Phase 3's
// calibration sweep) rather than inventing a second queue.
enum class CommandType : int32_t {
    None = 0,
    Arm = 1,
};

struct Command {
    CommandType type = CommandType::None;
    double bpm = 100.0;
    int32_t beatsPerBar = 4;
    int32_t countInBeats = 4;
    int64_t preRollFrames = 0;
};

} // namespace songnotes
