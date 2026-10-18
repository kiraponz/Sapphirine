// The Android frontend's side of the core: the CPU thread, the render surface, the settings the
// app can give the core. jni.cpp drives it from Kotlin; android_host.cpp implements it together
// with the Mednafen driver hooks (driver_stubs.cpp has the still-empty ones).

#pragma once

#include <stdint.h>

#include <jni.h>
#include <string>
#include <vector>

struct ANativeWindow;

namespace AndroidHost {

// Called from JNI_OnLoad. [native_core] is a global reference to the Kotlin NativeCore class.
void SetJavaVM(JavaVM* vm, jclass native_core);

// Sets up Mednafen once per process (MDFNI_Init, MDFNI_InitFinalize with the core's own
// folder under [data_dir], and the saves in [data_dir]/memcards). False, with a reason in [error], when it cannot run.
bool Initialize(const std::string& data_dir, std::string* error);

// Boots the disc image at [path] (a /saf/ path or a file: .chd, .ccd, .cue or .toc; empty = the
// BIOS alone, no disc) on the calling thread, which becomes the CPU thread, and runs it until
// the VM shuts down. False, with a reason in [error], when it did not
// boot. Save states are not part of this yet.
bool RunSystem(const std::string& path, const std::string& save_state, std::string* error);

// True from the moment the VM booted until it is destroyed.
bool HasActiveSystem();

void RequestPause(bool paused);
void RequestShutdown();

// Presses or releases the button at bit [index] (PadButton.code) of the pad on [port] (0 or 1).
// Any thread; the CPU thread hands it to the core at the next frame or mid-frame sync.
void SetPadBind(uint32_t port, uint32_t index, bool pressed);

// Runs at the fast-forward speed (SetSetting "Main"/FastForwardSpeed) while [enabled], at the
// fast-forward volume.
void SetFastForward(bool enabled);

// Writes one value of the app's settings (CoreKeys.kt). "Core" holds Mednafen's own settings by
// name, written at the next boot ("ss.scsp.resamp_quality" and "video.deinterlacer" also reach
// a running game, after CommitSettings); "BIOS" the BIOS file names for the next boot;
// "Display"/AspectRatio, "Main"/FastForwardSpeed and the "Audio" volumes and buffer size apply
// at once. Anything else is ignored. See android_host.cpp.
void SetSetting(const char* section, const char* key, const char* value);
void CommitSettings();

// The surface to render to, or null for none. Takes the reference [window] holds. Blocks until
// the CPU thread has let go of the previous window.
void SetWindow(ANativeWindow* window, uint32_t width, uint32_t height);
void SetDisplayRefreshRate(float hz);

// Puts the picture at the top of a portrait surface, [top_margin] pixels down, when
// [portrait_top]; otherwise in the middle.
void SetDisplayLayout(bool portrait_top, uint32_t top_margin);

// The edges of the surface the on-screen messages and overlays keep clear of, in surface pixels.
// Nothing draws overlays yet.
void SetOsdSafeArea(uint32_t left, uint32_t top, uint32_t right, uint32_t bottom);

// "<region>\t<description>" for the BIOS image read from [fd] (1 Japan, 2 North America and
// Europe, -1 unknown), or "" when it is no Saturn BIOS. Does not close [fd].
std::string GetBiosInfo(int fd);

// The app's SAF documents (SafFiles.kt, through NativeCore's callbacks), for the core's /saf/
// paths. Any thread.
struct SafFileInfo
{
	uint64_t size = 0;
	int64_t modified_s = 0;
	bool is_directory = false;
};

// A new read-only descriptor for the file at [path], or -1.
int SafOpen(const std::string& path);
// False when nothing is at [path].
bool SafStat(const std::string& path, SafFileInfo* info);
// The names in the folder at [path]; false when it is not one.
bool SafList(const std::string& path, std::vector<std::string>* names);

// The discs of a game booted from a .m3u: their [titles] and the [current] one's index. False
// for a game that is not a playlist, or with no game. Any thread.
bool GetDiscPlaylist(std::vector<std::string>* titles, int* current);

// Puts disc [index] of the game's disc list in the drive: the tray opens at the next frame and
// closes on the disc about a second later. False for an index out of range. Any thread.
bool SwitchDisc(int index);

// Puts the disc image at [path] in the drive in place of the disc there, the same way. Opens
// the image on the calling thread; false when it cannot. Any thread.
bool InsertDisc(const std::string& path);

// The last error notice Mednafen gave (MDFND_OutputNotice, driver_stubs.cpp) since the previous
// call, or "". Its loaders report failures this way instead of throwing.
std::string TakeCoreErrorNotice();

uint32_t GetFrameNumber();
float GetFps();

} // namespace AndroidHost
