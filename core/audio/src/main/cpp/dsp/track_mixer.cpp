#include "track_mixer.h"

#include <algorithm>

namespace songnotes::dsp {

std::vector<float> mixTracks(const std::vector<Track> &tracks, int64_t startFrameInclusive,
                              int64_t endFrameExclusive) {
    const int64_t length = std::max<int64_t>(0, endFrameExclusive - startFrameInclusive);
    std::vector<float> out(static_cast<size_t>(length), 0.0f);
    if (length == 0) return out;

    const bool anySoloed = std::any_of(tracks.begin(), tracks.end(), [](const Track &t) { return t.soloed; });

    for (const auto &track : tracks) {
        const bool audible = anySoloed ? track.soloed : !track.muted;
        if (!audible) continue;

        for (const auto &clip : track.clips) {
            if (!clip.buffer || clip.lengthFrames <= 0) continue;

            const int64_t clipEnd = clip.startFrame + clip.lengthFrames; // exclusive, track timeline
            const int64_t overlapStart = std::max(startFrameInclusive, clip.startFrame);
            const int64_t overlapEnd = std::min(endFrameExclusive, clipEnd);
            if (overlapStart >= overlapEnd) continue;

            for (int64_t frame = overlapStart; frame < overlapEnd; frame++) {
                const auto outIdx = static_cast<size_t>(frame - startFrameInclusive);
                const int64_t framesIntoClip = frame - clip.startFrame;
                const auto bufIdx = static_cast<size_t>(clip.bufferOffsetFrames + framesIntoClip);
                if (bufIdx >= clip.buffer->size()) continue; // defensive; shouldn't happen if lengthFrames is honest
                out[outIdx] += (*clip.buffer)[bufIdx] * track.gain;
            }
        }
    }

    return out;
}

std::vector<Clip> punchIn(const std::vector<Clip> &existingClips, const Clip &insertClip) {
    std::vector<Clip> result;
    const int64_t insertStart = insertClip.startFrame;
    const int64_t insertEnd = insertClip.startFrame + insertClip.lengthFrames;

    for (const auto &clip : existingClips) {
        const int64_t clipStart = clip.startFrame;
        const int64_t clipEnd = clip.startFrame + clip.lengthFrames;

        if (clipEnd <= insertStart || clipStart >= insertEnd) {
            result.push_back(clip); // no overlap at all
            continue;
        }
        if (clipStart < insertStart) {
            // Portion before the punch survives — trim the tail.
            Clip head = clip;
            head.lengthFrames = insertStart - clipStart;
            if (head.lengthFrames > 0) result.push_back(head);
        }
        if (clipEnd > insertEnd) {
            // Portion after the punch survives — trim the head. Metadata
            // only: bufferOffsetFrames advances by exactly how many frames
            // got cut from the front, `buffer` itself is untouched.
            Clip tail = clip;
            const int64_t trimmedFrames = insertEnd - clipStart;
            tail.startFrame = insertEnd;
            tail.bufferOffsetFrames = clip.bufferOffsetFrames + trimmedFrames;
            tail.lengthFrames = clipEnd - insertEnd;
            if (tail.lengthFrames > 0) result.push_back(tail);
        }
        // Else: clip is entirely within the punch range — dropped (neither
        // branch above fires).
    }

    result.push_back(insertClip);
    std::sort(result.begin(), result.end(),
              [](const Clip &a, const Clip &b) { return a.startFrame < b.startFrame; });
    return result;
}

} // namespace songnotes::dsp
