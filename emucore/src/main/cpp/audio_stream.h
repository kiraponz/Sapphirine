// The audio output: the AAudio stream (stereo 16-bit at the device's native rate) and the
// single-producer / single-consumer ring buffer that hands the emulation's frames to the
// stream's callback thread with atomics only, so the data callback never locks, allocates or
// logs.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <stdint.h>

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <string>

namespace AndroidHost {

// One booted VM's audio output, held by the CPU thread's run (android_host.cpp). open(),
// pause() and the reopen path go through other threads when noted; everything else is for the
// CPU (emulation) thread, which is the ring's single producer. The AAudio data callback (a
// thread AAudio owns) is the single consumer.
class AudioStream
{
public:
	AudioStream() = default;
	AudioStream(const AudioStream&) = delete;
	AudioStream& operator=(const AudioStream&) = delete;
	~AudioStream() { close(); }

	// Opens the stream, asking AAudio for an unspecified sample rate and taking the device's
	// native one back (rate(), valid from here on). A layer built before can keep a rate alive:
	// a reopen requests that rate first and falls back to the unspecified one. False, with the
	// reason in [error], when AAudio cannot give us a stream; the VM then runs without sound.
	bool open(std::string* error);

	// Stops and closes the stream. Blocks until the data callback is out of its current run
	// (AAudio's contract), so the ring is quiet afterwards. Never on a callback thread; safe
	// to call more than once.
	void close();

	// Feeds the device: requestStart. Call after open() and a flushAndPrefill, so the callback
	// wakes on silence instead of an underrun. False when the request was refused.
	bool start();

	// Stops the callback: requestPause, then waits until the state change took, so a following
	// flushAndPrefill works on a quiet ring. Bounded; may be called from a non-CPU thread.
	void pause();

	// Producer side. Empties the ring and fills it with [target_frames] frames of silence, for
	// the callback to drain while the emulator catches up. Only with the callback not running:
	// before start(), or right after pause().
	void flushAndPrefill(int32_t target_frames);

	// Producer side. Pushes [count] interleaved stereo frames, each sample scaled by the Q8
	// [gain_q8] (0..512; 0 = silence) with saturation, dropping whatever does not fit (counted
	// as overruns; the fast-forward path drops on purpose, the paced path should never drop).
	// [frames] null zero-writes instead (the prefill goes through here).
	void push(const int16_t* frames, int32_t count, int32_t gain_q8);

	// The ring's fill, in frames (write position minus read position).
	int64_t fillFrames() const;

	// The underruns (callback slices that had to output silence) and overruns (frames dropped
	// for not fitting) since the last call, for the once-a-second log line. CPU thread.
	void takeStatistics(int64_t* underruns, int64_t* overruns);

	// True (once) when the error callback saw a disconnect or the like: the stream is dead and
	// the caller must reopen from a non-callback thread — AAudio forbids reopening from the
	// error callback itself (see the AAudio lifecycle docs).
	bool takeError();

	// The stream's sample rate, the device's native one on the first open.
	int32_t rate() const { return s_rate; }

private:
	static aaudio_data_callback_result_t dataCallback(AAudioStream* stream, void* userData,
		void* audioData, int32_t numFrames);
	static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

	// Writes [frames] interleaved frames at the (already taken) ring position [pos], sized for
	// the scale-and-saturate pass at [gain_q8].
	void writeSegment(int64_t pos, const int16_t* frames, int32_t count, int32_t gain_q8);

	// The ring: interleaved stereo frames, s_capacity of them (a power of two, so the mask
	// arithmetic wraps), indexed by the running position counters s_read/s_write. The read
	// side belongs to the callback thread, the write side to the CPU thread.
	std::unique_ptr<int16_t[]> s_ring;
	int64_t s_capacity = 0;
	int64_t s_mask = 0;
	int32_t s_rate = 0;			// the device's actual rate, read back after the open.
	std::atomic<int64_t> s_read_pos{0};
	std::atomic<int64_t> s_write_pos{0};
	std::atomic<int64_t> s_underruns{0};	// the callback's slice count, for takeStatistics.
	int64_t s_dropped = 0;			// frames dropped for not fitting; CPU thread only.

	// The error callback's record; nothing else runs from that thread.
	std::atomic<bool> s_error{false};
	std::atomic<int> s_error_result{0};	// an aaudio_result_t.

	AAudioStream* s_stream = nullptr;
};

} // namespace AndroidHost
