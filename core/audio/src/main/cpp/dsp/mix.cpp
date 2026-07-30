#include "mix.h"

#include <algorithm>
#include <cmath>

namespace songnotes::dsp {

std::vector<float> mixAndNormalize(const std::vector<float> &a, const std::vector<float> &b) {
    const size_t length = std::max(a.size(), b.size());
    std::vector<float> out(length, 0.0f);
    for (size_t i = 0; i < length; i++) {
        const float sa = i < a.size() ? a[i] : 0.0f;
        const float sb = i < b.size() ? b[i] : 0.0f;
        out[i] = sa + sb;
    }

    float peak = 0.0f;
    for (float sample : out) peak = std::max(peak, std::fabs(sample));
    if (peak > 1.0f) {
        const float scale = 1.0f / peak;
        for (float &sample : out) sample *= scale;
    }

    return out;
}

} // namespace songnotes::dsp
