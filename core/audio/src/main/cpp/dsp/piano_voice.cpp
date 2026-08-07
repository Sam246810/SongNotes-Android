#include "piano_voice.h"

#include <algorithm>
#include <cmath>

namespace songnotes::dsp {

int32_t nearestSampleIndexFor(int32_t midi) {
    int32_t bestIndex = 0;
    int32_t bestDist = std::abs(kPianoSamples[0].midi - midi);
    for (int32_t i = 1; i < kPianoSampleCount; i++) {
        const int32_t dist = std::abs(kPianoSamples[static_cast<size_t>(i)].midi - midi);
        if (dist < bestDist) {
            bestDist = dist;
            bestIndex = i;
        }
    }
    return bestIndex;
}

double playbackRateFor(int32_t midi, int32_t sampleMidi, double sampleFileHz, double engineSampleRateHz) {
    const double semitoneRatio = std::pow(2.0, static_cast<double>(midi - sampleMidi) / 12.0);
    const double sampleRateRatio = sampleFileHz / engineSampleRateHz;
    return semitoneRatio * sampleRateRatio;
}

namespace {

constexpr double kAttackSeconds = 0.005;
constexpr double kDecay1Seconds = 0.8;
constexpr double kDecay1Value = 0.25;
constexpr double kDecay2Seconds = 4.0;
constexpr double kDecay2Value = 0.001;
constexpr double kFloor = 0.001;

// Geometric (exponential) interpolation between (t0,v0) and (t1,v1),
// matching Web Audio's exponentialRampToValueAtTime.
double expInterp(double v0, double v1, double t0, double t1, double t) {
    if (t1 <= t0) return v1;
    const double frac = std::clamp((t - t0) / (t1 - t0), 0.0, 1.0);
    return v0 * std::pow(v1 / v0, frac);
}

double heldEnvelope(double ageSeconds) {
    if (ageSeconds <= 0.0) return 0.0;
    if (ageSeconds < kAttackSeconds) return ageSeconds / kAttackSeconds;
    if (ageSeconds < kDecay1Seconds) return expInterp(1.0, kDecay1Value, kAttackSeconds, kDecay1Seconds, ageSeconds);
    if (ageSeconds < kDecay2Seconds) {
        return expInterp(kDecay1Value, kDecay2Value, kDecay1Seconds, kDecay2Seconds, ageSeconds);
    }
    return kDecay2Value;
}

} // namespace

double envelopeAt(double ageSeconds, double releaseAgeSeconds) {
    if (releaseAgeSeconds < 0.0 || ageSeconds <= releaseAgeSeconds) return heldEnvelope(ageSeconds);
    const double heldAtRelease = std::max(heldEnvelope(releaseAgeSeconds), 1e-6);
    const double sinceRelease = ageSeconds - releaseAgeSeconds;
    if (sinceRelease < kPianoReleaseSeconds) {
        return expInterp(heldAtRelease, kFloor, 0.0, kPianoReleaseSeconds, sinceRelease);
    }
    return kFloor;
}

bool interpolatedSample(const float *buffer, int32_t bufferLength, double readPos, float *outSample) {
    const auto idx = static_cast<int32_t>(readPos);
    if (idx < 0 || idx + 1 >= bufferLength) return false;
    const auto frac = static_cast<float>(readPos - static_cast<double>(idx));
    const float s0 = buffer[idx];
    const float s1 = buffer[idx + 1];
    *outSample = s0 + (s1 - s0) * frac;
    return true;
}

VoiceRenderResult renderVoiceInto(float *out, int32_t numFrames, const float *buffer, int32_t bufferLength,
                                   double startReadPos, double rate, double startAgeSeconds,
                                   double releaseAgeSeconds, double sampleRateHz, float gain) {
    double readPos = startReadPos;
    for (int32_t frame = 0; frame < numFrames; frame++) {
        float sample;
        if (!interpolatedSample(buffer, bufferLength, readPos, &sample)) {
            return VoiceRenderResult{readPos, true};
        }
        const double ageSeconds = startAgeSeconds + static_cast<double>(frame) / sampleRateHz;
        const double env = envelopeAt(ageSeconds, releaseAgeSeconds);
        out[frame] += sample * static_cast<float>(env) * gain;
        readPos += rate;
    }
    return VoiceRenderResult{readPos, false};
}

} // namespace songnotes::dsp
