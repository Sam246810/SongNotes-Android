#include "wav_encoder.h"

#include <cstring>
#include <fstream>

namespace songnotes::dsp {

namespace {

// WAV's own on-disk format is little-endian; every target this codebase
// builds for (ARM64, x86_64) is little-endian too, so a plain memcpy of a
// little-endian-declared integer's bytes is correct without any explicit
// byte-swapping — same assumption the rest of this codebase already makes
// for raw .f32 take files (see writerThreadLoop in audio_engine.cpp).
void appendU32(std::vector<uint8_t> &out, uint32_t value) {
    uint8_t bytes[4];
    std::memcpy(bytes, &value, 4);
    out.insert(out.end(), bytes, bytes + 4);
}

void appendU16(std::vector<uint8_t> &out, uint16_t value) {
    uint8_t bytes[2];
    std::memcpy(bytes, &value, 2);
    out.insert(out.end(), bytes, bytes + 2);
}

void appendTag(std::vector<uint8_t> &out, const char *fourCc) {
    out.insert(out.end(), fourCc, fourCc + 4);
}

} // namespace

std::vector<uint8_t> encodeWavFloat32(const std::vector<float> &samples, int32_t sampleRate,
                                       int32_t channelCount) {
    constexpr uint16_t kFormatIeeeFloat = 3;
    constexpr uint16_t kBitsPerSample = 32;
    const auto dataSize = static_cast<uint32_t>(samples.size() * sizeof(float));
    const auto blockAlign = static_cast<uint16_t>(channelCount * (kBitsPerSample / 8));
    const auto byteRate = static_cast<uint32_t>(sampleRate * blockAlign);

    std::vector<uint8_t> out;
    out.reserve(44 + dataSize);

    // RIFF header.
    appendTag(out, "RIFF");
    appendU32(out, 36 + dataSize); // total file size - 8
    appendTag(out, "WAVE");

    // fmt chunk (16 bytes, canonical PCMWAVEFORMAT layout — cbSize is
    // omitted, matching what libsndfile/soundfile write for float32 WAV).
    appendTag(out, "fmt ");
    appendU32(out, 16);
    appendU16(out, kFormatIeeeFloat);
    appendU16(out, static_cast<uint16_t>(channelCount));
    appendU32(out, static_cast<uint32_t>(sampleRate));
    appendU32(out, byteRate);
    appendU16(out, blockAlign);
    appendU16(out, kBitsPerSample);

    // data chunk.
    appendTag(out, "data");
    appendU32(out, dataSize);
    const auto *sampleBytes = reinterpret_cast<const uint8_t *>(samples.data());
    out.insert(out.end(), sampleBytes, sampleBytes + dataSize);

    return out;
}

bool writeWavFile(const std::string &path, const std::vector<float> &samples, int32_t sampleRate,
                   int32_t channelCount) {
    const std::vector<uint8_t> encoded = encodeWavFloat32(samples, sampleRate, channelCount);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) return false;
    file.write(reinterpret_cast<const char *>(encoded.data()), static_cast<std::streamsize>(encoded.size()));
    return file.good();
}

} // namespace songnotes::dsp
