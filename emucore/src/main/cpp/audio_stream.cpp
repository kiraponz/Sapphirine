// Implementation of audio_stream.h (see there for the contract).

// SPDX-License-Identifier: GPL-3.0-or-later

#include "audio_stream.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <utility>

namespace
{

constexpr const char* LOG_TAG = "emucore";

// The share of the ring that a BufferMS value may aim at. The app clamps BufferMS to 10..500
// (CoreKeys.kt), but the core does not trust it: the ring is sized for this much plus a burst
// and a worst-case frame, so any value the app can send fits.
constexpr int32_t MAX_BUFFER_MS = 500;

// Stereo int16 frames.
constexpr int CHANNELS = 2;

// The stream format built in open(): stereo, 16-bit samples, the device's native rate, in the
// shared pool at low latency (the only output mode most phone pipelines offer; with the ring in
// front the small callback quantum is not a problem for the emulation thread).
constexpr aaudio_performance_mode_t STREAM_PERFORMANCE_MODE = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;

// Scales and saturates one sample: gain in Q8 (0..512 = 0..2.0), mul-shift, then clamp. No
// wrap-around: a 200 % volume wrapping the sample is exactly the crackle this step avoids.
inline int16_t apply_gain(int16_t sample, int32_t gain_q8)
{
	const int32_t scaled = ((int32_t)sample * gain_q8) >> 8;
	return (int16_t)std::clamp(scaled, -32768, 32767);
}

} // namespace

namespace AndroidHost
{

bool AudioStream::open(std::string* error)
{
	if (s_stream)
		return true;

	// The first open asks for an unspecified rate and takes the device's native one back
	// (rate()), which the frame loop mirrors into espec.SoundRate. A reopen after a disconnect
	// requests the dead stream's rate first so the (mid-run) rate change — which the ss module
	// absorbs by rebuilding its resampler, src/ss/sound.cpp SOUND_StartFrame — happens only
	// when it must; a device that refuses the rate falls through to the unspecified attempt.
	for (int attempt = 0; attempt < 2; attempt++)
	{
		const bool unspecified_rate = attempt == 1 || s_rate == 0;

		AAudioStreamBuilder* builder = nullptr;
		if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder)
		{
			*error = "AAudio could not create a stream builder";
			return false;
		}

		AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
		AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
		AAudioStreamBuilder_setPerformanceMode(builder, STREAM_PERFORMANCE_MODE);
		AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
		AAudioStreamBuilder_setChannelCount(builder, CHANNELS);
		AAudioStreamBuilder_setSampleRate(builder, unspecified_rate ? 0 : s_rate);

		// The callbacks get `this`: the run (android_host.cpp's CPU thread) holds the instance
		// alive as long as the stream does, and callbacks stop before close() — which itself
		// blocks until the data callback is out of its current run — so nothing dangles.
		AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
		AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

		const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &s_stream);
		AAudioStreamBuilder_delete(builder);

		if (result == AAUDIO_OK && s_stream)
		{
			s_rate = AAudioStream_getSampleRate(s_stream);

			// Enough room for a full BufferMS (the app may change it while the VM runs), one
			// callback burst (about 10 ms at LOW_LATENCY's finest) and a worst-case frame,
			// rounded up to a power of two for the mask arithmetic.
			const int64_t wanted = s_rate * (MAX_BUFFER_MS + 50) / 1000;
			s_capacity = 1024;
			while (s_capacity < wanted)
				s_capacity <<= 1;
			s_mask = s_capacity - 1;

			// Interleaved stereo, zeroed once: the ring reads clean from the very first
			// callback, even before the first pushed frame.
			s_ring.reset(new int16_t[(size_t)s_capacity * CHANNELS]);
			std::memset(s_ring.get(), 0, (size_t)s_capacity * CHANNELS * sizeof(int16_t));
			s_read_pos.store(0, std::memory_order_relaxed);
			s_write_pos.store(0, std::memory_order_relaxed);
			return true;
		}

		// The dead stream's rate may not exist on the new device (a bluetooth switch, say);
		// only give up once the unspecified-rate attempt also failed.
		if (unspecified_rate)
		{
			*error = std::string("AAudio could not open an output stream: ")
				+ AAudio_convertResultToText(result);
			return false;
		}
	}

	return false;
}

void AudioStream::close()
{
	// AAudioStream_close blocks until the data callback is out of its current run (AAudio's
	// lifecycle contract) and none follow, so after this the ring has no consumer; the run
	// either tears the instance down right away or flushes it before reusing it.
	if (!s_stream)
		return;

	AAudioStream* stream = s_stream;
	s_stream = nullptr;
	AAudioStream_close(stream);
}

bool AudioStream::start()
{
	if (!s_stream)
		return false;

	return AAudioStream_requestStart(s_stream) == AAUDIO_OK;
}

void AudioStream::pause()
{
	if (!s_stream)
		return;

	if (AAudioStream_requestPause(s_stream) != AAUDIO_OK)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "AAudio requestPause failed");
		return;
	}

	// requestPause is asynchronous; spin (bounded at about one second) until the state change
	// took, so the callers that rework the ring afterwards do so while the callback is gone.
	aaudio_stream_state_t state = AAudioStream_getState(s_stream);
	for (int i = 0; state != AAUDIO_STREAM_STATE_PAUSED && i < 100; i++)
	{
		if (state == AAUDIO_STREAM_STATE_DISCONNECTED
			|| state == AAUDIO_STREAM_STATE_CLOSING
			|| state == AAUDIO_STREAM_STATE_CLOSED)
			break;
		if (AAudioStream_waitForStateChange(s_stream, state, &state, 10 * 1000000ll) != AAUDIO_OK)
			break;
	}

	if (state != AAUDIO_STREAM_STATE_PAUSED)
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG,
			"AAudio stream did not reach PAUSED after the pause request");
}

void AudioStream::flushAndPrefill(int32_t target_frames)
{
	if (!s_ring)
		return;

	// The callback is not running (the callers' contract, see pause() and start()). Catch the
	// write position up to the read one — everything buffered drops off the end — and then
	// write silence for the callback to drain until the first real frames arrive.
	const int64_t read_pos = s_read_pos.load(std::memory_order_acquire);
	s_write_pos.store(read_pos, std::memory_order_release);
	push(nullptr, target_frames, 0);
}

void AudioStream::push(const int16_t* frames, int32_t count, int32_t gain_q8)
{
	if (!count || !s_ring)
		return;

	// The free space: the writer may run up to one capacity ahead of the reader. The reader's
	// position is read with acquire, so every frame the callback published done is counted.
	const int64_t read_pos = s_read_pos.load(std::memory_order_acquire);
	const int64_t write_pos = s_write_pos.load(std::memory_order_relaxed);
	const int64_t free_space = s_capacity - (write_pos - read_pos);

	// What does not fit is dropped, not blocked: the paced path fits by construction (the rate
	// control holds the fill at the target), and the fast-forward path prefers dropping over
	// slowing the emulation back down.
	const int32_t done = (int64_t)count <= free_space ? count : (int32_t)free_space;
	if (done <= 0)
	{
		s_dropped += count;
		return;
	}

	writeSegment(write_pos, frames, done, gain_q8);
	s_write_pos.store(write_pos + done, std::memory_order_release);

	if (done < count)
		s_dropped += count - done;
}

void AudioStream::writeSegment(int64_t pos, const int16_t* frames, int32_t count, int32_t gain_q8)
{
	// The wrap-aware write: the ring is one buffer, so a run that straddles its end splits.
	while (count > 0)
	{
		const int64_t at = pos & s_mask;
		const int64_t run = std::min<int64_t>(s_capacity - at, count);

		int16_t* dst = s_ring.get() + (size_t)at * CHANNELS;
		if (!frames || gain_q8 == 0)
		{
			std::memset(dst, 0, (size_t)run * CHANNELS * sizeof(int16_t));
			if (frames)
				frames += (size_t)run * CHANNELS;
		}
		else
		{
			for (int64_t i = 0; i < run * CHANNELS; i++)
				dst[i] = apply_gain(*frames++, gain_q8);
		}

		pos += run;
		count -= (int32_t)run;
	}
}

int64_t AudioStream::fillFrames() const
{
	// The producer sees the consumer's latest position with acquire, so the frame accounting
	// never counts a frame the callback has already drained.
	return s_write_pos.load(std::memory_order_acquire) - s_read_pos.load(std::memory_order_relaxed);
}

void AudioStream::takeStatistics(int64_t* underruns, int64_t* overruns)
{
	// Reads and clears: the counters belong to one second's log line, and they live on both
	// sides of the ring (the underrun side is the callback's), hence the exchange.
	*underruns = s_underruns.exchange(0, std::memory_order_relaxed);
	*overruns = std::exchange(s_dropped, 0);
}

bool AudioStream::takeError()
{
	// Turn the error callback's flag into a one-shot "yes": the caller (a non-callback thread;
	// AAudio forbids reopening from the error callback itself) tears the dead stream down and
	// opens a new one.
	if (!s_error.exchange(false, std::memory_order_acquire))
		return false;

	const int code = s_error_result.exchange(0, std::memory_order_relaxed);
	__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "AAudio stream error: %s (%d)",
		AAudio_convertResultToText((aaudio_result_t)code), code);
	return true;
}

aaudio_data_callback_result_t AudioStream::dataCallback(AAudioStream* stream, void* userData,
	void* audio_data, int32_t numFrames)
{
	// The consumer thread (AAudio's own): no locks, no allocation, no logging. Drain the ring,
	// silence whatever beyond, then publish the new read position with release so the producer
	// can fill the space.
	(void)stream;
	AudioStream* self = (AudioStream*)userData;
	int16_t* const audioData = (int16_t*)audio_data;

	const int64_t write_pos = self->s_write_pos.load(std::memory_order_acquire);
	int64_t read_pos = self->s_read_pos.load(std::memory_order_relaxed);

	int64_t avail = write_pos - read_pos;
	if (avail > numFrames)
		avail = numFrames;

	int32_t done = 0;
	while (done < avail)
	{
		// The read side wraps like the write side; a run straddling the ring's end splits.
		const int64_t at = read_pos & self->s_mask;
		const int64_t run = std::min<int64_t>(self->s_capacity - at, avail - done);

		std::memcpy(audioData + done * CHANNELS, self->s_ring.get() + (size_t)at * CHANNELS,
			(size_t)run * CHANNELS * sizeof(int16_t));

		done += (int32_t)run;
		read_pos += run;
	}

	if (done < numFrames)
	{
		// Underrun: the ring has nothing for the missing part. Silence goes out (that is the
		// crackle this whole step exists to avoid) and one callback slice is one count.
		std::memset(audioData + done * CHANNELS, 0,
			(size_t)(numFrames - done) * CHANNELS * sizeof(int16_t));
		self->s_underruns.fetch_add(1, std::memory_order_relaxed);
	}

	self->s_read_pos.store(read_pos, std::memory_order_release);
	return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioStream::errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error)
{
	(void)stream;
	// From the error callback thread. The stream is on its way down and reopening belongs on a
	// proper thread (the AAudio docs forbid it here), so this only records the fact.
	AudioStream* self = (AudioStream*)userData;
	self->s_error_result.store((int)error, std::memory_order_relaxed);
	self->s_error.store(true, std::memory_order_release);
}

} // namespace AndroidHost
