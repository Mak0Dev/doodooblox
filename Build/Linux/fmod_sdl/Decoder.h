// Compressed audio for the FMOD replacement: MP3, Ogg Vorbis and WAV - what
// Roblox accepts for sound uploads - decoded from memory to float frames.
#pragma once

#include <vector>

namespace FmodSdl
{
	class Decoder
	{
	public:
		virtual ~Decoder() {}

		int channels;               // as in the file
		int sampleRate;
		unsigned long long length;  // in frames, 0 when unknown

		// Interleaved float, 'channels' per frame. Returns the frames written;
		// fewer than asked means the end of the data.
		virtual unsigned read(float* out, unsigned frames) = 0;
		virtual bool seek(unsigned long long frame) = 0;

		// Picks the decoder by the data's signature. 'data' must outlive the
		// decoder. NULL when the format is not one of the three.
		static Decoder* open(const std::vector<unsigned char>& data);

	protected:
		Decoder() : channels(0), sampleRate(0), length(0) {}
	};
}
