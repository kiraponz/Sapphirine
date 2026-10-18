// Telling Saturn BIOS images apart: by their SHA-256, against the list the ss module itself
// checks them with (ss.cpp's BIOSDB), so the app can say which region a file is for and the
// boot can pick the right one for the game's region.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string>

namespace AndroidHost
{

// The size of every Saturn BIOS image.
constexpr long BIOS_IMAGE_SIZE = 524288;

// The regions a BIOS is for, numbered as NativeCore.getBiosInfoFromFd reports them.
enum class BiosRegion
{
	Unknown = -1,
	Japan = 1,
	NorthAmericaEurope = 2,
};

struct BiosInfo
{
	BiosRegion region = BiosRegion::Unknown;
	// The image's usual file name ("sega_101.bin"), or "" for one the list does not know.
	std::string name;
};

// What the BIOS read from [fd] is. False when it is not BIOS_IMAGE_SIZE bytes or cannot be
// read. Does not close [fd]. Does not need Mednafen initialized.
bool IdentifyBios(int fd, BiosInfo* info);

// The same for the file at [path].
bool IdentifyBiosFile(const std::string& path, BiosInfo* info);

} // namespace AndroidHost
