// A CD image in MAME's CHD format, read with libchdr, for Mednafen's CD interface. Mednafen
// 1.32.1 has no CHD reader of its own (src/cdrom reads cue/toc/ccd only); this one presents a
// CHD the way CDAccess_Image presents a cue sheet, so the two read alike: the same pregap and
// lead-out synthesis, and the same Q subchannel built from the track layout.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <mednafen/mednafen.h>
#include <mednafen/cdrom/CDAccess.h>

#include <libchdr/chd.h>

#include <memory>
#include <string>

namespace AndroidHost
{

class CDAccess_CHD final : public Mednafen::CDAccess
{
public:
	// Opens the CHD at [path] through [vfs]. The VirtualFS only has to live through the
	// constructor; the opened stream stays with this object. Throws MDFN_Error.
	CDAccess_CHD(Mednafen::VirtualFS* vfs, const std::string& path);
	~CDAccess_CHD() override;

	void Read_Raw_Sector(uint8* buf, int32 lba) override;
	bool Fast_Read_Raw_PW_TSRE(uint8* pwbuf, int32 lba) const noexcept override;
	void Read_TOC(Mednafen::CDUtility::TOC* toc) override;

private:
	// How a track's sectors are stored in the CHD (its metadata's TYPE).
	enum class Format
	{
		Audio,      // 2352 bytes of big-endian samples
		Mode1,      // 2048 bytes of user data
		Mode1Raw,   // the whole 2352-byte sector
		Mode2,      // 2336 bytes from the subheader on (also MODE2_FORM_MIX)
		Mode2Form1, // 2048 bytes of user data
		Mode2Form2, // 2324 bytes of user data
		Mode2Raw,   // the whole 2352-byte sector (also CDI/2352)
	};

	// A track, in CDAccess_Image's terms: [lba] is INDEX 01; before it come [pregap_dv]
	// sectors that the CHD holds and before those [pregap] that it does not; after the
	// [sectors] of the track proper come [postgap] sectors the CHD does not hold either.
	struct Track
	{
		int32 lba = 0;
		int32 pregap = 0;
		int32 pregap_dv = 0;
		int32 sectors = 0;
		int32 postgap = 0;
		Format format = Format::Mode1Raw;
		uint8 subq_control = 0;
		uint32 chd_frame = 0; // The CHD frame of the first sector it holds (lba - pregap_dv).
	};

	void ReadTracks();
	void GenerateTOC();
	int32 FindTrack(int32 lba) const;
	int32 MakeSubPQ(int32 lba, uint8* SubPWBuf) const;
	const uint8* ReadFrame(uint32 frame);

	std::unique_ptr<Mednafen::Stream> stream;
	core_file chd_stream;
	chd_file* chd = nullptr;

	uint32 hunk_bytes = 0;
	uint32 unit_bytes = 0;
	std::unique_ptr<uint8[]> hunk;
	uint32 cached_hunk = UINT32_MAX;

	Track tracks[100];
	int first_track = 1;
	int last_track = 0;
	int32 total_sectors = 0;
	uint8 disc_type = 0;
	Mednafen::CDUtility::TOC toc;
};

} // namespace AndroidHost
