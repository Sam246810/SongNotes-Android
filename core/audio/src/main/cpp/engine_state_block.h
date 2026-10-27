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
    std::atomic<int32_t> isArmed{0};              // true during count-in, false once past the downbeat
    std::atomic<int32_t> countInBeatsRemaining{0}; // for the UI's "3, 2, 1..." display
    std::atomic<int32_t> isCalibrating{0};              // Phase 3: sweep playback + loopback capture in flight
    std::atomic<int32_t> calibrationFramesCaptured{0};  // counts up to the capture target, then isCalibrating drops
    // Input level while Armed/Recording, for the scratchpad's meter: peak
    // |sample| x 1000 with a ~300 ms fall, so the UI's 100 ms poll can't
    // miss a short peak. inputClipped latches 1 once any sample of the
    // current take reaches full scale; reset when a take is armed.
    std::atomic<int32_t> inputPeakMilli{0};
    std::atomic<int32_t> inputClipped{0};
};

static_assert(sizeof(EngineStateBlock) == 52,
              "EngineStateBlock layout changed — update EngineState.kt's offsets to match");

} // namespace songnotes
