/*
 *     Copyright (C) 2020  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <cmath>
#include <algorithm>
#include <cstdlib>
#include "linearresampler.h"

namespace libretrodroid {

LinearResampler::LinearResampler() {
    const char *hq = getenv("RAINEY_AUDIO_HQ");
    cubic = !(hq != nullptr && hq[0] == '0');
}

static inline float catmullRom(float p0, float p1, float p2, float p3, float t) {
    return 0.5f * ((2.0f * p1) + (-p0 + p2) * t + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t * t +
                   (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t * t * t);
}

void LinearResampler::resample(const int16_t *source, int32_t inputFrames, int16_t *sink, int32_t sinkFrames) {
    if (inputFrames <= 0) {
        for (int32_t i = 0; i < sinkFrames * 2; i++) sink[i] = 0;
        return;
    }

    if (cubic) {
        for (int32_t k = 0; k < sinkFrames; k++) {
            double pos = (double) k * inputFrames / sinkFrames;
            int32_t i1 = (int32_t) pos;
            float t = (float) (pos - i1);
            int32_t i2 = std::min(i1 + 1, inputFrames - 1);
            int32_t i3 = std::min(i1 + 2, inputFrames - 1);
            for (int ch = 0; ch < 2; ch++) {
                float p0 = i1 > 0 ? source[(i1 - 1) * 2 + ch] : (ch == 0 ? prevL : prevR);
                float v = catmullRom(p0, source[i1 * 2 + ch], source[i2 * 2 + ch], source[i3 * 2 + ch], t);
                sink[k * 2 + ch] = (int16_t) std::max(-32768.0f, std::min(32767.0f, v));
            }
        }
        prevL = source[(inputFrames - 1) * 2];
        prevR = source[(inputFrames - 1) * 2 + 1];
        return;
    }

    double outputTime = 0;
    double outputTimeStep = 1.0 / sinkFrames;

    double floatingPart, integerPart;

    while (sinkFrames > 0) {
        floatingPart = std::modf(outputTime * inputFrames, &integerPart);

        int32_t floorFrame = integerPart;
        int32_t ceilFrame = std::min(floorFrame + 1, inputFrames - 1);

        *sink++ = source[ceilFrame * 2] * (floatingPart) + source[floorFrame * 2] * (1.0 - floatingPart);
        *sink++ = source[ceilFrame * 2 + 1] * (floatingPart) + source[floorFrame * 2 + 1] * (1.0 - floatingPart);
        outputTime += outputTimeStep;
        sinkFrames--;
    }
}

} //namespace libretrodroid
