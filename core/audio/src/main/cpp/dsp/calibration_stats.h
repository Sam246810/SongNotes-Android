#pragma once

#include <vector>

namespace songnotes::dsp {

// Median Absolute Deviation outlier rejection: returns only the values
// within `thresholdMads` scaled MADs of the median. Used across repeated
// sweep measurements to drop any single repetition a transient noise
// burst corrupted, without needing a fixed absolute threshold that would
// depend on how loud or quiet the room happens to be. Returns the input
// unchanged if there are fewer than 3 values (not enough to meaningfully
// call anything an outlier) or if the values are all identical.
std::vector<double> rejectOutliersMad(const std::vector<double> &values, double thresholdMads = 3.0);

// Peak-to-noise ratio in dB: 20*log10(peakMagnitude / noiseFloor). Decides
// whether a single sweep repetition's measurement is trustworthy at all,
// before it's even considered for outlier rejection — a rep with a
// dominant peak (high PNR) is real signal; a rep with no clear peak (low
// PNR) means the room was too noisy, the loopback path is broken, or
// nothing meaningful was actually captured that repetition.
double peakToNoiseRatioDb(float peakMagnitude, float noiseFloor);

} // namespace songnotes::dsp
