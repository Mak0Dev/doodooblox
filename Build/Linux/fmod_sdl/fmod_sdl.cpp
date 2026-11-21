// The slice of the FMOD 1.07 low-level API the engine uses, implemented on SDL
// audio: a software mixer for the Linux player and the web player, which have
// no FMOD drop (FMOD Ex was never licensed here for either).
//
// What the engine calls is here - systems, sounds from memory or file, streams,
// user PCM streams (VideoFrame), channels with volume, pitch, looping, position
// and 3D attenuation and panning, channel groups (SoundService's master) and the
// end-of-sound callback, and recording from one microphone (voice chat).
// Everything else stays in fmod_stubs.cpp and fails with FMOD_ERR_UNSUPPORTED:
// DSP plug-ins, geometry. Reverb is accepted and ignored.
//
// Threads. The engine calls in from its DataModel threads; one mixer thread of
// ours fills SDL's audio queue a few milliseconds ahead of the device (on the
// web SDL drains that queue into Web Audio on the page's thread). One recursive
// lock covers all state: VideoFrame's PCM callback, called while mixing, calls
// straight back in. End callbacks run from System::update, as FMOD's do, on the
// engine's thread and outside the lock.
//
// Channel handles. FMOD hands out Channel* values that go stale when the sound
// ends, and the engine keeps using them (isPlaying on a finished channel). They
// are encoded slot numbers with a generation here, never dereferenced, so a
// stale one is an FMOD_ERR_INVALID_HANDLE rather than a use after free. Channel
// handles are odd; ChannelGroup, Sound and System handles are real pointers.
#include "fmod.h"
#include "fmod.hpp"

#include <SDL.h>

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#endif

#include <math.h>
#include <string.h>

#include <fstream>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "Decoder.h"

namespace FmodSdl
{

typedef std::lock_guard<std::recursive_mutex> Lock;

static std::recursive_mutex& mutex()
{
	static std::recursive_mutex m;
	return m;
}

struct SystemImpl;

struct GroupImpl
{
	SystemImpl* system;
	GroupImpl* parent;
	float volume;
	bool mute;
	bool paused;
	void* userData;
	std::string name;

	GroupImpl(SystemImpl* system, GroupImpl* parent, const char* name)
		: system(system), parent(parent), volume(1), mute(false), paused(false), userData(NULL), name(name ? name : "")
	{
	}
};

struct SoundImpl
{
	SystemImpl* system;
	FMOD_MODE mode;
	int channels;               // 1 or 2 after downmixing
	int sampleRate;
	unsigned long long length;  // frames; 0xFFFFFFFF.. for an open-ended user stream

	// One of three sources: the whole sound decoded, a decoder pulled while
	// playing (FMOD_CREATESTREAM), or the owner's PCM callback (FMOD_OPENUSER).
	std::vector<short> pcm;
	std::vector<unsigned char> encoded;
	Decoder* decoder;
	int decoderChannels;
	FMOD_SOUND_PCMREAD_CALLBACK pcmRead;
	FMOD_SOUND_FORMAT userFormat;
	int userChannels;

	float defaultFrequency;
	int defaultPriority;
	float minDistance, maxDistance;
	int loopCount;
	void* userData;

	SoundImpl(SystemImpl* system)
		: system(system), mode(0), channels(0), sampleRate(0), length(0), decoder(NULL), decoderChannels(0),
		  pcmRead(NULL), userFormat(FMOD_SOUND_FORMAT_PCM16), userChannels(0),
		  defaultFrequency(0), defaultPriority(128), minDistance(1), maxDistance(10000), loopCount(-1), userData(NULL)
	{
	}

	~SoundImpl() { delete decoder; }

	bool isStream() const { return decoder != NULL || pcmRead != NULL; }
};

struct Vector
{
	float x, y, z;
	Vector() : x(0), y(0), z(0) {}
	Vector(const FMOD_VECTOR& v) : x(v.x), y(v.y), z(v.z) {}
	Vector(float x, float y, float z) : x(x), y(y), z(z) {}
	Vector operator-(const Vector& o) const { return Vector(x - o.x, y - o.y, z - o.z); }
	float dot(const Vector& o) const { return x * o.x + y * o.y + z * o.z; }
	Vector cross(const Vector& o) const { return Vector(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
	float length() const { return sqrtf(dot(*this)); }
};

struct SystemImpl
{
	GroupImpl* master;
	int maxChannels;
	bool initialized;
	void* userData;

	Vector listenerPos, listenerForward, listenerUp;
	float rolloffScale;

	SystemImpl() : master(NULL), maxChannels(32), initialized(false), userData(NULL), listenerForward(0, 0, 1), listenerUp(0, 1, 0), rolloffScale(1) {}
};

// One playing (or finished, awaiting its end callback) sound.
struct Slot
{
	enum State { FREE, PLAYING, ENDED };

	unsigned generation;
	State state;
	SystemImpl* system;
	SoundImpl* sound;
	GroupImpl* group;

	FMOD_MODE mode;
	float frequency;
	float volume;
	bool paused, mute;
	int loopsLeft;              // -1 forever
	int priority;
	Vector position, velocity;
	float minDistance, maxDistance;
	void* userData;
	FMOD_CHANNELCONTROL_CALLBACK callback;

	// Source cursor: PCM index for a decoded sound; decoders and callbacks
	// keep their own.
	unsigned long long readPos;
	unsigned long long playPos; // frames the mixer has played, for getPosition
	bool sourceDone;

	// Stereo float frames read from the source and not yet mixed, and the
	// resampler's two frames and phase between them.
	std::vector<float> buffer;
	unsigned bufferRead, bufferFill;
	float prev[2], cur[2];
	double phase;
	bool primed;

	float gainL, gainR;         // what the last block ended on, for ramping

	Slot() : generation(1), state(FREE) { reset(); }

	void reset()
	{
		system = NULL; sound = NULL; group = NULL;
		mode = 0; frequency = 0; volume = 1; paused = false; mute = false; loopsLeft = 0; priority = 128;
		position = velocity = Vector(); minDistance = 1; maxDistance = 10000;
		userData = NULL; callback = NULL;
		readPos = playPos = 0; sourceDone = false;
		bufferRead = bufferFill = 0;
		prev[0] = prev[1] = cur[0] = cur[1] = 0; phase = 0; primed = false;
		gainL = gainR = 0;
	}
};

static const unsigned MaxSlots = 1024;
// Bit 0 marks a channel, bits 1-10 are the slot, the rest its generation.
static const int HandleIndexBits = 11;

static std::vector<Slot>& slots()
{
	static std::vector<Slot> s(MaxSlots);
	return s;
}

static std::set<SystemImpl*>& systems() { static std::set<SystemImpl*> s; return s; }
static std::set<SoundImpl*>& sounds() { static std::set<SoundImpl*> s; return s; }
static std::set<GroupImpl*>& groups() { static std::set<GroupImpl*> s; return s; }

static void* channelHandle(unsigned index)
{
	return (void*) (((size_t) (slots()[index].generation & 0xFFFFF) << HandleIndexBits) | (index << 1) | 1);
}

static Slot* channelFrom(const void* handle)
{
	size_t h = (size_t) handle;
	if (!(h & 1))
		return NULL;
	unsigned index = (unsigned) ((h >> 1) & ((1 << (HandleIndexBits - 1)) - 1));
	unsigned generation = (unsigned) (h >> HandleIndexBits);
	if (index >= MaxSlots)
		return NULL;
	Slot& slot = slots()[index];
	if (slot.state == Slot::FREE || (slot.generation & 0xFFFFF) != generation)
		return NULL;
	return &slot;
}

static GroupImpl* groupFrom(const void* handle)
{
	if (!handle || ((size_t) handle & 1))
		return NULL;
	GroupImpl* group = (GroupImpl*) handle;
	return groups().count(group) ? group : NULL;
}

static SoundImpl* soundFrom(const void* handle)
{
	SoundImpl* sound = (SoundImpl*) handle;
	return handle && sounds().count(sound) ? sound : NULL;
}

static SystemImpl* systemFrom(const void* handle)
{
	SystemImpl* system = (SystemImpl*) handle;
	return handle && systems().count(system) ? system : NULL;
}

// Stops a channel; its end callback follows on the next System::update.
static void endChannel(Slot& slot)
{
	if (slot.state != Slot::PLAYING)
		return;
	slot.state = Slot::ENDED;
	slot.sound = NULL;
}

static void freeSlot(Slot& slot)
{
	slot.state = Slot::FREE;
	slot.generation++;
	slot.reset();
}

// ----------------------------------------------------------------- output ----

static void mixBlock(float* out, unsigned frames, int outputRate);

class Output
{
public:
	static Output& get() { static Output o; return o; }

	int rate;

	// Opens the device the first time any system is initialized and keeps it:
	// on the web closing it throws away the AudioContext, and a new one would
	// need another click before the browser lets it play.
	bool start()
	{
		std::lock_guard<std::mutex> lock(startMutex);
		if (device)
			return true;

		if (!SDL_WasInit(SDL_INIT_AUDIO) && SDL_InitSubSystem(SDL_INIT_AUDIO) != 0)
		{
			SDL_Log("fmod_sdl: no audio: %s", SDL_GetError());
			return false;
		}

		SDL_AudioSpec want, have;
		SDL_zero(want);
		want.freq = 48000;
		want.format = AUDIO_F32SYS;
		want.channels = 2;
		want.samples = 1024;
		want.callback = NULL; // queued from the mixer thread

		device = SDL_OpenAudioDevice(NULL, 0, &want, &have, SDL_AUDIO_ALLOW_FREQUENCY_CHANGE | SDL_AUDIO_ALLOW_SAMPLES_CHANGE);
		if (!device)
		{
			SDL_Log("fmod_sdl: cannot open an audio device: %s", SDL_GetError());
			return false;
		}

		rate = have.freq;
		// Enough queued to ride over two device periods (on the web: two
		// ScriptProcessor callbacks, which run between the page's frames).
		target = (unsigned) have.samples * 2 + (unsigned) (rate / 50);
		running = true;
		thread = std::thread(&Output::run, this);
		thread.detach();
		SDL_PauseAudioDevice(device, 0);
		return true;
	}

	bool isOpen() const { return device != 0; }

private:
	enum { Block = 512 };

	SDL_AudioDeviceID device;
	unsigned target;
	volatile bool running;
	std::thread thread;
	std::mutex startMutex;

	Output() : rate(48000), device(0), target(0), running(false) {}

	void run()
	{
		std::vector<float> block(Block * 2);
		while (running)
		{
			const unsigned queued = SDL_GetQueuedAudioSize(device) / (sizeof(float) * 2);
			if (queued >= target)
			{
				SDL_Delay(3);
				continue;
			}

			{
				Lock lock(mutex());
				mixBlock(&block[0], Block, rate);
			}
			SDL_QueueAudio(device, &block[0], Block * sizeof(float) * 2);
		}
	}
};

// --------------------------------------------------------------- capture ----
//
// FMOD recording: one microphone written into a sound the owner created with
// FMOD_OPENUSER and no read callback (a plain PCM16 ring). The owner polls
// getRecordPosition and reads the ring with Sound::lock, as with real FMOD.
//
// On the web the microphone is getUserMedia: a ScriptProcessor on the page's
// thread writes mono floats into a ring in shared memory and publishes how many
// it wrote. Elsewhere it is an SDL capture device. Either way the samples come
// at the source's own rate and an SDL_AudioStream turns them into the sound's
// rate and channel count. Nothing moves them in the background: getRecordPosition
// does, and the owner calls it every few frames anyway.

#ifdef __EMSCRIPTEN__
static const unsigned WebRingSize = 1 << 16; // mono samples, 1.4 s at 48 kHz
static float webRing[WebRingSize];
// [0] samples written so far (wraps at 2^32), [1] source rate once the
// microphone is live, [2] -1 when the browser refused it.
static int webState[3];
#endif

struct Recorder
{
	SoundImpl* sound;
	bool loop;
	unsigned position;          // frames into the sound
	SDL_AudioStream* convert;
#ifdef __EMSCRIPTEN__
	unsigned webRead;
	bool reportedFailure;
#else
	SDL_AudioDeviceID device;
	int deviceRate;
#endif

	Recorder() : sound(NULL), loop(false), position(0), convert(NULL)
#ifdef __EMSCRIPTEN__
		, webRead(0), reportedFailure(false)
#else
		, device(0), deviceRate(0)
#endif
	{
	}
};

static Recorder& recorder() { static Recorder r; return r; }

static int recordDriverCount()
{
#ifdef __EMSCRIPTEN__
	return 1;
#else
	int n = SDL_GetNumAudioDevices(1);
	return n > 0 ? n : 0;
#endif
}

static void stopRecording()
{
	Recorder& r = recorder();
	if (!r.sound)
		return;

#ifdef __EMSCRIPTEN__
	// Stopping the tracks, not just disconnecting them, is what turns the
	// browser's recording indicator off.
	MAIN_THREAD_ASYNC_EM_ASM({
		var mic = Module['fmodSdlMic'];
		if (!mic)
			return;
		++mic.gen;
		if (mic.node) { mic.node.onaudioprocess = null; mic.node.disconnect(); }
		if (mic.source) mic.source.disconnect();
		if (mic.stream) mic.stream.getTracks().forEach(function(t) { t.stop(); });
		mic.node = mic.source = mic.stream = undefined;
	});
#else
	if (r.device)
		SDL_CloseAudioDevice(r.device);
	r.device = 0;
#endif

	if (r.convert)
		SDL_FreeAudioStream(r.convert);
	r.convert = NULL;
	r.sound = NULL;
}

static FMOD_RESULT startRecording(int id, SoundImpl* sound, bool loop)
{
	stopRecording();

	Recorder& r = recorder();
	r.sound = sound;
	r.loop = loop;
	r.position = 0;

#ifdef __EMSCRIPTEN__
	// A ScriptProcessor still running from the last recording may write the
	// count once more before the stop above reaches the page; the rate is only
	// set by a live microphone, so a zero here makes the reader ignore it.
	__atomic_store_n(&webState[1], 0, __ATOMIC_RELEASE);
	__atomic_store_n(&webState[2], 0, __ATOMIC_RELEASE);
	r.webRead = 0;
	r.reportedFailure = false;

	// Asks for the microphone (the browser prompts the first time), then feeds
	// the ring. A stream that arrives after a stop, or after another start, is
	// stopped at once instead of leaving the microphone open.
	MAIN_THREAD_ASYNC_EM_ASM({
		var mic = Module['fmodSdlMic'] || (Module['fmodSdlMic'] = { gen: 0 });
		var gen = ++mic.gen;
		// One declaration per var: the preprocessor splits this block at any
		// comma outside parentheses.
		var ring = $0 >>> 2;
		var size = $1;
		var state = $2 >>> 2;
		Atomics.store(HEAP32, state, 0);
		var fail = function(e) {
			if (mic.gen != gen)
				return;
			console.warn('Voice chat: no microphone: ' + (e && (e.name || e.message) || e));
			Atomics.store(HEAP32, state + 2, -1);
		};
		if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
			fail('getUserMedia is not available (the page must be served over HTTPS)');
			return;
		}
		navigator.mediaDevices.getUserMedia({
			audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
			video: false
		}).then(function(stream) {
			if (mic.gen != gen) {
				stream.getTracks().forEach(function(t) { t.stop(); });
				return;
			}
			var ctx = (Module['SDL2'] && Module['SDL2'].audioContext) || mic.ctx;
			if (!ctx)
				ctx = mic.ctx = new (window.AudioContext || window.webkitAudioContext)();
			if (ctx.state == 'suspended')
				ctx.resume();
			var source = ctx.createMediaStreamSource(stream);
			var node = ctx.createScriptProcessor(2048, 1, 1);
			var written = 0;
			node.onaudioprocess = function(e) {
				e.outputBuffer.getChannelData(0).fill(0);
				if (mic.gen != gen)
					return;
				var input = e.inputBuffer;
				var n = input.length;
				var channels = input.numberOfChannels;
				var data = [];
				for (var c = 0; c < channels; ++c)
					data.push(input.getChannelData(c));
				for (var i = 0; i < n; ++i) {
					var s = 0;
					for (var c = 0; c < channels; ++c)
						s += data[c][i];
					HEAPF32[ring + (written & (size - 1))] = s / channels;
					++written;
				}
				Atomics.store(HEAP32, state, written | 0);
			};
			source.connect(node);
			// A ScriptProcessor only runs while connected to the output; it
			// writes silence there.
			node.connect(ctx.destination);
			mic.stream = stream;
			mic.source = source;
			mic.node = node;
			Atomics.store(HEAP32, state + 1, ctx.sampleRate);
		}).catch(fail);
	}, webRing, WebRingSize, webState);
	return FMOD_OK;
#else
	SDL_AudioSpec want, have;
	SDL_zero(want);
	want.freq = 48000;
	want.format = AUDIO_F32SYS;
	want.channels = 1;
	want.samples = 1024;
	want.callback = NULL; // dequeued by getRecordPosition

	const char* name = SDL_GetAudioDeviceName(id, 1);
	r.device = SDL_OpenAudioDevice(name, 1, &want, &have, SDL_AUDIO_ALLOW_FREQUENCY_CHANGE);
	if (!r.device)
	{
		SDL_Log("fmod_sdl: cannot open recording device %d: %s", id, SDL_GetError());
		r.sound = NULL;
		return FMOD_ERR_RECORD;
	}
	r.deviceRate = have.freq;
	SDL_PauseAudioDevice(r.device, 0);
	return FMOD_OK;
#endif
}

// Moves what the microphone captured since the last call into the sound.
static FMOD_RESULT pumpRecording()
{
	Recorder& r = recorder();
	SoundImpl* sound = r.sound;
	if (!sound)
		return FMOD_OK;

	static std::vector<float> source;
	int rate;

#ifdef __EMSCRIPTEN__
	if (__atomic_load_n(&webState[2], __ATOMIC_ACQUIRE) < 0)
	{
		if (!r.reportedFailure)
			SDL_Log("fmod_sdl: the browser gave no microphone");
		r.reportedFailure = true;
		return FMOD_ERR_RECORD_DISCONNECTED;
	}
	rate = __atomic_load_n(&webState[1], __ATOMIC_ACQUIRE);
	if (rate <= 0)
		return FMOD_OK; // still waiting for the permission prompt
	const unsigned written = (unsigned) __atomic_load_n(&webState[0], __ATOMIC_ACQUIRE);
	unsigned n = written - r.webRead;
	// Fell a whole ring behind (a stalled tab): keep the newest half.
	if (n > WebRingSize)
	{
		r.webRead = written - WebRingSize / 2;
		n = WebRingSize / 2;
	}
	source.resize(n);
	for (unsigned i = 0; i < n; ++i)
		source[i] = webRing[(r.webRead + i) & (WebRingSize - 1)];
	r.webRead = written;
#else
	rate = r.deviceRate;
	source.resize(SDL_GetQueuedAudioSize(r.device) / sizeof(float));
	if (!source.empty())
		source.resize(SDL_DequeueAudio(r.device, &source[0], source.size() * sizeof(float)) / sizeof(float));
#endif

	const int channels = sound->userChannels;
	if (!r.convert)
	{
		r.convert = SDL_NewAudioStream(AUDIO_F32SYS, 1, rate, AUDIO_S16SYS, channels, sound->sampleRate);
		if (!r.convert)
			return FMOD_ERR_RECORD;
	}
	if (!source.empty())
		SDL_AudioStreamPut(r.convert, &source[0], (int) (source.size() * sizeof(float)));

	const unsigned frames = (unsigned) sound->length;
	short block[1024 * 2];
	int got;
	while (r.sound && (got = SDL_AudioStreamGet(r.convert, block, sizeof(block))) > 0)
	{
		const int n = got / (int) (sizeof(short) * channels);
		for (int i = 0; i < n; ++i)
		{
			memcpy(&sound->pcm[(size_t) r.position * channels], &block[i * channels], sizeof(short) * channels);
			if (++r.position >= frames)
			{
				if (!r.loop)
				{
					stopRecording();
					break;
				}
				r.position = 0;
			}
		}
	}
	return FMOD_OK;
}

// ------------------------------------------------------------------ mixer ----

static void groupState(GroupImpl* group, float& volume, bool& mute, bool& paused)
{
	volume = 1; mute = false; paused = false;
	for (int depth = 0; group && depth < 16; group = group->parent, ++depth)
	{
		volume *= group->volume;
		mute = mute || group->mute;
		paused = paused || group->paused;
	}
}

// Reads up to 'frames' source frames into slot.buffer as stereo float.
static unsigned readSource(Slot& slot, unsigned frames)
{
	SoundImpl* sound = slot.sound;
	float* out = &slot.buffer[0];

	if (!sound->pcm.empty() || (!sound->decoder && !sound->pcmRead))
	{
		const unsigned long long available = sound->length > slot.readPos ? sound->length - slot.readPos : 0;
		const unsigned n = (unsigned) (available < frames ? available : frames);
		const short* src = sound->pcm.empty() ? NULL : &sound->pcm[slot.readPos * sound->channels];
		for (unsigned i = 0; i < n; ++i)
		{
			if (sound->channels == 1)
				out[2 * i] = out[2 * i + 1] = src[i] * (1.0f / 32768);
			else
			{
				out[2 * i] = src[2 * i] * (1.0f / 32768);
				out[2 * i + 1] = src[2 * i + 1] * (1.0f / 32768);
			}
		}
		slot.readPos += n;
		return n;
	}

	if (sound->decoder)
	{
		const int ch = sound->decoderChannels;
		static thread_local std::vector<float> scratch;
		scratch.resize((size_t) frames * ch);
		const unsigned n = sound->decoder->read(&scratch[0], frames);
		for (unsigned i = 0; i < n; ++i)
		{
			const float* f = &scratch[(size_t) i * ch];
			if (ch == 1)
				out[2 * i] = out[2 * i + 1] = f[0];
			else
			{
				out[2 * i] = f[0];
				out[2 * i + 1] = f[1];
			}
		}
		slot.readPos += n;
		return n;
	}

	// FMOD_OPENUSER: the owner fills bytes of its declared format.
	const int ch = sound->userChannels;
	const bool isFloat = sound->userFormat == FMOD_SOUND_FORMAT_PCMFLOAT;
	const unsigned bytesPerFrame = ch * (isFloat ? 4 : 2);
	static thread_local std::vector<unsigned char> raw;
	raw.resize((size_t) frames * bytesPerFrame);
	sound->pcmRead((FMOD_SOUND*) sound, &raw[0], frames * bytesPerFrame);
	for (unsigned i = 0; i < frames; ++i)
	{
		float l, r;
		if (isFloat)
		{
			const float* f = (const float*) &raw[(size_t) i * bytesPerFrame];
			l = f[0]; r = ch > 1 ? f[1] : l;
		}
		else
		{
			const short* s = (const short*) &raw[(size_t) i * bytesPerFrame];
			l = s[0] * (1.0f / 32768); r = ch > 1 ? s[1] * (1.0f / 32768) : l;
		}
		out[2 * i] = l;
		out[2 * i + 1] = r;
	}
	slot.readPos += frames;
	return frames;
}

static void seekSource(Slot& slot, unsigned long long frame)
{
	SoundImpl* sound = slot.sound;
	if (sound->decoder)
		sound->decoder->seek(frame);
	slot.readPos = frame;
	slot.playPos = frame;
	slot.bufferRead = slot.bufferFill = 0;
	slot.primed = false;
	slot.phase = 0;
	slot.sourceDone = false;
}

static bool looping(const Slot& slot)
{
	return (slot.mode & (FMOD_LOOP_NORMAL | FMOD_LOOP_BIDI)) && slot.loopsLeft != 0;
}

// The next source frame into cur; false once the sound has run out.
static bool nextFrame(Slot& slot)
{
	if (slot.bufferRead >= slot.bufferFill)
	{
		if (slot.sourceDone)
			return false;

		const unsigned chunk = 1024;
		slot.buffer.resize(chunk * 2);
		unsigned got = readSource(slot, chunk);

		// Loop back to the start as many times as it takes to fill a chunk,
		// but never spin on a sound with nothing in it.
		if (got < chunk && looping(slot) && slot.sound->length > 0 && !slot.sound->pcmRead)
		{
			if (slot.loopsLeft > 0)
				slot.loopsLeft--;
			if (slot.sound->decoder)
				slot.sound->decoder->seek(0);
			slot.readPos = 0;
		}
		else if (got == 0)
		{
			slot.sourceDone = true;
			return false;
		}

		slot.bufferRead = 0;
		slot.bufferFill = got;
		if (got == 0)
			return nextFrame(slot);
	}

	slot.prev[0] = slot.cur[0];
	slot.prev[1] = slot.cur[1];
	slot.cur[0] = slot.buffer[2 * slot.bufferRead];
	slot.cur[1] = slot.buffer[2 * slot.bufferRead + 1];
	slot.bufferRead++;
	slot.playPos++;
	return true;
}

static float rolloff(const Slot& slot, float distance)
{
	const float minD = slot.minDistance > 1e-4f ? slot.minDistance : 1e-4f;
	const float maxD = slot.maxDistance > minD ? slot.maxDistance : minD;
	if (distance <= minD)
		return 1;

	const float linear = distance >= maxD ? 0 : 1 - (distance - minD) / (maxD - minD);
	const float d = distance < maxD ? distance : maxD;
	const float inverse = minD / (minD + slot.system->rolloffScale * (d - minD));

	if (slot.mode & FMOD_3D_LINEARROLLOFF)
		return linear;
	if (slot.mode & FMOD_3D_LINEARSQUAREROLLOFF)
		return linear * linear;
	if (slot.mode & FMOD_3D_INVERSETAPEREDROLLOFF)
		return inverse < linear * linear ? inverse : linear * linear;
	return inverse;
}

static void targetGains(const Slot& slot, float& left, float& right)
{
	float groupVolume; bool groupMute, groupPaused;
	groupState(slot.group, groupVolume, groupMute, groupPaused);

	float gain = slot.mute || groupMute ? 0 : slot.volume * groupVolume;
	left = right = gain;

	if (!(slot.mode & FMOD_3D))
		return;

	// FMOD's default left-handed space: x right, y up, z forward.
	SystemImpl* system = slot.system;
	Vector rel = (slot.mode & FMOD_3D_HEADRELATIVE) ? slot.position : slot.position - system->listenerPos;
	const float distance = rel.length();
	gain *= rolloff(slot, distance);

	float pan = 0;
	if (distance > 1e-3f)
	{
		Vector forward = (slot.mode & FMOD_3D_HEADRELATIVE) ? Vector(0, 0, 1) : system->listenerForward;
		Vector up = (slot.mode & FMOD_3D_HEADRELATIVE) ? Vector(0, 1, 0) : system->listenerUp;
		Vector side = up.cross(forward);
		const float sideLength = side.length();
		if (sideLength > 1e-6f)
			pan = side.dot(rel) / (sideLength * distance);
	}

	// Equal power, scaled so a source straight ahead plays at full level on
	// both sides, the same as a 2D one.
	const float angle = (pan + 1) * 0.785398163f;
	const float l = 1.41421356f * cosf(angle), r = 1.41421356f * sinf(angle);
	left = gain * (l < 1 ? l : 1);
	right = gain * (r < 1 ? r : 1);
}

static void mixSlot(Slot& slot, float* out, unsigned frames, int outputRate)
{
	float groupVolume; bool groupMute, groupPaused;
	groupState(slot.group, groupVolume, groupMute, groupPaused);
	if (slot.paused || groupPaused || !slot.sound)
		return;

	float left, right;
	targetGains(slot, left, right);

	const bool mono3D = (slot.mode & FMOD_3D) != 0;
	const double step = (slot.frequency > 0 ? slot.frequency : slot.sound->sampleRate) / (double) outputRate;

	if (!slot.primed)
	{
		if (!nextFrame(slot)) { endChannel(slot); return; }
		slot.prev[0] = slot.cur[0];
		slot.prev[1] = slot.cur[1];
		if (!nextFrame(slot)) { slot.cur[0] = slot.cur[1] = 0; slot.sourceDone = true; }
		slot.playPos = 0;
		slot.primed = true;
		slot.phase = 0;
	}

	const float dl = (left - slot.gainL) / frames, dr = (right - slot.gainR) / frames;
	float gl = slot.gainL, gr = slot.gainR;
	bool finished = false;

	for (unsigned i = 0; i < frames; ++i)
	{
		while (slot.phase >= 1.0)
		{
			slot.phase -= 1.0;
			if (!nextFrame(slot))
			{
				finished = true;
				break;
			}
		}
		if (finished)
			break;

		const float t = (float) slot.phase;
		float l = slot.prev[0] + (slot.cur[0] - slot.prev[0]) * t;
		float r = slot.prev[1] + (slot.cur[1] - slot.prev[1]) * t;
		if (mono3D)
			l = r = 0.5f * (l + r);

		gl += dl; gr += dr;
		out[2 * i] += l * gl;
		out[2 * i + 1] += r * gr;
		slot.phase += step;
	}

	slot.gainL = left;
	slot.gainR = right;

	if (finished)
		endChannel(slot);
}

static void mixBlock(float* out, unsigned frames, int outputRate)
{
	memset(out, 0, sizeof(float) * 2 * frames);

	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].state == Slot::PLAYING)
			mixSlot(all[i], out, frames, outputRate);

	for (unsigned i = 0; i < frames * 2; ++i)
	{
		float s = out[i];
		out[i] = s > 1 ? 1 : (s < -1 ? -1 : s);
	}
}

// ------------------------------------------------------------ sound setup ----

static bool readFile(const char* name, std::vector<unsigned char>& data)
{
	std::ifstream file(name, std::ios::binary);
	if (!file)
		return false;
	file.seekg(0, std::ios::end);
	std::streamoff size = file.tellg();
	if (size <= 0)
		return false;
	file.seekg(0);
	data.resize((size_t) size);
	file.read((char*) &data[0], size);
	return (bool) file;
}

static FMOD_RESULT createSoundImpl(SystemImpl* system, const char* nameOrData, FMOD_MODE mode, FMOD_CREATESOUNDEXINFO* exinfo, bool stream, SoundImpl** result)
{
	std::unique_ptr<SoundImpl> sound(new SoundImpl(system));
	sound->mode = mode & ~(FMOD_OPENMEMORY | FMOD_OPENMEMORY_POINT | FMOD_OPENUSER | FMOD_CREATESTREAM);
	if (exinfo && exinfo->userdata)
		sound->userData = exinfo->userdata;

	if ((mode & FMOD_OPENUSER) && exinfo && !exinfo->pcmreadcallback)
	{
		// No callback: a PCM16 buffer of exinfo->length bytes that the owner
		// fills through Sound::lock or records into.
		if (exinfo->numchannels <= 0 || exinfo->defaultfrequency <= 0 || !exinfo->length)
			return FMOD_ERR_INVALID_PARAM;
		if (exinfo->format != FMOD_SOUND_FORMAT_PCM16 || exinfo->numchannels > 2)
			return FMOD_ERR_FORMAT;
		sound->userFormat = exinfo->format;
		sound->userChannels = sound->channels = exinfo->numchannels;
		sound->sampleRate = exinfo->defaultfrequency;
		sound->length = exinfo->length / (sizeof(short) * exinfo->numchannels);
		if (!sound->length)
			return FMOD_ERR_INVALID_PARAM;
		sound->pcm.assign((size_t) sound->length * sound->channels, 0);
	}
	else if (mode & FMOD_OPENUSER)
	{
		if (!exinfo || exinfo->numchannels <= 0 || exinfo->defaultfrequency <= 0 || !exinfo->pcmreadcallback)
			return FMOD_ERR_INVALID_PARAM;
		if (exinfo->format != FMOD_SOUND_FORMAT_PCM16 && exinfo->format != FMOD_SOUND_FORMAT_PCMFLOAT)
			return FMOD_ERR_FORMAT;
		sound->pcmRead = exinfo->pcmreadcallback;
		sound->userFormat = exinfo->format;
		sound->userChannels = exinfo->numchannels;
		sound->channels = exinfo->numchannels > 1 ? 2 : 1;
		sound->sampleRate = exinfo->defaultfrequency;
		sound->length = exinfo->length ? exinfo->length : 0xFFFFFFFFu;
	}
	else
	{
		std::vector<unsigned char> data;
		if (mode & (FMOD_OPENMEMORY | FMOD_OPENMEMORY_POINT))
		{
			if (!nameOrData || !exinfo || !exinfo->length)
				return FMOD_ERR_INVALID_PARAM;
			data.assign((const unsigned char*) nameOrData, (const unsigned char*) nameOrData + exinfo->length);
		}
		else if (!nameOrData || !readFile(nameOrData, data))
			return FMOD_ERR_FILE_NOTFOUND;

		sound->encoded.swap(data);
		Decoder* decoder = Decoder::open(sound->encoded);
		if (!decoder)
			return FMOD_ERR_FORMAT;

		sound->sampleRate = decoder->sampleRate;
		sound->channels = decoder->channels > 1 ? 2 : 1;

		if (stream)
		{
			sound->decoder = decoder;
			sound->decoderChannels = decoder->channels;
			sound->length = decoder->length;
		}
		else
		{
			// Decode it all now; a sound is short (streams start at 500 KB of
			// file) and is then played any number of times at once.
			std::vector<float> chunk(4096 * decoder->channels);
			unsigned n;
			while ((n = decoder->read(&chunk[0], 4096)) > 0)
			{
				for (unsigned i = 0; i < n; ++i)
				{
					const float* f = &chunk[(size_t) i * decoder->channels];
					for (int c = 0; c < sound->channels; ++c)
					{
						float s = f[c] * 32767.0f;
						sound->pcm.push_back((short) (s > 32767 ? 32767 : (s < -32768 ? -32768 : s)));
					}
				}
			}
			delete decoder;
			sound->length = sound->pcm.size() / sound->channels;
			std::vector<unsigned char>().swap(sound->encoded);
		}
	}

	sound->defaultFrequency = (float) sound->sampleRate;

	Lock lock(mutex());
	sounds().insert(sound.get());
	*result = sound.release();
	return FMOD_OK;
}

static void stopChannelsOf(SoundImpl* sound)
{
	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].sound == sound)
			endChannel(all[i]);
}

static void releaseSystem(SystemImpl* system)
{
	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].state != Slot::FREE && all[i].system == system)
			freeSlot(all[i]);

	for (std::set<SoundImpl*>::iterator it = sounds().begin(); it != sounds().end();)
	{
		SoundImpl* sound = *it;
		if (sound->system == system)
		{
			sounds().erase(it++);
			delete sound;
		}
		else
			++it;
	}

	for (std::set<GroupImpl*>::iterator it = groups().begin(); it != groups().end();)
	{
		GroupImpl* group = *it;
		if (group->system == system)
		{
			groups().erase(it++);
			delete group;
		}
		else
			++it;
	}

	systems().erase(system);
	delete system;
}

static float msToFrames(unsigned ms, int rate) { return (float) ms * rate / 1000.0f; }

} // namespace FmodSdl

using namespace FmodSdl;

#define LOCKED Lock lock(mutex())
#define CHANNEL_OR_GROUP \
	LOCKED; \
	Slot* slot = channelFrom(this); \
	GroupImpl* group = slot ? NULL : groupFrom(this); \
	if (!slot && !group) return FMOD_ERR_INVALID_HANDLE
#define CHANNEL \
	LOCKED; \
	Slot* slot = channelFrom(this); \
	if (!slot) return FMOD_ERR_INVALID_HANDLE
#define SOUND \
	LOCKED; \
	SoundImpl* sound = soundFrom(this); \
	if (!sound) return FMOD_ERR_INVALID_HANDLE
#define SYSTEM \
	LOCKED; \
	SystemImpl* system = systemFrom(this); \
	if (!system) return FMOD_ERR_INVALID_HANDLE

// ------------------------------------------------------------------ C API ----

extern "C" {

FMOD_RESULT F_API FMOD_System_Create(FMOD_SYSTEM** system)
{
	if (!system)
		return FMOD_ERR_INVALID_PARAM;
	LOCKED;
	SystemImpl* impl = new SystemImpl();
	impl->master = new GroupImpl(impl, NULL, "Master");
	groups().insert(impl->master);
	systems().insert(impl);
	*system = (FMOD_SYSTEM*) impl;
	return FMOD_OK;
}

FMOD_RESULT F_API FMOD_Memory_GetStats(int* currentalloced, int* maxalloced, FMOD_BOOL blocking)
{
	LOCKED;
	size_t total = 0;
	for (std::set<SoundImpl*>::iterator it = sounds().begin(); it != sounds().end(); ++it)
		total += (*it)->pcm.size() * sizeof(short) + (*it)->encoded.size();
	if (currentalloced) *currentalloced = (int) total;
	if (maxalloced) *maxalloced = (int) total;
	return FMOD_OK;
}

FMOD_RESULT F_API FMOD_Channel_GetUserData(FMOD_CHANNEL* channel, void** userdata)
{
	return ((FMOD::Channel*) channel)->getUserData(userdata);
}

FMOD_RESULT F_API FMOD_Sound_GetUserData(FMOD_SOUND* sound, void** userdata)
{
	return ((FMOD::Sound*) sound)->getUserData(userdata);
}

}

namespace FMOD
{

// ----------------------------------------------------------------- System ----

FMOD_RESULT F_API System::release()
{
	SYSTEM;
	releaseSystem(system);
	return FMOD_OK;
}

FMOD_RESULT F_API System::getNumDrivers(int* numdrivers) { if (numdrivers) *numdrivers = 1; return FMOD_OK; }
FMOD_RESULT F_API System::setDriver(int driver) { return driver == 0 ? FMOD_OK : FMOD_ERR_INVALID_PARAM; }
FMOD_RESULT F_API System::getDriver(int* driver) { if (driver) *driver = 0; return FMOD_OK; }

FMOD_RESULT F_API System::getDriverInfo(int id, char* name, int namelen, FMOD_GUID* guid, int* systemrate, FMOD_SPEAKERMODE* speakermode, int* speakermodechannels)
{
	if (name && namelen > 0)
	{
#ifdef __EMSCRIPTEN__
		strncpy(name, "Web Audio", namelen);
#else
		strncpy(name, "SDL audio", namelen);
#endif
		name[namelen - 1] = 0;
	}
	if (guid) memset(guid, 0, sizeof(*guid));
	if (systemrate) *systemrate = Output::get().rate;
	if (speakermode) *speakermode = FMOD_SPEAKERMODE_STEREO;
	if (speakermodechannels) *speakermodechannels = 2;
	return FMOD_OK;
}

FMOD_RESULT F_API System::setSoftwareFormat(int samplerate, FMOD_SPEAKERMODE speakermode, int numrawspeakers) { return FMOD_OK; }

FMOD_RESULT F_API System::getSoftwareFormat(int* samplerate, FMOD_SPEAKERMODE* speakermode, int* numrawspeakers)
{
	if (samplerate) *samplerate = Output::get().rate;
	if (speakermode) *speakermode = FMOD_SPEAKERMODE_STEREO;
	if (numrawspeakers) *numrawspeakers = 0;
	return FMOD_OK;
}

FMOD_RESULT F_API System::setDSPBufferSize(unsigned int bufferlength, int numbuffers) { return FMOD_OK; }
FMOD_RESULT F_API System::setStreamBufferSize(unsigned int filebuffersize, FMOD_TIMEUNIT filebuffersizetype) { return FMOD_OK; }

FMOD_RESULT F_API System::init(int maxchannels, FMOD_INITFLAGS flags, void* extradriverdata)
{
	{
		SYSTEM;
		system->maxChannels = maxchannels > 0 ? maxchannels : 32;
	}
	// Outside the lock: opening the device waits on the page's thread on the
	// web, which may itself be waiting to mix.
	if (!Output::get().start())
		return FMOD_ERR_OUTPUT_INIT;
	SYSTEM;
	system->initialized = true;
	return FMOD_OK;
}

FMOD_RESULT F_API System::close() { return FMOD_OK; }

FMOD_RESULT F_API System::update()
{
	std::vector<std::pair<void*, FMOD_CHANNELCONTROL_CALLBACK> > ended;
	{
		SYSTEM;
		std::vector<Slot>& all = slots();
		for (unsigned i = 0; i < MaxSlots; ++i)
			if (all[i].state == Slot::ENDED && all[i].system == system)
				ended.push_back(std::make_pair(channelHandle(i), all[i].callback));
	}

	for (size_t i = 0; i < ended.size(); ++i)
		if (ended[i].second)
			ended[i].second((FMOD_CHANNELCONTROL*) ended[i].first, FMOD_CHANNELCONTROL_CHANNEL, FMOD_CHANNELCONTROL_CALLBACK_END, NULL, NULL);

	LOCKED;
	for (size_t i = 0; i < ended.size(); ++i)
		if (Slot* slot = channelFrom(ended[i].first))
			if (slot->state == Slot::ENDED)
				freeSlot(*slot);
	return FMOD_OK;
}

FMOD_RESULT F_API System::set3DSettings(float dopplerscale, float distancefactor, float rolloffscale)
{
	SYSTEM;
	system->rolloffScale = rolloffscale > 0 ? rolloffscale : 1;
	return FMOD_OK;
}

FMOD_RESULT F_API System::set3DListenerAttributes(int listener, const FMOD_VECTOR* pos, const FMOD_VECTOR* vel, const FMOD_VECTOR* forward, const FMOD_VECTOR* up)
{
	SYSTEM;
	if (pos) system->listenerPos = *pos;
	if (forward) system->listenerForward = *forward;
	if (up) system->listenerUp = *up;
	return FMOD_OK;
}

FMOD_RESULT F_API System::getVersion(unsigned int* version) { if (version) *version = FMOD_VERSION; return FMOD_OK; }

FMOD_RESULT F_API System::getChannelsPlaying(int* channels)
{
	SYSTEM;
	int count = 0;
	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].state == Slot::PLAYING && all[i].system == system)
			count++;
	if (channels) *channels = count;
	return FMOD_OK;
}

FMOD_RESULT F_API System::getChannelsReal(int* realchannels) { return getChannelsPlaying(realchannels); }

FMOD_RESULT F_API System::getCPUUsage(float* dsp, float* stream, float* geometry, float* update, float* total)
{
	if (dsp) *dsp = 0; if (stream) *stream = 0; if (geometry) *geometry = 0; if (update) *update = 0; if (total) *total = 0;
	return FMOD_OK;
}

FMOD_RESULT F_API System::createSound(const char* name_or_data, FMOD_MODE mode, FMOD_CREATESOUNDEXINFO* exinfo, Sound** sound)
{
	SystemImpl* system;
	{
		LOCKED;
		system = systemFrom(this);
	}
	if (!system || !sound)
		return system ? FMOD_ERR_INVALID_PARAM : FMOD_ERR_INVALID_HANDLE;
	// Decoding happens outside the lock so the mixer never waits on it.
	return createSoundImpl(system, name_or_data, mode, exinfo, (mode & FMOD_CREATESTREAM) != 0, (SoundImpl**) sound);
}

FMOD_RESULT F_API System::createStream(const char* name_or_data, FMOD_MODE mode, FMOD_CREATESOUNDEXINFO* exinfo, Sound** sound)
{
	return createSound(name_or_data, mode | FMOD_CREATESTREAM, exinfo, sound);
}

FMOD_RESULT F_API System::createChannelGroup(const char* name, ChannelGroup** channelgroup)
{
	SYSTEM;
	if (!channelgroup)
		return FMOD_ERR_INVALID_PARAM;
	GroupImpl* group = new GroupImpl(system, system->master, name);
	groups().insert(group);
	*channelgroup = (ChannelGroup*) group;
	return FMOD_OK;
}

FMOD_RESULT F_API System::getMasterChannelGroup(ChannelGroup** channelgroup)
{
	SYSTEM;
	if (channelgroup) *channelgroup = (ChannelGroup*) system->master;
	return FMOD_OK;
}

FMOD_RESULT F_API System::playSound(Sound* soundHandle, ChannelGroup* channelgroup, bool paused, Channel** channel)
{
	SYSTEM;
	SoundImpl* sound = soundFrom(soundHandle);
	if (!sound)
		return FMOD_ERR_INVALID_HANDLE;

	GroupImpl* group = channelgroup ? groupFrom(channelgroup) : system->master;
	if (!group)
		group = system->master;

	std::vector<Slot>& all = slots();

	// A stream has one read cursor, so it plays on one channel at a time.
	if (sound->isStream())
	{
		stopChannelsOf(sound);
		if (sound->decoder)
			sound->decoder->seek(0);
	}

	int playing = 0;
	int freeIndex = -1;
	for (unsigned i = 0; i < MaxSlots; ++i)
	{
		if (all[i].state == Slot::FREE)
		{
			if (freeIndex < 0)
				freeIndex = i;
		}
		else if (all[i].system == system && all[i].state == Slot::PLAYING)
			playing++;
	}

	// Over the voice limit: the lowest priority (highest number) sound that is
	// playing gives way, as FMOD steals the least important voice.
	if (playing >= system->maxChannels)
	{
		int victim = -1;
		for (unsigned i = 0; i < MaxSlots; ++i)
			if (all[i].state == Slot::PLAYING && all[i].system == system && (victim < 0 || all[i].priority >= all[victim].priority))
				victim = i;
		if (victim >= 0)
			endChannel(all[victim]);
	}

	if (freeIndex < 0)
		return FMOD_ERR_CHANNEL_ALLOC;

	Slot& slot = all[freeIndex];
	slot.reset();
	slot.state = Slot::PLAYING;
	slot.system = system;
	slot.sound = sound;
	slot.group = group;
	slot.mode = sound->mode;
	slot.frequency = sound->defaultFrequency;
	slot.paused = paused;
	slot.loopsLeft = sound->loopCount;
	slot.priority = sound->defaultPriority;
	slot.minDistance = sound->minDistance;
	slot.maxDistance = sound->maxDistance;

	if (channel)
		*channel = (Channel*) channelHandle(freeIndex);
	return FMOD_OK;
}

FMOD_RESULT F_API System::setReverbProperties(int instance, const FMOD_REVERB_PROPERTIES* prop) { return FMOD_OK; }
FMOD_RESULT F_API System::lockDSP() { mutex().lock(); return FMOD_OK; }
FMOD_RESULT F_API System::unlockDSP() { mutex().unlock(); return FMOD_OK; }
FMOD_RESULT F_API System::mixerSuspend() { return FMOD_OK; }
FMOD_RESULT F_API System::mixerResume() { return FMOD_OK; }

FMOD_RESULT F_API System::getRecordNumDrivers(int* numdrivers, int* numconnected)
{
	SYSTEM;
	const int n = recordDriverCount();
	if (numdrivers) *numdrivers = n;
	if (numconnected) *numconnected = n;
	return FMOD_OK;
}

FMOD_RESULT F_API System::getRecordDriverInfo(int id, char* name, int namelen, FMOD_GUID* guid, int* systemrate, FMOD_SPEAKERMODE* speakermode, int* speakermodechannels, FMOD_DRIVER_STATE* state)
{
	SYSTEM;
	if (id < 0 || id >= recordDriverCount())
		return FMOD_ERR_INVALID_PARAM;
	if (name && namelen > 0)
	{
#ifdef __EMSCRIPTEN__
		strncpy(name, "Microphone", namelen);
#else
		const char* deviceName = SDL_GetAudioDeviceName(id, 1);
		strncpy(name, deviceName ? deviceName : "Microphone", namelen);
#endif
		name[namelen - 1] = 0;
	}
	if (guid) memset(guid, 0, sizeof(*guid));
	if (systemrate) *systemrate = 48000;
	// Captured as mono whatever the device has: the conversion to the sound's
	// channel count happens here, not in the owner.
	if (speakermode) *speakermode = FMOD_SPEAKERMODE_MONO;
	if (speakermodechannels) *speakermodechannels = 1;
	if (state) *state = FMOD_DRIVER_STATE_CONNECTED | (id == 0 ? FMOD_DRIVER_STATE_DEFAULT : 0);
	return FMOD_OK;
}

FMOD_RESULT F_API System::recordStart(int id, Sound* soundHandle, bool loop)
{
	SYSTEM;
	SoundImpl* sound = soundFrom(soundHandle);
	if (!sound)
		return FMOD_ERR_INVALID_HANDLE;
	if (id < 0 || id >= recordDriverCount())
		return FMOD_ERR_INVALID_PARAM;
	if (sound->pcm.empty() || sound->isStream())
		return FMOD_ERR_FORMAT;
	return startRecording(id, sound, loop);
}

FMOD_RESULT F_API System::recordStop(int id)
{
	SYSTEM;
	stopRecording();
	return FMOD_OK;
}

FMOD_RESULT F_API System::getRecordPosition(int id, unsigned int* position)
{
	SYSTEM;
	const FMOD_RESULT result = pumpRecording();
	if (position) *position = recorder().position;
	return result;
}

FMOD_RESULT F_API System::isRecording(int id, bool* recording)
{
	SYSTEM;
	pumpRecording();
	if (recording) *recording = recorder().sound != NULL;
	return FMOD_OK;
}

FMOD_RESULT F_API System::setUserData(void* userdata) { SYSTEM; system->userData = userdata; return FMOD_OK; }
FMOD_RESULT F_API System::getUserData(void** userdata) { SYSTEM; if (userdata) *userdata = system->userData; return FMOD_OK; }

// ------------------------------------------------------------------ Sound ----

FMOD_RESULT F_API Sound::release()
{
	SOUND;
	if (recorder().sound == sound)
		stopRecording();
	stopChannelsOf(sound);
	sounds().erase(sound);
	delete sound;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::getSystemObject(System** system)
{
	SOUND;
	if (system) *system = (System*) sound->system;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::setDefaults(float frequency, int priority)
{
	SOUND;
	if (frequency > 0) sound->defaultFrequency = frequency;
	sound->defaultPriority = priority;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::getDefaults(float* frequency, int* priority)
{
	SOUND;
	if (frequency) *frequency = sound->defaultFrequency;
	if (priority) *priority = sound->defaultPriority;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::set3DMinMaxDistance(float min, float max)
{
	SOUND;
	if (min < 0 || max < min) return FMOD_ERR_INVALID_PARAM;
	sound->minDistance = min;
	sound->maxDistance = max;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::get3DMinMaxDistance(float* min, float* max)
{
	SOUND;
	if (min) *min = sound->minDistance;
	if (max) *max = sound->maxDistance;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::getName(char* name, int namelen) { SOUND; if (name && namelen > 0) name[0] = 0; return FMOD_OK; }

FMOD_RESULT F_API Sound::getLength(unsigned int* length, FMOD_TIMEUNIT lengthtype)
{
	SOUND;
	if (!length) return FMOD_ERR_INVALID_PARAM;
	if (lengthtype == FMOD_TIMEUNIT_MS)
		*length = sound->length >= 0xFFFFFFFFu ? 0xFFFFFFFFu : (unsigned) (sound->length * 1000 / (sound->sampleRate ? sound->sampleRate : 1));
	else if (lengthtype == FMOD_TIMEUNIT_PCM)
		*length = (unsigned) sound->length;
	else if (lengthtype == FMOD_TIMEUNIT_PCMBYTES)
		*length = (unsigned) (sound->length * sound->channels * 2);
	else
		return FMOD_ERR_FORMAT;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::getFormat(FMOD_SOUND_TYPE* type, FMOD_SOUND_FORMAT* format, int* channels, int* bits)
{
	SOUND;
	if (type) *type = FMOD_SOUND_TYPE_UNKNOWN;
	if (format) *format = FMOD_SOUND_FORMAT_PCM16;
	if (channels) *channels = sound->channels;
	if (bits) *bits = 16;
	return FMOD_OK;
}

// Byte ranges of a buffer sound; a range past the end wraps into ptr2. There
// is nothing to lock: the recorder only writes from getRecordPosition, which
// the owner calls on the thread it reads from.
FMOD_RESULT F_API Sound::lock(unsigned int offset, unsigned int length, void** ptr1, void** ptr2, unsigned int* len1, unsigned int* len2)
{
	SOUND;
	if (!ptr1 || !len1 || sound->pcm.empty())
		return FMOD_ERR_INVALID_PARAM;
	const unsigned total = (unsigned) (sound->pcm.size() * sizeof(short));
	if (offset >= total || !length)
		return FMOD_ERR_INVALID_PARAM;
	if (length > total)
		length = total;
	unsigned char* base = (unsigned char*) &sound->pcm[0];
	const unsigned first = length < total - offset ? length : total - offset;
	*ptr1 = base + offset;
	*len1 = first;
	if (ptr2) *ptr2 = first < length ? base : NULL;
	if (len2) *len2 = length - first;
	return FMOD_OK;
}

FMOD_RESULT F_API Sound::unlock(void* ptr1, void* ptr2, unsigned int len1, unsigned int len2) { SOUND; return FMOD_OK; }

FMOD_RESULT F_API Sound::setMode(FMOD_MODE mode) { SOUND; sound->mode = mode; return FMOD_OK; }
FMOD_RESULT F_API Sound::getMode(FMOD_MODE* mode) { SOUND; if (mode) *mode = sound->mode; return FMOD_OK; }
FMOD_RESULT F_API Sound::setLoopCount(int loopcount) { SOUND; sound->loopCount = loopcount; return FMOD_OK; }
FMOD_RESULT F_API Sound::getLoopCount(int* loopcount) { SOUND; if (loopcount) *loopcount = sound->loopCount; return FMOD_OK; }
FMOD_RESULT F_API Sound::setUserData(void* userdata) { SOUND; sound->userData = userdata; return FMOD_OK; }
FMOD_RESULT F_API Sound::getUserData(void** userdata) { SOUND; if (userdata) *userdata = sound->userData; return FMOD_OK; }

// --------------------------------------------------------- ChannelControl ----

FMOD_RESULT F_API ChannelControl::getSystemObject(System** system)
{
	CHANNEL_OR_GROUP;
	if (system) *system = (System*) (slot ? slot->system : group->system);
	return FMOD_OK;
}

static void stopGroup(GroupImpl* group)
{
	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
	{
		if (all[i].state != Slot::PLAYING)
			continue;
		for (GroupImpl* g = all[i].group; g; g = g->parent)
			if (g == group) { endChannel(all[i]); break; }
	}
}

FMOD_RESULT F_API ChannelControl::stop()
{
	CHANNEL_OR_GROUP;
	if (slot) endChannel(*slot); else stopGroup(group);
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setPaused(bool paused)
{
	CHANNEL_OR_GROUP;
	if (slot) slot->paused = paused; else group->paused = paused;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::getPaused(bool* paused)
{
	CHANNEL_OR_GROUP;
	if (paused) *paused = slot ? slot->paused : group->paused;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setVolume(float volume)
{
	CHANNEL_OR_GROUP;
	if (volume < 0) volume = 0;
	if (slot) slot->volume = volume; else group->volume = volume;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::getVolume(float* volume)
{
	CHANNEL_OR_GROUP;
	if (volume) *volume = slot ? slot->volume : group->volume;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setMute(bool mute)
{
	CHANNEL_OR_GROUP;
	if (slot) slot->mute = mute; else group->mute = mute;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::getMute(bool* mute)
{
	CHANNEL_OR_GROUP;
	if (mute) *mute = slot ? slot->mute : group->mute;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setPitch(float pitch)
{
	CHANNEL;
	if (slot->sound) slot->frequency = slot->sound->defaultFrequency * pitch;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setReverbProperties(int instance, float wet) { CHANNEL_OR_GROUP; return FMOD_OK; }

// Only the groups of flags that have a bit set change, as in FMOD: setting a
// rolloff leaves the loop mode alone.
FMOD_RESULT F_API ChannelControl::setMode(FMOD_MODE mode)
{
	CHANNEL;
	const FMOD_MODE loopBits = FMOD_LOOP_OFF | FMOD_LOOP_NORMAL | FMOD_LOOP_BIDI;
	const FMOD_MODE dimensionBits = FMOD_2D | FMOD_3D;
	const FMOD_MODE relativeBits = FMOD_3D_HEADRELATIVE | FMOD_3D_WORLDRELATIVE;
	const FMOD_MODE rolloffBits = FMOD_3D_INVERSEROLLOFF | FMOD_3D_LINEARROLLOFF | FMOD_3D_LINEARSQUAREROLLOFF |
		FMOD_3D_INVERSETAPEREDROLLOFF | FMOD_3D_CUSTOMROLLOFF;
	const FMOD_MODE groupsOf[] = { loopBits, dimensionBits, relativeBits, rolloffBits };
	for (size_t i = 0; i < sizeof(groupsOf) / sizeof(groupsOf[0]); ++i)
		if (mode & groupsOf[i])
			slot->mode = (slot->mode & ~groupsOf[i]) | (mode & groupsOf[i]);
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::getMode(FMOD_MODE* mode)
{
	CHANNEL;
	if (mode) *mode = slot->mode;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setCallback(FMOD_CHANNELCONTROL_CALLBACK callback)
{
	CHANNEL_OR_GROUP;
	if (slot) slot->callback = callback;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::isPlaying(bool* isplaying)
{
	CHANNEL_OR_GROUP;
	if (isplaying)
	{
		if (slot)
			*isplaying = slot->state == Slot::PLAYING;
		else
		{
			*isplaying = false;
			std::vector<Slot>& all = slots();
			for (unsigned i = 0; i < MaxSlots && !*isplaying; ++i)
				if (all[i].state == Slot::PLAYING && all[i].group == group)
					*isplaying = true;
		}
	}
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::set3DAttributes(const FMOD_VECTOR* pos, const FMOD_VECTOR* vel, const FMOD_VECTOR* alt_pan_pos)
{
	CHANNEL;
	if (pos) slot->position = *pos;
	if (vel) slot->velocity = *vel;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::get3DAttributes(FMOD_VECTOR* pos, FMOD_VECTOR* vel, FMOD_VECTOR* alt_pan_pos)
{
	CHANNEL;
	if (pos) { pos->x = slot->position.x; pos->y = slot->position.y; pos->z = slot->position.z; }
	if (vel) { vel->x = slot->velocity.x; vel->y = slot->velocity.y; vel->z = slot->velocity.z; }
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::set3DMinMaxDistance(float mindistance, float maxdistance)
{
	CHANNEL;
	if (mindistance < 0 || maxdistance < mindistance) return FMOD_ERR_INVALID_PARAM;
	slot->minDistance = mindistance;
	slot->maxDistance = maxdistance;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::get3DMinMaxDistance(float* mindistance, float* maxdistance)
{
	CHANNEL;
	if (mindistance) *mindistance = slot->minDistance;
	if (maxdistance) *maxdistance = slot->maxDistance;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::setUserData(void* userdata)
{
	CHANNEL_OR_GROUP;
	if (slot) slot->userData = userdata; else group->userData = userdata;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelControl::getUserData(void** userdata)
{
	CHANNEL_OR_GROUP;
	if (userdata) *userdata = slot ? slot->userData : group->userData;
	return FMOD_OK;
}

// ---------------------------------------------------------------- Channel ----

FMOD_RESULT F_API Channel::setFrequency(float frequency)
{
	CHANNEL;
	if (frequency < 0) frequency = 0;
	slot->frequency = frequency;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::getFrequency(float* frequency)
{
	CHANNEL;
	if (frequency) *frequency = slot->frequency;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::setPriority(int priority) { CHANNEL; slot->priority = priority; return FMOD_OK; }
FMOD_RESULT F_API Channel::getPriority(int* priority) { CHANNEL; if (priority) *priority = slot->priority; return FMOD_OK; }

FMOD_RESULT F_API Channel::setPosition(unsigned int position, FMOD_TIMEUNIT postype)
{
	CHANNEL;
	SoundImpl* sound = slot->sound;
	if (!sound) return FMOD_OK;
	unsigned long long frame;
	if (postype == FMOD_TIMEUNIT_MS) frame = (unsigned long long) msToFrames(position, sound->sampleRate);
	else if (postype == FMOD_TIMEUNIT_PCM) frame = position;
	else if (postype == FMOD_TIMEUNIT_PCMBYTES) frame = position / (sound->channels * 2);
	else return FMOD_ERR_FORMAT;

	if (sound->pcmRead)
		return FMOD_OK; // the owner seeks its own stream (VideoFrame)
	if (sound->length && frame >= sound->length)
		frame = sound->length - 1;
	seekSource(*slot, frame);
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::getPosition(unsigned int* position, FMOD_TIMEUNIT postype)
{
	CHANNEL;
	if (!position) return FMOD_ERR_INVALID_PARAM;
	SoundImpl* sound = slot->sound;
	if (!sound) { *position = 0; return FMOD_OK; }

	unsigned long long frame = slot->playPos;
	if (!sound->pcmRead && sound->length > 0 && sound->length < 0xFFFFFFFFu)
		frame %= sound->length;

	if (postype == FMOD_TIMEUNIT_MS) *position = (unsigned) (frame * 1000 / (sound->sampleRate ? sound->sampleRate : 1));
	else if (postype == FMOD_TIMEUNIT_PCM) *position = (unsigned) frame;
	else if (postype == FMOD_TIMEUNIT_PCMBYTES) *position = (unsigned) (frame * sound->channels * 2);
	else return FMOD_ERR_FORMAT;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::setChannelGroup(ChannelGroup* channelgroup)
{
	CHANNEL;
	GroupImpl* group = groupFrom(channelgroup);
	slot->group = group ? group : slot->system->master;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::getChannelGroup(ChannelGroup** channelgroup)
{
	CHANNEL;
	if (channelgroup) *channelgroup = (ChannelGroup*) slot->group;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::setLoopCount(int loopcount) { CHANNEL; slot->loopsLeft = loopcount; return FMOD_OK; }
FMOD_RESULT F_API Channel::getLoopCount(int* loopcount) { CHANNEL; if (loopcount) *loopcount = slot->loopsLeft; return FMOD_OK; }
FMOD_RESULT F_API Channel::isVirtual(bool* isvirtual) { CHANNEL; if (isvirtual) *isvirtual = false; return FMOD_OK; }

FMOD_RESULT F_API Channel::getCurrentSound(Sound** sound)
{
	CHANNEL;
	if (sound) *sound = (Sound*) slot->sound;
	return FMOD_OK;
}

FMOD_RESULT F_API Channel::getIndex(int* index)
{
	CHANNEL;
	if (index) *index = (int) (slot - &slots()[0]);
	return FMOD_OK;
}

// ----------------------------------------------------------- ChannelGroup ----

FMOD_RESULT F_API ChannelGroup::release()
{
	LOCKED;
	GroupImpl* group = groupFrom(this);
	if (!group) return FMOD_ERR_INVALID_HANDLE;
	if (group == group->system->master) return FMOD_ERR_INVALID_PARAM;

	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].state != Slot::FREE && all[i].group == group)
			all[i].group = group->parent;
	for (std::set<GroupImpl*>::iterator it = groups().begin(); it != groups().end(); ++it)
		if ((*it)->parent == group)
			(*it)->parent = group->parent;

	groups().erase(group);
	delete group;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelGroup::addGroup(ChannelGroup* child, bool propagatedspclock, DSPConnection** connection)
{
	LOCKED;
	GroupImpl* group = groupFrom(this);
	GroupImpl* childGroup = groupFrom(child);
	if (!group || !childGroup) return FMOD_ERR_INVALID_HANDLE;
	for (GroupImpl* g = group; g; g = g->parent)
		if (g == childGroup) return FMOD_ERR_INVALID_PARAM;
	childGroup->parent = group;
	if (connection) *connection = NULL;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelGroup::getParentGroup(ChannelGroup** parent)
{
	LOCKED;
	GroupImpl* group = groupFrom(this);
	if (!group) return FMOD_ERR_INVALID_HANDLE;
	if (parent) *parent = (ChannelGroup*) group->parent;
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelGroup::getName(char* name, int namelen)
{
	LOCKED;
	GroupImpl* group = groupFrom(this);
	if (!group) return FMOD_ERR_INVALID_HANDLE;
	if (name && namelen > 0)
	{
		strncpy(name, group->name.c_str(), namelen);
		name[namelen - 1] = 0;
	}
	return FMOD_OK;
}

FMOD_RESULT F_API ChannelGroup::getNumChannels(int* numchannels)
{
	LOCKED;
	GroupImpl* group = groupFrom(this);
	if (!group) return FMOD_ERR_INVALID_HANDLE;
	int count = 0;
	std::vector<Slot>& all = slots();
	for (unsigned i = 0; i < MaxSlots; ++i)
		if (all[i].state == Slot::PLAYING && all[i].group == group)
			count++;
	if (numchannels) *numchannels = count;
	return FMOD_OK;
}

}
