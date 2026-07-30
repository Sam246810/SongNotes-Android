#pragma once

#include <optional>
#include <vector>

namespace songnotes::dsp {

// Detects transient onset times (seconds) in a mono PCM buffer using a
// short-window RMS energy envelope with a refractory gap between onsets —
// a faithful port of the desktop web app's `detectOnsets` (src/audio/
// latency.js). The plan is explicit that this specific algorithm is NOT
// suitable for the automatic acoustic-loopback path (a phone speaker's
// weak, reverberant click bleed defeats a naive energy threshold — see
// sweep.h/matched_filter.h for what that path uses instead), but its
// assumptions DO hold for the manual tap-along path: a direct
// finger-tap on the device body is a loud, sharp, high-SNR transient
// picked up mostly through structure-borne conduction straight to the
// mic, not a faint room-reflected click — exactly the signal this
// detector was designed for.
std::vector<double> detectOnsets(const std::vector<float> &pcm, double sampleRate, double minGapSec = 0.15,
                                  double thresholdRatio = 0.35, double windowSec = 0.003);

// Given detected onset times and the times taps were scheduled (both in
// the recording's own timebase, seconds), estimates the measured
// round-trip latency as the median of (nearest onset at-or-after each
// scheduled tap minus that scheduled time). Onsets more than maxMatchSec
// after their nearest scheduled tap, or matched to none, are ignored —
// `estimateLatencyFromOnsets`'s own JS test calls this "implausibly far
// from any click." Returns nullopt if fewer than max(2, ceil(N/2))
// scheduled taps could be matched, mirroring the JS version's `null`.
std::optional<double> estimateLatencyFromOnsets(const std::vector<double> &detectedTimes,
                                                 const std::vector<double> &scheduledTimes,
                                                 double maxMatchSec = 0.25);

} // namespace songnotes::dsp
