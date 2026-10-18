// SPDX-License-Identifier: GPL-3.0-or-later

#include <mednafen/mednafen.h>
#include <mednafen/hash/sha256.h>

#include "bios.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <memory>

namespace AndroidHost
{

namespace
{

using Mednafen::operator""_sha256;

// ss.cpp's BIOSDB: the Saturn BIOS images Mednafen knows, and the regions they are for (the
// first three are Japanese; the other two serve North America, Europe and every other area).
const struct
{
	const char* name;
	Mednafen::sha256_digest hash;
	BiosRegion region;
} KNOWN_BIOS[] =
{
	{ "sega1003.bin",  "cc1e1b7f88f1c6e6fc35994bae2c2292e06fdae258c79eb26a1f1391e72914a8"_sha256, BiosRegion::Japan },
	{ "sega_100.bin",  "ae4058627bb5db9be6d8d83c6be95a4aa981acc8a89042e517e73317886c8bc2"_sha256, BiosRegion::Japan },
	{ "sega_101.bin",  "dcfef4b99605f872b6c3b6d05c045385cdea3d1b702906a0ed930df7bcb7deac"_sha256, BiosRegion::Japan },
	{ "sega_100a.bin", "87293093fad802fcff31fcab427a16caff1acbc5184899b8383b360fd58efb73"_sha256, BiosRegion::NorthAmericaEurope },
	{ "mpr-17933.bin", "96e106f740ab448cf89f0dd49dfbac7fe5391cb6bd6e14ad5e3061c13330266f"_sha256, BiosRegion::NorthAmericaEurope },
};

} // namespace

bool IdentifyBios(int fd, BiosInfo* info)
{
	struct stat st;
	if (fstat(fd, &st) != 0 || st.st_size != BIOS_IMAGE_SIZE)
		return false;

	std::unique_ptr<uint8[]> data(new uint8[BIOS_IMAGE_SIZE]);
	long done = 0;
	while (done < BIOS_IMAGE_SIZE)
	{
		const ssize_t got = pread(fd, data.get() + done, BIOS_IMAGE_SIZE - done, done);
		if (got <= 0)
			return false;
		done += got;
	}

	const Mednafen::sha256_digest hash = Mednafen::sha256(data.get(), BIOS_IMAGE_SIZE);
	*info = BiosInfo();
	for (const auto& known : KNOWN_BIOS)
	{
		if (known.hash == hash)
		{
			info->region = known.region;
			info->name = known.name;
			break;
		}
	}
	return true;
}

bool IdentifyBiosFile(const std::string& path, BiosInfo* info)
{
	const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return false;

	const bool ok = IdentifyBios(fd, info);
	close(fd);
	return ok;
}

} // namespace AndroidHost
