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
#include <mednafen/state.h>
#include <mednafen/MemoryStream.h>
#include <mednafen/hash/md5.h>
#include <mednafen/cdrom/CDInterface.h> // what ss/cdb.h's CDInterface needs
#include <mednafen/ss/ss.h>             // the MDFN_IEN_SS namespace and sscpu_timestamp_t
#include <mednafen/ss/cdb.h>

#include "android_host.h"
#include "audio_stream.h"
#include "bios.h"
#include "disc.h"
#include "png_writer.h"

#include <android/log.h>
#include <android/native_window.h>

#include <sys/stat.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <dirent.h>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

// EmulatedSS is defined at global scope in src/ss/ss.cpp. mednafen.cpp declares it the same
// way, under #ifdef WANT_SS_EMU.
extern const Mednafen::MDFNGI EmulatedSS;

// The fork exports the ss module's un-written backup RAM flag (src/ss/ss.cpp), for the app's
// memory-card status (IsSavingMemoryCards).
namespace MDFN_IEN_SS { bool SS_BackupRAMPending(void); }

using AndroidHost::AudioStream;

namespace
{

constexpr const char* LOG_TAG = "emucore";

// The Saturn core's framebuffer geometry: 704 pixels wide, 480 lines for NTSC and 576 for PAL
// (src/ss/vdp2_render.cpp, VDP2REND_SetGetVideoParams). One surface covers both.
constexpr int FB_WIDTH = 704;
constexpr int FB_HEIGHT_CAP = 576;

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

// The picture's shape on the window (SetSetting "Display"/AspectRatio; CoreKeys.kt).
enum class AspectRatio
{
	Standard,	// 4:3, the TV's
	Wide,		// 16:9, for games that draw squeezed for wide TVs
	Stretch,	// the whole window
};
std::atomic<AspectRatio> s_aspect_ratio{AspectRatio::Standard};
// The window the pixel format was last forced on, so that happens once per surface.
ANativeWindow* s_geometry_window = nullptr;

// Mednafen's per-frame video state, owned by the CPU thread while the VM runs.
std::unique_ptr<Mednafen::MDFN_Surface> s_surface;
std::unique_ptr<uint32_t[]> s_surface_pixels;	// The surface's external pixel buffer.
std::unique_ptr<int32_t[]> s_line_widths;	// espec.LineWidths, FB_HEIGHT_CAP long.
Mednafen::EmulateSpecStruct s_espec;

// ---------------------------------------------------------------------------
// The task queue: work the other threads hand to the CPU thread (a save state or load, a
// rewind load, the screenshot's grab), run there between the frames — next to the disc
// changes the frame loop steps through — because Mednafen is not thread-safe. Each task
// counts as its own small request: the poster takes it in and waits for it to have run.
// ---------------------------------------------------------------------------

struct CpuTask
{
	std::function<std::string()> body;	// "" for success, else why it did not work.
	std::mutex mutex;
	std::condition_variable cond;
	bool done = false;
	std::string error;			// What body returned, read through by the poster.
};

std::mutex s_task_mutex;
std::deque<std::shared_ptr<CpuTask>> s_task_queue;

// Runs [body] on the CPU thread, the poster waiting for the result: "" when it succeeded,
// else why not. "No game is running" without going near the queue when nothing is running
// (or has just stopped), so a poster never waits for what nothing will run.
std::string post_cpu_task(std::function<std::string()>&& body)
{
	if (s_stop.load() || !s_active.load())
		return "No game is running";

	auto task = std::make_shared<CpuTask>();
	task->body = std::move(body);

	{
		std::lock_guard<std::mutex> lock(s_task_mutex);
		s_task_queue.push_back(task);
	}

	// The CPU thread checks the queue at every frame boundary, but waits on s_vm_cond
	// while paused: notify for that case (and harmlessly, when not, over the pacing).
	{
		std::lock_guard<std::mutex> lock(s_vm_mutex);
		s_vm_cond.notify_all();
	}

	std::unique_lock<std::mutex> wait(task->mutex);
	task->cond.wait(wait, [&task]() { return task->done; });
	return task->error;
}

// Whether anything is queued, for the pause wait's predicate (under s_vm_mutex).
bool tasks_queued()
{
	std::lock_guard<std::mutex> lock(s_task_mutex);
	return !s_task_queue.empty();
}

// Pops the first task of the queue and runs it, on the CPU thread, without any of the VM's
// locks held (the poster of the next task, a pause or a stop all stay possible); takes the
// result and lets the poster go. False with nothing queued.
bool run_one_task()
{
	std::shared_ptr<CpuTask> task;
	{
		std::lock_guard<std::mutex> lock(s_task_mutex);
		if (s_task_queue.empty())
			return false;
		task = s_task_queue.front();
		s_task_queue.pop_front();
	}

	task->error = task->body ? task->body() : std::string("No game is running");

	{
		std::lock_guard<std::mutex> lock(task->mutex);
		task->done = true;
	}
	task->cond.notify_all();
	return true;
}

// Answers every task left queued — anything the loop will not come around to run — with
// "No game is running": the CPU thread calls this when it leaves the frame loop (the
// RunEndGuard, whatever way the run ended).
void fail_queued_tasks()
{
	std::deque<std::shared_ptr<CpuTask>> left;
	{
		std::lock_guard<std::mutex> lock(s_task_mutex);
		left.swap(s_task_queue);
	}
	for (const auto& task : left)
	{
		task->error = "No game is running";
		{
			std::lock_guard<std::mutex> lock(task->mutex);
			task->done = true;
		}
		task->cond.notify_all();
	}
}

// ---------------------------------------------------------------------------
// Cheats (Action Replay) and the app's picture of the main RAM. The frame loop applies the
// cheats a frame at a time, right before MDFNI_Emulate, and the memory search's reads and
// writes come through the queue above as AndroidHost::ReadMemory/WriteMemory. Both reach
// the work RAM the way Mednafen's own cheat engine does — the ss module's CheatMemRead /
// CheatMemWrite, through MDFNGameInfo->CheatInfo (git.h): the module looks the address up
// in its physical memory map, where both work RAMs sit, keeps the SH-2 caches in step with
// every byte it writes, and answers with the big-endian bytes the memory has.
// ---------------------------------------------------------------------------

// The app's main RAM (the memory search's offsets): the High work RAM at 0x06000000 first,
// then the Low work RAM at 0x00200000.
constexpr uint32_t MAIN_RAM_BYTES = 2 * 1024 * 1024;
constexpr uint32_t WORK_RAM_HIGH_ADDRESS = 0x06000000;
constexpr uint32_t WORK_RAM_LOW_ADDRESS = 0x00200000;

// The real address of [offset] (the cheat hooks mask 27 bits, so the High RAM's echo above
// 0x060FFFFF needs no further thought).
inline uint32_t main_ram_saturn_address(uint32_t offset)
{
	return offset < MAIN_RAM_BYTES / 2
		? WORK_RAM_HIGH_ADDRESS + offset
		: WORK_RAM_LOW_ADDRESS + (offset - MAIN_RAM_BYTES / 2);
}

// One line of a parsed cheat code: a write of [size] (1 or 2) bytes of [value], big-endian,
// at [address] — or, when [compare], not a write but the condition the next line stands
// under (it runs only when the [size]-byte value at [address] is this one).
struct CheatLine
{
	uint32_t address = 0;
	uint16_t value = 0;
	uint8_t size = 2;
	bool compare = false;
};

// The cheats the app set (SetCheats), their lines parsed once at the call: the frame loop
// takes the current list under the mutex as the shared pointer (a set is a pointer swap,
// and the loop's own reference keeps it alive whatever the app does to the next one).
std::mutex s_cheats_mutex;
std::shared_ptr<const std::vector<CheatLine>> s_cheats =
	std::make_shared<const std::vector<CheatLine>>();

// The hex digit [c], or -1.
int cheat_hex_digit(char c)
{
	if (c >= '0' && c <= '9')
		return c - '0';
	if (c >= 'A' && c <= 'F')
		return c - 'A' + 10;
	if (c >= 'a' && c <= 'f')
		return c - 'a' + 10;
	return -1;
}

// One "AAAAAAAA VVVV" line of a code into [out]; false when the core would not run it: the
// command (the first digit) must be 1 (a 16-bit write), 3 (an 8-bit one, value "00VV") or
// D (the condition for the next line), and the address — the other seven digits, as
// 0x0AAAAAAA, the mask Mednafen's cheat engine puts over them — must be a work RAM one,
// even for a 16-bit write. What the app's check (ActionReplay.check) flags does not get
// this far; the parse leaves out what it would, so the two agree on what runs.
bool parse_cheat_line(const std::string& line, CheatLine* out)
{
	if (line.size() != 8 + 1 + 4)
		return false;

	uint32_t address_field = 0, value = 0;
	for (unsigned i = 0; i < 8; i++)
	{
		const int digit = cheat_hex_digit(line[i]);
		if (digit < 0)
			return false;
		address_field = (address_field << 4) | (uint32_t)digit;
	}

	// The separator (the app's check leaves one), then the value.
	if (line[8] != ' ' && line[8] != ':' && line[8] != '\t')
		return false;
	for (unsigned i = 9; i < 13; i++)
	{
		const int digit = cheat_hex_digit(line[i]);
		if (digit < 0)
			return false;
		value = (value << 4) | (uint32_t)digit;
	}

	const uint32_t address = address_field & 0x0FFFFFFF;
	const bool low = address >= 0x00200000 && address <= 0x002FFFFF;
	const bool high = address >= 0x06000000 && address <= 0x07FFFFFF;
	if (!low && !high)
		return false;

	switch (line[0])
	{
		case '1':
			if (address & 1)	// A 16-bit write at an odd address makes no line.
				return false;
			*out = CheatLine{address, (uint16_t)value, 2, false};
			return true;

		case '3':
			if (value > 0xFF)
				return false;
			*out = CheatLine{address, (uint16_t)value, 1, false};
			return true;

		case 'D':
		case 'd':
			*out = CheatLine{address, (uint16_t)value, 2, true};
			return true;

		default:
			return false;
	}
}

// The cheats onto the work RAM, on the CPU thread right before a frame: the writes go out
// the ss module's own way (the cheat engine's writer, through its processor caches), and a
// D line reads its condition whatever came before it — the line after a D one runs only
// when the D's value was there.
void apply_cheats()
{
	if (!Mednafen::MDFNGameInfo || strcmp(Mednafen::MDFNGameInfo->shortname, "ss"))
		return;
	const Mednafen::CheatInfoStruct& cheat = Mednafen::MDFNGameInfo->CheatInfo;

	std::shared_ptr<const std::vector<CheatLine>> cheats;
	{
		std::lock_guard<std::mutex> lock(s_cheats_mutex);
		cheats = s_cheats;
	}

	bool skip_next = false;
	for (const CheatLine& line : *cheats)
	{
		if (line.compare)
		{
			// Big-endian: the address's byte on top, the one under it beneath.
			const uint32_t held = ((uint32_t)cheat.MemRead(line.address) << 8) |
				cheat.MemRead(line.address + 1);
			skip_next = held != line.value;
			continue;
		}

		if (!skip_next)
		{
			for (unsigned i = 0; i < line.size; i++)
				cheat.MemWrite(line.address + i,
					(uint8)(line.value >> (8 * (line.size - 1 - i))));
		}
		skip_next = false;
	}
}

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

// True while the VM runs fast (NativeCore.setFastForward, from EmulatorCore.setFastForward);
// the frame loop reads it at every frame.
std::atomic<bool> s_fast_forward{false};

// How fast fast-forward runs, as a multiple of full speed (SetSetting "Main"/FastForwardSpeed,
// 2..10), or 0 for as fast as the device goes.
std::atomic<int32_t> s_fast_forward_speed{0};

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

// The settings the app sent, kept for the boot and for the CPU thread (s_settings_mutex):
// the BIOS path keys (section "BIOS"), and Mednafen's own settings (section "Core", keyed by
// their Mednafen names; CoreKeys.kt). A boot writes all of them into Mednafen; while a game
// runs, only LIVE_CORE_SETTINGS reach it (at the next frame, after a CommitSettings), which
// matches what the app applies at once (SettingsEffects.appliesOnNextBoot has the rest).
std::mutex s_settings_mutex;
std::map<std::string, std::string> s_bios_settings;
std::map<std::string, std::string> s_core_settings;
std::atomic<bool> s_live_settings_pending{false};

// The Mednafen settings that take effect in a running game: the resampler is read at every
// frame, and the deinterlacer is rebuilt by its change callback.
constexpr const char* LIVE_CORE_SETTINGS[] = { "ss.scsp.resamp_quality", "video.deinterlacer" };

// Writes the app's Mednafen settings into Mednafen: all of them, or with [live_only] those that
// reach a running game. A value Mednafen rejects is logged and skipped, so one bad value does
// not stop the boot.
void apply_core_settings(bool live_only)
{
	std::map<std::string, std::string> values;
	{
		std::lock_guard<std::mutex> lock(s_settings_mutex);
		values = s_core_settings;
	}

	for (const auto& [name, value] : values)
	{
		if (live_only && std::none_of(std::begin(LIVE_CORE_SETTINGS), std::end(LIVE_CORE_SETTINGS),
				[&](const char* live) { return name == live; }))
			continue;

		try
		{
			if (!Mednafen::MDFNI_SetSetting(name.c_str(), value.c_str(), false))
				__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Setting %s=%s was not taken", name.c_str(), value.c_str());
		}
		catch (const std::exception& e)
		{
			__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Setting %s=%s: %s", name.c_str(), value.c_str(), e.what());
		}
	}
}

// ---------------------------------------------------------------------------
// BIOS paths: pick_bios_file / resolve_bios_path / apply_bios_settings.
// ---------------------------------------------------------------------------

// The BIOS file in the folder at [dir] for [region]: the first (by name) that is known to be
// that region's, else the first that is not known at all, else the first BIOS-sized file of
// any region, so a game still gets to try the BIOS there is. "" when there is none.
std::string pick_bios_file(const std::string& dir, AndroidHost::BiosRegion region)
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

	std::string unknown, other;
	for (const std::string& name : names)
	{
		if (name == "." || name == "..")
			continue;

		const std::string path = dir + "/" + name;
		AndroidHost::BiosInfo info;
		if (!AndroidHost::IdentifyBiosFile(path, &info))
			continue;

		if (info.region == region)
			return path;
		if (info.region == AndroidHost::BiosRegion::Unknown)
		{
			if (unknown.empty())
				unknown = path;
		}
		else if (other.empty())
			other = path;
	}

	return unknown.size() ? unknown : other;
}

// The absolute BIOS path the app's value [name] means: a file of the BIOS folder under the
// app's data directory, with "" meaning "the best one there for [region]" (pick_bios_file).
// Values that point out of the folder are not supported: the app copies a picked BIOS in
// (EmulatorCore.biosDir), and MDFN_MakeFName(MDFNMKF_FIRMWARE, ...) puts absolute paths
// through unchanged.
std::string resolve_bios_path(const std::string& name, AndroidHost::BiosRegion region)
{
	if (name.size())
		return s_data_dir + "/bios/" + name;

	return pick_bios_file(s_data_dir + "/bios", region);
}

// Writes the app's BIOS picks into Mednafen: BIOS/PathNTSCJ feeds ss.bios_jp, and
// BIOS/PathNTSCU (falling back to BIOS/PathPAL) feeds ss.bios_na_eu. An empty value lets the
// emulator choose a file for the region. When the folder has no usable file, the setting keeps
// Mednafen's default and the boot fails on the missing BIOS file with the module's own error.
void apply_bios_settings()
{
	std::map<std::string, std::string> values;
	{
		std::lock_guard<std::mutex> lock(s_settings_mutex);
		values = s_bios_settings;
	}

	const std::string jp_path = resolve_bios_path(values["PathNTSCJ"], AndroidHost::BiosRegion::Japan);
	const std::string na_eu_path = resolve_bios_path(
		values["PathNTSCU"].empty() ? values["PathPAL"] : values["PathNTSCU"],
		AndroidHost::BiosRegion::NorthAmericaEurope);

	if (jp_path.size())
		Mednafen::MDFNI_SetSetting("ss.bios_jp", jp_path.c_str(), false);
	if (na_eu_path.size())
		Mednafen::MDFNI_SetSetting("ss.bios_na_eu", na_eu_path.c_str(), false);

	// The module's checks refuse a BIOS of the other region, and any file named like a known
	// image without being it. The app lets a game's settings pick any BIOS (and falls back to
	// one of another region when that is all there is), so they stay off.
	Mednafen::MDFNI_SetSetting("ss.bios_sanity", "0", false);
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

// The wall-clock millisecond, for what gets written down when (the save states' savedAtMs,
// the rewind points' ages).
int64_t now_wall_ms()
{
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	return ts.tv_sec * 1000ll + ts.tv_nsec / 1000000;
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

// The placement of the picture on a window [width]x[height]: the largest rectangle of the
// aspect ratio setting's shape that fits (the whole window for Stretch), centered — or at the
// top of a portrait window, [top_margin] pixels down, as SetDisplayLayout says. Under
// s_window_mutex (it reads the layout).
void picture_rect(int width, int height, int* x, int* y, int* w, int* h)
{
	switch (s_aspect_ratio.load(std::memory_order_relaxed))
	{
		case AspectRatio::Stretch:
			*w = width;
			*h = height;
			break;

		case AspectRatio::Wide:
			*w = std::min(width, height * 16 / 9);
			*h = *w * 9 / 16;
			break;

		default:
			*w = std::min(width, height * 4 / 3);
			*h = *w * 3 / 4;
			break;
	}
	*x = (width - *w) / 2;
	*y = (s_portrait_top && height > width) ? std::min<int>((int)s_top_margin, height - *h)
						: (height - *h) / 2;
}

// How blit_frame and the PNG writer sample the frame over a [w]x[h] target (always 4:3;
// the callers ask for sizes of that shape): for each target pixel whose source line the
// emulation drew, calls [put](target x, target y, the frame pixel as the surface's uint32
// holds it — R in the low byte). What an undrawn line holds is the caller's to have
// ready (the window is pre-filled black, as the PNG writer pre-fills its own buffer).
template <class Put>
void sample_frame(int w, int h, Put&& put)
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

		// Each line starts at DisplayRect.x (the left border the h-overscan setting trims
		// off, vdp2_render.cpp) and is LineWidths[y] pixels wide from there (the core's
		// assert: DisplayRect.x + LineWidths[y] <= 704). DisplayRect.w, when LineWidths is
		// flat (~0), is that same width.
		for (int xx = 0; xx < w; xx++)
			put(xx, yy, srow[(int32_t)((int64_t)xx * lw / w)]);
	}
}

// Scales the frame's DisplayRect (per-line widths when given, else DisplayRect.w) over the rect
// [*x, *y, *w, *h] of [buf], sampling the surface with nearest neighbour and writing the alpha
// byte up. Under s_window_mutex, on the CPU thread between frames.
void blit_frame(ANativeWindow_Buffer* buf, int x, int y, int w, int h)
{
	uint32_t* base = reinterpret_cast<uint32_t*>(buf->bits) + (size_t)y * buf->stride + x;

	sample_frame(w, h, [&](int xx, int yy, uint32_t pix) {
		base[(size_t)yy * buf->stride + xx] = pix | OPAQUE_BLACK;
	});
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

	// Whatever the frame does not reach (the letterbox around the picture) turns opaque black
	// first.
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

// The loaded game's discs, for the app's disc menu and the disc changes. Guarded by s_disc_mutex:
// the app reads and requests from its threads, the CPU thread loads and carries the changes out.
std::mutex s_disc_mutex;
bool s_disc_playlist = false;	// Booted from a .m3u.
std::vector<AndroidHost::OpenedDisc> s_discs;	// Mednafen's disc list, slot by slot.
int s_disc_current = -1;	// The slot in the drive (or going in), -1 for none.

// A disc change waiting for the CPU thread: put slot [index] in the drive, after swapping
// [image] into it when there is one (InsertDisc).
struct DiscChange
{
	int index = -1;
	std::unique_ptr<Mednafen::CDInterface> image;
};
std::unique_ptr<DiscChange> s_disc_change;

// How long the tray stays open for a disc change, in frames: about a second, so the game sees
// the lid open (the CD block reports it) before the next disc is there.
constexpr int DISC_CHANGE_FRAMES = 60;

// The change being carried out by the frame loop, and the frames left before the tray closes.
std::unique_ptr<DiscChange> s_disc_changing;
int s_disc_change_frames = 0;

// The drive states of Mednafen's CD drive (MDFN_LoadCD's layout).
constexpr uint32_t DRIVE_TRAY_OPEN = 0;
constexpr uint32_t DRIVE_TRAY_CLOSED = 2;

void reset_discs()
{
	std::lock_guard<std::mutex> lock(s_disc_mutex);
	s_disc_playlist = false;
	s_discs.clear();
	s_disc_current = -1;
	s_disc_change.reset();
	s_disc_changing.reset();
	s_disc_change_frames = 0;
}

// One step of a disc change, once per frame on the CPU thread: a new request opens the tray,
// and DISC_CHANGE_FRAMES later the tray closes on the new disc.
void step_disc_change()
{
	if (!s_disc_changing)
	{
		int current;
		{
			std::lock_guard<std::mutex> lock(s_disc_mutex);
			if (!s_disc_change)
				return;
			s_disc_changing = std::move(s_disc_change);
			current = std::max(s_disc_current, 0);
		}
		Mednafen::MDFNI_SetMedia(0, DRIVE_TRAY_OPEN, (uint32_t)current, 0);
		s_disc_change_frames = DISC_CHANGE_FRAMES;
		return;
	}

	if (--s_disc_change_frames > 0)
		return;

	std::unique_ptr<DiscChange> change = std::move(s_disc_changing);
	if (change->image)
	{
		AndroidHost::SwappableDisc* slot;
		{
			std::lock_guard<std::mutex> lock(s_disc_mutex);
			slot = s_discs.at((size_t)change->index).slot;
		}
		slot->Swap(std::move(change->image));
	}
	Mednafen::MDFNI_SetMedia(0, DRIVE_TRAY_CLOSED, (uint32_t)change->index, 0);
}

// Boots the disc image or .m3u playlist at [path] (disc.h) through Mednafen's own CD loading,
// as a CD whatever the extension (a .chd is no extension Mednafen knows), with each disc opened
// by MDFND_OpenCDImage. Then the tray closes on the layout's default disc (drive 0: the first
// disc, or a playlist's #MEDNAFEN_DEFAULT), as Mednafen's driver does after a load.
void load_disc(const std::string& path)
{
	// The loader reports its failures as error notices rather than exceptions.
	AndroidHost::TakeCoreErrorNotice();
	AndroidHost::TakeOpenedDiscs();
	if (!Mednafen::MDFNI_LoadGame("ss", AndroidHost::DiscVFS(path), path.c_str(), true))
	{
		const std::string notice = AndroidHost::TakeCoreErrorNotice();
		throw std::runtime_error(notice.size() ? notice : "The disc could not be loaded");
	}

	try
	{
		const Mednafen::RMD_DriveDefaults& defaults = Mednafen::MDFNGameInfo->RMD->DrivesDefaults.at(0);
		if (!Mednafen::MDFNI_SetMedia(0, defaults.State, defaults.Media, defaults.Orientation))
			throw std::runtime_error("The disc could not be put in the drive");

		std::lock_guard<std::mutex> lock(s_disc_mutex);
		s_discs = AndroidHost::TakeOpenedDiscs();
		s_disc_playlist = Mednafen::NVFS.test_ext(path, ".m3u");
		s_disc_current = (int)defaults.Media;
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

// Moves the files of the folder Mednafen saved to before (its default "sav") into [to], where
// none of the same name is, and removes the folder once it is empty.
void move_old_saves(const std::string& from, const std::string& to)
{
	DIR* dp = opendir(from.c_str());
	if (!dp)
		return;

	struct dirent* de;
	while ((de = readdir(dp)))
	{
		const std::string name = de->d_name;
		if (name == "." || name == "..")
			continue;

		const std::string target = to + "/" + name;
		struct stat st;
		if (stat(target.c_str(), &st) != 0)
			rename((from + "/" + name).c_str(), target.c_str());
	}
	closedir(dp);
	rmdir(from.c_str());
}

// ---------------------------------------------------------------------------
// Save states, rewind and screenshots, for the app's screens (jni.cpp). The state
// operations run on the CPU thread as tasks (post_cpu_task; the frame loop or the pause
// wait runs them between the frames), so Mednafen stays single-threaded all through.
// ---------------------------------------------------------------------------

// The state container ("SPST"): our own small header around Mednafen's state, which goes
// inside unchanged — magic, u32 version (little endian throughout), i64 savedAtMs, u16
// serial length + bytes (UTF-8, may be empty), u32 PNG length + bytes (may be none), u32
// state length + bytes. MDFNSS_SaveSM gets no surface, so Mednafen embeds nothing and our
// own PNG is all the thumbnail there is.
constexpr uint8_t SPST_MAGIC[4] = { 'S', 'P', 'S', 'T' };
constexpr uint32_t SPST_VERSION = 1;

// The picture sizes the PNG writer gets (all 4:3, over the frame's own shape): the save
// state's thumbnail, the rewind point's smaller one, and the screenshot.
constexpr int THUMB_WIDTH = 320;
constexpr int THUMB_HEIGHT = 240;
constexpr int REWIND_THUMB_WIDTH = 160;
constexpr int REWIND_THUMB_HEIGHT = 120;
constexpr int SCREENSHOT_WIDTH = 640;
constexpr int SCREENSHOT_HEIGHT = 480;

// The running game's serial, saved with the states (SPST header); empty for the BIOS-only
// boot. Set here on the CPU thread at the boot and read by its tasks (all on that thread).
std::string s_save_serial;

// The app's rewind settings ("Main"/Rewind*: CoreKeys.kt writes the toggle as "true"/
// "false", the frequency as seconds, the slots as 1..20). The frame loop reads them.
std::atomic<bool> s_rewind_enabled{false};
std::atomic<double> s_rewind_frequency{10.0};	// Seconds of play between the points.
std::atomic<int32_t> s_rewind_slots{6};

// Frames since the last rewind point, on the CPU thread (tasks reset it with the history).
int64_t s_rewind_frame_count = 0;

// The points taken since a boot: when (wall clock, ms), the state bytes and the thumbnail.
// Newest first, at most RewindSaveSlots of them (a point taken, or a smaller setting,
// drops the oldest). The frame loop takes; the app reads (s_rewind_mutex).
struct RewindEntry
{
	int64_t taken_ms = 0;
	std::vector<uint8_t> state;
	std::vector<uint8_t> png;	// The 160x120 thumbnail.
};
std::mutex s_rewind_mutex;
std::vector<RewindEntry> s_rewind_points;
uint64_t s_rewind_points_taken = 0;	// For the once-in-a-while cost log.

// Mednafen's state into a std::vector, a MemoryStream and back (SaveSM throws the failures).
void copy_state_from_stream(std::vector<uint8_t>* state)
{
	Mednafen::MemoryStream st;
	Mednafen::MDFNSS_SaveSM(&st, false);

	const uint8_t* bytes = st.map();
	state->assign(bytes, bytes + st.size());
	st.unmap();
}

// The last frame as PNG-ready RGB8 image, [width] by [height]: the shared sampling of the
// frame (sample_frame), black where the emulation drew nothing. On the CPU thread.
std::vector<uint8_t> frame_rgb(int width, int height)
{
	std::vector<uint8_t> rgb((size_t)width * height * 3, 0);	// Black to start.

	sample_frame(width, height, [&](int xx, int yy, uint32_t pix) {
		// The surface is ABGR32_8888: R is the low byte, as blit_frame's OPAQUE_BLACK
		// tells (the alpha on top).
		uint8_t* pixel = rgb.data() + ((size_t)yy * width + xx) * 3;
		pixel[0] = (uint8_t)pix;
		pixel[1] = (uint8_t)(pix >> 8);
		pixel[2] = (uint8_t)(pix >> 16);
	});

	return rgb;
}

// The container around Mednafen's state bytes (and the thumbnail's PNG bytes beside them).
// Little endian throughout, via Mednafen's own number packers.
std::vector<uint8_t> assemble_spst(const std::string& serial, const std::vector<uint8_t>& png,
	const std::vector<uint8_t>& state)
{
	std::vector<uint8_t> bytes;
	bytes.resize(sizeof(SPST_MAGIC) + 4 + 8 + 2 + serial.size() + 4 + png.size() + 4 + state.size());

	size_t p = 0;

	memcpy(bytes.data() + p, SPST_MAGIC, sizeof(SPST_MAGIC));
	p += sizeof(SPST_MAGIC);

	MDFN_en32lsb(bytes.data() + p, SPST_VERSION);
	p += 4;

	MDFN_en64lsb(bytes.data() + p, (uint64)now_wall_ms());
	p += 8;

	MDFN_en16lsb(bytes.data() + p, (uint16)serial.size());
	p += 2;
	memcpy(bytes.data() + p, serial.data(), serial.size());
	p += serial.size();

	MDFN_en32lsb(bytes.data() + p, (uint32)png.size());
	p += 4;
	memcpy(bytes.data() + p, png.data(), png.size());
	p += png.size();

	MDFN_en32lsb(bytes.data() + p, (uint32)state.size());
	p += 4;
	memcpy(bytes.data() + p, state.data(), state.size());

	return bytes;
}

// The saved state to [path]: the SPST container written to [path].tmp and renamed over
// [path] (atomic — a half-written file never has the state's name). On the CPU thread (a
// task body); "" on success, else why not.
std::string write_spst_file(const std::string& path)
{
	std::vector<uint8_t> state;
	copy_state_from_stream(&state);

	// Our own thumbnail (320x240) is the container's PNG; Mednafen's state embeds nothing
	// (MDFNSS_SaveSM gets no surface).
	const std::vector<uint8_t> rgb = frame_rgb(THUMB_WIDTH, THUMB_HEIGHT);
	const std::vector<uint8_t> png = AndroidHost::EncodePngRgb(THUMB_WIDTH, THUMB_HEIGHT, rgb.data());
	const std::vector<uint8_t> bytes = assemble_spst(s_save_serial, png, state);

	const std::string tmp_path = path + ".tmp";
	FILE* file = fopen(tmp_path.c_str(), "wb");
	if (!file)
		return "Opening " + tmp_path + ": " + strerror(errno);

	// A write or a close failing takes the temp file with it and fails the save.
	std::string error;
	if (fwrite(bytes.data(), 1, bytes.size(), file) != bytes.size())
		error = strerror(errno);
	if (error.empty() && fclose(file) != 0)
		error = strerror(errno);
	if (error.size())
	{
		const std::string message = "Writing " + tmp_path + ": " + error;
		remove(tmp_path.c_str());
		return message;
	}

	if (rename(tmp_path.c_str(), path.c_str()) != 0)
	{
		const std::string message = "Renaming " + tmp_path + ": " + strerror(errno);
		remove(tmp_path.c_str());
		return message;
	}

	return "";
}

// The state container at [path]: [serial] (may be empty), [png] (may be none), and — when
// [state] is given — the Mednafen state copied out of the end of the file. False when it
// cannot be opened, is short, or is no SPST file of a version we read. The lengths the
// file names are checked against its size before anything is read by them.
bool read_spst(const std::string& path, std::string* serial, std::vector<uint8_t>* png,
	std::vector<uint8_t>* state)
{
	FILE* file = fopen(path.c_str(), "rb");
	if (!file)
		return false;

	struct stat st;
	if (fstat(fileno(file), &st) != 0)
	{
		fclose(file);
		return false;
	}

	// The fields in the order assemble_spst wrote them. Every length is checked against what
	// is left of the file before anything is read by it.
	uint64_t left = (uint64_t)st.st_size;
	auto take = [&](void* out, uint64_t count) {
		if (count > left || fread(out, 1, (size_t)count, file) != count)
			return false;
		left -= count;
		return true;
	};

	bool ok = false;
	do
	{
		uint8_t head[4 + 4 + 8];	// Magic, version, savedAtMs (not needed here).
		if (!take(head, sizeof(head)) || memcmp(head, SPST_MAGIC, sizeof(SPST_MAGIC)) ||
			MDFN_de32lsb(head + 4) != SPST_VERSION)
			break;

		uint8_t len16[2];
		if (!take(len16, sizeof(len16)))
			break;
		std::vector<uint8_t> serial_bytes(MDFN_de16lsb(len16));
		if (!take(serial_bytes.data(), serial_bytes.size()))
			break;

		uint8_t len32[4];
		if (!take(len32, sizeof(len32)))
			break;
		std::vector<uint8_t> png_bytes(MDFN_de32lsb(len32) <= left ? MDFN_de32lsb(len32) : 0);
		if (png_bytes.size() != MDFN_de32lsb(len32) || !take(png_bytes.data(), png_bytes.size()))
			break;

		if (state)
		{
			if (!take(len32, sizeof(len32)))
				break;
			if (MDFN_de32lsb(len32) > left)
				break;
			state->resize(MDFN_de32lsb(len32));
			if (!take(state->data(), state->size()))
				break;
		}

		if (serial)
			serial->assign((const char*)serial_bytes.data(), serial_bytes.size());
		if (png)
			*png = std::move(png_bytes);
		ok = true;
	} while (false);

	fclose(file);
	return ok;
}

// The Mednafen state in the SPST file at [path], loaded back into the running game
// (MDFNSS_LoadSM; it throws with the why on a mismade file). On the CPU thread — a task
// body, or the boot for the "finished, resumed later" restart.
void load_state_file(const std::string& path)
{
	std::vector<uint8_t> state;
	if (!read_spst(path, nullptr, nullptr, &state))
		throw std::runtime_error("Not a Sapphirine save state: " + path);

	// The bytes into a MemoryStream, at its front where Mednafen's loader takes them.
	Mednafen::MemoryStream st;
	st.write(state.data(), state.size());
	st.seek(0, SEEK_SET);
	Mednafen::MDFNSS_LoadSM(&st, false);
}

// The rewind history and its counter emptied: a boot (the points were taken about the
// boot's own disc), and a state the user loads (their place does not exist in it). On the
// CPU thread.
void clear_rewind_points()
{
	{
		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		s_rewind_points.clear();
	}
	s_rewind_frame_count = 0;
}

// The state's bytes into the history with the point's thumbnail, newest first and trimmed
// to RewindSaveSlots. On the CPU thread, right after the frame (MDFNI_Emulate and its
// draw); on a failure nothing is taken and the log gets the reason.
void take_rewind_point()
{
	const int64_t t0 = now_ns();

	try
	{
		RewindEntry point;
		copy_state_from_stream(&point.state);
		const size_t state_size = point.state.size();
		const double save_ms = (now_ns() - t0) / 1e6;

		// Our own thumbnail, smaller than a save state's (160x120).
		const std::vector<uint8_t> rgb = frame_rgb(REWIND_THUMB_WIDTH, REWIND_THUMB_HEIGHT);
		point.png = AndroidHost::EncodePngRgb(REWIND_THUMB_WIDTH, REWIND_THUMB_HEIGHT, rgb.data());
		point.taken_ms = now_wall_ms();

		bool log_it = false;
		{
			std::lock_guard<std::mutex> lock(s_rewind_mutex);
			s_rewind_points.insert(s_rewind_points.begin(), std::move(point));

			// At most as many as the setting keeps: also what a shrunken setting means
			// for the ones already here.
			const size_t kept = (size_t)s_rewind_slots.load(std::memory_order_relaxed);
			if (s_rewind_points.size() > kept)
				s_rewind_points.resize(kept);

			log_it = ++s_rewind_points_taken <= 3 || s_rewind_points_taken % 20 == 0;
		}

		// The Saturn state's cost is one of the things to measure on a device
		// (IMPLEMENTATION.md 2.2): the log gets the size and the saving time, the first
		// few points and then one in about twenty.
		if (log_it)
			__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Rewind point #%llu: %u bytes, MDFNSS_SaveSM took %.2f ms",
				(unsigned long long)s_rewind_points_taken, (unsigned)state_size, save_ms);
	}
	catch (const std::exception& e)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "No rewind point taken: %s", e.what());
	}
}

// Back to the rewind point [index] (the newest is 0): its state loaded, the points newer
// than it dropped, and the frame counting of the next one starting over. On the CPU
// thread; "" on success, else why not.
std::string load_rewind_point(int index)
{
	std::vector<uint8_t> state;
	{
		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		if (index < 0 || (size_t)index >= s_rewind_points.size())
			return "No rewind point " + std::to_string(index);

		// The point loaded stays as the first, itself rewound to again; the bytes come
		// along as a copy for the load (the list is the app's to read meanwhile, and the
		// copy is a few steps under a millisecond against a multi-megabyte state).
		state = s_rewind_points[(size_t)index].state;
		s_rewind_points.erase(s_rewind_points.begin(), s_rewind_points.begin() + index);
	}

	// The state bytes into a MemoryStream, at its front where Mednafen's loader takes
	// them — this is the same road a save state file goes, only without the container.
	Mednafen::MemoryStream st;
	st.write(state.data(), state.size());
	st.seek(0, SEEK_SET);
	Mednafen::MDFNSS_LoadSM(&st, false);

	s_rewind_frame_count = 0;
	return "";
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
		mkdir((data_dir + "/memcards").c_str(), 0755);
		mkdir((data_dir + "/mednafen/mcs").c_str(), 0755);
		mkdir((data_dir + "/mednafen/mcm").c_str(), 0755);
		mkdir((data_dir + "/mednafen/cheats").c_str(), 0755);

		if (!Mednafen::MDFNI_Init())
			throw std::runtime_error("Mednafen initialization failed");
		if (!Mednafen::MDFNI_InitFinalize((data_dir + "/mednafen").c_str()))
			throw std::runtime_error("Mednafen initialization failed");

		// The backup memory (.bkr, the console's; .bcr, a backup cart's) and the clock (.smpc)
		// go to the folder the app lists as memory cards (MemoryCardRepository).
		Mednafen::MDFNI_SetSetting("filesys.path_sav", (data_dir + "/memcards").c_str(), false);
		move_old_saves(data_dir + "/mednafen/sav", data_dir + "/memcards");
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
	if (s_active.load())
	{
		*error = "A system is already running";
		return false;
	}

	s_frame_number.store(0);
	s_fps.store(0);
	reset_discs();

	// Set once MDFNGameInfo can go through MDFNI_CloseGame: CloseGame must only run after a
	// successful LoadCD (git.h). Seen by the catch below, so it lives at function scope.
	bool close_safe = false;

	try
	{
		if (!Mednafen::MDFNSystems.size())
			throw std::runtime_error("The core has not been initialized");

		apply_core_settings(false);
		apply_bios_settings();

		if (path.size())
			load_disc(path);
		else
			load_bios_only();
		// From here on the game goes down through MDFNI_CloseGame.

		// The game's serial for the state headers: the same answer the app's library shows
		// (disc.cpp's own, the first disc's of an .m3u), taken once at the boot. Not for the
		// BIOS-only boot, which has no disc. This thread is the CPU thread, so every later
		// user of the serial (the save states' tasks) reads it after the boot.
		s_save_serial = path.size() ? AndroidHost::ReadDiscSerial(path) : std::string();
		close_safe = true;

		// Where the driver would connect the input devices (nothing in LoadCommonPost does
		// it): without this the first Emulate crashes in SMPC_UpdateInput. See the Input
		// ports section.
		connect_input_devices();

		// The module builds its deinterlacer through the setting's change callback, which
		// only runs once a game is loaded (settings.cpp Set()); setting the live settings
		// again now creates it.
		s_live_settings_pending.store(false);
		apply_core_settings(true);

		// "Finished, and resumed later": the boot starts from the state file at
		// [save_state], as the emulation it carries — the m3u's disc it was playing at
		// comes back with its state (mednafen.cpp's MDFNDRIVE record and our drive hook).
		// It is all on this thread (still the CPU thread; the frame loop starts in a
		// moment), so a failure here is a failed boot like any other.
		if (save_state.size())
			load_state_file(save_state);

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
				fail_queued_tasks();	// The posters the loop does not run anymore.
				s_audio.reset();
				s_sound_buffer.reset();
				reset_discs();
				s_active.store(false);
				s_stop.store(false);
				s_fps.store(0);
			}
		} run_end_guard;

		open_audio();

		// Every boot starts with nothing held, and no rewind points (they belong to the
		// boot and its place in the game, like the pads' buttons do).
		for(auto& bits : s_pad_bits)
			bits.store(0, std::memory_order_relaxed);
		clear_rewind_points();

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
			// The wait ends for the task queue too: a poster (a save state or load, a rewind
			// load, a screenshot grab) is served right here — Mednafen all to itself, mid
			// pause as well — and the waiting goes on, with the sound untouched; a resume
			// is what brings it back, from a fresh prefill.
			{
				std::unique_lock<std::mutex> lock(s_vm_mutex);
				if (s_paused && !s_stop.load())
				{
					lock.unlock();
					pause_audio();
					lock.lock();

					while (s_paused && !s_stop.load())
					{
						s_vm_cond.wait(lock, []() { return s_stop.load() || !s_paused || tasks_queued(); });
						if (s_paused && !s_stop.load())
						{
							// Woken for the queue: run it without any of the VM's locks
							// (a pause or a stop land meanwhile), and wait again.
							lock.unlock();
							while (run_one_task())
								;
							lock.lock();
						}
					}

					// A stop any way it came, or an actual resume.
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

			if (s_live_settings_pending.exchange(false))
				apply_core_settings(true);

			update_pad_buffers();
			step_disc_change();

			// The queue, next to the disc change work and before the frame: the posters
			// (their save state, rewind load, screenshot grab) wait for this, and while
			// the VM pauses the wait above deals with them itself.
			while (run_one_task())
				;

			// The cheats write the work RAM here, so the frame starts on them: Mednafen is
			// the CPU thread's alone (the memory search's reads and writes have just had
			// their turn on the queue), and the module's writer keeps the caches in step.
			apply_cheats();

			const int64_t t0 = now_ns();
			Mednafen::MDFNI_Emulate(&s_espec);
			const int64_t t1 = now_ns();

			// What the mid-frame syncs (MDFND_MidSync) did not hand over yet.
			push_pending_sound(&s_espec);

			s_frame_number.fetch_add(1);
			fps_frames++;
			emu_ns_total += t1 - t0;

			draw_frame();

			// Rewind, when it is on: every RewindFrequency seconds of emulated time (the
			// frames counted at the pacing's own rate — the game's, and a slow one's time
			// still is what it emulates), a point off the last frame. Not while paused
			// (the loop does not run a frame there) and not in the middle of a disc
			// change — Mednafen's own state_rewind stays out of it.
			if (s_rewind_enabled.load(std::memory_order_relaxed))
			{
				s_rewind_frame_count++;
				if (!s_disc_changing)
				{
					const int64_t interval = std::max<int64_t>(1,
						(int64_t)(s_rewind_frequency.load(std::memory_order_relaxed) * rate));
					if (s_rewind_frame_count >= interval)
					{
						s_rewind_frame_count = 0;
						take_rewind_point();
					}
				}
			}

			// Pace against an absolute deadline, so a late frame does not push the next one.
			// If a whole frame or more was lost (a surface swap, a hiccup), jump the deadline
			// instead of running a burst of frames with no sleep to catch up. Fast-forward
			// paces to its multiple of the frame rate, or does not sleep at all at speed 0;
			// the ring drops what does not fit.
			const int32_t ff_speed = s_fast_forward_speed.load(std::memory_order_relaxed);
			const double drc = fast_forward ? 1.0 : drc_factor();
			s_drc_scale.store((int64_t)(drc * 65536.0), std::memory_order_relaxed);
			const int64_t now = now_ns();
			if (fast_forward && ff_speed <= 1)
				deadline = now;
			else
			{
				deadline += fast_forward ? frame_ns / ff_speed : (int64_t)(frame_ns * drc);
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
		reset_discs();

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
	if (!strcmp(section, "Core"))
	{
		std::lock_guard<std::mutex> lock(s_settings_mutex);
		s_core_settings[key] = value;
		return;
	}

	if (!strcmp(section, "Display"))
	{
		if (!strcmp(key, "AspectRatio"))
		{
			s_aspect_ratio.store(!strcmp(value, "16:9") ? AspectRatio::Wide
				: !strcmp(value, "Stretch") ? AspectRatio::Stretch : AspectRatio::Standard,
				std::memory_order_relaxed);
		}
		return;
	}

	if (!strcmp(section, "Main"))
	{
		if (!strcmp(key, "FastForwardSpeed"))
			s_fast_forward_speed.store(std::clamp(atoi(value), 0, 10), std::memory_order_relaxed);
		else if (!strcmp(key, "RewindEnable"))
			s_rewind_enabled.store(!strcmp(value, "true") || !strcmp(value, "1") || atoi(value) != 0,
				std::memory_order_relaxed);
		else if (!strcmp(key, "RewindFrequency"))
			s_rewind_frequency.store(std::clamp(atof(value), 0.1, 60.0), std::memory_order_relaxed);
		else if (!strcmp(key, "RewindSaveSlots"))
			s_rewind_slots.store(std::clamp(atoi(value), 1, 20), std::memory_order_relaxed);
		return;
	}

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
	// The CPU thread writes the live Mednafen settings at its next frame (Mednafen's settings
	// belong to it while a game runs). The BIOS paths and the other Mednafen settings wait
	// for the next boot by design; the rest of SetSetting applies at once.
	s_live_settings_pending.store(true);
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

std::string GetBiosInfo(int fd)
{
	BiosInfo info;
	if (!IdentifyBios(fd, &info))
		return "";

	return std::to_string((int)info.region) + "\t" + (info.name.size() ? info.name : "Unknown BIOS");
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

bool GetDiscPlaylist(std::vector<std::string>* titles, int* current)
{
	std::lock_guard<std::mutex> lock(s_disc_mutex);
	if (!s_active.load() || !s_disc_playlist)
		return false;

	for (const OpenedDisc& disc : s_discs)
		titles->push_back(disc.title);
	*current = s_disc_current;
	return true;
}

bool SwitchDisc(int index)
{
	std::lock_guard<std::mutex> lock(s_disc_mutex);
	if (!s_active.load() || index < 0 || (size_t)index >= s_discs.size())
		return false;

	if (index == s_disc_current && !s_disc_change)
		return true;

	s_disc_change.reset(new DiscChange());
	s_disc_change->index = index;
	s_disc_current = index;
	return true;
}

bool InsertDisc(const std::string& path)
{
	// Opened here, on the caller's thread, so a bad image fails the call instead of the frame
	// loop; the CPU thread only swaps it in.
	std::unique_ptr<Mednafen::CDInterface> image;
	try
	{
		image = OpenDisc(path);
	}
	catch (const std::exception& e)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Cannot insert %s: %s", path.c_str(), e.what());
		return false;
	}

	std::lock_guard<std::mutex> lock(s_disc_mutex);
	if (!s_active.load() || s_discs.empty())
		return false;

	// The new disc goes into the slot in the drive, which takes its title.
	const int index = std::max(s_disc_current, 0);
	s_discs[(size_t)index].title = DiscTitle(path);
	s_disc_change.reset(new DiscChange());
	s_disc_change->index = index;
	s_disc_change->image = std::move(image);
	s_disc_current = index;
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

std::string SaveState(const std::string& path)
{
	// The container write on the CPU thread, the poster waiting (post_cpu_task): the state
	// is Mednafen's to give between two frames. The rewind history is untouched by it.
	return post_cpu_task([&path]() -> std::string {
		try
		{
			return write_spst_file(path);
		}
		catch (const std::exception& e)
		{
			return e.what();
		}
	});
}

std::string LoadState(const std::string& path)
{
	// The load as a task too. A state the user picks belongs to the game requested, so the
	// disc it was playing at is the one that comes back with its mednafen-side media
	// record; the player's rewind history goes, with the state that made it.
	return post_cpu_task([&path]() -> std::string {
		try
		{
			load_state_file(path);
			clear_rewind_points();
			return "";
		}
		catch (const std::exception& e)
		{
			return e.what();
		}
	});
}

bool GetSaveStateInfo(const std::string& path, SaveStateInfo* info)
{
	// The file's head — up to the thumbnail, none of the state itself — at the calling
	// thread's own pace (plain file in the app's folders).
	return read_spst(path, &info->serial, &info->png, nullptr);
}

void GetRewindPoints(std::vector<RewindPoint>* points)
{
	const int64_t now = now_wall_ms();
	std::lock_guard<std::mutex> lock(s_rewind_mutex);

	points->clear();
	points->reserve(s_rewind_points.size());
	for (const RewindEntry& point : s_rewind_points)
	{
		RewindPoint listed;
		listed.age_ms = now > point.taken_ms ? now - point.taken_ms : 0;
		listed.png = point.png;
		points->push_back(std::move(listed));
	}
}

bool LoadRewindPoint(int index)
{
	// The load as a task (as the app's other state work); the result's "" is a "went fine".
	return post_cpu_task([index]() -> std::string {
		try
		{
			return load_rewind_point(index);
		}
		catch (const std::exception& e)
		{
			return e.what();
		}
	}).empty();
}

void SaveScreenshot(const std::string& path)
{
	// The frame data belongs to the CPU thread, so a task grabs the pixels at its next
	// frame boundary — that the very same way when the VM is paused, for the queue runs
	// there. Nothing happens with no game (the question comes back answered "No game is
	// running"), and the caller is given back right after the grab: the encode and the
	// file write take a thread of the core's own.
	std::vector<uint8_t> rgb;
	const std::string error = post_cpu_task([&rgb]() -> std::string {
		try
		{
			rgb = frame_rgb(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);
		}
		catch (const std::exception& e)
		{
			__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Screenshot: %s", e.what());
			return e.what();
		}
		return "";
	});
	(void)error;

	if (rgb.empty())
		return;

	try
	{
		std::thread([path, rgb = std::move(rgb)]()
		{
			try
			{
				const std::vector<uint8_t> png =
					EncodePngRgb(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, rgb.data());

				FILE* file = fopen(path.c_str(), "wb");
				if (!file)
					throw std::runtime_error(std::string("Opening: ") + strerror(errno));

				const size_t written = fwrite(png.data(), 1, png.size(), file);
				const int closed = fclose(file);
				if (written != png.size() || closed != 0)
					throw std::runtime_error(std::string("Writing: ") + strerror(errno));
			}
			catch (const std::exception& e)
			{
				__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Screenshot to %s: %s", path.c_str(), e.what());
			}
		}).detach();
	}
	catch (const std::exception& e)
	{
		// No thread to be had (a system at its memory limits, say): logged, and the picture
		// goes nowhere.
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Screenshot thread: %s", e.what());
	}
}

bool IsSavingMemoryCards()
{
	// The fork exports the ss module's own flag (its backup RAM holds changes not written
	// to its file yet); only a loaded game of its kind has one to ask about.
	if (!Mednafen::MDFNGameInfo || strcmp(Mednafen::MDFNGameInfo->shortname, "ss"))
		return false;

	return MDFN_IEN_SS::SS_BackupRAMPending();
}

void SetCheats(const std::vector<std::string>& codes)
{
	std::vector<CheatLine> lines;
	for (const std::string& code : codes)
	{
		// The codes come as the lines the app's check normalized ("AAAAAAAA VVVV"); blanks
		// and comments change nothing here, and anything else parses to nothing.
		size_t start = 0;
		while (start < code.size())
		{
			const size_t end = code.find('\n', start);

			const std::string raw = end == std::string::npos
				? code.substr(start) : code.substr(start, end - start);
			const size_t a = raw.find_first_not_of(" \t\r");
			const std::string line = a == std::string::npos ? std::string()
				: raw.substr(a, raw.find_last_not_of(" \t\r") - a + 1);

			CheatLine op;
			if (parse_cheat_line(line, &op))
				lines.push_back(op);

			if (end == std::string::npos)
				break;
			start = end + 1;
		}
	}

	auto next = std::make_shared<const std::vector<CheatLine>>(std::move(lines));
	{
		std::lock_guard<std::mutex> lock(s_cheats_mutex);
		s_cheats = next;
	}
	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Cheats: %zu lines accepted", next->size());
}

bool ReadMemory(uint32_t offset, uint32_t length, std::vector<uint8_t>* bytes)
{
	if (offset > MAIN_RAM_BYTES || length > MAIN_RAM_BYTES - offset)
		return false;

	// The reads (and the writes below) are tasks of the CPU thread like the state ones:
	// the cheat engine they go through belongs to it. The answer is the caller's bytes.
	return post_cpu_task([&offset, &length, bytes]() -> std::string {
		if (!Mednafen::MDFNGameInfo || strcmp(Mednafen::MDFNGameInfo->shortname, "ss"))
			return "No game is running";

		try
		{
			const auto read = Mednafen::MDFNGameInfo->CheatInfo.MemRead;
			bytes->resize(length);
			for (uint32_t i = 0; i < length; i++)
				(*bytes)[i] = read(main_ram_saturn_address(offset + i));
			return "";
		}
		catch (const std::exception& e)
		{
			return e.what();
		}
	}).empty();
}

bool WriteMemory(uint32_t offset, uint32_t size, uint32_t value)
{
	// A 2/4-byte write stands on an aligned address (a byte has no such care); the offsets
	// keep the parity of the work-RAM addresses they name, so the check is on the offset.
	if (size != 1 && size != 2 && size != 4)
		return false;
	if ((size == 2 && (offset & 1)) || (size == 4 && (offset & 3)) ||
		size > MAIN_RAM_BYTES || offset > MAIN_RAM_BYTES - size)
		return false;

	return post_cpu_task([&offset, &size, &value]() -> std::string {
		if (!Mednafen::MDFNGameInfo || strcmp(Mednafen::MDFNGameInfo->shortname, "ss"))
			return "No game is running";

		try
		{
			const auto write = Mednafen::MDFNGameInfo->CheatInfo.MemWrite;
			// Big-endian, a byte at a time (the writer keeps the caches in step with every
			// one), so a write over the High RAM's end into the Low comes out right too.
			for (uint32_t i = 0; i < size; i++)
				write(main_ram_saturn_address(offset + i),
					(uint8)(value >> (8 * (size - 1 - i))));
			return "";
		}
		catch (const std::exception& e)
		{
			return e.what();
		}
	}).empty();
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

// What the drive holds now: the boot's tray closing on its first disc, the disc changes,
// and — what reads the disc list in step with the state — a save state's own media record
// (mednafen.cpp's MDFNDRIVE section, applied while its state loads) naming the m3u disc
// that was in the drive when the state was made. With the tray closed, [media_idx] is the
// disc list's slot; an open tray says nothing about a disc yet (a disc change in progress
// has its slot booked ahead).
void MDFND_MediaSetNotification(uint32 drive_idx, uint32 state_idx, uint32 media_idx, uint32 orientation_idx)
{
	(void)orientation_idx;

	if (drive_idx != 0 || state_idx != DRIVE_TRAY_CLOSED)
		return;

	std::lock_guard<std::mutex> lock(s_disc_mutex);
	s_disc_current = (int)media_idx;
}

} // namespace Mednafen
