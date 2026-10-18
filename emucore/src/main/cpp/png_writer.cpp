// The PNG encoder (png_writer.h), on top of zlib's deflate and CRC32 — PNG format only: a
// signature, one chunk each of IHDR, IDAT and IEND, big-endian numbers and CRCs.

#include "png_writer.h"

#include <zlib.h>

#include <cstring>
#include <stdexcept>

namespace AndroidHost {

namespace
{

constexpr uint8_t SIGNATURE[8] = { 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

// A big-endian u32, the way PNG's numbers and chunk lengths go (whatever this CPU's ordering).
void put_u32_be(std::vector<uint8_t>* out, uint32_t value)
{
	out->push_back((uint8_t)(value >> 24));
	out->push_back((uint8_t)(value >> 16));
	out->push_back((uint8_t)(value >> 8));
	out->push_back((uint8_t)value);
}

// One chunk: length, type, payload, and the CRC over the type with the payload behind it.
void put_chunk(std::vector<uint8_t>* out, const char* type, const uint8_t* data, uint32_t size)
{
	put_u32_be(out, size);
	out->insert(out->end(), type, type + 4);
	out->insert(out->end(), data, data + size);

	uLong crc = crc32(0, (const Bytef*)type, 4);
	if (size)
		crc = crc32(crc, data, size);
	put_u32_be(out, (uint32_t)crc);
}

} // namespace

std::vector<uint8_t> EncodePngRgb(int width, int height, const uint8_t* pixels)
{
	std::vector<uint8_t> png;
	if (!pixels || width <= 0 || height <= 0)
		return png;

	png.reserve(1024);
	png.insert(png.end(), SIGNATURE, SIGNATURE + sizeof(SIGNATURE));

	// IHDR: RGB8 (colour type 2, one byte per channel), zlib-compressed, not interlaced.
	{
		std::vector<uint8_t> ihdr;
		ihdr.reserve(13);
		put_u32_be(&ihdr, (uint32_t)width);
		put_u32_be(&ihdr, (uint32_t)height);
		ihdr.push_back(8);	// Bit depth.
		ihdr.push_back(2);	// Colour type: truecolour RGB.
		ihdr.push_back(0);	// Compression: deflate.
		ihdr.push_back(0);	// Filter method: our lines take the plain filter (0).
		ihdr.push_back(0);	// Interlace: none.
		put_chunk(&png, "IHDR", ihdr.data(), (uint32_t)ihdr.size());
	}

	// IDAT: every scanline (a row of RGB triples) behind its own filter byte, 0 = the pixels
	// as they are, and the whole of it through zlib's stream — what compress() writes, which
	// is the format PNG's image data expects.
	{
		const size_t pixel_bytes = (size_t)width * 3;
		const size_t scanline = pixel_bytes + 1;	// The filter byte first.

		std::vector<uint8_t> raw((size_t)height * scanline);
		for (int y = 0; y < height; y++)
		{
			uint8_t* line = raw.data() + (size_t)y * scanline;
			*line++ = 0;
			memcpy(line, pixels + (size_t)y * pixel_bytes, pixel_bytes);
		}

		std::vector<uint8_t> idat(compressBound((uLong)raw.size()));
		uLongf packed_size = idat.size();
		if (compress(idat.data(), &packed_size, raw.data(), (uLong)raw.size()) != Z_OK)
			throw std::runtime_error("PNG deflate failed");
		idat.resize(packed_size);

		put_chunk(&png, "IDAT", idat.data(), (uint32_t)idat.size());
	}

	put_chunk(&png, "IEND", nullptr, 0);
	return png;
}

} // namespace AndroidHost
