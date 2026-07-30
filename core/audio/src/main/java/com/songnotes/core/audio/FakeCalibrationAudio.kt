package com.songnotes.core.audio

/**
 * Test double for [CalibrationAudio] — Rule I: "a fake throws on any
 * unexpected call." Since the interface exposes only two methods (Rule I's
 * whole point), "unexpected" here means any call beyond what the test
 * queued up via [expectSweepResult] — a wizard bug that calls [runSweeps]
 * more times than the test scripted, for instance, fails loudly via
 * exception rather than silently returning some placeholder result.
 *
 * No JVM test source set exists in this module yet to actually exercise
 * this from a `@Test` — see docs/handoff/PHASE-03.md. Written now so it's
 * ready the moment one does, rather than retrofitted later.
 */
class FakeCalibrationAudio : CalibrationAudio {

    val playedBuffers: List<FloatArray> get() = _playedBuffers
    private val _playedBuffers = mutableListOf<FloatArray>()

    private val queuedSweepResults = ArrayDeque<CalibrationSession.Result>()

    fun expectSweepResult(result: CalibrationSession.Result) {
        queuedSweepResults.addLast(result)
    }

    override suspend fun runSweeps(repetitionCount: Int): CalibrationSession.Result {
        if (queuedSweepResults.isEmpty()) {
            throw IllegalStateException(
                "FakeCalibrationAudio.runSweeps($repetitionCount) called with no expectSweepResult() queued",
            )
        }
        return queuedSweepResults.removeFirst()
    }

    override suspend fun playPreMixed(buffer: FloatArray) {
        _playedBuffers.add(buffer)
    }
}
