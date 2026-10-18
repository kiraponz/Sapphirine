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
// folder under [data_dir]). False, with a reason in [error], when it cannot run.
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

// Runs without pacing while [enabled], at the fast-forward volume.
void SetFastForward(bool enabled);

// Writes one value to the core's base settings. The app sends PS1-era keys the Saturn core has
// no use for yet: the BIOS path section ("BIOS"/PathNTSCJ|PathNTSCU|PathPAL) is kept for the
// next boot, the "Audio" section's volumes and buffer size apply at once, and everything else
// is ignored. See android_host.cpp.
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

// The last error notice Mednafen gave (MDFND_OutputNotice, driver_stubs.cpp) since the previous
// call, or "". Its loaders report failures this way instead of throwing.
std::string TakeCoreErrorNotice();

uint32_t GetFrameNumber();
float GetFps();

} // namespace AndroidHost
