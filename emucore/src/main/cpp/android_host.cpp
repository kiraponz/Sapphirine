// The CPU thread (the thread RunSystem runs on), the render surface and the settings, ported to
// the Mednafen "ss" module. Discs load through Mednafen's own CD loading (load_disc); the
// BIOS-only boot replicates it piece by piece, since that always needs a CD image to open (see
// load_bios_only).

// types.h (through mednafen.h) #errors if it comes after common C headers that define NULL,
// EOF or errno values — so mednafen.h goes first, before the jni.h pulled in by android_host.h.
#include <mednafen/mednafen.h>
#include <mednafen/general.h>
#include <mednafen/mempatcher.h>
#include <mednafen/movie.h>
#include <mednafen/state_rewind.h>
#include <mednafen/hash/md5.h>
#include <mednafen/cdrom/CDInterface.h> // what ss/cdb.h's CDInterface needs
#include <mednafen/ss/ss.h>             // the MDFN_IEN_SS namespace and sscpu_timestamp_t
#include <mednafen/ss/cdb.h>

#include "android_host.h"
#include "audio_stream.h"
#include "disc.h"

#include <android/log.h>
#include <android/native_window.h>

#include <sys/stat.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

// EmulatedSS is defined at global scope in src/ss/ss.cpp. mednafen.cpp declares it the same
// way, under #ifdef WANT_SS_EMU.
extern const Mednafen::MDFNGI EmulatedSS;

using AndroidHost::AudioStream;

namespace
{

constexpr const char* LOG_TAG = "emucore";

// The Saturn core's framebuffer geometry: 704 pixels wide, 480 lines for NTSC and 576 for PAL
// (src/ss/vdp2_render.cpp, VDP2REND_SetGetVideoParams). One surface covers both.
constexpr int FB_WIDTH = 704;
constexpr int FB_HEIGHT_CAP = 576;

// Bytes in a Saturn BIOS ROM image, for picking an unnamed one out of the BIOS folder.
constexpr off_t BIOS_SIZE = 524288;

// ---------------------------------------------------------------------------
// JVM callbacks (the Kotlin NativeCore), set up in JNI_OnLoad.
// ---------------------------------------------------------------------------

JavaVM* s_java_vm = nullptr;
jclass s_native_core = nullptr;
jmethodID s_on_vm_paused_changed = nullptr;
jmethodID s_saf_open = nullptr;
jmethodID s_saf_stat = nullptr;
jmethodID s_saf_list = nullptr;
jmethodID s_on_pad_rumble = nullptr;

JNIEnv* get_jni_env()
{
	JNIEnv* env = nullptr;
	if (s_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
		return env;

	// A core thread Java has not seen yet. It stays attached until the process ends.
	s_java_vm->AttachCurrentThread(&env, nullptr);
	return env;
}

void on_vm_paused_changed(bool paused)
{
	JNIEnv* env = get_jni_env();
	env->CallStaticVoidMethod(s_native_core, s_on_vm_paused_changed, paused ? JNI_TRUE : JNI_FALSE);
	if (env->ExceptionCheck())
	{
		// The Kotlin side catches everything; clear, and keep the core thread alive.
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

// ---------------------------------------------------------------------------
// The VM: the run flags, the frame counters, the window and the frame data.
// The frame data belongs to the CPU thread only; the rest is shared.
// ---------------------------------------------------------------------------

std::mutex s_vm_mutex;
std::condition_variable s_vm_cond;
bool s_paused = false;
std::atomic<bool> s_stop{false};
std::atomic<bool> s_active{false};

std::atomic<int64_t> s_frame_number{0};
// The fps over the last full second, as fps * 2^16 (an atomic int64).
std::atomic<int64_t> s_fps{0};

// The window, guarded by s_window_mutex, which the CPU thread holds while a buffer is locked
// and drawn: SetWindow then waits until the draw is over instead of pulling it out from under
// the frame.
std::mutex s_window_mutex;
ANativeWindow* s_window = nullptr;
// The placement of the picture (SetDisplayLayout), under the same mutex, since the draw uses it.
bool s_portrait_top = true;
uint32_t s_top_margin = 0;
// The window the pixel format was last forced on, so that happens once per surface.
ANativeWindow* s_geometry_window = nullptr;

// Mednafen's per-frame video state, owned by the CPU thread while the VM runs.
std::unique_ptr<Mednafen::MDFN_Surface> s_surface;
std::unique_ptr<uint32_t[]> s_surface_pixels;	// The surface's external pixel buffer.
std::unique_ptr<int32_t[]> s_line_widths;	// espec.LineWidths, FB_HEIGHT_CAP long.
Mednafen::EmulateSpecStruct s_espec;

// ---------------------------------------------------------------------------
// The sound output: the AAudio stream and the settings that steer it. The ring between the
// CPU thread and the stream's callback lives in AudioStream (audio_stream.h).
// ---------------------------------------------------------------------------

// The largest output rate the emulation's sound buffer is sized for. Phones hand out their
// native rates (48 kHz on current ones, 96 kHz over USB at most); the buffer is big enough for
// a full frame at this rate with margin, whatever the device actually gave us.
constexpr int32_t MAX_SOUND_RATE = 96000;

// The app's Audio settings (SetSetting, section "Audio"; CoreKeys.kt). Volumes arrive as
// percentages (the app clamps to 0..200), the buffer target as milliseconds (10..500). They are
// read by every pushed frame, so a running VM takes them without a commit.
std::atomic<int32_t> s_volume_percent{100};
std::atomic<int32_t> s_fast_forward_volume_percent{100};
std::atomic<bool> s_output_muted{false};
std::atomic<int32_t> s_buffer_ms{100};

// True while the VM runs without pacing (NativeCore.setFastForward, from
// EmulatorCore.setFastForward); the frame loop reads it at every frame.
std::atomic<bool> s_fast_forward{false};

// The once-per-boots audio output, created in RunSystem and closed at the teardown (or the
// moment its stream is beyond saving). Producer side (push) is the CPU thread.
std::unique_ptr<AudioStream> s_audio;

// The int16 buffer the emulation renders a frame's sound into (espec.SoundBuf), and its size in
// frames. Owned by the CPU thread.
std::unique_ptr<int16_t[]> s_sound_buffer;
int32_t s_sound_buffer_frames = 0;

// The frame loop's to-do, set by RequestPause's resume side and consumed at the next frame
// boundary: flush the ring, prefill silence up to the target and play again.
std::atomic<bool> s_audio_play_pending{false};

// The rate control's current factor as fps*2^16-style fixed point (65536 = 1.0), for the log.
std::atomic<int64_t> s_drc_scale{65536};

// The rate-control constants (their formula and reasoning sit in the frame loop's comment):
// per frame, the pacing period is scaled by factor = 1 + gain × (fill − target) / target,
// with gain 1/100 and the factor clamped at ±0.5 %.
constexpr double DRC_GAIN = 0.01;
constexpr double DRC_CLAMP = 0.005;

// The ring's fill the rate control holds: the app's BufferMS, in frames of the device's rate.
int32_t target_fill_frames(int32_t rate)
{
	return (int32_t)std::min<int64_t>(s_buffer_ms.load(std::memory_order_relaxed) * (int64_t)rate / 1000,
		INT32_MAX);
}

// The Q8 gain the pushed samples get: the output volume, or the fast-forward one while
// fast-forwarding, and silence (0) when muted.
int32_t sound_gain_q8()
{
	if (s_output_muted.load(std::memory_order_relaxed))
		return 0;

	const bool fast_forward = s_fast_forward.load(std::memory_order_relaxed);
	const int32_t percent = (fast_forward
		? s_fast_forward_volume_percent
		: s_volume_percent).load(std::memory_order_relaxed);

	return std::clamp(percent, 0, 200) * 256 / 100;
}

// Pushes espec's frames that nothing has given the audio output yet —
// [SoundBufSize_DriverProcessed, SoundBufSize), the un-pushed tail Mednafen's own bookkeeping
// accounts for exactly — and marks them consumed. The MDFND_MidSync hook lands here at the mid-
// sync point inside MDFNI_Emulate, and the frame loop right after it for the frames produced
// after the sync; a frame's sound therefore goes out exactly once (SoundBufSize restarts from 0
// in each MDFNI_Emulate, Mednafen.cpp asserts the buffer over it itself).
void push_pending_sound(Mednafen::EmulateSpecStruct* espec)
{
	if (!espec->SoundBuf)
	{
		// The driver's sound-disabled configuration: keep the bookkeeping in step so a stream
		// that comes back (a disconnect reopen) finds counters that agree.
		espec->SoundBufSize_DriverProcessed = espec->SoundBufSize;
		return;
	}

	const int32_t consumed = espec->SoundBufSize_DriverProcessed;
	const int32_t produced = espec->SoundBufSize;

	// A sign flip below zero would mean a rewind swallowed the buffer — not wired up — but
	// clamp rather than read half a frame of garbage in front of SoundBuf.
	if (produced < consumed)
	{
		espec->SoundBufSize_DriverProcessed = produced;
		return;
	}

	if (AudioStream* audio = s_audio.get(); audio && produced > consumed)
		audio->push(espec->SoundBuf + (size_t)consumed * 2, produced - consumed, sound_gain_q8());

	espec->SoundBufSize_DriverProcessed = produced;
}

// The sound buffer espec renders into: room for one frame at MAX_SOUND_RATE, doubled for the
// slack of a long (PAL, interlaced) frame and the resampler's rounding.
constexpr int32_t SOUND_BUFFER_FRAMES = MAX_SOUND_RATE / 50 * 2;

// Points espec at the stream's rate and the sound buffer, or turns the sound off (MDFNI_Emulate
// asserts that SoundBuf, SoundRate and SoundBufMaxSize are all set or all zero).
void set_espec_sound(int32_t rate)
{
	if (rate > 0 && rate <= MAX_SOUND_RATE)
	{
		if (!s_sound_buffer)
			s_sound_buffer.reset(new int16_t[(size_t)SOUND_BUFFER_FRAMES * 2]);
		s_espec.SoundRate = rate;
		s_espec.SoundBuf = s_sound_buffer.get();
		s_espec.SoundBufMaxSize = SOUND_BUFFER_FRAMES;
	}
	else
	{
		s_espec.SoundRate = 0;
		s_espec.SoundBuf = nullptr;
		s_espec.SoundBufMaxSize = 0;
	}
}

// Starts the output from silence: the ring emptied and prefilled up to the target, so the
// callback drains silence while the emulation catches up instead of starting on an underrun.
// Only with the callback stopped (before the first start, after pause_audio()).
void play_audio()
{
	if (!s_audio)
		return;

	s_audio->flushAndPrefill(target_fill_frames(s_audio->rate()));
	if (!s_audio->start())
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "AAudio requestStart failed");
}

void pause_audio()
{
	if (s_audio)
		s_audio->pause();
}

// Opens the output and points espec at it. Without a stream the VM runs silent.
void open_audio()
{
	s_audio.reset(new AudioStream());

	std::string error;
	if (!s_audio->open(&error))
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "No sound: %s", error.c_str());
		s_audio.reset();
		set_espec_sound(0);
		return;
	}

	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Sound at %d Hz", s_audio->rate());
	set_espec_sound(s_audio->rate());
}

// A stream the error callback gave up on (headphones out, a Bluetooth switch) is reopened here,
// on the CPU thread, as AAudio wants. If the new device's rate differs, espec follows it and the
// ss module rebuilds its resampler at the next frame.
void reopen_audio_if_lost()
{
	if (!s_audio || !s_audio->takeError())
		return;

	s_audio->close();
	open_audio();
	play_audio();
}

// The pacing period's rate-control factor. The audio device's clock and CLOCK_MONOTONIC drift
// apart by up to a few hundredths of a percent, so a fixed period slowly drains or floods the
// ring (the drained case is the crackle). Each frame the period is scaled by
//
//   factor = 1 + DRC_GAIN × (fill − target) / target, clamped to 1 ± DRC_CLAMP
//
// so a fuller ring slows the emulation down a little and an emptier one speeds it up. With the
// gain at 1 %, the fill settles within a few percent of the target for any realistic drift, and
// the ±0.5 % clamp keeps the change of pitch inaudible (as RetroArch's dynamic rate control).
double drc_factor()
{
	if (!s_audio)
		return 1.0;

	const double target = target_fill_frames(s_audio->rate());
	if (target <= 0)
		return 1.0;

	const double error = (s_audio->fillFrames() - target) / target;
	return 1.0 + std::clamp(DRC_GAIN * error, -DRC_CLAMP, DRC_CLAMP);
}

// The empty disc list of the BIOS-only boot, which the ss module keeps a pointer into. It must
// outlive the VM: the module stores the address, not a copy. A disc boot uses Mednafen's own
// list (CDInterfaces in mednafen.cpp) instead.
std::vector<Mednafen::CDInterface*> s_cd_interfaces;

// The app's data directory (Initialize), and with it the BIOS folder the core reads.
std::string s_data_dir;

// The BIOS path keys the app sent (SetSetting, section "BIOS"). The core reads them back on the
// next boot, when they turn into absolute paths. A running VM does not see them, which matches
// how the app treats BIOS changes (appliesOnNextBoot).
std::mutex s_settings_mutex;
std::map<std::string, std::string> s_bios_settings;

// ---------------------------------------------------------------------------
// BIOS paths: find_bios_file / resolve_bios_path / apply_bios_settings.
// ---------------------------------------------------------------------------

// The name of the first regular file of exactly BIOS_SIZE bytes in the folder at [dir] (sorted
// by name), or "" when there is none.
std::string find_bios_file(const std::string& dir)
{
	DIR* dp = opendir(dir.c_str());
	if (!dp)
		return "";

	std::vector<std::string> names;
	struct dirent* de;
	while ((de = readdir(dp)))
		names.push_back(de->d_name);
	closedir(dp);

	std::sort(names.begin(), names.end());

	std::string ret;
	for (const std::string& name : names)
	{
		if (name == "." || name == "..")
			continue;

		const std::string path = dir + "/" + name;
		struct stat st;
		if (stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size == BIOS_SIZE)
		{
			ret = path;
			break;
		}
	}

	return ret;
}

// The absolute BIOS path the app's value [name] means: a file of the BIOS folder under the
// app's data directory, with "" meaning "the first BIOS-sized file there". Values that point
// out of the folder are not supported: the app copies a picked BIOS in (EmulatorCore.biosDir,
// anyway), and MDFN_MakeFName(MDFNMKF_FIRMWARE, ...) puts absolute paths through unchanged.
std::string resolve_bios_path(const std::string& name)
{
	if (name.size())
		return s_data_dir + "/bios/" + name;

	return find_bios_file(s_data_dir + "/bios");
}

// Writes the app's BIOS picks into Mednafen: BIOS/PathNTSCJ feeds ss.bios_jp, and
// BIOS/PathNTSCU (falling back to BIOS/PathPAL) feeds ss.bios_na_eu. An empty value means "let
// the emulator choose": the first BIOS-sized file in the BIOS folder (sorted by name). When the
// folder has no usable file, the setting keeps Mednafen's default and the boot fails on the
// missing BIOS file with the module's own error.
void apply_bios_settings()
{
	std::map<std::string, std::string> values;
	{
		std::lock_guard<std::mutex> lock(s_settings_mutex);
		values = s_bios_settings;
	}

	// Both region keys empty is fine: one file then serves both regions.
	const std::string jp_path = resolve_bios_path(values["PathNTSCJ"]);
	const std::string na_eu_path = resolve_bios_path(
		values["PathNTSCU"].empty() ? values["PathPAL"] : values["PathNTSCU"]);

	if (jp_path.size())
		Mednafen::MDFNI_SetSetting("ss.bios_jp", jp_path.c_str(), false);
	if (na_eu_path.size())
		Mednafen::MDFNI_SetSetting("ss.bios_na_eu", na_eu_path.c_str(), false);

	// Region handling and BIOS identification are later tasks: the Japanese region for now,
	// and the ROM-image sanity checks off, so any 512 KiB file boots.
	Mednafen::MDFNI_SetSetting("ss.bios_sanity", "0", false);
	Mednafen::MDFNI_SetSetting("ss.region_default", "jp", false);
}

// ---------------------------------------------------------------------------
// Input ports: the ss module has 13 of them (SMPC_PortInfo, src/ss/smpc.cpp) — ports 0-11
// the SMPC maps onto its (two) connectors and their multitaps, and port 12 "builtin", the
// SMPC's own switches (the reset button; the ST-V test/service/pause buttons). Connecting
// them is the driver's job after a load; the BIOS boot's first frame died exactly for the
// lack of it: SMPC_UpdateInput reads the builtin port's buffer (*MiscInputPtr), which
// stays null until MDFNI_SetInput(12, ...) provides it.
// ---------------------------------------------------------------------------

// One connected port's input buffer. Mednafen allocates it (MDFNI_SetInput), keeps it for the
// port's lifetime, and zeroes it on every connect — and with an all-zero buffer every device
// reads "nothing pressed": the gamepad inverts the driver's bits (src/ss/input/gamepad.cpp:
// buttons = ~data), and the builtin's reset button reads bit 0 (SMPC_UpdateInput). Step 3
// The pads' buttons arrive from the UI thread as bits (SetPadBind) and are copied into these
// buffers on the CPU thread right before each frame.
struct InputBuffer
{
	uint8* ptr = nullptr;
	uint32_t size = 0;
};

// Without multitaps, virtual ports 0 and 1 are the Saturn's two connectors (MapPorts in
// src/ss/smpc.cpp); both get a standard pad.
constexpr unsigned PAD_PORTS = 2;

InputBuffer s_gamepad_buffers[PAD_PORTS];	// Ports 0 and 1: the digital gamepads.
InputBuffer s_builtin_buffer;	// Port 12: the reset / ST-V buttons, never pressed by us.

// The pressed buttons per pad, one bit per button at its IODevice_Gamepad_IDII position
// (src/ss/input/gamepad.cpp: Z, Y, X, R, up, down, left, right, B, C, A, Start, -, -, -, L;
// PadButton.code on the Kotlin side). Written by the UI thread, read by the CPU thread.
std::atomic<uint16_t> s_pad_bits[PAD_PORTS];

// Hands the pads' current buttons to the core, ahead of MDFNI_Emulate (which reads the buffers
// as the frame starts, and at its mid-frame syncs). The gamepad device takes the bits as two
// little-endian bytes and inverts them itself.
void update_pad_buffers()
{
	for(unsigned port = 0; port < PAD_PORTS; port++)
	{
		const InputBuffer& buffer = s_gamepad_buffers[port];
		if(!buffer.ptr || buffer.size < 2)
			continue;

		const uint16_t bits = s_pad_bits[port].load(std::memory_order_relaxed);
		buffer.ptr[0] = (uint8)bits;
		buffer.ptr[1] = (uint8)(bits >> 8);
	}
}

// The index of the device called [name] on [port] (PortInfo.DeviceInfo). MDFNI_SetInput takes
// the index, and the lists can move; looking it up keeps the numbers next to their source.
unsigned device_connect_index(unsigned port, const char* name)
{
	const auto& devices = Mednafen::MDFNGameInfo->PortInfo[port].DeviceInfo;

	for(unsigned i = 0; i < devices.size(); i++)
		if(!strcmp(devices[i].ShortName, name))
			return i;

	throw std::runtime_error(std::string("The ss module has no \"") + name + "\" device on port "
						 + std::to_string(port));
}

// Connects [port] to the device index [type] and records the returned buffer (MDFNI_SetInput
// zeroes it; its length is what it derived from the device's InputByteSize).
InputBuffer connect_device(unsigned port, unsigned type)
{
	InputBuffer ret;

	ret.size = Mednafen::MDFNGameInfo->PortInfo[port].DeviceInfo[type].IDII.InputByteSize;
	ret.ptr = Mednafen::MDFNI_SetInput(port, type);
	if(!ret.ptr)
		throw std::runtime_error("The core has no input device for the connected port");

	return ret;
}

// The port wiring: a gamepad on each connector, nothing on the other virtual (multitap) ports,
// and the builtin switches last. All buffers come back zeroed.
void connect_input_devices()
{
	for(unsigned port = 0; port < PAD_PORTS; port++)
		s_gamepad_buffers[port] = connect_device(port, device_connect_index(port, "gamepad"));
	for(unsigned port = PAD_PORTS; port < 12; port++)
		Mednafen::MDFNI_SetInput(port, device_connect_index(port, "none"));
	s_builtin_buffer = connect_device(12, device_connect_index(12, "builtin"));
}

// ---------------------------------------------------------------------------
// The frame rate and the frame pacing.
// ---------------------------------------------------------------------------

// The frame rate, computed the way Saturn.emu's makeFrameRate does it for the Saturn's VDP2
// (NTSC, non-interlaced):
//
//   vdpClock = 1746818181.8181818181 / 61 / 4
//   rate = vdpClock / (454.99 * 263) ≈ 59.83 Hz
//
// PAL (1734687500 Hz clock, 313 lines) and interlaced (262.5 lines) rates need the emulated PAL
// flag and the interlace mode, which src/ss/vdp2.cpp keeps to itself; until they can be read,
// this is the NTSC non-interlaced rate — right for the BIOS screen.
double frame_rate()
{
	const double vdp_clock = 1746818181.8181818181 / 61.0 / 4.0;
	return vdp_clock / (454.99 * 263.0);
}

int64_t now_ns()
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000000000ll + ts.tv_nsec;
}

void sleep_until_ns(int64_t deadline)
{
	struct timespec ts;
	ts.tv_sec = (time_t)(deadline / 1000000000ll);
	ts.tv_nsec = (long)(deadline % 1000000000ll);
	while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr) == EINTR)
		;
}

// ---------------------------------------------------------------------------
// Drawing a frame to the window.
// ---------------------------------------------------------------------------

// Opaque black in WINDOW_FORMAT_RGBA_8888: RGB off, alpha (the top byte of the little-endian
// uint32) up. Frames are blitted with the alpha byte forced up by OR-ing this in: the ss
// renderer leaves the surface's alpha bits 0, and a transparent frame would show whatever sits
// under the game surface.
constexpr uint32_t OPAQUE_BLACK = 0xFF000000u;

// The placement of the 4:3 picture on a window [width]x[height]: the largest 4:3 rectangle
// that fits, centered — or at the top of a portrait window, [top_margin] pixels down, as
// SetDisplayLayout says. Under s_window_mutex (it reads the layout).
void picture_rect(int width, int height, int* x, int* y, int* w, int* h)
{
	*w = std::min(width, height * 4 / 3);
	*h = *w * 3 / 4;
	*x = (width - *w) / 2;
	*y = (s_portrait_top && height > width) ? std::min<int>((int)s_top_margin, height - *h)
						: (height - *h) / 2;
}

// Scales the frame's DisplayRect (per-line widths when given, else DisplayRect.w) over the rect
// [*x, *y, *w, *h] of [buf], sampling the surface with nearest neighbour and writing the alpha
// byte up. Under s_window_mutex, on the CPU thread between frames.
void blit_frame(ANativeWindow_Buffer* buf, int x, int y, int w, int h)
{
	const Mednafen::MDFN_Rect& dr = s_espec.DisplayRect;
	const int32_t* line_widths = s_espec.LineWidths;

	// ~0 says "the emulation does not set per-line widths; use DisplayRect.w for every line"
	// (mednafen.cpp's sanity checks use the same sentinel). The ss renderer always writes real
	// widths, but the guard costs nothing.
	const bool flat = line_widths[0] == (int32_t)~0;

	const uint32_t* spix = s_surface->pix<uint32_t>();
	const int32_t pitch = s_surface->pitchinpix;	// = FB_WIDTH.

	for (int yy = 0; yy < h; yy++)
	{
		int32_t sy = dr.y + (int32_t)((int64_t)yy * dr.h / h);
		sy = std::max<int32_t>(0, std::min<int32_t>(sy, FB_HEIGHT_CAP - 1));

		const int32_t lw = flat ? dr.w : line_widths[sy];
		if (lw <= 0)
			continue;	// What the emulation left undrawn stays black.

		const uint32_t* srow = spix + (size_t)sy * (size_t)pitch + dr.x;
		uint32_t* drow = reinterpret_cast<uint32_t*>(buf->bits) + (size_t)(y + yy) * buf->stride + x;

		// Each line starts at DisplayRect.x (the left border the h-overscan setting trims
		// off, vdp2_render.cpp) and is LineWidths[y] pixels wide from there (the core's
		// assert: DisplayRect.x + LineWidths[y] <= 704). DisplayRect.w, when LineWidths is
		// flat (~0), is that same width.
		for (int xx = 0; xx < w; xx++)
			drow[xx] = srow[(int32_t)((int64_t)xx * lw / w)] | OPAQUE_BLACK;
	}
}

// Draws the last frame to the window, if there is one. Holds s_window_mutex, which SetWindow
// waits on — a surface swap never happens while a buffer is locked.
void draw_frame()
{
	std::lock_guard<std::mutex> lock(s_window_mutex);

	ANativeWindow* window = s_window;
	if (!window)
		return;

	// The buffer has to be in the format the blit writes (see OPAQUE_BLACK). Width and height
	// of 0 leave the size alone: the window is already the size the app's surface reports.
	if (window != s_geometry_window)
	{
		const int32_t ret = ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888);
		if (ret != 0)
		{
			__android_log_print(ANDROID_LOG_WARN, LOG_TAG,
				"ANativeWindow_setBuffersGeometry(RGBA_8888) failed: %d", ret);
			return;
		}
		s_geometry_window = window;
	}

	ANativeWindow_Buffer buffer;
	if (ANativeWindow_lock(window, &buffer, nullptr) != 0)
	{
		// The surface is gone while the VM was at it (SetWindow releases the old one; the
		// next SetWindow call sorts it out again). Skip the frame, the next one draws.
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "ANativeWindow_lock failed");
		return;
	}

	int x, y, w, h;
	picture_rect(buffer.width, buffer.height, &x, &y, &w, &h);

	// Whatever the frame does not reach (the letterbox around the 4:3 picture) turns opaque
	// black first.
	uint32_t* pix = reinterpret_cast<uint32_t*>(buffer.bits);
	std::fill(pix, pix + (size_t)buffer.stride * buffer.height, OPAQUE_BLACK);

	if (w > 0 && h > 0 && s_espec.DisplayRect.h > 0)
		blit_frame(&buffer, x, y, w, h);

	ANativeWindow_unlockAndPost(window);
}

// ---------------------------------------------------------------------------
// Loading: a disc, or the BIOS alone. Either leaves a game MDFNI_CloseGame can take down, or
// throws with nothing to close but (for the BIOS) the bare descriptor RunSystem's catch drops.
// ---------------------------------------------------------------------------

// Boots the disc image at [path] (disc.h) through Mednafen's own CD loading, which does
// everything LoadCDGame does for a file it opened itself, and then closes the tray on it, as
// Mednafen's driver does after a load with the layout's default (drive 0, "Tray Closed", the
// first disc).
void load_disc(const std::string& path)
{
	Mednafen::CDInterface* cdif = AndroidHost::OpenDisc(path);

	// The loader reports its failures as error notices rather than exceptions, and takes the
	// disc with it either way.
	AndroidHost::TakeCoreErrorNotice();
	if (!Mednafen::MDFNI_LoadExternalCD("ss", path.c_str(), cdif))
	{
		const std::string notice = AndroidHost::TakeCoreErrorNotice();
		throw std::runtime_error(notice.size() ? notice : "The disc could not be loaded");
	}

	try
	{
		if (!Mednafen::MDFNI_SetMedia(0, 2, 0, 0))
			throw std::runtime_error("The disc could not be put in the drive");
	}
	catch (...)
	{
		Mednafen::MDFNI_CloseGame();
		throw;
	}
}

// The boot without a disc. Mednafen's MDFNI_LoadGame (and its LoadCDGame) requires a CD image
// to open, and their cleanup and module-selection helpers are file-local, so this replicates
// LoadCDGame's steps with an empty disc list, which the ss module is designed to take (git.h:
// CDInterfaces "may be == 0 for LoadCD()"). With the region left at ss.region_default (jp), the
// BIOS boots and reaches its menu, and the drive reads as tray-open, empty.
void load_bios_only()
{
	const Mednafen::MDFNGI* module = nullptr;
	for (const Mednafen::MDFNGI* m : Mednafen::MDFNSystems)
	{
		if (!strcmp(m->shortname, "ss"))
		{
			module = m;
			break;
		}
	}
	if (!module)
		throw std::runtime_error("The \"ss\" module is not part of this build");

	// The drive layout of a CD system with no discs: what Mednafen's (file-local)
	// MDFN_LoadCD produces for an empty .m3u — the drive with its three states, and no
	// media to default to, so the drive starts in state 0, "Tray Open".
	std::unique_ptr<Mednafen::RMD_Layout> rmd(new Mednafen::RMD_Layout());
	{
		Mednafen::RMD_Drive drive;

		drive.Name = "Virtual CD Drive";
		drive.PossibleStates.push_back(Mednafen::RMD_State({"Tray Open", false, false, true}));
		drive.PossibleStates.push_back(Mednafen::RMD_State({"Tray Closed (Empty)", false, false, false}));
		drive.PossibleStates.push_back(Mednafen::RMD_State({"Tray Closed", true, true, false}));
		drive.CompatibleMedia.push_back(0);
		drive.MediaMtoPDelay = 2000;

		rmd->Drives.push_back(drive);
		rmd->DrivesDefaults.push_back(Mednafen::RMD_DriveDefaults({0, 0, 0}));
		rmd->MediaTypes.push_back(Mednafen::RMD_MediaType({"CD"}));
	}

	// The game name Mednafen would derive from the disc ("BIOS"), for the module's log
	// lines and its cheat-file naming.
	Mednafen::MDFN_SetFileBase(s_data_dir, "BIOS", "");
	Mednafen::MDFNGameInfo = new Mednafen::MDFNGI(*module);
	Mednafen::MDFNGameInfo->RMD = rmd.release();

	// The disc-layout MD5 of zero discs (LoadCDGame calls its file-local
	// CalcDiscsLayoutMD5, which hashes nothing for an empty list).
	Mednafen::md5_context layout_md5;
	layout_md5.starts();
	layout_md5.finish(Mednafen::MDFNGameInfo->MD5);

	// With an empty disc list, the ss module skips all disc reading: the game ID and the
	// region detection fall out of their loops, and the region stays region_default. A
	// failure here leaves the module already self-cleaned (LoadCD's contract: it throws only
	// after its own Cleanup()), and RunSystem's catch drops the descriptor without CloseGame.
	Mednafen::MDFNGameInfo->LoadCD(&s_cd_interfaces);

	// From here on, MDFNI_CloseGame() is what undoes the load (git.h: CloseGame only after
	// a successful LoadCD).
	try
	{
		// The module builds its deinterlacer through the setting's change callback, which
		// only runs once a game is loaded (settings.cpp Set()); putting the setting into
		// place now creates it. "weave" is Mednafen's default.
		Mednafen::MDFNI_SetSetting("video.deinterlacer", "weave", false);

		// The drive after load, as the disc changer would leave it for "no disc": tray open,
		// nothing in it. (CDB_SetDisc's tray_open matches drive state 0.)
		MDFN_IEN_SS::CDB_SetDisc(true, nullptr);

		// The parts of Mednafen's LoadCommonPost that matter without per-game configuration
		// files, a custom palette or a debugger. Nothing starts a movie or a rewind here
		// (MDFNMOV_CheckMovies only lists what exists), and temporal blur stays off
		// (ss.tblur defaults to "0").
		Mednafen::MDFNGameInfo->name = "BIOS";
		Mednafen::MDFN_LoadGameCheats(nullptr);
		Mednafen::MDFNMP_InstallReadPatches();
		Mednafen::MDFNI_SetLayerEnableMask(~0ULL);
		Mednafen::MDFNSS_CheckStates();
		Mednafen::MDFNMOV_CheckMovies();
		Mednafen::MDFNSRW_Begin();
	}
	catch (...)
	{
		Mednafen::MDFNI_CloseGame();
		throw;
	}
}

} // namespace

namespace AndroidHost
{

void SetJavaVM(JavaVM* vm, jclass native_core)
{
	s_java_vm = vm;
	s_native_core = native_core;

	JNIEnv* env = get_jni_env();
	s_on_vm_paused_changed = env->GetStaticMethodID(native_core, "onVmPausedChanged", "(Z)V");
	s_saf_open = env->GetStaticMethodID(native_core, "safOpen", "(Ljava/lang/String;)I");
	s_saf_stat = env->GetStaticMethodID(native_core, "safStat", "(Ljava/lang/String;)[J");
	s_saf_list = env->GetStaticMethodID(native_core, "safList", "(Ljava/lang/String;)[Ljava/lang/String;");
	s_on_pad_rumble = env->GetStaticMethodID(native_core, "onPadRumble", "(III)V");

	// A missing callback is a programming error and a broken core; leaving the exception
	// raised would only shift it into the first JNI call after this.
	if (env->ExceptionCheck())
	{
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

bool Initialize(const std::string& data_dir, std::string* error)
{
	s_data_dir = data_dir;

	try
	{
		// Mednafen writes here (its filesys.* settings, relative to the base directory): the
		// backup memory, the save states, the cheats. Make them ahead, so the writes at a
		// (future) close succeed.
		mkdir((data_dir + "/mednafen").c_str(), 0755);
		mkdir((data_dir + "/mednafen/sav").c_str(), 0755);
		mkdir((data_dir + "/mednafen/mcs").c_str(), 0755);
		mkdir((data_dir + "/mednafen/mcm").c_str(), 0755);
		mkdir((data_dir + "/mednafen/cheats").c_str(), 0755);

		if (!Mednafen::MDFNI_Init())
			throw std::runtime_error("Mednafen initialization failed");
		if (!Mednafen::MDFNI_InitFinalize((data_dir + "/mednafen").c_str()))
			throw std::runtime_error("Mednafen initialization failed");
	}
	catch (const std::exception& e)
	{
		*error = e.what();
		return false;
	}

	return true;
}

bool RunSystem(const std::string& path, const std::string& save_state, std::string* error)
{
	if (save_state.size())
	{
		*error = "Save states are not supported yet";
		return false;
	}
	if (s_active.load())
	{
		*error = "A system is already running";
		return false;
	}

	s_frame_number.store(0);
	s_fps.store(0);

	// Set once MDFNGameInfo can go through MDFNI_CloseGame: CloseGame must only run after a
	// successful LoadCD (git.h). Seen by the catch below, so it lives at function scope.
	bool close_safe = false;

	try
	{
		if (!Mednafen::MDFNSystems.size())
			throw std::runtime_error("The core has not been initialized");

		apply_bios_settings();

		if (path.size())
			load_disc(path);
		else
			load_bios_only();
		// From here on the game goes down through MDFNI_CloseGame.
		close_safe = true;

		// Where the driver would connect the input devices (nothing in LoadCommonPost does
		// it): without this the first Emulate crashes in SMPC_UpdateInput. See the Input
		// ports section.
		connect_input_devices();

		// -------------------------------------------------------------
		// The frame loop, on this thread: pause-aware, paced to the frame
		// rate (nudged by the audio rate control), drawing under the window
		// lock, and feeding the audio ring. No input yet.
		// -------------------------------------------------------------

		s_surface_pixels.reset(new uint32_t[FB_WIDTH * FB_HEIGHT_CAP]);
		std::fill(s_surface_pixels.get(), s_surface_pixels.get() + FB_WIDTH * FB_HEIGHT_CAP, 0u);
		// External pixels (MDFN_Surface's void* constructor, alloc_init_pixels = false): the
		// surface never owns or zeroes them, so they stay ours to fill and clear.
		s_surface.reset(new Mednafen::MDFN_Surface(s_surface_pixels.get(), FB_WIDTH, FB_HEIGHT_CAP,
			FB_WIDTH, Mednafen::MDFN_PixelFormat(Mednafen::MDFN_PixelFormat::ABGR32_8888), false));

		s_line_widths.reset(new int32_t[FB_HEIGHT_CAP]);
		// The emulation sets a line's width when it draws it, bottom-up per frame; lines it
		// did not touch (the first two, say Saturn.emu's runFrame) read 0 and do not draw.
		std::fill(s_line_widths.get(), s_line_widths.get() + FB_HEIGHT_CAP, 0);

		s_espec = Mednafen::EmulateSpecStruct{};
		s_espec.surface = s_surface.get();
		s_espec.LineWidths = s_line_widths.get();

		// The boot is on. Whatever way this function leaves from here — the normal shutdown
		// below, or an exception out of the frame loop — the run is over, and the next boot
		// must find no active system (a mid-run failure once leaked a stuck one): the VM's
		// flags and the last fps reset here, on every exit, and the sound stops (closing the
		// stream waits for its callback, so the ring and the buffer go after it).
		struct RunEndGuard
		{
			~RunEndGuard()
			{
				s_audio.reset();
				s_sound_buffer.reset();
				s_active.store(false);
				s_stop.store(false);
				s_fps.store(0);
			}
		} run_end_guard;

		open_audio();

		// Every boot starts with nothing held.
		for(auto& bits : s_pad_bits)
			bits.store(0, std::memory_order_relaxed);

		s_active.store(true);
		s_stop.store(false);
		s_paused = false;
		on_vm_paused_changed(false);

		const double rate = frame_rate();
		const int64_t frame_ns = (int64_t)(1e9 / rate);
		__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s boot, pacing to %.3f fps", path.size() ? "Disc" : "BIOS", rate);

		play_audio();

		int64_t deadline = now_ns() + frame_ns;
		int64_t fps_since = now_ns();
		int64_t fps_frames = 0;
		int64_t emu_ns_total = 0;
		bool fast_forward = false;

		while (!s_stop.load())
		{
			// Paused: the sound stops before the wait, and starts again from a fresh prefill
			// after it, so a resume neither replays stale sound nor starts on an underrun.
			{
				std::unique_lock<std::mutex> lock(s_vm_mutex);
				if (s_paused && !s_stop.load())
				{
					lock.unlock();
					pause_audio();
					lock.lock();
					s_vm_cond.wait(lock, []() { return s_stop.load() || !s_paused; });
					if (!s_stop.load())
					{
						lock.unlock();
						play_audio();
						deadline = now_ns() + frame_ns;
					}
				}
			}
			if (s_stop.load())
				break;

			reopen_audio_if_lost();

			// Leaving fast-forward: the ring is as full as the drops let it get, far above the
			// target, and the rate control's 0.5 % would take minutes to drain it. Restart it
			// from the prefill instead.
			const bool ff = s_fast_forward.load(std::memory_order_relaxed);
			if (fast_forward && !ff)
			{
				pause_audio();
				play_audio();
				deadline = now_ns() + frame_ns;
			}
			fast_forward = ff;

			// Each MDFNI_Emulate restarts SoundBufSize from 0; the driver-side counters are
			// ours to restart (Mednafen's own driver uses a fresh espec per frame).
			s_espec.SoundBufSize_DriverProcessed = 0;
			s_espec.MasterCycles_DriverProcessed = 0;

			update_pad_buffers();

			const int64_t t0 = now_ns();
			Mednafen::MDFNI_Emulate(&s_espec);
			const int64_t t1 = now_ns();

			// What the mid-frame syncs (MDFND_MidSync) did not hand over yet.
			push_pending_sound(&s_espec);

			s_frame_number.fetch_add(1);
			fps_frames++;
			emu_ns_total += t1 - t0;

			draw_frame();

			// Pace against an absolute deadline, so a late frame does not push the next one.
			// If a whole frame or more was lost (a surface swap, a hiccup), jump the deadline
			// instead of running a burst of frames with no sleep to catch up. Fast-forward
			// does not sleep at all; the ring drops what does not fit.
			const double drc = fast_forward ? 1.0 : drc_factor();
			s_drc_scale.store((int64_t)(drc * 65536.0), std::memory_order_relaxed);
			const int64_t now = now_ns();
			if (fast_forward)
				deadline = now;
			else
			{
				deadline += (int64_t)(frame_ns * drc);
				if (deadline - now < -frame_ns)
					deadline = now + frame_ns;
				else
					sleep_until_ns(deadline);
			}

			// Once a second: the fps, how long Emulate takes, and the sound's health — the
			// ring's fill against the target, under- and overruns, and the rate control.
			const int64_t ct = now_ns();
			if (ct - fps_since >= 1000000000ll)
			{
				double fps = fps_frames * 1e9 / (ct - fps_since);
				double avg_ms = fps_frames ? (emu_ns_total / 1e6) / fps_frames : 0.0;
				s_fps.store((int64_t)(fps * 65536.0));

				int64_t under = 0, over = 0;
				double fill_ms = 0;
				if (s_audio)
				{
					s_audio->takeStatistics(&under, &over);
					fill_ms = s_audio->fillFrames() * 1000.0 / s_audio->rate();
				}
				const double drc_percent = (s_drc_scale.load(std::memory_order_relaxed) / 65536.0 - 1.0) * 100.0;

				__android_log_print(ANDROID_LOG_INFO, LOG_TAG,
					"fps=%.2f emu=%.2fms audio=%.0fms under=%lld over=%lld drc=%+.3f%%",
					fps, avg_ms, fill_ms, (long long)under, (long long)over, drc_percent);
				fps_frames = 0;
				emu_ns_total = 0;
				fps_since = ct;
			}
		}

		s_audio.reset();
		Mednafen::MDFNI_CloseGame();
		close_safe = false;
	}
	catch (const std::exception& e)
	{
		if (Mednafen::MDFNGameInfo && !close_safe)
		{
			// The boot failed before (or during) LoadCD, which cleans the module itself up
			// (its contract: throw only after Cleanup()). Drop the descriptor without
			// CloseGame — git.h forbids CloseGame on a failed Load — and let
			// MDFNI_CloseGame free everything else Mednafen set up. It skips CloseGame
			// when MDFNGameInfo is null.
			delete Mednafen::MDFNGameInfo->RMD;
			delete Mednafen::MDFNGameInfo;
			Mednafen::MDFNGameInfo = nullptr;
		}
		Mednafen::MDFNI_CloseGame();

		s_surface.reset();
		s_surface_pixels.reset();
		s_line_widths.reset();
		s_espec = Mednafen::EmulateSpecStruct{};

		*error = e.what();
		return false;
	}

	// The VM shut down: release the frame data for the next boot (MDFNI_CloseGame above took
	// the emulation down, and run_end_guard resets the run flags). The app sees the stop
	// itself, so no paused callback goes out.
	s_surface.reset();
	s_surface_pixels.reset();
	s_line_widths.reset();
	s_espec = Mednafen::EmulateSpecStruct{};

	return true;
}

bool HasActiveSystem()
{
	return s_active.load();
}

void RequestPause(bool paused)
{
	if (!s_active.load())
		return;

	{
		std::lock_guard<std::mutex> lock(s_vm_mutex);
		if (s_paused == paused)
			return;
		s_paused = paused;
	}

	// The CPU thread sits in the condition wait while paused; wake it.
	s_vm_cond.notify_all();
	on_vm_paused_changed(paused);
}

void RequestShutdown()
{
	s_stop.store(true);
	{
		// The loop may be waiting under the pause for the stop to show; it reads s_stop
		// under s_vm_mutex, so the notification goes out with the mutex held, as usual
		// with condvars.
		std::lock_guard<std::mutex> lock(s_vm_mutex);
	}
	s_vm_cond.notify_all();
}

void SetPadBind(uint32_t port, uint32_t index, bool pressed)
{
	if(port >= PAD_PORTS || index >= 16)
		return;

	const uint16_t bit = (uint16_t)(1u << index);
	if(pressed)
		s_pad_bits[port].fetch_or(bit, std::memory_order_relaxed);
	else
		s_pad_bits[port].fetch_and((uint16_t)~bit, std::memory_order_relaxed);
}

void SetFastForward(bool enabled)
{
	// The frame loop picks it up at the next frame (and restarts the sound when it ends).
	s_fast_forward.store(enabled, std::memory_order_relaxed);
}

void SetSetting(const char* section, const char* key, const char* value)
{
	// The app sends PS1-era keys that the Saturn core has no use for; only the BIOS paths and
	// the Audio section's volumes and buffer size mean anything now.
	if (!strcmp(section, "Audio"))
	{
		if (!strcmp(key, "OutputVolume"))
			s_volume_percent.store(std::clamp(atoi(value), 0, 200), std::memory_order_relaxed);
		else if (!strcmp(key, "FastForwardVolume"))
			s_fast_forward_volume_percent.store(std::clamp(atoi(value), 0, 200), std::memory_order_relaxed);
		else if (!strcmp(key, "OutputMuted"))
			s_output_muted.store(!strcmp(value, "true"), std::memory_order_relaxed);
		else if (!strcmp(key, "BufferMS"))
			s_buffer_ms.store(std::clamp(atoi(value), 10, 500), std::memory_order_relaxed);
		return;
	}

	if (strcmp(section, "BIOS"))
		return;

	std::lock_guard<std::mutex> lock(s_settings_mutex);
	s_bios_settings[key] = value;
}

void CommitSettings()
{
	// Nothing but the BIOS paths (SetSetting) can reach a running VM right now, and those wait
	// for the next boot by design.
}

void SetWindow(ANativeWindow* window, uint32_t width, uint32_t height)
{
	// The blit fills the window with the frame itself, so the surface's reported size has no
	// other use right now. It arrives with the reference (jni.cpp), like the format does:
	// the buffer is forced to WINDOW_FORMAT_RGBA_8888 once per surface, at the first draw.
	(void)width;
	(void)height;

	// Takes over the reference jni.cpp acquired with ANativeWindow_fromSurface. The mutex is
	// held until the current frame's buffer is unlocked again, so the old window is released
	// only once nobody draws to it.
	ANativeWindow* old;
	{
		std::lock_guard<std::mutex> lock(s_window_mutex);
		old = s_window;
		s_window = window;
		s_geometry_window = nullptr;	// The format goes on the next window drawn to.
	}

	if (old)
		ANativeWindow_release(old);
}

void SetDisplayRefreshRate(float hz)
{
	// The pacing goes by the computed frame rate, not by the display's refresh.
	(void)hz;
}

void SetDisplayLayout(bool portrait_top, uint32_t top_margin)
{
	std::lock_guard<std::mutex> lock(s_window_mutex);
	s_portrait_top = portrait_top;
	s_top_margin = top_margin;
}

void SetOsdSafeArea(uint32_t left, uint32_t top, uint32_t right, uint32_t bottom)
{
	// Nothing draws overlays yet; the insets sit with the app.
	(void)left;
	(void)top;
	(void)right;
	(void)bottom;
}

// The SAF callbacks clear any exception the Kotlin side let through (it catches everything) and
// read it as "not there".
int SafOpen(const std::string& path)
{
	JNIEnv* env = get_jni_env();
	jstring jpath = env->NewStringUTF(path.c_str());
	const jint fd = env->CallStaticIntMethod(s_native_core, s_saf_open, jpath);
	env->DeleteLocalRef(jpath);
	if (env->ExceptionCheck())
	{
		env->ExceptionClear();
		return -1;
	}
	return fd;
}

bool SafStat(const std::string& path, SafFileInfo* info)
{
	JNIEnv* env = get_jni_env();
	jstring jpath = env->NewStringUTF(path.c_str());
	auto values = static_cast<jlongArray>(env->CallStaticObjectMethod(s_native_core, s_saf_stat, jpath));
	env->DeleteLocalRef(jpath);
	if (env->ExceptionCheck())
	{
		env->ExceptionClear();
		return false;
	}
	if (!values)
		return false;

	// [size, modified (seconds), 1 for a folder or 0] (SafFiles.stat).
	jlong v[3] = {};
	const bool ok = env->GetArrayLength(values) >= 3;
	if (ok)
		env->GetLongArrayRegion(values, 0, 3, v);
	env->DeleteLocalRef(values);
	if (!ok)
		return false;

	info->size = (uint64_t)v[0];
	info->modified_s = v[1];
	info->is_directory = v[2] != 0;
	return true;
}

bool SafList(const std::string& path, std::vector<std::string>* names)
{
	JNIEnv* env = get_jni_env();
	jstring jpath = env->NewStringUTF(path.c_str());
	auto entries = static_cast<jobjectArray>(env->CallStaticObjectMethod(s_native_core, s_saf_list, jpath));
	env->DeleteLocalRef(jpath);
	if (env->ExceptionCheck())
	{
		env->ExceptionClear();
		return false;
	}
	if (!entries)
		return false;

	// Each entry is "name\tisDir\tsize\tmodified" (SafFiles.list); only the name is wanted.
	const jsize count = env->GetArrayLength(entries);
	for (jsize i = 0; i < count; i++)
	{
		auto entry = static_cast<jstring>(env->GetObjectArrayElement(entries, i));
		if (!entry)
			continue;
		const char* chars = env->GetStringUTFChars(entry, nullptr);
		std::string line(chars);
		env->ReleaseStringUTFChars(entry, chars);
		env->DeleteLocalRef(entry);
		names->push_back(line.substr(0, line.find('\t')));
	}
	env->DeleteLocalRef(entries);
	return true;
}

uint32_t GetFrameNumber()
{
	return (uint32_t)s_frame_number.load();
}

float GetFps()
{
	if (!s_active.load())
		return 0.0f;

	return (float)s_fps.load() / 65536.0f;
}

} // namespace AndroidHost

namespace Mednafen
{

// The ss module syncs several times a frame (ss.cpp's MidSync) and flushes its sound into
// espec at each; handing it to the ring right away keeps the ring's fill smooth within the
// frame instead of arriving in one lump at the end of it. On the CPU thread, inside
// MDFNI_Emulate.
void MDFND_MidSync(EmulateSpecStruct* espec, const unsigned flags)
{
	push_pending_sound(espec);

	// The module reads the pads again after this (UpdateSMPCInput), so a press that came in
	// during the frame reaches the game this frame rather than the next.
	if(flags & MIDSYNC_FLAG_UPDATE_INPUT)
		update_pad_buffers();
}

} // namespace Mednafen
