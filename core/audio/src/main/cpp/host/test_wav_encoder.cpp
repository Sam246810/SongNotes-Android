#include <gtest/gtest.h>

#include <cstdio>
#include <cstring>
#include <fstream>

#include "dsp/wav_encoder.h"

using songnotes::dsp::encodeWavFloat32;
using songnotes::dsp::writeWavFile;

namespace {

uint32_t readU32(const std::vector<uint8_t> &bytes, size_t offset) {
    uint32_t value;
    std::memcpy(&value, bytes.data() + offset, 4);
    return value;
}

uint16_t readU16(const std::vector<uint8_t> &bytes, size_t offset) {
    uint16_t value;
    std::memcpy(&value, bytes.data() + offset, 2);
    return value;
}

std::string readTag(const std::vector<uint8_t> &bytes, size_t offset) {
    return {reinterpret_cast<const char *>(bytes.data() + offset), 4};
}

} // namespace

TEST(WavEncoder, HeaderFieldsAreCorrectForAMonoBuffer) {
    std::vector<float> samples = {1.0f, -1.0f, 0.5f, 0.0f};
    auto encoded = encodeWavFloat32(samples, 48000, 1);

    ASSERT_EQ(encoded.size(), 44u + samples.size() * sizeof(float));
    EXPECT_EQ(readTag(encoded, 0), "RIFF");
    EXPECT_EQ(readU32(encoded, 4), 36u + static_cast<uint32_t>(samples.size() * sizeof(float)));
    EXPECT_EQ(readTag(encoded, 8), "WAVE");
    EXPECT_EQ(readTag(encoded, 12), "fmt ");
    EXPECT_EQ(readU32(encoded, 16), 16u); // fmt chunk size
    EXPECT_EQ(readU16(encoded, 20), 3u);  // WAVE_FORMAT_IEEE_FLOAT
    EXPECT_EQ(readU16(encoded, 22), 1u);  // channel count
    EXPECT_EQ(readU32(encoded, 24), 48000u); // sample rate
    EXPECT_EQ(readU32(encoded, 28), 48000u * 4u); // byte rate = sampleRate * blockAlign
    EXPECT_EQ(readU16(encoded, 32), 4u);  // block align (32-bit mono = 4 bytes/frame)
    EXPECT_EQ(readU16(encoded, 34), 32u); // bits per sample
    EXPECT_EQ(readTag(encoded, 36), "data");
    EXPECT_EQ(readU32(encoded, 40), static_cast<uint32_t>(samples.size() * sizeof(float)));
}

TEST(WavEncoder, HeaderFieldsAccountForChannelCount) {
    std::vector<float> samples(8, 0.0f); // 4 stereo frames
    auto encoded = encodeWavFloat32(samples, 44100, 2);
    EXPECT_EQ(readU16(encoded, 22), 2u);  // channel count
    EXPECT_EQ(readU16(encoded, 32), 8u);  // block align (2 ch * 4 bytes)
    EXPECT_EQ(readU32(encoded, 28), 44100u * 8u); // byte rate
}

TEST(WavEncoder, SampleDataRoundTripsExactly) {
    std::vector<float> samples = {0.1f, -0.9f, 3.14159f, -12345.6789f, 0.0f};
    auto encoded = encodeWavFloat32(samples, 48000, 1);

    ASSERT_EQ(encoded.size() - 44, samples.size() * sizeof(float));
    std::vector<float> readBack(samples.size());
    std::memcpy(readBack.data(), encoded.data() + 44, samples.size() * sizeof(float));
    for (size_t i = 0; i < samples.size(); i++) {
        EXPECT_FLOAT_EQ(readBack[i], samples[i]) << "mismatch at frame " << i;
    }
}

TEST(WavEncoder, EmptySamplesProducesJustTheHeader) {
    auto encoded = encodeWavFloat32({}, 48000, 1);
    EXPECT_EQ(encoded.size(), 44u);
    EXPECT_EQ(readU32(encoded, 40), 0u); // data chunk size
}

TEST(WavEncoder, WriteWavFileRoundTripsThroughDisk) {
    std::vector<float> samples = {1.0f, 2.0f, 3.0f, 4.0f};
    const std::string path = "wav_encoder_test_output.wav";
    ASSERT_TRUE(writeWavFile(path, samples, 48000, 1));

    std::ifstream file(path, std::ios::binary | std::ios::ate);
    ASSERT_TRUE(file.is_open());
    const auto fileSize = static_cast<size_t>(file.tellg());
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> fileBytes(fileSize);
    file.read(reinterpret_cast<char *>(fileBytes.data()), static_cast<std::streamsize>(fileSize));

    auto expected = encodeWavFloat32(samples, 48000, 1);
    ASSERT_EQ(fileBytes.size(), expected.size());
    EXPECT_EQ(fileBytes, expected);

    std::remove(path.c_str());
}
