#pragma once

#include <atomic>
#include <cstdint>

namespace songnotes {

// Written by the audio (RT) thread via relaxed atomic stores; read from
// Kotlin as a direct ByteBuffer (see AudioEngine.kt / EngineState.kt) with
// NO JNI call on the poll path — NewDirectByteBuffer is called exactly once,
// at engine creation.
//
// All fields are deliberately int32_t: a mixed 32/64-bit layout risks a
// torn read on armeabi-v7a, and Phase 1 doesn't need more than ~12 hours of
// frame-count headroom (int32 frames at 48kHz is ~12.4 hours before
// wraparound) — plenty for a single take.
//
// Field order and byte size here MUST match the offsets read by
// core/audio/src/main/java/com/songnotes/core/audio/EngineState.kt exactly.
// There is no generated binding — just two files kept in sync by hand.
struct EngineStateBlock {
    std::atomic<int32_t> isRecording{0};
    std::atomic<int32_t> isPlaying{0};
    std::atomic<int32_t> framesRecorded{0};
    std::atomic<int32_t> playbackFrame{0};
    std::atomic<int32_t> playbackTotalFrames{0};
    std::atomic<int32_t> xRunCount{0};
    std::atomic<int32_t> framesDropped{0};
};

static_assert(sizeof(EngineStateBlock) == 28,
              "EngineStateBlock layout changed — update EngineState.kt's offsets to match");

} // namespace songnotes
