package com.songnotes.core.audio

/**
 * Kotlin facade over the pure-math calibration DSP in `core/audio/src/main/cpp/dsp/`
 * (`sweep.{h,cpp}`, `matched_filter.{h,cpp}`, `calibration_stats.{h,cpp}`).
 *
 * Deliberately stateless and independent of [AudioEngine] — no handle to
 * create or release, every function is a pure in/out call. This is the
 * "host-testable C++ lib first, then JNI-wrapped" slice from the plan; see
 * `core/audio/src/main/cpp/host/test_calibration_roundtrip.cpp` for the
 * proof that the underlying math recovers a known delay from a synthesized
 * recording. Running real sweep repetitions through the duplex engine's
 * record/playback path and feeding the results here is still ahead — see
 * docs/handoff/PHASE-03.md.
 *
 * Every `external fun` here is implemented in
 * `core/audio/src/main/cpp/calibration_jni.cpp`.
 */
object Calibration {

    data class SweepData(val sweep: FloatArray, val inverseFilter: FloatArray)

    /**
     * Generates an exponential sine sweep (ESS) from [f1Hz] to [f2Hz] over
     * [lengthSeconds], plus its Farina inverse filter. Play [SweepData.sweep]
     * out through the duplex engine, record the acoustic loopback, then pass
     * both the recording and [SweepData.inverseFilter] to
     * [measureRoundTripDelay].
     */
    fun generateSweepAndInverse(
        sampleRate: Double,
        f1Hz: Double,
        f2Hz: Double,
        lengthSeconds: Double,
        amplitude: Float,
    ): SweepData {
        val packed = nativeGenerateSweepAndInverse(sampleRate, f1Hz, f2Hz, lengthSeconds, amplitude)
        val half = packed.size / 2
        return SweepData(
            sweep = packed.copyOfRange(0, half),
            inverseFilter = packed.copyOfRange(half, packed.size),
        )
    }

    data class DelayMeasurement(
        /** Recovered round-trip delay in frames. Fractional — sub-sample parabolic interpolation. */
        val frames: Double,
        /** Peak-to-noise ratio in dB. Below ~20dB means don't trust [frames] for this repetition. */
        val pnrDb: Double,
    )

    /**
     * Deconvolves [recording] against [inverseFilter] and locates the peak,
     * recovering the round-trip latency of whatever acoustic/electrical path
     * produced [recording]. [sweepLength] must be the length (in frames) of
     * the sweep that was played — i.e. `SweepData.sweep.size`.
     */
    fun measureRoundTripDelay(
        recording: FloatArray,
        inverseFilter: FloatArray,
        sweepLength: Int,
    ): DelayMeasurement {
        val result = nativeMeasureRoundTripDelay(recording, inverseFilter, sweepLength)
        return DelayMeasurement(frames = result[0], pnrDb = result[1])
    }

    /**
     * Median Absolute Deviation outlier rejection across repeated delay
     * measurements — drops any single repetition a transient noise burst
     * corrupted. Returns [values] unchanged if there are fewer than 3 or all
     * are identical.
     */
    fun rejectOutliersMad(values: DoubleArray, thresholdMads: Double = 3.0): DoubleArray =
        nativeRejectOutliersMad(values, thresholdMads)

    /** `20*log10(peakMagnitude / noiseFloor)`. */
    fun peakToNoiseRatioDb(peakMagnitude: Float, noiseFloor: Float): Double =
        nativePeakToNoiseRatioDb(peakMagnitude, noiseFloor)

    private external fun nativeGenerateSweepAndInverse(
        sampleRate: Double,
        f1Hz: Double,
        f2Hz: Double,
        lengthSeconds: Double,
        amplitude: Float,
    ): FloatArray

    private external fun nativeMeasureRoundTripDelay(
        recording: FloatArray,
        inverseFilter: FloatArray,
        sweepLength: Int,
    ): DoubleArray

    private external fun nativeRejectOutliersMad(values: DoubleArray, thresholdMads: Double): DoubleArray

    private external fun nativePeakToNoiseRatioDb(peakMagnitude: Float, noiseFloor: Float): Double

    init {
        System.loadLibrary("songnotes_audio")
    }
}
