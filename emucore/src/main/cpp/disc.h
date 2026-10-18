// Opening the disc images the app hands the core: SAF documents by their /saf/ paths
// (SafFiles.kt) or plain files, as CHD (cdaccess_chd.h), CCD or cue/toc (Mednafen's own
// readers), and .m3u playlists of them (read by Mednafen, which opens each disc through
// MDFND_OpenCDImage here).
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <mednafen/mednafen.h>
#include <mednafen/cdrom/CDInterface.h>

#include <memory>
#include <string>
#include <vector>

namespace AndroidHost
{

// A disc slot of the loaded game: it reads through the disc image in it, which can be swapped
// for another one (InsertDisc, a disc from outside the game's playlist) while the tray is open.
// Every disc Mednafen loads comes wrapped in one of these.
class SwappableDisc final : public Mednafen::CDInterface
{
public:
	explicit SwappableDisc(std::unique_ptr<Mednafen::CDInterface> disc);

	// Puts [disc] in place of the image in this slot. On the CPU thread, with the tray open.
	void Swap(std::unique_ptr<Mednafen::CDInterface> disc);

	void HintReadSector(int32 lba) override;
	bool ReadRawSector(uint8* buf, int32 lba) override;
	bool ReadRawSectorPWOnly(uint8* pwbuf, int32 lba, bool hint_fullread) override;
	bool NonDeterministic_CheckSectorReady(int32 lba) override;

private:
	std::unique_ptr<Mednafen::CDInterface> disc;
};

// The disc image at [path], read on the calling thread. Throws MDFN_Error.
std::unique_ptr<Mednafen::CDInterface> OpenDisc(const std::string& path);

// The VirtualFS that reads [path]: the SAF one for /saf/ paths, else Mednafen's native one.
Mednafen::VirtualFS* DiscVFS(const std::string& path);

// The file name of the disc image at [path] without its extension, for a disc's title.
std::string DiscTitle(const std::string& path);

// A disc MDFND_OpenCDImage opened: its slot (Mednafen owns it until the game closes) and title.
struct OpenedDisc
{
	SwappableDisc* slot;
	std::string title;
};

// The discs MDFND_OpenCDImage opened since the last call, in the order Mednafen loaded them
// (which is the order of its disc list and its media indexes).
std::vector<OpenedDisc> TakeOpenedDiscs();

// The product number in the Saturn disc header of the image at [path] (e.g. "T-4507G"), or ""
// when it is no Saturn disc or cannot be read. Does not need Mednafen initialized.
std::string ReadDiscSerial(const std::string& path);

} // namespace AndroidHost
