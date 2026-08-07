package com.songnotes.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.songnotes.core.domain.PIANO_SAMPLES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One decoded piano sample: mono 32-bit float PCM, plus the sample rate it was decoded at. */
data class DecodedPianoSample(val midi: Int, val buffer: FloatArray, val sampleRateHz: Int)

/**
 * Decodes the bundled Salamander piano mp3s (`assets/piano/{midi}.mp3` —
 * see that directory's `NOTICE.md` for licensing/attribution) into mono
 * 32-bit float PCM using Android's own `MediaExtractor`/`MediaCodec`.
 * Deliberately does NOT add an NDK-side mp3 decoder: native decode is a
 * solved problem on Android (every device ships a hardware/software mp3
 * decoder), and the C++ engine already accepts plain `FloatArray` clip
 * buffers over JNI (see `dsp::Clip`) — an on-device decoder would give the
 * piano path nothing that isn't already true of every other buffer the
 * engine plays.
 *
 * Runs on [Dispatchers.Default] — this is the "loader thread (asset
 * decode)" `docs/PLAN.md`'s own threading model names but nothing had
 * used until now (the metronome click is synthesized, not sampled; the
 * multitrack engine's clips all come from the user's own recordings, not
 * bundled assets).
 */
object PianoSampleLoader {

    suspend fun loadAll(context: Context): List<DecodedPianoSample> = withContext(Dispatchers.Default) {
        PIANO_SAMPLES.map { sample -> decodeAsset(context, sample.midi) }
    }

    private fun decodeAsset(context: Context, midi: Int): DecodedPianoSample {
        val extractor = MediaExtractor()
        val chunks = mutableListOf<FloatArray>()
        var sourceSampleRate = 0
        try {
            context.assets.openFd("piano/$midi.mp3").use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }

            val trackIndex = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) { "no MIME on piano/$midi.mp3's track" }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                drainDecoder(codec, extractor, sourceChannels, chunks)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }

        val buffer = FloatArray(chunks.sumOf { it.size })
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(buffer, pos)
            pos += chunk.size
        }
        return DecodedPianoSample(midi = midi, buffer = buffer, sampleRateHz = sourceSampleRate)
    }

    private fun drainDecoder(
        codec: MediaCodec,
        extractor: MediaExtractor,
        sourceChannels: Int,
        outChunks: MutableList<FloatArray>,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                if (bufferInfo.size > 0) {
                    outChunks.add(pcm16ToMonoFloat(outputBuffer, bufferInfo, sourceChannels))
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
    }

    /** Reads 16-bit signed PCM (the format every Android audio decoder supports) from [outputBuffer], downmixing [sourceChannels] to mono f32 in [-1, 1]. */
    private fun pcm16ToMonoFloat(outputBuffer: ByteBuffer, info: MediaCodec.BufferInfo, sourceChannels: Int): FloatArray {
        outputBuffer.position(info.offset)
        outputBuffer.limit(info.offset + info.size)
        val shorts = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / sourceChannels
        val out = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until sourceChannels) sum += shorts.get(frame * sourceChannels + ch)
            out[frame] = (sum.toFloat() / sourceChannels) / 32768.0f
        }
        return out
    }
}
