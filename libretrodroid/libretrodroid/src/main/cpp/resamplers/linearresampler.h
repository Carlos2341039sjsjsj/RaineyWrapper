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

#ifndef LIBRETRODROID_LINEARRESAMPLER_H
#define LIBRETRODROID_LINEARRESAMPLER_H

#include <cstdint>
#include "resampler.h"

namespace libretrodroid {

class LinearResampler : public Resampler {
public:
    void resample(const int16_t *source, int32_t inputFrames, int16_t *sink, int32_t sinkFrames) override;
    LinearResampler();

private:
    // Interpolacion cubica (Catmull-Rom): mucho menos distorsion que la lineal al pasar de 32/44.1 kHz a 48 kHz.
    bool cubic = true;
    int16_t prevL = 0;
    int16_t prevR = 0;

public:
    virtual ~LinearResampler() = default;
};

} //namespace libretrodroid

#endif //LIBRETRODROID_LINEARRESAMPLER_H
