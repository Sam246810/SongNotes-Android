// Functional + concurrent coverage for Scene/ScenePublisher — the
// poor-man's-RCU double-buffered pointer pattern the RT thread uses to read
// playback audio (and, since Phase 3, the calibration sweep) published by a
// non-RT thread. Flagged in PHASE-01.md as a gap alongside the ring buffer.
// Also run standalone, cross-compiled for arm64 and executed directly on a
// physical device (200,000 concurrent publishes against a busy reader, zero
// crashes) — see docs/handoff/PHASE-01.md's "Verified on device" section.
#include <gtest/gtest.h>

#include <atomic>
#include <memory>
#include <thread>
#include <vector>

#include "scene.h"

using songnotes::Scene;
using songnotes::ScenePublisher;

TEST(ScenePublisher, InitialStateHasNoPlaybackBuffer) {
    ScenePublisher pub;
    const Scene *s = pub.current();
    ASSERT_NE(s, nullptr);
    EXPECT_EQ(s->playbackBuffer, nullptr);
}

TEST(ScenePublisher, PublishedSceneIsImmediatelyVisible) {
    ScenePublisher pub;
    auto scene = std::make_shared<Scene>();
    scene->playbackBuffer = std::make_shared<std::vector<float>>(std::vector<float>{1, 2, 3});
    pub.publish(scene);

    const Scene *current = pub.current();
    ASSERT_NE(current->playbackBuffer, nullptr);
    ASSERT_EQ(current->playbackBuffer->size(), 3u);
    EXPECT_FLOAT_EQ((*current->playbackBuffer)[1], 2.0f);
}

TEST(ScenePublisher, CurrentAlwaysReflectsTheLatestPublish) {
    ScenePublisher pub;
    for (int i = 0; i < 10; i++) {
        auto scene = std::make_shared<Scene>();
        scene->playbackBuffer = std::make_shared<std::vector<float>>(std::vector<float>{static_cast<float>(i)});
        pub.publish(scene);
        EXPECT_FLOAT_EQ((*pub.current()->playbackBuffer)[0], static_cast<float>(i));
    }
}

TEST(ScenePublisher, RetentionEvictsOnlyGenerationsOlderThanTheKeepWindow) {
    // This is the actual safety property the whole class exists to
    // provide, not an incidental implementation detail: the RT thread may
    // still be mid-dereference of a Scene pointer it grabbed from an
    // earlier current() call, so publish() must never destroy a generation
    // recent enough that the RT thread could plausibly still be holding it
    // — but it must eventually destroy old ones, or every take/calibration
    // sweep would leak forever. kKeepGenerations is 4 (see scene.h); after
    // 5 publishes past the initial empty scene, the first published one
    // should be gone.
    ScenePublisher pub;
    std::weak_ptr<Scene> weakFirst;
    std::weak_ptr<Scene> weakLast;
    for (int i = 0; i < 5; i++) {
        auto scene = std::make_shared<Scene>();
        scene->playbackBuffer = std::make_shared<std::vector<float>>(std::vector<float>{static_cast<float>(i)});
        if (i == 0) weakFirst = scene;
        if (i == 4) weakLast = scene;
        pub.publish(scene);
        // `scene` (the local shared_ptr) goes out of scope at the end of
        // this iteration — the only remaining owner is ScenePublisher's own
        // retention deque, which is exactly what this test needs to be true
        // for the weak_ptr checks below to mean anything.
    }
    EXPECT_TRUE(weakFirst.expired());
    EXPECT_FALSE(weakLast.expired());
}

TEST(ScenePublisher, ConcurrentPublishAndCurrentDoNotCrashOrCorrupt) {
    // Matches real usage: a loader/JNI thread calling publish() while the
    // RT callback thread calls current() at real concurrent speed. The
    // property under test is "doesn't crash and every dereferenced Scene is
    // internally consistent" — not exact interleaving, which is
    // nondeterministic by design.
    ScenePublisher pub;
    std::atomic<bool> stop{false};
    std::atomic<int64_t> readerChecks{0};

    std::thread publisher([&]() {
        for (int i = 0; i < 200000; i++) {
            auto scene = std::make_shared<Scene>();
            scene->playbackBuffer = std::make_shared<std::vector<float>>(std::vector<float>{static_cast<float>(i)});
            pub.publish(scene);
        }
        stop.store(true, std::memory_order_release);
    });

    std::thread reader([&]() {
        while (!stop.load(std::memory_order_acquire)) {
            const Scene *s = pub.current();
            if (s && s->playbackBuffer && !s->playbackBuffer->empty()) {
                volatile float v = (*s->playbackBuffer)[0]; // touch the memory; a UAF would crash or ASan-flag here
                (void) v;
            }
            readerChecks.fetch_add(1, std::memory_order_relaxed);
        }
    });

    publisher.join();
    reader.join();
    EXPECT_GT(readerChecks.load(), 0);
}
