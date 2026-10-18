// Opening the disc images the app hands the core: SAF documents by their /saf/ paths
// (SafFiles.kt) or plain files, as CHD (cdaccess_chd.h), CCD or cue/toc (Mednafen's own
// readers).
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <mednafen/mednafen.h>
#include <mednafen/cdrom/CDInterface.h>

#include <string>

namespace AndroidHost
{

// The disc image at [path], read on the calling thread. Throws MDFN_Error.
Mednafen::CDInterface* OpenDisc(const std::string& path);

// The product number in the Saturn disc header of the image at [path] (e.g. "T-4507G"), or ""
// when it is no Saturn disc or cannot be read. Does not need Mednafen initialized.
std::string ReadDiscSerial(const std::string& path);

} // namespace AndroidHost
