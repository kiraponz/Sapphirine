/* driver_stubs.cpp — frontend ("driver") side stubs for libemucore.so.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Everything Mednafen's core calls out to the driver for lives here: the MDFND_* hooks from
 * mednafen-driver.h and the other *-driver.h headers, and the entry points it would otherwise
 * pick up from netplay.cpp (which we do not build: it drags net/ in, and the frontend here only
 * needs its symbols to exist). android_host.cpp has the driver side that does drive emulation
 * (the frame loop, the surface, the sound, and MDFND_MidSync with it). Threading comes from
 * Mednafen's own MThreading_POSIX.cpp.
 */

#include <mednafen/mednafen.h>
#include <mednafen/driver.h>
#include <mednafen/netplay-driver.h>

#include "android_host.h"

// types.h requires being included before common C headers (it errors out otherwise).
#include <android/log.h>

#include <mutex>
#include <string>

using namespace Mednafen;

namespace
{

constexpr const char* LOG_TAG = "mednafen-core";

// The last error notice, for AndroidHost::TakeCoreErrorNotice. Notices can come from the CD
// and VDP2 threads too, hence the lock.
std::mutex s_error_mutex;
std::string s_error_notice;

void log(const char* s)
{
	// Messages are informational here; nothing in this task needs user interaction.
	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", s);
}

}

namespace Mednafen
{

// ---------------------------------------------------------------------------
// MDFND_* hooks (src/mednafen-driver.h, state-driver.h, movie-driver.h,
// video-driver.h, netplay-driver.h). Empty or trivial: this task only needs the link.
// ---------------------------------------------------------------------------

void MDFND_OutputNotice(MDFN_NoticeType t, const char* s) noexcept
{
	log(s);

	if (t == MDFN_NOTICE_ERROR)
	{
		try
		{
			std::lock_guard<std::mutex> lock(s_error_mutex);
			s_error_notice = s;
		}
		catch (...)
		{
		}
	}
}

void MDFND_OutputInfo(const char* s) noexcept
{
	log(s);
}

void MDFND_DispMessage(char* text)
{
	log(text);	// Never called from the code we build (only drivers call DispMessage).
}

bool MDFND_CheckNeedExit(void)
{
	return false;
}

void MDFND_SetStateStatus(StateStatusStruct* status) noexcept
{
}

void MDFND_SetMovieStatus(StateStatusStruct* status) noexcept
{
}

void MDFND_NetplayText(const char* text, bool NetEcho)
{
}

void MDFND_NetplaySetHints(bool active, bool behind, uint32 local_players_mask)
{
}

// ---------------------------------------------------------------------------
// Netplay entry stubs (replacement for src/netplay.cpp, which needs net/).
// Everything is disabled: netplay is compiled out as far as the code that reads
// MDFNnetplay is concerned.
// ---------------------------------------------------------------------------

int MDFNnetplay = 0;

void MDFNI_NetplayConnect(void)
{
}

void MDFNI_NetplayDisconnect(void)
{
}

void MDFNI_NetplayLine(const char* text, bool& inputable, bool& viewable)
{
	(void)text; (void)inputable; (void)viewable;
}

void Netplay_Update(const uint32 PortDeviceCache[], uint8* const PortData[], const uint32 PortLen[])
{
}

void Netplay_PostProcess(const uint32 PortDevIdx[], uint8* const PortData[], const uint32 PortLen[])
{
}

void NetplaySendState(void)
{
}

bool NetplaySendCommand(const uint8 command, const uint32 len, const void* data)
{
	(void)command; (void)len; (void)data;
	return false;
}

}

std::string AndroidHost::TakeCoreErrorNotice()
{
	std::lock_guard<std::mutex> lock(s_error_mutex);
	std::string ret;
	ret.swap(s_error_notice);
	return ret;
}

// Anything else the link needs from the driver side shows up as an "undefined symbol"
// with -Wl,--no-undefined; stubs land in this file as they are found.
