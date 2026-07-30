#pragma once

#include <atomic>
#include <deque>
#include <memory>
#include <vector>

#include "dsp/track_mixer.h"

namespace songnotes {

// Immutable per-take playback scene: the samples currently playing back
// through the output stream. Two mutually-exclusive payloads, one per
// playback mode (EngineMode::Playing uses playbackBuffer; ::MultitrackPlaying
// uses multitrack) — both live on the same Scene/ScenePublisher rather than
// each mode getting its own publisher, since a Scene is already just an
// immutable snapshot published atomically and only one mode is ever active
// at a time. Phase 1 established the double-buffered pointer pattern at
// its smallest possible scope (one buffer, or none); Phase 4 is the real
// multitrack graph that comment always pointed at.
struct Scene {
    std::shared_ptr<const std::vector<float>> playbackBuffer; // mono f32; null = silence
    std::shared_ptr<const std::vector<dsp::Track>> multitrack; // null unless MultitrackPlaying
};

// A tiny poor-man's RCU. The audio (RT) thread only ever dereferences the
// raw pointer from current() for the duration of a single onAudioReady call
// — it never holds one across calls. The publishing thread (loader/UI)
// retires old generations by keeping them alive for a few subsequent
// publishes before actually releasing them, which is cheap and correct as
// long as publishes don't happen far faster than audio callbacks return —
// they don't; loading a new take is a human-triggered, sub-Hz event.
//
// publish() must only be called from one thread at a time (the loader
// thread in this phase). current() must only be called from the audio
// callback thread.
class ScenePublisher {
public:
    ScenePublisher() {
        auto initial = std::make_shared<Scene>();
        mCurrent.store(initial.get(), std::memory_order_release);
        mRetired.push_back(std::move(initial));
    }

    void publish(std::shared_ptr<Scene> next) {
        mCurrent.store(next.get(), std::memory_order_release);
        mRetired.push_back(std::move(next));
        constexpr size_t kKeepGenerations = 4;
        while (mRetired.size() > kKeepGenerations) {
            mRetired.pop_front();
        }
    }

    // RT-safe: one atomic load, no allocation, no locks.
    const Scene *current() const { return mCurrent.load(std::memory_order_acquire); }

private:
    std::atomic<Scene *> mCurrent;
    std::deque<std::shared_ptr<Scene>> mRetired; // touched only by the publishing thread
};

} // namespace songnotes
