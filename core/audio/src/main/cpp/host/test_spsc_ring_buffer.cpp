// Functional + concurrent coverage for SpscRingBuffer<T> — the lock-free
// queue carrying captured audio from the RT callback thread to the writer
// thread (and, since Phase 3, the calibration capture path too). Flagged in
// PHASE-01.md as a gap: "No host-side GoogleTest coverage was added for the
// ring buffer... Given this phase is a hard gate per the plan and TSan
// correctness matters a lot here, adding ring-buffer tests... is worth
// doing." The concurrent test below was also run standalone, cross-compiled
// for arm64 and executed directly on a physical device (2,000,000 elements,
// zero drops/duplicates/reordering) — see docs/handoff/PHASE-01.md's
// "Verified on device" section for that run's actual numbers. This file is
// the same coverage, in the form that runs under `ctest` once a desktop
// C++17 compiler is available to build this target.
#include <gtest/gtest.h>

#include <cstdint>
#include <thread>
#include <vector>

#include "spsc_ring_buffer.h"

using songnotes::SpscRingBuffer;

TEST(SpscRingBuffer, WriteThenReadRoundTrips) {
    SpscRingBuffer<float> ring(16);
    float in[5] = {1, 2, 3, 4, 5};
    EXPECT_EQ(ring.write(in, 5), 5u);
    EXPECT_EQ(ring.availableToRead(), 5u);

    float out[5] = {};
    EXPECT_EQ(ring.read(out, 5), 5u);
    for (int i = 0; i < 5; i++) EXPECT_FLOAT_EQ(out[i], in[i]);
    EXPECT_EQ(ring.availableToRead(), 0u);
}

TEST(SpscRingBuffer, WriteCapsAtCapacityRatherThanOverflowing) {
    SpscRingBuffer<float> ring(8);
    float in[10];
    for (int i = 0; i < 10; i++) in[i] = static_cast<float>(i);
    // The excess is silently dropped, not written out of bounds or wrapped
    // over unread data — write() never blocks and never allocates.
    EXPECT_EQ(ring.write(in, 10), 8u);
}

TEST(SpscRingBuffer, ReadReturnsOnlyWhatsActuallyAvailable) {
    SpscRingBuffer<float> ring(8);
    float in[3] = {1, 2, 3};
    ring.write(in, 3);
    float out[10] = {};
    EXPECT_EQ(ring.read(out, 10), 3u);
}

TEST(SpscRingBuffer, StaysCorrectAcrossManyWraparounds) {
    // Capacity of 4 with 3-element writes forces the index to wrap on
    // nearly every cycle — this is where an off-by-one in the `& mMask`
    // indexing would show up as corrupted data, not a crash.
    SpscRingBuffer<float> ring(4);
    for (int cycle = 0; cycle < 1000; cycle++) {
        float in[3] = {static_cast<float>(cycle), static_cast<float>(cycle + 1), static_cast<float>(cycle + 2)};
        ASSERT_EQ(ring.write(in, 3), 3u) << "at cycle " << cycle;
        float out[3] = {};
        ASSERT_EQ(ring.read(out, 3), 3u) << "at cycle " << cycle;
        EXPECT_FLOAT_EQ(out[0], in[0]) << "at cycle " << cycle;
        EXPECT_FLOAT_EQ(out[1], in[1]) << "at cycle " << cycle;
        EXPECT_FLOAT_EQ(out[2], in[2]) << "at cycle " << cycle;
    }
}

TEST(SpscRingBuffer, ClearResetsBothIndices) {
    SpscRingBuffer<float> ring(8);
    float in[3] = {1, 2, 3};
    ring.write(in, 3);
    ring.clear();
    EXPECT_EQ(ring.availableToRead(), 0u);
    float out[3];
    EXPECT_EQ(ring.read(out, 3), 0u);
}

TEST(SpscRingBuffer, ConcurrentProducerConsumerPreservesOrderWithNoLossOrDuplication) {
    // Mirrors real usage: one thread writes continuously (the RT callback),
    // a different thread reads continuously (the writer/calibration-capture
    // consumer), at real concurrent speed rather than the strictly
    // alternating write-then-read of the tests above. This is the test that
    // actually exercises the acquire/release memory ordering between
    // mWriteIndex and mReadIndex — a wrong memory_order here would show up
    // as a torn read or a value appearing before its write is visible, not
    // necessarily a crash, which is exactly why this needs a real
    // multi-threaded run rather than single-threaded reasoning about the
    // code.
    constexpr int64_t kTotal = 2'000'000;
    SpscRingBuffer<int64_t> ring(1024);
    std::vector<int64_t> received;
    received.reserve(static_cast<size_t>(kTotal));

    std::thread producer([&]() {
        int64_t next = 0;
        while (next < kTotal) {
            int64_t val = next;
            if (ring.write(&val, 1) == 1) next++;
        }
    });

    std::thread consumer([&]() {
        int64_t val;
        while (static_cast<int64_t>(received.size()) < kTotal) {
            if (ring.read(&val, 1) == 1) received.push_back(val);
        }
    });

    producer.join();
    consumer.join();

    ASSERT_EQ(static_cast<int64_t>(received.size()), kTotal);
    for (int64_t i = 0; i < kTotal; i++) {
        ASSERT_EQ(received[static_cast<size_t>(i)], i) << "first mismatch at index " << i;
    }
}
