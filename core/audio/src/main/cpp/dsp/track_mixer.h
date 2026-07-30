#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace songnotes::dsp {

// One audio region placed on a track's timeline. `buffer` is the full
// underlying mono f32 source audio and is never mutated or copied by
// trimming — only the metadata below changes, so splitting a clip (see
// punchIn()) is pure bookkeeping, not an audio copy.
//
// startFrame: where this clip's playback begins on the TRACK's shared
//   timeline (frame 0 of the track, not of `buffer`).
// bufferOffsetFrames: where within `buffer` playback starts — nonzero
//   after the clip's head has been trimmed.
// lengthFrames: how many frames actually play, starting at
//   bufferOffsetFrames — may be less than `buffer->size()` after trimming
//   either end.
struct Clip {
    std::shared_ptr<const std::vector<float>> buffer;
    int64_t startFrame = 0;
    int64_t bufferOffsetFrames = 0;
    int64_t lengthFrames = 0;
};

struct Track {
    std::vector<Clip> clips;
    float gain = 1.0f;
    bool muted = false;
    bool soloed = false;
};

// Mixes `tracks` into `out[0, endFrameExclusive - startFrameInclusive)` —
// `out` must already be sized/allocated by the caller to at least that
// many frames; this function performs no allocation of its own, which is
// what makes it safe to call from the RT audio callback (mixTracks() below
// cannot be — it allocates its return vector on every call). Overlapping
// clips within a track sum; no output clipping/limiting is applied (a
// reference mixer wouldn't add one either, and this function's whole
// reason to exist is being bit-for-bit reproducible against one — see
// docs/handoff/PHASE-04.md).
//
// Solo semantics (a judgment call, not specified by the plan — documented
// here since a different reading would break sample-identity against
// whatever the JVM reference mixer assumes): if ANY track is soloed, only
// soloed tracks play, and a soloed track's own `muted` flag is ignored
// while any solo is active (solo overrides mute) — this is the common DAW
// convention, not "the" convention.
//
// This is the ONE mixing implementation meant to back both real-time
// playback (called chunk-at-a-time from onAudioReady, per the plan's
// "same engine path" principle applied here too) and offline mixdown
// (called once with [0, totalFrames), via mixTracks() below) —
// deliberately not two independently-written mixing paths that could
// silently drift apart.
void mixTracksInto(const std::vector<Track> &tracks, int64_t startFrameInclusive, int64_t endFrameExclusive,
                    float *out);

// Allocating convenience wrapper around mixTracksInto() — for offline
// mixdown and host tests, where allocation is fine. Never call this from
// the RT audio callback; use mixTracksInto() with a pre-sized scratch
// buffer there instead.
std::vector<float> mixTracks(const std::vector<Track> &tracks, int64_t startFrameInclusive,
                              int64_t endFrameExclusive);

// Returns a new clip list with `insertClip` punched into `existingClips`:
// any existing clip overlapping [insertClip.startFrame,
// insertClip.startFrame + insertClip.lengthFrames) is trimmed (or, if
// entirely within the punched range, dropped) so nothing else plays in
// that range — everything outside it is untouched. A clip that straddles
// the punch range on both sides is split into a head fragment and a tail
// fragment, both trimmed via metadata only (see `Clip`'s own comment —
// no audio data is copied). Result is sorted by startFrame for
// readability; mixTracks() doesn't care about clip order.
std::vector<Clip> punchIn(const std::vector<Clip> &existingClips, const Clip &insertClip);

} // namespace songnotes::dsp
