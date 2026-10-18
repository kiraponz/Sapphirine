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
// the VM shuts down. False, with a reason in [error], when it did not boot. A non-empty
// [save_state] restarts the (loaded) game from that state file after the boot.
bool RunSystem(const std::string& path, const std::string& save_state, std::string* error);

// True from the moment the VM booted until it is destroyed.
bool HasActiveSystem();

void RequestPause(bool paused);
void RequestShutdown();

// Presses or releases the button at bit [index] (PadButton.code) of the pad on [port] (0 or 1),
// with [value] the pressure (0..1; only [port]'s 3D Control Pad's analog shoulders, its L and R,
// take the value in). Code 16 (PadButton.Analog) is not a Saturn button: on a 3D pad a press
// flips its analog mode. Any thread; the CPU thread hands it to the core at the next frame or
// mid-frame sync.
void SetPadBind(uint32_t port, uint32_t index, float value);

// Moves the pad's stick (the left one, the only one a 3D Control Pad has) to [x] and [y], each
// -1..1 with +y down. Any thread; the CPU thread hands it to the core at the next frame.
void SetPadStick(uint32_t port, float x, float y);

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

// ---------------------------------------------------------------------------
// Save states, rewind, screenshots and the memory-card status. The state operations
// (SaveState, LoadState, LoadRewindPoint, the grab in SaveScreenshot) run on the CPU
// thread between the frames — Mednafen is not thread-safe — with the caller waiting;
// while the VM is paused, they run there too. When no game is running (or the run ends
// first), they answer "No game is running" instead of waiting.
// ---------------------------------------------------------------------------

// Saves the running game to [path], as the state container android_host.cpp describes, and
// clears nothing else. "" when it succeeded, else why not. Blocks (CPU thread).
std::string SaveState(const std::string& path);

// Loads the state at [path] into the running game and clears the rewind history: the game's
// disc-in-drive of an .m3u comes back with the state, through Mednafen's own media record.
// "" when it succeeded, else why not. Blocks (CPU thread).
std::string LoadState(const std::string& path);

struct SaveStateInfo
{
	std::string serial;		// Of the state's game; "" when it was the BIOS alone.
	std::vector<uint8_t> png;	// The thumbnail, empty when the file has none.
};

// The serial and thumbnail from the header of the state file at [path], none of the state
// itself; false when it is not one the core can read. Any thread; blocking file I/O.
bool GetSaveStateInfo(const std::string& path, SaveStateInfo* info);

// One rewind point as GetRewindPoints reads it.
struct RewindPoint
{
	int64_t age_ms = 0;		// How long ago (wall clock) it was taken.
	std::vector<uint8_t> png;	// The 160x120 thumbnail.
};

// The rewind points, newest first. Any thread.
void GetRewindPoints(std::vector<RewindPoint>* points);

// Goes back to the rewind point [index] (as GetRewindPoints lists it) and drops the points
// newer than it. False out of range. Blocks (CPU thread).
bool LoadRewindPoint(int index);

// Captures the last frame from the CPU thread, then encodes it as a PNG and writes it to
// [path] on a thread of the core's own (the caller is not blocked for the encode). Nothing
// happens with no game. Failures are logged.
void SaveScreenshot(const std::string& path);

// True while the running game's (ss module's) backup RAM has changes waiting to be written
// to its file — a state would race them — false otherwise or without a game. Any thread.
bool IsSavingMemoryCards();

// ---------------------------------------------------------------------------
// Cheats (Action Replay) and the main-RAM access of the memory search. Both reach the work
// RAM the way Mednafen's cheat engine does — through MDFNGameInfo->CheatInfo's MemRead and
// MemWrite, the ss module's readers and writers over its own physical memory map (ss.cpp),
// where both work RAMs sit; MemWrite also checks the map's write permission and keeps the
// SH-2 caches in step. Without a game they answer false, and the reads and writes run on
// the CPU thread between the frames, with the caller waiting (LoadState's way).
// ---------------------------------------------------------------------------

// The cheats of the game the codes belong to: their lines parsed once and applied by the frame
// loop before every frame, until SetCheats comes again or the process ends. Lines the core would
// not understand are left out. Any thread.
void SetCheats(const std::vector<std::string>& codes);

// [length] bytes of the app's main RAM (EmulatorCore.MAIN_RAM_BYTES: 2 MB, the High work RAM at
// 0x06000000 first, then the Low work RAM at 0x00200000; [offset] into it), as the big-endian
// bytes the Saturn's memory has. False with no game, or when [offset] and [length] do not fit.
// Blocks (CPU thread).
bool ReadMemory(uint32_t offset, uint32_t length, std::vector<uint8_t>* bytes);

// Writes the [size] (1, 2 or 4) big-endian bytes of [value] at [offset] of main RAM (see
// ReadMemory). False with no game, a [size] that is none of those, when it does not fit, or
// when a 2/4-byte write stands at an odd address (byte writes have no such care). Blocks
// (CPU thread).
bool WriteMemory(uint32_t offset, uint32_t size, uint32_t value);

uint32_t GetFrameNumber();
float GetFps();

} // namespace AndroidHost
