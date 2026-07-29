#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace songnotes {

// Lock-free single-producer/single-consumer ring buffer. Exactly one thread
// may call write(); exactly one (possibly different) thread may call
// read()/availableToRead(). `capacity` must be a power of two.
//
// Used two ways in this module: SpscRingBuffer<float> carries captured
// audio from the RT callback thread to the writer thread; the same
// template (not duplicated) would carry a command queue if/when one is
// needed for a producer on the UI thread instead.
template <typename T>
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t capacity) : mMask(capacity - 1), mBuffer(capacity) {}

    // Producer only, never blocks, never allocates. Returns the count
    // actually written — on overflow the rest is dropped, never a partial
    // element write.
    size_t write(const T *data, size_t count) {
        const size_t writeIdx = mWriteIndex.load(std::memory_order_relaxed);
        const size_t readIdx = mReadIndex.load(std::memory_order_acquire);
        const size_t free = mBuffer.size() - (writeIdx - readIdx);
        const size_t toWrite = count < free ? count : free;
        for (size_t i = 0; i < toWrite; i++) {
            mBuffer[(writeIdx + i) & mMask] = data[i];
        }
        mWriteIndex.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer only, never blocks. Returns the count actually read.
    size_t read(T *out, size_t count) {
        const size_t readIdx = mReadIndex.load(std::memory_order_relaxed);
        const size_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        const size_t available = writeIdx - readIdx;
        const size_t toRead = count < available ? count : available;
        for (size_t i = 0; i < toRead; i++) {
            out[i] = mBuffer[(readIdx + i) & mMask];
        }
        mReadIndex.store(readIdx + toRead, std::memory_order_release);
        return toRead;
    }

    // Consumer-side only (safe to call from the producer too as a rough
    // estimate, but its precision is only guaranteed for the consumer).
    size_t availableToRead() const {
        return mWriteIndex.load(std::memory_order_acquire) -
               mReadIndex.load(std::memory_order_relaxed);
    }

private:
    const size_t mMask;
    std::vector<T> mBuffer;
    std::atomic<size_t> mWriteIndex{0};
    std::atomic<size_t> mReadIndex{0};
};

} // namespace songnotes
