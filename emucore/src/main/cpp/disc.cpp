// SPDX-License-Identifier: GPL-3.0-or-later

#include "disc.h"

#include <mednafen/NativeVFS.h>
#include <mednafen/Stream.h>
#include <mednafen/VirtualFS.h>
#include <mednafen/cdrom/CDAccess.h>
#include <mednafen/cdrom/CDAccess_CCD.h>
#include <mednafen/cdrom/CDAccess_Image.h>
#include <mednafen/cdrom/CDInterface_ST.h>

#include "android_host.h"
#include "cdaccess_chd.h"

#include <android/log.h>

#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <memory>
#include <vector>

using namespace Mednafen;

namespace AndroidHost
{

namespace
{

constexpr const char* LOG_TAG = "emucore";
constexpr const char SAF_PREFIX[] = "/saf/";

// A read-only Stream over a file descriptor, reading with pread at its own position.
class FdStream final : public Stream
{
public:
	FdStream(int fd, const std::string& name) : fd(fd), name(name) {}
	~FdStream() override { close(); }

	uint64 attributes() override { return ATTRIBUTE_READABLE | ATTRIBUTE_SEEKABLE; }

	uint64 read(void* data, uint64 count, bool error_on_eos = true) override
	{
		uint64 done = 0;
		while (done < count)
		{
			const ssize_t n = pread(fd, (uint8*)data + done, count - done, (off_t)(pos + done));
			if (n < 0)
			{
				if (errno == EINTR)
					continue;
				const int e = errno;
				throw MDFN_Error(e, _("Error reading from %s: %s"), name.c_str(), strerror(e));
			}
			if (n == 0)
				break;
			done += (uint64)n;
		}
		pos += done;

		if (done < count && error_on_eos)
			throw MDFN_Error(0, _("Unexpected end of file %s."), name.c_str());
		return done;
	}

	void write(const void*, uint64) override { throw MDFN_Error(EBADF, _("%s is read-only."), name.c_str()); }
	void truncate(uint64) override { throw MDFN_Error(EBADF, _("%s is read-only."), name.c_str()); }

	void seek(int64 offset, int whence) override
	{
		int64 base = 0;
		if (whence == SEEK_CUR)
			base = (int64)pos;
		else if (whence == SEEK_END)
			base = (int64)size();

		if (base + offset < 0)
			throw MDFN_Error(EINVAL, _("Seek before the start of %s."), name.c_str());
		pos = (uint64)(base + offset);
	}

	uint64 tell() override { return pos; }

	uint64 size() override
	{
		struct stat st;
		if (fstat(fd, &st) != 0)
		{
			const int e = errno;
			throw MDFN_Error(e, _("Error reading the size of %s: %s"), name.c_str(), strerror(e));
		}
		return (uint64)st.st_size;
	}

	void flush() override {}

	void close() override
	{
		if (fd >= 0)
			::close(fd);
		fd = -1;
	}

private:
	int fd;
	uint64 pos = 0;
	std::string name;
};

// The app's SAF documents as a read-only VirtualFS, for the /saf/ paths (SafFiles.kt): files
// open as descriptors from the content resolver, and a cue sheet's relative names resolve in
// the folder of the path like any other.
class SafVFS final : public VirtualFS
{
public:
	SafVFS() : VirtualFS('/', "/") {}

	Stream* open(const std::string& path, const uint32 mode, const int do_lock, const bool throw_on_noent,
		const CanaryType canary) override
	{
		(void)do_lock;
		(void)canary;

		if (mode != MODE_READ)
			throw MDFN_Error(EROFS, _("Error opening file %s %s: %s"), get_human_path(path).c_str(),
				get_human_mode(mode).c_str(), strerror(EROFS));

		const int fd = SafOpen(path);
		if (fd < 0)
		{
			if (!throw_on_noent)
				return nullptr;
			throw MDFN_Error(ENOENT, _("Error opening file %s %s: %s"), get_human_path(path).c_str(),
				get_human_mode(mode).c_str(), strerror(ENOENT));
		}
		return new FdStream(fd, get_human_path(path));
	}

	int mkdir(const std::string& path, const bool, const bool) override
	{
		throw MDFN_Error(EROFS, _("Error creating directory %s: %s"), get_human_path(path).c_str(), strerror(EROFS));
	}

	bool unlink(const std::string& path, const bool, const CanaryType) override
	{
		throw MDFN_Error(EROFS, _("Error deleting file %s: %s"), get_human_path(path).c_str(), strerror(EROFS));
	}

	void rename(const std::string& oldpath, const std::string&, const CanaryType) override
	{
		throw MDFN_Error(EROFS, _("Error renaming %s: %s"), get_human_path(oldpath).c_str(), strerror(EROFS));
	}

	bool finfo(const std::string& path, FileInfo* fi, const bool throw_on_noent) override
	{
		SafFileInfo info;
		if (!SafStat(path, &info))
		{
			if (throw_on_noent)
				throw MDFN_Error(ENOENT, _("Error getting file information for %s: %s"),
					get_human_path(path).c_str(), strerror(ENOENT));
			return false;
		}

		if (fi)
		{
			*fi = FileInfo();
			fi->size = info.size;
			fi->mtime_us = info.modified_s * 1000000;
			fi->is_regular = !info.is_directory;
			fi->is_directory = info.is_directory;
		}
		return true;
	}

	void readdirentries(const std::string& path, std::function<bool(const std::string&)> callb) override
	{
		std::vector<std::string> names;
		if (!SafList(path, &names))
			throw MDFN_Error(ENOENT, _("Error reading directory entries from %s: %s"),
				get_human_path(path).c_str(), strerror(ENOENT));

		for (const std::string& name : names)
			if (!callb(name))
				break;
	}

	// The document's own name: the rest of a /saf/ path is URL-encoded ids, of no use in a message.
	std::string get_human_path(const std::string& path) override
	{
		const size_t slash = path.find_last_of('/');
		return '"' + (slash == std::string::npos ? path : path.substr(slash + 1)) + '"';
	}
};

SafVFS s_saf_vfs;

VirtualFS* vfs_for(const std::string& path)
{
	if (!path.compare(0, sizeof(SAF_PREFIX) - 1, SAF_PREFIX))
		return &s_saf_vfs;
	return &NVFS;
}

// The reader for the image at [path], by its extension (as CDAccess_Open, plus CHD). Images are
// read from where they are rather than cached in memory, so the VirtualFS (both are static)
// stays in use.
std::unique_ptr<CDAccess> open_access(const std::string& path)
{
	VirtualFS* vfs = vfs_for(path);

	if (vfs->test_ext(path, ".chd"))
		return std::unique_ptr<CDAccess>(new CDAccess_CHD(vfs, path));
	if (vfs->test_ext(path, ".ccd"))
		return std::unique_ptr<CDAccess>(new CDAccess_CCD(vfs, path, false));
	return std::unique_ptr<CDAccess>(new CDAccess_Image(vfs, path, false));
}

} // namespace

CDInterface* OpenDisc(const std::string& path)
{
	// Single-threaded: the sectors are read on the CPU thread as the emulated drive asks.
	// (CDInterface::Open only allows that for archives; the SAF documents are plain files.)
	return new CDInterface_ST(open_access(path));
}

std::string ReadDiscSerial(const std::string& path)
{
	try
	{
		std::unique_ptr<CDAccess> access = open_access(path);

		// The system ID area (IP.BIN) is the user data of sector 0: "SEGA SEGASATURN ", then
		// the maker at 0x10 and the product number at 0x20, ten bytes padded with spaces.
		uint8 sector[2352 + 96];
		access->Read_Raw_Sector(sector, 0);
		const uint8* data = sector + (sector[15] == 2 ? 24 : 16);

		if (memcmp(data, "SEGA SEGASATURN ", 16))
			return "";

		std::string serial(reinterpret_cast<const char*>(data + 0x20), 10);
		while (serial.size() && (uint8)serial.back() <= ' ')
			serial.pop_back();
		while (serial.size() && (uint8)serial.front() <= ' ')
			serial.erase(0, 1);

		for (char c : serial)
			if ((uint8)c < 0x20 || (uint8)c >= 0x7F)
				return "";

		return serial;
	}
	catch (const std::exception& e)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "No serial from %s: %s", path.c_str(), e.what());
		return "";
	}
}

} // namespace AndroidHost
