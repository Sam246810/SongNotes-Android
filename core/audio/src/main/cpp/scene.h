#pragma once

#include <atomic>
#include <deque>
#include <memory>
#include <vector>

namespace songnotes {

// Immutable per-take playback scene: the samples currently playing back
// through the output stream. Phase 4 adds real multitrack graphs (multiple
// clips, gain/mute/solo); Phase 1 establishes the double-buffered pointer
// pattern at its smallest possible scope — one buffer, or none.
struct Scene {
    std::shared_ptr<const std::vector<float>> playbackBuffer; // mono f32; null = silence
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
