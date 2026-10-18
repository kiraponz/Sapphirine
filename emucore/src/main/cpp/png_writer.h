// The core's own PNG encoder, for the pictures it writes itself (the save-state and rewind
// thumbnails, the screenshots): RGB8, no filtering, zlib-deflated scanlines. png_writer.cpp.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <stdint.h>

#include <vector>

namespace AndroidHost {

// The PNG bytes of an RGB8 image, [width] by [height]: [pixels] holds three bytes per pixel
// (red first), row by row, from the top. Empty when the encoder was given nothing to encode.
std::vector<uint8_t> EncodePngRgb(int width, int height, const uint8_t* pixels);

} // namespace AndroidHost
