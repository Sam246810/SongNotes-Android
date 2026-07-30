#include "click_track.h"

#include <algorithm>
#include <cmath>

#include "click.h"

namespace songnotes::dsp {

std::vector<float> renderClickTrack(double sampleRate, double bpm, int32_t beatsPerBar, int64_t totalFrames,
                                     double downbeatHz, double regularHz, double clickLengthSeconds,
                                     float amplitude) {
    std::vector<float> out(static_cast<size_t>(std::max<int64_t>(totalFrames, 0)), 0.0f);
    if (out.empty() || bpm <= 0.0) return out;

    const auto clickLengthFrames = static_cast<int32_t>(std::llround(sampleRate * clickLengthSeconds));
    const auto downbeatTemplate = generateClick(sampleRate, downbeatHz, clickLengthFrames, amplitude);
    const auto regularTemplate = generateClick(sampleRate, regularHz, clickLengthFrames, amplitude);

    const auto beatIntervalFrames = static_cast<int64_t>(std::llround(sampleRate * 60.0 / bpm));
    if (beatIntervalFrames <= 0) return out; // degenerate bpm; caller passed something absurd

    const int32_t safeBeatsPerBar = std::max(1, beatsPerBar);

    for (int64_t beatFrame = 0, beatIndex = 0; beatFrame < totalFrames;
         beatFrame += beatIntervalFrames, beatIndex++) {
        const auto &tmpl = (beatIndex % safeBeatsPerBar == 0) ? downbeatTemplate : regularTemplate;
        const int64_t framesAvailable = totalFrames - beatFrame;
        const auto toCopy = static_cast<size_t>(std::min<int64_t>(static_cast<int64_t>(tmpl.size()), framesAvailable));
        for (size_t i = 0; i < toCopy; i++) {
            out[static_cast<size_t>(beatFrame) + i] += tmpl[i];
        }
    }

    return out;
}

} // namespace songnotes::dsp
