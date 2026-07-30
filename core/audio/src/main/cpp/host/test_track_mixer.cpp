#include <gtest/gtest.h>

#include <memory>

#include "dsp/track_mixer.h"

using songnotes::dsp::Clip;
using songnotes::dsp::mixTracks;
using songnotes::dsp::mixTracksInto;
using songnotes::dsp::punchIn;
using songnotes::dsp::Track;

namespace {

std::shared_ptr<const std::vector<float>> makeBuffer(std::vector<float> values) {
    return std::make_shared<const std::vector<float>>(std::move(values));
}

Clip fullClip(std::shared_ptr<const std::vector<float>> buffer, int64_t startFrame) {
    Clip c;
    c.buffer = buffer;
    c.startFrame = startFrame;
    c.bufferOffsetFrames = 0;
    c.lengthFrames = static_cast<int64_t>(buffer->size());
    return c;
}

} // namespace

TEST(TrackMixer, SingleTrackSingleClipPassesThrough) {
    Track track;
    track.clips.push_back(fullClip(makeBuffer({1.0f, 2.0f, 3.0f}), 0));
    auto mixed = mixTracks({track}, 0, 3);
    ASSERT_EQ(mixed.size(), 3u);
    EXPECT_FLOAT_EQ(mixed[0], 1.0f);
    EXPECT_FLOAT_EQ(mixed[1], 2.0f);
    EXPECT_FLOAT_EQ(mixed[2], 3.0f);
}

TEST(TrackMixer, MultipleTracksSum) {
    Track a;
    a.clips.push_back(fullClip(makeBuffer({1.0f, 1.0f}), 0));
    Track b;
    b.clips.push_back(fullClip(makeBuffer({0.5f, 0.5f}), 0));
    auto mixed = mixTracks({a, b}, 0, 2);
    EXPECT_FLOAT_EQ(mixed[0], 1.5f);
    EXPECT_FLOAT_EQ(mixed[1], 1.5f);
}

TEST(TrackMixer, OverlappingClipsWithinATrackSum) {
    Track track;
    track.clips.push_back(fullClip(makeBuffer({1.0f, 1.0f, 1.0f}), 0));
    track.clips.push_back(fullClip(makeBuffer({2.0f, 2.0f}), 1)); // overlaps frames 1-2
    auto mixed = mixTracks({track}, 0, 3);
    EXPECT_FLOAT_EQ(mixed[0], 1.0f);
    EXPECT_FLOAT_EQ(mixed[1], 3.0f);
    EXPECT_FLOAT_EQ(mixed[2], 3.0f);
}

TEST(TrackMixer, GainScalesTheTrack) {
    Track track;
    track.gain = 0.5f;
    track.clips.push_back(fullClip(makeBuffer({2.0f, 4.0f}), 0));
    auto mixed = mixTracks({track}, 0, 2);
    EXPECT_FLOAT_EQ(mixed[0], 1.0f);
    EXPECT_FLOAT_EQ(mixed[1], 2.0f);
}

TEST(TrackMixer, MutedTrackContributesNothing) {
    Track track;
    track.muted = true;
    track.clips.push_back(fullClip(makeBuffer({5.0f, 5.0f}), 0));
    auto mixed = mixTracks({track}, 0, 2);
    EXPECT_FLOAT_EQ(mixed[0], 0.0f);
    EXPECT_FLOAT_EQ(mixed[1], 0.0f);
}

TEST(TrackMixer, SoloSilencesNonSoloedTracks) {
    Track a;
    a.soloed = true;
    a.clips.push_back(fullClip(makeBuffer({1.0f}), 0));
    Track b; // not soloed, not muted -- should still be silenced since a is soloed
    b.clips.push_back(fullClip(makeBuffer({1.0f}), 0));
    auto mixed = mixTracks({a, b}, 0, 1);
    EXPECT_FLOAT_EQ(mixed[0], 1.0f); // only a's contribution
}

TEST(TrackMixer, SoloOverridesTheSoloedTracksOwnMute) {
    Track track;
    track.soloed = true;
    track.muted = true; // documented convention: solo overrides mute
    track.clips.push_back(fullClip(makeBuffer({7.0f}), 0));
    auto mixed = mixTracks({track}, 0, 1);
    EXPECT_FLOAT_EQ(mixed[0], 7.0f);
}

TEST(TrackMixer, RequestedWindowNarrowerThanClipReturnsOnlyThatSlice) {
    Track track;
    track.clips.push_back(fullClip(makeBuffer({1.0f, 2.0f, 3.0f, 4.0f, 5.0f}), 0));
    auto mixed = mixTracks({track}, 2, 4);
    ASSERT_EQ(mixed.size(), 2u);
    EXPECT_FLOAT_EQ(mixed[0], 3.0f);
    EXPECT_FLOAT_EQ(mixed[1], 4.0f);
}

TEST(TrackMixer, ChunkedMixingMatchesWholeBufferMixing) {
    // The property real-time playback depends on: mixing the same tracks
    // callback-sized chunk at a time must produce byte-identical output to
    // mixing the whole range in one call — otherwise real-time playback and
    // offline mixdown (Phase 4's actual Done criterion) could silently
    // disagree.
    Track a;
    a.gain = 0.7f;
    a.clips.push_back(fullClip(makeBuffer({1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), 3));
    Track b;
    b.clips.push_back(fullClip(makeBuffer({-1, -2, -3, -4, -5}), 0));
    b.clips.push_back(fullClip(makeBuffer({0.5f, 0.5f, 0.5f}), 10));
    std::vector<Track> tracks = {a, b};

    constexpr int64_t kTotalFrames = 20;
    auto wholeBuffer = mixTracks(tracks, 0, kTotalFrames);
    ASSERT_EQ(wholeBuffer.size(), static_cast<size_t>(kTotalFrames));

    std::vector<float> chunked;
    constexpr int64_t kChunkSize = 3; // deliberately doesn't divide kTotalFrames evenly
    for (int64_t start = 0; start < kTotalFrames; start += kChunkSize) {
        const int64_t end = std::min(start + kChunkSize, kTotalFrames);
        auto chunk = mixTracks(tracks, start, end);
        chunked.insert(chunked.end(), chunk.begin(), chunk.end());
    }

    ASSERT_EQ(chunked.size(), wholeBuffer.size());
    for (size_t i = 0; i < wholeBuffer.size(); i++) {
        EXPECT_FLOAT_EQ(chunked[i], wholeBuffer[i]) << "mismatch at frame " << i;
    }
}

TEST(TrackMixer, NonAllocatingVariantMatchesAllocatingVariant) {
    // mixTracks() is a thin wrapper around mixTracksInto() -- this pins
    // that relationship down explicitly, since mixTracksInto() is the one
    // actually safe to call from the RT audio callback (no internal
    // allocation) and it's important the two never quietly diverge.
    Track track;
    track.clips.push_back(fullClip(makeBuffer({1.0f, 2.0f, 3.0f, 4.0f}), 0));
    auto viaAllocating = mixTracks({track}, 0, 4);

    std::vector<float> viaNonAllocating(4, -999.0f); // poisoned, to prove it gets fully overwritten
    mixTracksInto({track}, 0, 4, viaNonAllocating.data());

    ASSERT_EQ(viaAllocating.size(), viaNonAllocating.size());
    for (size_t i = 0; i < viaAllocating.size(); i++) {
        EXPECT_FLOAT_EQ(viaAllocating[i], viaNonAllocating[i]) << "mismatch at frame " << i;
    }
}

TEST(TrackMixer, PunchInLeavesNonOverlappingClipsUntouched) {
    std::vector<Clip> existing = {fullClip(makeBuffer({1, 1}), 0), fullClip(makeBuffer({2, 2}), 100)};
    Clip insert = fullClip(makeBuffer({9, 9}), 50);
    auto result = punchIn(existing, insert);
    ASSERT_EQ(result.size(), 3u);
    // Sorted by startFrame: original[0] (0), insert (50), original[1] (100).
    EXPECT_EQ(result[0].startFrame, 0);
    EXPECT_EQ(result[0].lengthFrames, 2);
    EXPECT_EQ(result[1].startFrame, 50);
    EXPECT_EQ(result[2].startFrame, 100);
    EXPECT_EQ(result[2].lengthFrames, 2);
}

TEST(TrackMixer, PunchInDropsClipEntirelyWithinTheRange) {
    std::vector<Clip> existing = {fullClip(makeBuffer({1, 1, 1}), 10)}; // frames [10, 13)
    Clip insert = fullClip(makeBuffer(std::vector<float>(8, 9.0f)), 8); // frames [8, 16), fully covers [10, 13)
    auto result = punchIn(existing, insert);
    ASSERT_EQ(result.size(), 1u);
    EXPECT_EQ(result[0].startFrame, 8); // only the insert survives
}

TEST(TrackMixer, PunchInTrimsClipThatExtendsBeforeThePunch) {
    // Existing clip spans [0, 10). Punch covers [5, 15).
    std::vector<Clip> existing = {fullClip(makeBuffer(std::vector<float>(10, 1.0f)), 0)};
    Clip insert = fullClip(makeBuffer(std::vector<float>(10, 9.0f)), 5);
    auto result = punchIn(existing, insert);
    ASSERT_EQ(result.size(), 2u);
    // Head fragment: [0, 5) -- trimmed tail, bufferOffset unchanged.
    EXPECT_EQ(result[0].startFrame, 0);
    EXPECT_EQ(result[0].bufferOffsetFrames, 0);
    EXPECT_EQ(result[0].lengthFrames, 5);
    EXPECT_EQ(result[1].startFrame, 5); // the insert
}

TEST(TrackMixer, PunchInTrimsClipThatExtendsAfterThePunch) {
    // Existing clip spans [0, 10). Punch covers [-5, 5).
    std::vector<Clip> existing = {fullClip(makeBuffer(std::vector<float>(10, 1.0f)), 0)};
    Clip insert = fullClip(makeBuffer(std::vector<float>(10, 9.0f)), -5);
    auto result = punchIn(existing, insert);
    ASSERT_EQ(result.size(), 2u);
    EXPECT_EQ(result[0].startFrame, -5); // the insert, sorts first
    // Tail fragment: [5, 10) -- head trimmed, bufferOffset advances by 5.
    EXPECT_EQ(result[1].startFrame, 5);
    EXPECT_EQ(result[1].bufferOffsetFrames, 5);
    EXPECT_EQ(result[1].lengthFrames, 5);
}

TEST(TrackMixer, PunchInSplitsAClipThatStraddlesBothSides) {
    // Existing clip spans [0, 20). Punch covers [8, 12) -- entirely inside.
    std::vector<Clip> existing = {fullClip(makeBuffer(std::vector<float>(20, 1.0f)), 0)};
    Clip insert = fullClip(makeBuffer(std::vector<float>(4, 9.0f)), 8);
    auto result = punchIn(existing, insert);
    ASSERT_EQ(result.size(), 3u);
    // Head [0, 8), insert [8, 12), tail [12, 20) with bufferOffset 12.
    EXPECT_EQ(result[0].startFrame, 0);
    EXPECT_EQ(result[0].lengthFrames, 8);
    EXPECT_EQ(result[1].startFrame, 8);
    EXPECT_EQ(result[1].lengthFrames, 4);
    EXPECT_EQ(result[2].startFrame, 12);
    EXPECT_EQ(result[2].bufferOffsetFrames, 12);
    EXPECT_EQ(result[2].lengthFrames, 8);
}

TEST(TrackMixer, PunchInResultMixesCorrectlyBeforeDuringAndAfter) {
    // End-to-end: punch in a loud clip over the middle of a quiet one, then
    // confirm the MIX (not just the clip metadata) reads quiet-loud-quiet.
    std::vector<Clip> existing = {fullClip(makeBuffer(std::vector<float>(9, 1.0f)), 0)};
    Clip insert = fullClip(makeBuffer(std::vector<float>(3, 9.0f)), 3);
    auto punched = punchIn(existing, insert);

    Track track;
    track.clips = punched;
    auto mixed = mixTracks({track}, 0, 9);
    ASSERT_EQ(mixed.size(), 9u);
    for (int i = 0; i < 3; i++) EXPECT_FLOAT_EQ(mixed[i], 1.0f) << i;
    for (int i = 3; i < 6; i++) EXPECT_FLOAT_EQ(mixed[i], 9.0f) << i;
    for (int i = 6; i < 9; i++) EXPECT_FLOAT_EQ(mixed[i], 1.0f) << i;
}
