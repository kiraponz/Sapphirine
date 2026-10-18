// SPDX-License-Identifier: GPL-3.0-or-later

#include "cdaccess_chd.h"

#include <mednafen/cdrom/CDUtility.h>

#include <libchdr/cdrom.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

using namespace Mednafen;
using namespace Mednafen::CDUtility;

namespace AndroidHost
{

namespace
{

// libchdr reads through these, with the Stream in argp. The Stream belongs to the CDAccess_CHD,
// so libchdr's close (chd_close, or a failed chd_open_core_file) leaves it alone.
UINT64 stream_fsize(core_file* file)
{
	try
	{
		return static_cast<Stream*>(file->argp)->size();
	}
	catch (...)
	{
		return (UINT64)-1;
	}
}

size_t stream_fread(void* ptr, size_t size, size_t count, core_file* file)
{
	if (!size || !count)
		return 0;

	try
	{
		return (size_t)(static_cast<Stream*>(file->argp)->read(ptr, (uint64)size * count, false) / size);
	}
	catch (...)
	{
		return 0;
	}
}

int stream_fclose(core_file*)
{
	return 0;
}

int stream_fseek(core_file* file, INT64 offset, int whence)
{
	try
	{
		static_cast<Stream*>(file->argp)->seek(offset, whence);
		return 0;
	}
	catch (...)
	{
		return -1;
	}
}

// A CHD's name for a track type (the names cdrom.c's cdrom_convert_type_string_to_track_info
// knows, plus the cue-style ones older chdman versions wrote) and the CDAccess_CHD::Format it
// maps to.
struct TypeName
{
	const char* name;
	int format;
};

} // namespace

CDAccess_CHD::CDAccess_CHD(VirtualFS* vfs, const std::string& path)
{
	stream.reset(vfs->open(path, VirtualFS::MODE_READ));

	chd_stream.argp = stream.get();
	chd_stream.fsize = stream_fsize;
	chd_stream.fread = stream_fread;
	chd_stream.fclose = stream_fclose;
	chd_stream.fseek = stream_fseek;

	const chd_error err = chd_open_core_file(&chd_stream, CHD_OPEN_READ, nullptr, &chd);
	if (err != CHDERR_NONE)
	{
		chd = nullptr;
		throw MDFN_Error(0, _("Could not open the CHD %s: %s"), vfs->get_human_path(path).c_str(),
			chd_error_string(err));
	}

	try
	{
		const chd_header* header = chd_get_header(chd);
		hunk_bytes = header->hunkbytes;
		unit_bytes = header->unitbytes;

		// A CD's frame is the sector and its subchannel; a hunk holds whole frames.
		if (unit_bytes < 2352 || !hunk_bytes || hunk_bytes % unit_bytes)
			throw MDFN_Error(0, _("The CHD is not a CD image."));

		hunk.reset(new uint8[hunk_bytes]);

		ReadTracks();
		GenerateTOC();
	}
	catch (...)
	{
		chd_close(chd);
		chd = nullptr;
		throw;
	}
}

CDAccess_CHD::~CDAccess_CHD()
{
	if (chd)
		chd_close(chd);
}

void CDAccess_CHD::ReadTracks()
{
	static const TypeName type_names[] = {
		{ "AUDIO", (int)Format::Audio },
		{ "MODE1", (int)Format::Mode1 },
		{ "MODE1/2048", (int)Format::Mode1 },
		{ "MODE1_RAW", (int)Format::Mode1Raw },
		{ "MODE1/2352", (int)Format::Mode1Raw },
		{ "MODE2", (int)Format::Mode2 },
		{ "MODE2/2336", (int)Format::Mode2 },
		{ "MODE2_FORM_MIX", (int)Format::Mode2 },
		{ "MODE2_FORM1", (int)Format::Mode2Form1 },
		{ "MODE2/2048", (int)Format::Mode2Form1 },
		{ "MODE2_FORM2", (int)Format::Mode2Form2 },
		{ "MODE2/2324", (int)Format::Mode2Form2 },
		{ "MODE2_RAW", (int)Format::Mode2Raw },
		{ "MODE2/2352", (int)Format::Mode2Raw },
		{ "CDI/2352", (int)Format::Mode2Raw },
	};

	// The layout runs as in CDAccess_Image::ImageOpen: the disc starts 150 sectors before
	// track 1's INDEX 01, counted as track 1's pregap, and each track follows the last.
	int32 running_lba = -150;
	uint32 chd_frame = 0;
	bool any_mode2 = false;

	for (uint32 index = 0;; index++)
	{
		char meta[256];
		uint32 meta_len = 0;
		int number = 0, frames = 0, pregap = 0, postgap = 0;
		char type[32] = "", subtype[32] = "", pgtype[32] = "", pgsub[32] = "";

		if (chd_get_metadata(chd, CDROM_TRACK_METADATA2_TAG, index, meta, sizeof(meta) - 1, &meta_len, nullptr,
				nullptr) == CHDERR_NONE)
		{
			meta[std::min<uint32>(meta_len, sizeof(meta) - 1)] = 0;
			if (sscanf(meta, "TRACK:%d TYPE:%31s SUBTYPE:%31s FRAMES:%d PREGAP:%d PGTYPE:%31s PGSUB:%31s POSTGAP:%d",
					&number, type, subtype, &frames, &pregap, pgtype, pgsub, &postgap) != 8)
				throw MDFN_Error(0, _("Unreadable CHD track entry: %s"), meta);
		}
		else if (chd_get_metadata(chd, CDROM_TRACK_METADATA_TAG, index, meta, sizeof(meta) - 1, &meta_len, nullptr,
				nullptr) == CHDERR_NONE)
		{
			meta[std::min<uint32>(meta_len, sizeof(meta) - 1)] = 0;
			if (sscanf(meta, "TRACK:%d TYPE:%31s SUBTYPE:%31s FRAMES:%d", &number, type, subtype, &frames) != 4)
				throw MDFN_Error(0, _("Unreadable CHD track entry: %s"), meta);
		}
		else
			break;

		const int expected = index ? last_track + 1 : number;
		if (number < 1 || number > 99 || number != expected || frames < 0 || pregap < 0 || postgap < 0)
			throw MDFN_Error(0, _("Bad CHD track entry: %s"), meta);

		const TypeName* tn = std::find_if(std::begin(type_names), std::end(type_names),
			[&](const TypeName& t) { return !strcmp(t.name, type); });
		if (tn == std::end(type_names))
			throw MDFN_Error(0, _("Unsupported CHD track type \"%s\"."), type);

		if (!index)
			first_track = number;
		last_track = number;

		Track& t = tracks[number];
		t.format = (Format)tn->format;
		t.subq_control = t.format == Format::Audio ? 0 : SUBQ_CTRLF_DATA;
		any_mode2 |= t.format >= Format::Mode2;

		// A PGTYPE starting with 'V' means the pregap's sectors are in the CHD, at the start
		// of the track's frames; otherwise the pregap is silence the reader makes up.
		if (pgtype[0] == 'V')
		{
			t.pregap_dv = std::min(pregap, frames);
			t.pregap = 0;
		}
		else
		{
			t.pregap_dv = 0;
			t.pregap = pregap;
		}
		if (!index)
			t.pregap += 150;

		t.sectors = frames - t.pregap_dv;
		t.postgap = postgap;

		running_lba += t.pregap;
		t.chd_frame = chd_frame;
		running_lba += t.pregap_dv;
		t.lba = running_lba;
		running_lba += t.sectors + t.postgap;

		// chdman pads every track to a multiple of CD_TRACK_PADDING frames.
		chd_frame += (frames + CD_TRACK_PADDING - 1) / CD_TRACK_PADDING * CD_TRACK_PADDING;
	}

	if (!last_track)
		throw MDFN_Error(0, _("The CHD has no CD tracks."));

	total_sectors = running_lba;
	disc_type = any_mode2 ? DISC_TYPE_CD_XA : DISC_TYPE_CDDA_OR_M1;
}

void CDAccess_CHD::GenerateTOC()
{
	toc.Clear();
	toc.first_track = first_track;
	toc.last_track = last_track;
	toc.disc_type = disc_type;

	for (int i = first_track; i <= last_track; i++)
	{
		toc.tracks[i].lba = tracks[i].lba;
		toc.tracks[i].adr = ADR_CURPOS;
		toc.tracks[i].control = tracks[i].subq_control;
		toc.tracks[i].valid = true;
	}

	toc.tracks[100].lba = total_sectors;
	toc.tracks[100].adr = ADR_CURPOS;
	toc.tracks[100].control = tracks[last_track].subq_control;
	toc.tracks[100].valid = true;
}

void CDAccess_CHD::Read_TOC(TOC* rtoc)
{
	*rtoc = toc;
}

int32 CDAccess_CHD::FindTrack(int32 lba) const
{
	for (int i = first_track; i <= last_track; i++)
	{
		const Track& t = tracks[i];
		if (lba >= t.lba - t.pregap_dv - t.pregap && lba < t.lba + t.sectors + t.postgap)
			return i;
	}

	return -1;
}

// The Q subchannel of [lba], OR-ed into the interleaved [SubPWBuf] (with the pause bit in the
// gaps), as CDAccess_Image::MakeSubPQ builds it. Returns the track. Throws past the disc.
int32 CDAccess_CHD::MakeSubPQ(int32 lba, uint8* SubPWBuf) const
{
	const int32 track = FindTrack(lba);
	if (track < 0)
		throw MDFN_Error(0, _("Could not find track for sector %d!"), lba);

	const Track& t = tracks[track];
	const uint32 lba_relative = lba < t.lba ? t.lba - 1 - lba : lba - t.lba;
	const uint8 pause_or = (lba < t.lba || lba >= t.lba + t.sectors) ? 0x80 : 0x00;

	// More than two seconds into the pregap of a data track that follows an audio track, the
	// sectors still read as audio.
	uint8 control = t.subq_control;
	if (lba - t.lba < -150 && (t.subq_control & SUBQ_CTRLF_DATA) && track > first_track &&
		!(tracks[track - 1].subq_control & SUBQ_CTRLF_DATA))
		control = tracks[track - 1].subq_control;

	uint8 buf[0xC] = {};
	buf[0] = ADR_CURPOS | (control << 4);
	buf[1] = U8_to_BCD(track);
	buf[2] = U8_to_BCD(lba >= t.lba ? 1 : 0);
	ABA_to_AMSF_BCD(lba_relative, &buf[3], &buf[4], &buf[5]);
	buf[6] = 0;
	ABA_to_AMSF_BCD(LBA_to_ABA(lba), &buf[7], &buf[8], &buf[9]);
	subq_generate_checksum(buf);

	for (int i = 0; i < 96; i++)
		SubPWBuf[i] |= (((buf[i >> 3] >> (7 - (i & 0x7))) & 1) ? 0x40 : 0x00) | pause_or;

	return track;
}

// The bytes of CHD frame [frame], from the hunk holding it (decompressed once per hunk: a CD
// reads on sequentially, eight frames to a typical hunk).
const uint8* CDAccess_CHD::ReadFrame(uint32 frame)
{
	const uint64 offset = (uint64)frame * unit_bytes;
	const uint32 hunk_index = (uint32)(offset / hunk_bytes);

	if (hunk_index != cached_hunk)
	{
		cached_hunk = UINT32_MAX;
		const chd_error err = chd_read(chd, hunk_index, hunk.get());
		if (err != CHDERR_NONE)
			throw MDFN_Error(0, _("CHD read error at hunk %u: %s"), hunk_index, chd_error_string(err));
		cached_hunk = hunk_index;
	}

	return hunk.get() + (offset % hunk_bytes);
}

void CDAccess_CHD::Read_Raw_Sector(uint8* buf, int32 lba)
{
	// Past the last track: the lead-out, made up as CDAccess_Image does.
	if (lba >= total_sectors)
	{
		uint8 mode = disc_type == DISC_TYPE_CD_XA ? 0x02 : 0x01;
		switch (tracks[last_track].format)
		{
		case Format::Audio:
			break;
		case Format::Mode1:
		case Format::Mode1Raw:
			mode = 0x01;
			break;
		default:
			mode = 0x02;
			break;
		}
		synth_leadout_sector_lba(mode, toc, lba, buf);
		return;
	}

	memset(buf + 2352, 0, 96);
	const int32 track = MakeSubPQ(lba, buf + 2352);
	const Track& t = tracks[track];

	// The gaps the CHD does not hold: zeroed sectors with headers for data tracks.
	if (lba < t.lba - t.pregap_dv || lba >= t.lba + t.sectors)
	{
		const Track* et = &t;
		if (lba - t.lba < -150 && (t.subq_control & SUBQ_CTRLF_DATA) && track > first_track &&
			!(tracks[track - 1].subq_control & SUBQ_CTRLF_DATA))
			et = &tracks[track - 1];

		memset(buf, 0, 2352);
		switch (et->format)
		{
		case Format::Audio:
			break;
		case Format::Mode1:
		case Format::Mode1Raw:
			encode_mode1_sector(lba + 150, buf);
			break;
		default:
			buf[12 + 6] = 0x20;
			buf[12 + 10] = 0x20;
			encode_mode2_form2_sector(lba + 150, buf);
			break;
		}
		return;
	}

	const uint8* src = ReadFrame(t.chd_frame + (uint32)(lba - (t.lba - t.pregap_dv)));

	switch (t.format)
	{
	case Format::Audio:
		// CHDs keep CD audio big-endian; Mednafen wants it as on the disc, little-endian.
		memcpy(buf, src, 2352);
		Endian_A16_Swap(buf, 588 * 2);
		break;

	case Format::Mode1Raw:
	case Format::Mode2Raw:
		memcpy(buf, src, 2352);
		break;

	case Format::Mode1:
		memset(buf, 0, 2352);
		memcpy(buf + 16, src, 2048);
		encode_mode1_sector(lba + 150, buf);
		break;

	case Format::Mode2:
		memset(buf, 0, 2352);
		memcpy(buf + 16, src, 2336);
		encode_mode2_sector(lba + 150, buf);
		break;

	case Format::Mode2Form1:
		memset(buf, 0, 2352);
		memcpy(buf + 24, src, 2048);
		encode_mode2_form1_sector(lba + 150, buf);
		break;

	case Format::Mode2Form2:
		memset(buf, 0, 2352);
		buf[16 + 2] = buf[16 + 6] = 0x20; // The submode's form 2 bit, in both subheader copies.
		memcpy(buf + 24, src, 2324);
		encode_mode2_form2_sector(lba + 150, buf);
		break;
	}
}

// The subchannel is always made up from the layout (any the CHD carries is not used), so it can
// be had at once and from any thread: it reads nothing but the track table.
bool CDAccess_CHD::Fast_Read_Raw_PW_TSRE(uint8* pwbuf, int32 lba) const noexcept
{
	if (lba >= total_sectors)
	{
		subpw_synth_leadout_lba(toc, lba, pwbuf);
		return true;
	}

	memset(pwbuf, 0, 96);
	try
	{
		MakeSubPQ(lba, pwbuf);
	}
	catch (...)
	{
		return false;
	}

	return true;
}

} // namespace AndroidHost
