#include "Decoder.h"

#include <string.h>

#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "decoders/dr_mp3.h"

#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "decoders/dr_wav.h"

#define STB_VORBIS_NO_STDIO
#define STB_VORBIS_NO_PUSHDATA_API
#include "decoders/stb_vorbis.c"

namespace FmodSdl
{
	namespace
	{
		class Mp3Decoder : public Decoder
		{
			drmp3 mp3;
		public:
			bool init(const std::vector<unsigned char>& data)
			{
				if (!drmp3_init_memory(&mp3, &data[0], data.size(), NULL))
					return false;
				channels = mp3.channels;
				sampleRate = mp3.sampleRate;
				length = drmp3_get_pcm_frame_count(&mp3);
				drmp3_seek_to_pcm_frame(&mp3, 0);
				return channels > 0 && sampleRate > 0;
			}
			~Mp3Decoder() { drmp3_uninit(&mp3); }
			unsigned read(float* out, unsigned frames) { return (unsigned) drmp3_read_pcm_frames_f32(&mp3, frames, out); }
			bool seek(unsigned long long frame) { return drmp3_seek_to_pcm_frame(&mp3, frame) != 0; }
		};

		class WavDecoder : public Decoder
		{
			drwav wav;
		public:
			bool init(const std::vector<unsigned char>& data)
			{
				if (!drwav_init_memory(&wav, &data[0], data.size(), NULL))
					return false;
				channels = wav.channels;
				sampleRate = wav.sampleRate;
				length = wav.totalPCMFrameCount;
				return channels > 0 && sampleRate > 0;
			}
			~WavDecoder() { drwav_uninit(&wav); }
			unsigned read(float* out, unsigned frames) { return (unsigned) drwav_read_pcm_frames_f32(&wav, frames, out); }
			bool seek(unsigned long long frame) { return drwav_seek_to_pcm_frame(&wav, frame) != 0; }
		};

		class VorbisDecoder : public Decoder
		{
			stb_vorbis* vorbis;
		public:
			VorbisDecoder() : vorbis(NULL) {}
			bool init(const std::vector<unsigned char>& data)
			{
				int error = 0;
				vorbis = stb_vorbis_open_memory(&data[0], (int) data.size(), &error, NULL);
				if (!vorbis)
					return false;
				stb_vorbis_info info = stb_vorbis_get_info(vorbis);
				channels = info.channels;
				sampleRate = info.sample_rate;
				length = stb_vorbis_stream_length_in_samples(vorbis);
				return channels > 0 && sampleRate > 0;
			}
			~VorbisDecoder() { if (vorbis) stb_vorbis_close(vorbis); }
			unsigned read(float* out, unsigned frames)
			{
				unsigned done = 0;
				while (done < frames)
				{
					int got = stb_vorbis_get_samples_float_interleaved(vorbis, channels, out + done * channels, (frames - done) * channels);
					if (got <= 0)
						break;
					done += got;
				}
				return done;
			}
			bool seek(unsigned long long frame) { return stb_vorbis_seek(vorbis, (unsigned) frame) != 0; }
		};

		template <class T>
		Decoder* tryOpen(const std::vector<unsigned char>& data)
		{
			T* decoder = new T();
			if (decoder->init(data))
				return decoder;
			delete decoder;
			return NULL;
		}
	}

	Decoder* Decoder::open(const std::vector<unsigned char>& data)
	{
		if (data.size() < 12)
			return NULL;

		const unsigned char* p = &data[0];
		if (!memcmp(p, "OggS", 4))
			return tryOpen<VorbisDecoder>(data);
		if ((!memcmp(p, "RIFF", 4) || !memcmp(p, "RIFX", 4) || !memcmp(p, "RF64", 4)) && !memcmp(p + 8, "WAVE", 4))
			return tryOpen<WavDecoder>(data);

		// MP3 has no fixed signature (an ID3 tag, or straight into frame sync),
		// and arbitrary junk may precede the first frame; dr_mp3 skips it.
		return tryOpen<Mp3Decoder>(data);
	}
}
