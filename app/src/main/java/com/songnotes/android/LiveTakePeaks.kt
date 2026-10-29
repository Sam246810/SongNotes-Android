package com.songnotes.android

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Peaks of a take while it's being recorded, read from the take file the
 * engine's writer thread is filling (raw native-order float32, mono, the
 * take's own frame 0 first). Each call picks up whatever audio has landed
 * since the last one and returns one peak (max |sample|) per
 * [framesPerPeak] frames; a part-filled bucket waits for the next call.
 *
 * This is the real recorded audio, so the live picture is a waveform, not
 * the smoothed input-level meter it replaced. Call from a background thread.
 */
class LiveTakePeaks(private val file: File, val framesPerPeak: Int) {
    private var bytesRead = 0L
    private var bucketPeak = 0f
    private var bucketFrames = 0
    private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.nativeOrder())

    fun readNew(): List<Float> {
        val out = ArrayList<Float>()
        val length = file.length()
        // Only whole samples; the writer may be mid-way through one.
        val available = (length - bytesRead) / 4 * 4
        if (available <= 0L) return out
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(bytesRead)
            var remaining = available
            while (remaining > 0L) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val n = raf.channel.read(buffer)
                if (n <= 0) break
                buffer.flip()
                val floats = buffer.asFloatBuffer()
                while (floats.hasRemaining()) {
                    val s = abs(floats.get())
                    if (s > bucketPeak) bucketPeak = s
                    if (++bucketFrames == framesPerPeak) {
                        out.add(bucketPeak.coerceAtMost(1f))
                        bucketPeak = 0f
                        bucketFrames = 0
                    }
                }
                val whole = n / 4 * 4
                bytesRead += whole
                remaining -= whole
            }
        }
        return out
    }
}
