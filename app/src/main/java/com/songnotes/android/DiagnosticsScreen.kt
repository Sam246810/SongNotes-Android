package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.AudioRouteDetector
import com.songnotes.core.audio.Calibration
import com.songnotes.core.audio.CalibrationAudio
import com.songnotes.core.audio.CalibrationAudioEffects
import com.songnotes.core.audio.CalibrationSession
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.EngineCapabilities
import com.songnotes.core.audio.EngineState
import com.songnotes.core.audio.MultitrackProject
import com.songnotes.core.audio.RealCalibrationAudio
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 0's "hello Oboe" tone test, plus Phase 1's real duplex record/
 * playback round trip. Still one screen, still no navigation — there's
 * nothing yet to navigate to.
 */
@Composable
fun DiagnosticsScreen(engine: AudioEngine) {
    var isTonePlaying by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(EngineCapabilities.unavailable()) }

    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // The engine always opens a duplex pair (output-master pattern), even
    // for tone-only mode, so starting the tone needs RECORD_AUDIO too —
    // without it, input requestStart() fails and startTestTone() no-ops.
    val tonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            isTonePlaying = engine.startTestTone()
            caps = engine.capabilities()
        }
    }

    LaunchedEffect(isTonePlaying) {
        while (isTonePlaying) {
            caps = engine.capabilities()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("SongNotes — Audio Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Opens an output stream, plays a 440 Hz test tone, reports what it actually got.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            if (isTonePlaying) {
                engine.stopTestTone()
                isTonePlaying = false
                caps = engine.capabilities()
            } else if (!hasRecordPermission) {
                tonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                isTonePlaying = engine.startTestTone()
                caps = engine.capabilities()
            }
        }) {
            Text(if (isTonePlaying) "Stop test tone" else "Play test tone")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        CapabilityRow("Audio API", caps.audioApi)
        CapabilityRow("Sample rate", "${caps.sampleRate} Hz")
        CapabilityRow("Frames per burst", "${caps.framesPerBurst}")
        CapabilityRow("Channel count", "${caps.channelCount}")
        CapabilityRow("Format", caps.format)
        CapabilityRow("Sharing mode", caps.sharingMode)
        CapabilityRow("Performance mode", caps.performanceMode)
        CapabilityRow("MMap (fast path)", if (caps.isMMapUsed) "yes" else "no")
        CapabilityRow("XRun count", "${caps.xRunCount}")

        caps.lastError?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text("Last error: $error", color = MaterialTheme.colorScheme.error)
        }

        RecordPlaybackSection(engine)
        CalibrationDspSmokeTestSection()
        OnsetDetectionSmokeTestSection()
        EngineCalibrationCaptureSection(engine)
        CalibrationSessionSection(engine)
        VerificationPlaybackSmokeTestSection(engine)
        MultitrackPlaybackSmokeTestSection(engine)
        PunchInSmokeTestSection(engine)
        OverdubPunchInEndToEndSection(engine)
        WavExportSmokeTestSection(engine)
        JvmReferenceMixerCrossValidationSection(engine)
    }
}

/**
 * Phase 4's actual Done criterion: "exported WAV is sample-identical to a
 * JVM reference mixer given the same clip list." [engine.mixTracksNative]
 * calls the real `dsp::mixTracks` (C++); `com.songnotes.core.domain.mixTracks`
 * is a genuinely independent implementation in the new `:core:domain`
 * module — written from the algorithm description, not translated from
 * the C++, and already exhaustively tested on its own via
 * `:core:domain`'s JVM unit tests (12/12 passing, no device needed — the
 * first code in this project verifiable without a phone or NDK
 * cross-compile). This section is what only an on-device run can prove:
 * that the two independent implementations agree, given the exact same
 * numbers, on THIS device's floating-point arithmetic.
 *
 * Uses a hand-computed multi-track scenario (overlapping clips, per-track
 * gain, a clip with a gap on one track) rather than mute/solo — those
 * booleans are simple conditionals already covered identically by both
 * implementations' own test suites; what floating-point summation order
 * actually risks disagreeing on is the arithmetic itself, which this
 * scenario exercises directly.
 */
@Composable
private fun JvmReferenceMixerCrossValidationSection(engine: AudioEngine) {
    var resultText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runTest(): String {
        // track1: clips [1,1,1]@0 and [3,3]@5 (a gap from frame 3-5), gain 0.5.
        // track2: clip [2,2,2,2,2]@1, gain 1.0.
        // track3: clip [0.25,0.25,0.25]@2, gain 2.0.
        // Hand-computed expected mix over frames [0,7):
        val expected = floatArrayOf(0.5f, 2.5f, 3.0f, 2.5f, 2.5f, 3.5f, 1.5f)

        val nativeTracks = listOf(
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(
                    com.songnotes.core.audio.MultitrackClipSpec(buffer = floatArrayOf(1f, 1f, 1f), startFrame = 0L),
                    com.songnotes.core.audio.MultitrackClipSpec(buffer = floatArrayOf(3f, 3f), startFrame = 5L),
                ),
                gain = 0.5f,
            ),
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(
                    com.songnotes.core.audio.MultitrackClipSpec(
                        buffer = floatArrayOf(2f, 2f, 2f, 2f, 2f), startFrame = 1L,
                    ),
                ),
            ),
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(
                    com.songnotes.core.audio.MultitrackClipSpec(
                        buffer = floatArrayOf(0.25f, 0.25f, 0.25f), startFrame = 2L,
                    ),
                ),
                gain = 2.0f,
            ),
        )
        val domainTracks = listOf(
            com.songnotes.core.domain.Track(
                clips = listOf(
                    com.songnotes.core.domain.Clip(buffer = floatArrayOf(1f, 1f, 1f), startFrame = 0L),
                    com.songnotes.core.domain.Clip(buffer = floatArrayOf(3f, 3f), startFrame = 5L),
                ),
                gain = 0.5f,
            ),
            com.songnotes.core.domain.Track(
                clips = listOf(
                    com.songnotes.core.domain.Clip(buffer = floatArrayOf(2f, 2f, 2f, 2f, 2f), startFrame = 1L),
                ),
            ),
            com.songnotes.core.domain.Track(
                clips = listOf(
                    com.songnotes.core.domain.Clip(buffer = floatArrayOf(0.25f, 0.25f, 0.25f), startFrame = 2L),
                ),
                gain = 2.0f,
            ),
        )

        val nativeMixed = engine.mixTracksNative(nativeTracks)
        val jvmMixed = com.songnotes.core.domain.mixTracks(domainTracks, 0L, 7L)

        val nativeMatchesExpected = nativeMixed.size == expected.size &&
            nativeMixed.indices.all { abs(nativeMixed[it] - expected[it]) < 0.0001f }
        val jvmMatchesExpected = jvmMixed.size == expected.size &&
            jvmMixed.indices.all { abs(jvmMixed[it] - expected[it]) < 0.0001f }
        // The actual Done criterion: native vs JVM, bit-for-bit (both
        // implementations deliberately use the same iteration order — see
        // ClipMixer.kt's doc comment — so this checks EXACT equality, not
        // an epsilon tolerance).
        val nativeMatchesJvm = nativeMixed.size == jvmMixed.size &&
            nativeMixed.indices.all { nativeMixed[it] == jvmMixed[it] }

        val pass = nativeMatchesExpected && jvmMatchesExpected && nativeMatchesJvm
        return buildString {
            appendLine(if (pass) "PASS" else "FAIL — see values below")
            appendLine("Native (C++) mix:  ${nativeMixed.toList()}")
            appendLine("JVM (Kotlin) mix:  ${jvmMixed.toList()}")
            appendLine("Hand-computed:     ${expected.toList()}")
            appendLine("Native matches hand-computed expectation: $nativeMatchesExpected")
            appendLine("JVM matches hand-computed expectation: $jvmMatchesExpected")
            append("Native and JVM match EXACTLY (bit-for-bit): $nativeMatchesJvm")
        }
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("JVM reference mixer cross-validation (Phase 4 Done criterion)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Mixes the same 3-track, overlapping-clips-with-gain scenario through both the real C++ " +
            "mixer (AudioEngine.mixTracksNative) and the independent JVM reference mixer " +
            "(:core:domain's com.songnotes.core.domain.mixTracks), and checks they agree exactly.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            isRunning = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.Default) { runTest() }
                resultText = result
                isRunning = false
            }
        },
    ) {
        Text(if (isRunning) "Running..." else "Run cross-validation")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Not a product feature — a one-tap check that [AudioEngine.exportMixdownToWav]
 * actually links, mixes, encodes, and writes a real file on device. Two
 * short synthetic tracks with a known, hand-computed expected mix (an
 * overlap case, same shape as the host GoogleTest suite's own
 * `OverlappingClipsWithinATrackSum`-style cases, just across two tracks
 * instead of within one) — export, then read the file back from disk and
 * check BOTH the WAV header fields and the actual sample values, not just
 * "a file exists."
 */
@Composable
private fun WavExportSmokeTestSection(engine: AudioEngine) {
    val context = LocalContext.current
    var resultText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runTest(): String {
        val sampleRate = 48000
        val trackA = com.songnotes.core.audio.MultitrackTrackSpec(
            clips = listOf(
                com.songnotes.core.audio.MultitrackClipSpec(
                    buffer = floatArrayOf(1.0f, 1.0f, 1.0f), startFrame = 0L,
                ),
            ),
        )
        val trackB = com.songnotes.core.audio.MultitrackTrackSpec(
            clips = listOf(
                com.songnotes.core.audio.MultitrackClipSpec(
                    buffer = floatArrayOf(0.5f, 0.5f), startFrame = 1L,
                ),
            ),
        )
        // frame 0: A only = 1.0. frames 1-2: A + B = 1.5.
        val expectedMix = floatArrayOf(1.0f, 1.5f, 1.5f)

        val outFile = File(context.filesDir, "exports/phase4_wav_export_test.wav")
        outFile.parentFile?.mkdirs()
        outFile.delete()

        val exportOk = engine.exportMixdownToWav(outFile.absolutePath, listOf(trackA, trackB), sampleRate)
        if (!exportOk) return "FAIL — exportMixdownToWav returned false. See Last error above."
        if (!outFile.exists()) return "FAIL — exportMixdownToWav returned true but no file was written."

        val bytes = outFile.readBytes()
        val expectedDataSize = expectedMix.size * 4
        val headerOk = bytes.size >= 44 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "fmt " &&
            ByteBuffer.wrap(bytes, 20, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 3 && // IEEE float
            ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 1 && // mono
            ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int == sampleRate &&
            ByteBuffer.wrap(bytes, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 32 && // bits/sample
            String(bytes, 36, 4, Charsets.US_ASCII) == "data" &&
            ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int == expectedDataSize

        val samplesOk = headerOk && bytes.size == 44 + expectedDataSize
        val actualSamples = if (samplesOk) {
            FloatArray(expectedMix.size).also {
                ByteBuffer.wrap(bytes, 44, expectedDataSize).order(ByteOrder.nativeOrder()).asFloatBuffer().get(it)
            }
        } else {
            FloatArray(0)
        }
        val valuesOk = samplesOk && actualSamples.indices.all { abs(actualSamples[it] - expectedMix[it]) < 0.0001f }

        val pass = headerOk && samplesOk && valuesOk
        return buildString {
            appendLine(if (pass) "PASS" else "FAIL — see values below")
            appendLine("File: ${outFile.absolutePath} (${bytes.size} bytes)")
            appendLine("Header fields correct: $headerOk")
            appendLine("Data size matches expected ($expectedDataSize bytes): $samplesOk")
            appendLine(
                "Sample values: ${actualSamples.toList()} (expected ${expectedMix.toList()}, ok=$valuesOk)",
            )
        }
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("WAV export smoke test (Phase 4)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Mixes 2 overlapping synthetic tracks via AudioEngine.exportMixdownToWav, writes a real " +
            "32-bit float WAV file to app storage, then reads it back and checks both the header " +
            "fields and the actual mixed sample values against a hand-computed expectation.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            isRunning = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.Default) { runTest() }
                resultText = result
                isRunning = false
            }
        },
    ) {
        Text(if (isRunning) "Running..." else "Run WAV export smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The full Phase 4 punch-in loop, end to end — now driven through
 * [MultitrackProject] instead of hand-built/concatenated track lists, the
 * way a real punch-in UI eventually would: arm a real recording WITH
 * backing tracks audible ([MultitrackProject.armOverdub]), record ~2s of
 * actual mic input, read the resulting take back, splice it onto the
 * project's empty third track via [MultitrackProject.withPunchIn], then
 * play the whole project back via [MultitrackProject.play]. Splicing
 * correctness itself is already proven by [PunchInSmokeTestSection] (exact
 * clip shape + sample values); this section instead proves the pieces
 * connect — a real recorded take can actually be punched into a project
 * and played back — which unit-testing punchIn() alone can't show.
 */
@Composable
private fun OverdubPunchInEndToEndSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val takeFile = remember {
        File(context.filesDir, "takes/phase4_overdub_test.f32").also { it.parentFile?.mkdirs() }
    }

    fun sine(freqHz: Double, lengthSeconds: Double, sampleRate: Double, amplitude: Float): FloatArray {
        val frameCount = (sampleRate * lengthSeconds).toInt()
        return FloatArray(frameCount) { i ->
            (kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate) * amplitude).toFloat()
        }
    }

    fun runTest() {
        isRunning = true
        resultText = null
        val sampleRate = 48000.0
        val bpm = 80.0
        val beatsPerBar = 4
        val countInBeats = 4
        val recordSeconds = 2.0
        val backingTracksStartFrame = 0L
        val overdubTrackIndex = 2

        // Two backing tracks plus an empty target track for the overdub —
        // building this up via MultitrackProject is what every other call
        // site in this file used to do by hand-concatenating lists.
        var project = MultitrackProject()
            .addTrack(
                com.songnotes.core.audio.MultitrackTrackSpec(
                    clips = listOf(
                        com.songnotes.core.audio.MultitrackClipSpec(
                            buffer = sine(440.0, 2.0, sampleRate, 0.3f), startFrame = 0L,
                        ),
                    ),
                ),
            )
            .addTrack(
                com.songnotes.core.audio.MultitrackTrackSpec(
                    clips = listOf(
                        com.songnotes.core.audio.MultitrackClipSpec(
                            buffer = sine(660.0, 1.5, sampleRate, 0.3f), startFrame = 24_000L,
                        ),
                    ),
                ),
            )
            .addTrack() // empty — the overdub's target

        context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        scope.launch {
            statusText = "Recording (count-in, then ~${recordSeconds.toInt()}s) — backing tracks should be audible..."
            val armed = project.armOverdub(
                engine, takeFile.absolutePath, bpm, beatsPerBar, countInBeats,
                targetIndex = overdubTrackIndex, backingTracksStartFrame = backingTracksStartFrame,
            )
            if (!armed) {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
                statusText = null
                resultText = "armOverdub returned false — see Last error above."
                isRunning = false
                return@launch
            }

            val countInSeconds = countInBeats * 60.0 / bpm
            val totalSeconds = countInSeconds + recordSeconds
            val startTimeMs = System.currentTimeMillis()
            while ((System.currentTimeMillis() - startTimeMs) / 1000.0 < totalSeconds) {
                delay(100)
            }
            engine.stopRecording()
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            delay(100) // let the writer thread flush its last buffered frames to disk

            val takeBytes = takeFile.readBytes()
            if (takeBytes.isEmpty()) {
                statusText = null
                resultText = "Recorded take file is empty — recording likely failed; see Last error above."
                isRunning = false
                return@launch
            }
            val takeSamples = FloatArray(takeBytes.size / 4)
            ByteBuffer.wrap(takeBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(takeSamples)

            val newClip = com.songnotes.core.audio.MultitrackClipSpec(
                buffer = takeSamples, startFrame = backingTracksStartFrame,
            )
            project = project.withPunchIn(engine, overdubTrackIndex, newClip)
            val splicedClipCount = project.tracks[overdubTrackIndex].clips.size

            statusText = "Playing back backing tracks + the take just recorded..."
            val playbackOk = project.play(engine)
            var xrunAfterPlayback = engine.capabilities().xRunCount
            if (playbackOk) {
                while (engine.state().isPlaying) delay(50)
                xrunAfterPlayback = engine.capabilities().xRunCount
            }

            statusText = null
            isRunning = false
            val recordedSeconds = takeSamples.size / sampleRate
            resultText = buildString {
                appendLine(
                    "Recorded ${takeSamples.size} frames (%.2fs of real mic audio, target ~%.1fs)".format(
                        recordedSeconds, recordSeconds,
                    ),
                )
                appendLine(
                    "MultitrackProject.withPunchIn onto the empty track produced $splicedClipCount " +
                        "clip(s) (expected 1); project.totalFrames = ${project.totalFrames}",
                )
                appendLine("Combined multitrack playback (project.play, 3 tracks) started: $playbackOk")
                appendLine("xRun count after combined playback: $xrunAfterPlayback")
                append(
                    "Manual check: during recording you should have heard a 440Hz tone from the " +
                        "downbeat and a 660Hz tone join 0.5s later, on top of the count-in click. " +
                        "During the final playback (no click this time) you should hear those same " +
                        "two tones again, plus whatever the mic actually picked up during recording.",
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) runTest() else resultText = "Microphone permission is required to record."
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Overdub + punch-in, end to end (Phase 4)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Records a real ~2s take while 2 backing tracks play audibly, splices the take onto a " +
            "third track, then plays all 3 tracks back together — the full record-while-listening-" +
            "then-splice loop, driven through MultitrackProject rather than hand-built track lists.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            if (!hasRecordPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                runTest()
            }
        },
    ) {
        Text(statusText ?: if (isRunning) "Running..." else "Run overdub + punch-in test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Phase 4, second slice: real-time multitrack playback through
 * [AudioEngine.startMultitrackPlayback], now exercising multiple clips on
 * one track (not just multiple tracks) — track 1 has two separate clips
 * with a silent gap between them, which is what a punched-in track
 * actually looks like. Audibly: 440Hz from the start, a gap, then a
 * 523.25Hz (C5) clip on the SAME track from 1.5s; a 660Hz tone on a second
 * track staggered in at 0.5s; a muted 220Hz track (silent) that's the
 * longest of all, proving mute doesn't affect the engine's total-duration/
 * auto-stop calculation (matches `startMultitrackPlayback`'s totalFrames
 * computation in audio_engine.cpp, which deliberately ignores mute/solo).
 * The automated pass/fail below checks total-frame accounting and clean
 * auto-stop; actually hearing the right tones at the right times is a
 * manual confirmation on top of that.
 */
@Composable
private fun MultitrackPlaybackSmokeTestSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isPlaying by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun sine(freqHz: Double, lengthSeconds: Double, sampleRate: Double, amplitude: Float): FloatArray {
        val frameCount = (sampleRate * lengthSeconds).toInt()
        return FloatArray(frameCount) { i ->
            (kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate) * amplitude).toFloat()
        }
    }

    fun clip(freqHz: Double, lengthSeconds: Double, sampleRate: Double, amplitude: Float, startFrame: Long) =
        com.songnotes.core.audio.MultitrackClipSpec(
            buffer = sine(freqHz, lengthSeconds, sampleRate, amplitude), startFrame = startFrame,
        )

    fun runTest() {
        resultText = null
        val sampleRate = 48000.0
        val tracks = listOf(
            // Two clips on ONE track: 440Hz for 1.0s, a 0.5s silent gap,
            // then 523.25Hz (C5) for 1.0s starting at 1.5s -> ends at 2.5s.
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(
                    clip(440.0, 1.0, sampleRate, 0.3f, startFrame = 0L),
                    clip(523.25, 1.0, sampleRate, 0.3f, startFrame = 72_000L),
                ),
                gain = 0.7f,
            ),
            // 660Hz, staggered in 0.5s, 1.5s long -> ends at frame 96000.
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(clip(660.0, 1.5, sampleRate, 0.3f, startFrame = 24_000L)),
                gain = 0.5f,
            ),
            // 220Hz, muted, but the longest clip (2.5s) -> should be silent
            // yet still drive the engine's total-duration/auto-stop frame count.
            com.songnotes.core.audio.MultitrackTrackSpec(
                clips = listOf(clip(220.0, 2.5, sampleRate, 0.3f, startFrame = 0L)),
                muted = true,
            ),
        )
        val expectedTotalFrames = (sampleRate * 2.5).toInt() // driven by track 1's second clip / the muted track

        if (!engine.startMultitrackPlayback(tracks)) {
            resultText = "startMultitrackPlayback returned false — see Last error above."
            return
        }
        isPlaying = true
        val xRunBefore = engine.capabilities().xRunCount
        scope.launch {
            var observedTotal = 0
            while (engine.state().isPlaying) {
                observedTotal = engine.state().playbackTotalFrames
                delay(50)
            }
            isPlaying = false
            val finalState = engine.state()
            val xRunAfter = engine.capabilities().xRunCount
            val totalOk = observedTotal == expectedTotalFrames
            // The engine doesn't reset the cursor on natural completion (only
            // isPlaying flips false) — see the onAudioReady multitrack branch
            // in audio_engine.cpp — so "stopped cleanly" means the cursor rode
            // all the way to the end, not that it reset to 0.
            val stoppedCleanly = !finalState.isPlaying && finalState.playbackFrame == expectedTotalFrames
            val pass = totalOk && stoppedCleanly
            resultText = buildString {
                appendLine(if (pass) "PASS" else "FAIL — see values below")
                appendLine("Observed total frames: $observedTotal (expected $expectedTotalFrames, ok=$totalOk)")
                appendLine(
                    "Stopped cleanly (auto-idle at cursor=$expectedTotalFrames): $stoppedCleanly " +
                        "(actual cursor: ${finalState.playbackFrame})",
                )
                appendLine("xRun count: $xRunBefore -> $xRunAfter")
                append(
                    "Manual check: you should have heard a 440Hz tone from the start, silence from 1.0-" +
                        "1.5s, then a 523.25Hz (C5) tone on that SAME track from 1.5-2.5s; a 660Hz tone " +
                        "on a second track joining at 0.5s and stopping at 2.0s; no third tone audible " +
                        "(220Hz is muted) even though it's what makes playback run the full 2.5s.",
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) runTest() else resultText = "Microphone permission is required (duplex engine opens both streams)."
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Multitrack playback smoke test (Phase 4, multi-clip)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Plays 3 synthetic tracks (one with 2 clips + a gap, one muted) through " +
            "AudioEngine.startMultitrackPlayback — real-time chunked mixing via dsp::mixTracksInto, " +
            "not an offline mixdown.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isPlaying,
        onClick = {
            if (!hasRecordPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                runTest()
            }
        },
    ) {
        Text(if (isPlaying) "Playing..." else "Run multitrack playback smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Not a product feature — a one-tap check that [AudioEngine.punchIn]
 * (wrapping `dsp::punchIn` over JNI) actually links, marshals, and
 * produces the right clip shape. Mirrors
 * [CalibrationDspSmokeTestSection]'s pattern: pure logic, no audio
 * playback, so it can assert exact values rather than relying on a human
 * ear. Punches a loud clip into the middle of a quiet one and checks the
 * result reads quiet/loud/quiet — the same property the host GoogleTest
 * suite already proved for `dsp::punchIn` directly; this proves the JNI
 * boundary doesn't corrupt it.
 */
@Composable
private fun PunchInSmokeTestSection(engine: AudioEngine) {
    var resultText by remember { mutableStateOf<String?>(null) }

    fun runTest(): String {
        val quietValue = 0.1f
        val loudValue = 0.9f
        val existingClips = listOf(
            com.songnotes.core.audio.MultitrackClipSpec(
                buffer = FloatArray(1000) { quietValue }, startFrame = 0L,
            ),
        )
        val insertClip = com.songnotes.core.audio.MultitrackClipSpec(
            buffer = FloatArray(400) { loudValue }, startFrame = 300L,
        )
        val result = engine.punchIn(existingClips, insertClip)

        val sorted = result.sortedBy { it.startFrame }
        val shapeOk = sorted.size == 3 &&
            sorted[0].startFrame == 0L && sorted[0].lengthFrames == 300L &&
            sorted[1].startFrame == 300L && sorted[1].lengthFrames == 400L &&
            sorted[2].startFrame == 700L && sorted[2].lengthFrames == 300L
        fun sampleAt(clip: com.songnotes.core.audio.MultitrackClipSpec, frameInClip: Int) =
            clip.buffer[(clip.bufferOffsetFrames + frameInClip).toInt()]
        val valuesOk = shapeOk &&
            abs(sampleAt(sorted[0], 0) - quietValue) < 0.001f &&
            abs(sampleAt(sorted[1], 0) - loudValue) < 0.001f &&
            abs(sampleAt(sorted[2], 0) - quietValue) < 0.001f
        val pass = shapeOk && valuesOk

        return buildString {
            appendLine(if (pass) "PASS — JNI punch-in boundary verified" else "FAIL — see values below")
            appendLine("Result clip count: ${sorted.size} (expected 3, shapeOk=$shapeOk)")
            sorted.forEachIndexed { i, c ->
                appendLine(
                    "  clip $i: start=${c.startFrame} bufferOffset=${c.bufferOffsetFrames} " +
                        "length=${c.lengthFrames}",
                )
            }
            append("Sample values read quiet/loud/quiet as expected: $valuesOk")
        }
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Punch-in smoke test (Phase 4 JNI)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Punches a loud 400-frame clip into the middle of a quiet 1000-frame clip via " +
            "AudioEngine.punchIn and checks the result reads quiet/loud/quiet — proves the JNI " +
            "boundary around dsp::punchIn works, not just the underlying C++.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(onClick = { resultText = runTest() }) {
        Text("Run punch-in smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Proves Rules A/B/C end-to-end: a synthetic "already-aligned take" (a
 * plain tone standing in for a real recorded take — Rule C says takes are
 * stored already-aligned, so this deliberately does no offset math of its
 * own) gets mixed with a regenerated click track into one buffer
 * (Rule A), then played through [CalibrationAudio.playPreMixed] — the same
 * narrow interface (Rule I) the eventual wizard will use, not
 * [AudioEngine] directly. No count-in (Rule B) is structurally true here:
 * [CalibrationAudio] has no method that could add one.
 */
@Composable
private fun VerificationPlaybackSmokeTestSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isRunning by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runTest() {
        isRunning = true
        resultText = null
        scope.launch {
            val sampleRate = 48000.0
            val frameCount = (sampleRate * 3.0).toInt()
            // Stands in for a real recorded, already-calibration-aligned take.
            val fakeTake = FloatArray(frameCount) { i ->
                (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.3).toFloat()
            }
            val mixed = withContext(Dispatchers.Default) {
                Calibration.buildPreMixedVerificationBuffer(
                    take = fakeTake, sampleRate = sampleRate, bpm = 80.0, beatsPerBar = 4,
                )
            }
            val audio: CalibrationAudio = RealCalibrationAudio(engine)
            audio.playPreMixed(mixed)
            isRunning = false
            resultText = "Pre-mixed ${mixed.size} frames from a ${fakeTake.size}-frame fake take, " +
                "played via CalibrationAudio.playPreMixed — completed with no crash, single source, no count-in."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) runTest() else resultText = "Microphone permission is required (duplex engine opens both streams)."
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Verification playback smoke test (Rules A/B/C/I)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Mixes a synthetic fake take with a regenerated click track into one buffer, then plays it " +
            "through CalibrationAudio.playPreMixed — the same narrow interface the wizard will use.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            if (!hasRecordPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                runTest()
            }
        },
    ) {
        Text(if (isRunning) "Playing..." else "Run verification playback smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Exercises [CalibrationSession] — the N-repetition, MAD-aggregated,
 * AEC-defeat-checking flow the eventual wizard will call directly. Separate
 * from [EngineCalibrationCaptureSection] above (a single manual capture)
 * rather than replacing it — both remain useful, distinct diagnostics.
 */
@Composable
private fun CalibrationSessionSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isRunning by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    // Non-null while a Bluetooth route has been detected and we're waiting
    // for an explicit second tap — the plan's "refuse to auto-calibrate by
    // default... offer measure anyway" for exactly this route, not a
    // generic confirmation dialog.
    var bluetoothWarningRoute by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun beginSession() {
        resultText = null
        isRunning = true
        scope.launch {
            val route = AudioRouteDetector(context).currentInputRoute()
            val result = CalibrationSession(engine).run(repetitionCount = 5)
            isRunning = false

            val store = CalibrationStore(context)
            if (result.acceptedDelayFrames.isNotEmpty() && !result.aecDefeatSuspected) {
                store.save(route.routeKey, result.meanAcceptedDelayFrames)
            }
            val stored = store.load(route.routeKey)

            resultText = buildString {
                appendLine(
                    "Route: ${route.label} (key=${route.routeKey}, bluetooth=${route.isBluetooth})",
                )
                appendLine("${result.repetitions.size} repetitions:")
                result.repetitions.forEachIndexed { i, rep ->
                    appendLine("  rep $i: %.2f frames, PNR %.1f dB".format(rep.delayFrames, rep.pnrDb))
                }
                appendLine(
                    "Accepted after MAD rejection: ${result.acceptedDelayFrames.size}/${result.repetitions.size}",
                )
                appendLine(
                    "Mean accepted delay: %.2f frames (%.2f ms), spread %.2f frames".format(
                        result.meanAcceptedDelayFrames,
                        result.meanAcceptedDelayFrames / 48000.0 * 1000.0,
                        result.spreadFrames,
                    ),
                )
                appendLine(
                    if (result.aecDefeatSuspected) {
                        "AEC-DEFEAT SUSPECTED — PNR collapsed across repetitions (not saved)"
                    } else {
                        "No AEC-defeat signature detected"
                    },
                )
                append(
                    if (stored != null) {
                        "Stored for this route: %.2f frames, measured at epoch %d".format(
                            stored.offsetFrames, stored.measuredAtEpochMs,
                        )
                    } else {
                        "Nothing stored for this route."
                    },
                )
            }
        }
    }

    fun beginSessionCheckingRoute() {
        val route = AudioRouteDetector(context).currentInputRoute()
        if (route.isBluetooth && bluetoothWarningRoute != route.routeKey) {
            // First tap on a Bluetooth route: refuse and explain, per the
            // plan, rather than silently calibrating against a route whose
            // latency is both usually much higher and often less stable.
            bluetoothWarningRoute = route.routeKey
            resultText = "Detected route \"${route.label}\" is Bluetooth. Bluetooth audio latency " +
                "is typically higher and less consistent than wired or built-in — calibrating " +
                "against it may not hold up from one session to the next. Tap again to measure anyway."
            return
        }
        bluetoothWarningRoute = null
        beginSession()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) beginSessionCheckingRoute() else resultText = "Microphone permission is required."
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Calibration session — 5 reps + route storage + AEC-defeat check", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Detects the current input route (refusing Bluetooth by default, like the plan " +
            "specifies — tap again to override), runs 5 real captures, MAD-rejects outliers, " +
            "checks for the AEC-defeat signature, and persists the result keyed to that route.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            if (!hasRecordPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                beginSessionCheckingRoute()
            }
        },
    ) {
        Text(
            when {
                isRunning -> "Running session..."
                bluetoothWarningRoute != null -> "Measure anyway"
                else -> "Run 5-rep calibration session"
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        // Deliberately does NOT run a new capture or call save() — this is
        // the actual test of whether CalibrationStore persists across
        // process restarts, not just within one run's save-then-load.
        val route = AudioRouteDetector(context).currentInputRoute()
        val stored = CalibrationStore(context).load(route.routeKey)
        resultText = "Route: ${route.label} (key=${route.routeKey}, bluetooth=${route.isBluetooth})\n" +
            if (stored != null) {
                "Stored: %.2f frames, measured at epoch %d".format(stored.offsetFrames, stored.measuredAtEpochMs)
            } else {
                "Nothing stored for this route."
            }
    }) {
        Text("Check stored calibration (no new capture)")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Phase 3 engine integration: plays a real sweep through the duplex
 * engine's output stream and captures the actual acoustic loopback via the
 * mic — unlike [CalibrationDspSmokeTestSection] above, which only proves
 * the JNI boundary using a synthesized-in-Kotlin recording. This is the
 * real thing: [AudioEngine.startCalibrationCapture] plays [Calibration]'s
 * sweep out, [AudioEngine.takeCalibrationCapture] retrieves what the mic
 * actually heard, then the same [Calibration.measureRoundTripDelay] used by
 * the smoke test recovers the real round-trip latency of this phone's
 * speaker→air→mic path.
 */
@Composable
private fun EngineCalibrationCaptureSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isCapturing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun beginCapture() {
        resultText = null
        val sampleRate = 48000.0
        val sweepData = Calibration.generateSweepAndInverse(
            sampleRate = sampleRate, f1Hz = 200.0, f2Hz = 8000.0, lengthSeconds = 0.5, amplitude = 0.7f,
        )
        // 0.5s of room for the real acoustic round trip + reverb tail to
        // actually land in the capture, past where the sweep's dry image ends.
        val tailPaddingFrames = 24000

        // Open streams first (without starting a mode) so inputSessionId()
        // is real before AEC/NS/AGC setup, rather than racing capture start.
        if (!engine.ensureReady()) {
            resultText = "Failed to open streams — see Last error above."
            return
        }
        val effects = CalibrationAudioEffects(engine.inputSessionId())

        if (!engine.startCalibrationCapture(sweepData.sweep, tailPaddingFrames)) {
            effects.release()
            resultText = "Failed to start calibration capture — see Last error above."
            return
        }
        isCapturing = true
        scope.launch {
            while (engine.state().isCalibrating) {
                delay(100)
            }
            val recording = engine.takeCalibrationCapture()
            effects.release()
            isCapturing = false
            resultText = withContext(Dispatchers.Default) {
                val effectsLine = "AEC: %s, NS: %s, AGC: %s".format(
                    effectStatusText(effects.status.aecAvailable, effects.status.aecDisabled),
                    effectStatusText(effects.status.nsAvailable, effects.status.nsDisabled),
                    effectStatusText(effects.status.agcAvailable, effects.status.agcDisabled),
                )
                if (recording.isEmpty()) {
                    "No capture retrieved — aborted, or see Last error above.\n$effectsLine"
                } else {
                    val measurement = Calibration.measureRoundTripDelay(
                        recording = recording,
                        inverseFilter = sweepData.inverseFilter,
                        sweepLength = sweepData.sweep.size,
                    )
                    val delayMs = measurement.frames / sampleRate * 1000.0
                    "Captured ${recording.size} frames (expected ${sweepData.sweep.size + tailPaddingFrames}).\n" +
                        "Recovered round-trip delay: %.2f frames (%.2f ms)\nPNR: %.1f dB\n%s".format(
                            measurement.frames, delayMs, measurement.pnrDb, effectsLine,
                        )
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) beginCapture() else resultText = "Microphone permission is required."
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Engine calibration capture (Phase 3 integration)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Plays a real sweep through the duplex engine and captures the actual acoustic " +
            "loopback via the mic, then measures it with the same code the smoke test above " +
            "already proved works — this is the real device round trip, not synthesized data. " +
            "Requests AEC/NS/AGC disabled on the input session first (reports what was actually " +
            "available and actually disabled below — some devices can't fully honor this).",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isCapturing,
        onClick = {
            if (!hasRecordPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                beginCapture()
            }
        },
    ) {
        Text(if (isCapturing) "Capturing..." else "Run calibration capture")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Not a product feature — a one-tap on-device check that the new
 * Calibration JNI wrapping (calibration_jni.cpp) actually links and
 * marshals correctly. The host GoogleTest suite already proves the
 * underlying math is right (test_calibration_roundtrip.cpp); JNI method
 * name mangling can only be verified by actually calling it from Kotlin at
 * runtime — a mismatch there is an UnsatisfiedLinkError the C++ compiler
 * can't catch. Runs on Dispatchers.Default since the FFT-based convolution,
 * while fast, isn't free enough to block the UI thread on tap.
 */
@Composable
private fun CalibrationDspSmokeTestSection() {
    var resultText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Calibration DSP smoke test (Phase 3 JNI)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Generates a sweep via JNI, synthesizes a recording with a known " +
            "500-frame delay in Kotlin, and confirms measureRoundTripDelay " +
            "recovers it — proves the JNI boundary works, not just the " +
            "underlying C++ (that's what the host GoogleTest suite is for).",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            isRunning = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCalibrationSmokeTest() }
                resultText = result
                isRunning = false
            }
        },
    ) {
        Text(if (isRunning) "Running..." else "Run smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

private fun runCalibrationSmokeTest(): String {
    val sampleRate = 48000.0
    val knownDelayFrames = 500
    val tailPadding = 4800

    val sweepData = Calibration.generateSweepAndInverse(
        sampleRate = sampleRate,
        f1Hz = 200.0,
        f2Hz = 8000.0,
        lengthSeconds = 0.5,
        amplitude = 0.7f,
    )

    val recording = FloatArray(knownDelayFrames + sweepData.sweep.size + tailPadding)
    sweepData.sweep.copyInto(recording, destinationOffset = knownDelayFrames)

    val measurement = Calibration.measureRoundTripDelay(
        recording = recording,
        inverseFilter = sweepData.inverseFilter,
        sweepLength = sweepData.sweep.size,
    )

    val statsSample = Calibration.rejectOutliersMad(doubleArrayOf(42.0, 42.1, 41.9, 90.0))
    val pnrSample = Calibration.peakToNoiseRatioDb(10.0f, 1.0f)

    val delayOk = abs(measurement.frames - knownDelayFrames) < 1.0
    val pnrOk = measurement.pnrDb > 20.0
    val statsOk = statsSample.size == 3
    val pnrSampleOk = abs(pnrSample - 20.0) < 0.01

    val pass = delayOk && pnrOk && statsOk && pnrSampleOk
    return buildString {
        appendLine(if (pass) "PASS — JNI boundary verified" else "FAIL — see values below")
        appendLine("sweep frames: ${sweepData.sweep.size}, inverseFilter frames: ${sweepData.inverseFilter.size}")
        appendLine(
            "recovered delay: %.3f frames (expected %d, ok=%s)".format(
                measurement.frames, knownDelayFrames, delayOk,
            ),
        )
        appendLine("pnr: %.1f dB (ok=%s)".format(measurement.pnrDb, pnrOk))
        appendLine("MAD outlier rejection kept ${statsSample.size}/4 values (ok=$statsOk)")
        append("peakToNoiseRatioDb(10,1)=%.2f dB, expected 20.00 (ok=%s)".format(pnrSample, pnrSampleOk))
    }
}

/**
 * Same reasoning as [CalibrationDspSmokeTestSection]: proves the JNI
 * boundary for [Calibration.detectOnsets]/[Calibration.estimateLatencyFromOnsets]
 * (the manual tap-along path's DSP) actually works at runtime, not just
 * that the underlying C++ compiles — a method-name-mangling mismatch only
 * surfaces as an `UnsatisfiedLinkError` on first call. Uses a
 * synthesized-in-Kotlin PCM buffer with known tap positions, exactly like
 * the sweep smoke test above uses a synthesized recording with a known
 * delay — no microphone or physical tapping involved, so this only proves
 * the detection math + JNI marshaling, not the real acoustic path (that
 * needs an actual person tapping the device, which is what
 * `TapAlongCalibrationScreen` is for).
 */
@Composable
private fun OnsetDetectionSmokeTestSection() {
    var resultText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Onset detection smoke test (Phase 3 tap-along JNI)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Synthesizes a PCM buffer with 5 sharp transients at a known " +
            "+42ms offset from 5 'scheduled tap' times, and confirms " +
            "detectOnsets + estimateLatencyFromOnsets recover 42ms.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Button(
        enabled = !isRunning,
        onClick = {
            isRunning = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.Default) { runOnsetDetectionSmokeTest() }
                resultText = result
                isRunning = false
            }
        },
    ) {
        Text(if (isRunning) "Running..." else "Run smoke test")
    }

    resultText?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

private fun runOnsetDetectionSmokeTest(): String {
    val sampleRate = 48000.0
    val knownLatencySec = 0.042
    val scheduledTimes = doubleArrayOf(0.1, 0.4, 0.7, 1.0, 1.3)

    val pcm = FloatArray((1.6 * sampleRate).toInt())
    for (t in scheduledTimes) {
        val start = ((t + knownLatencySec) * sampleRate).toInt()
        for (i in 0 until 200) {
            if (start + i < pcm.size) pcm[start + i] = (kotlin.math.sin(i * 0.5) * 0.9).toFloat()
        }
    }

    val detected = Calibration.detectOnsets(pcm, sampleRate)
    val estimate = Calibration.estimateLatencyFromOnsets(detected, scheduledTimes)

    val detectedCountOk = detected.size == scheduledTimes.size
    val estimateOk = estimate != null && abs(estimate - knownLatencySec) < 0.001
    val silenceOk = Calibration.detectOnsets(FloatArray(1000), sampleRate).isEmpty()
    val tooFewOk = Calibration.estimateLatencyFromOnsets(doubleArrayOf(0.11), doubleArrayOf(0.1, 0.4, 0.7, 1.0)) == null

    val pass = detectedCountOk && estimateOk && silenceOk && tooFewOk
    return buildString {
        appendLine(if (pass) "PASS — JNI boundary verified" else "FAIL — see values below")
        appendLine("detected ${detected.size}/${scheduledTimes.size} onsets (ok=$detectedCountOk)")
        appendLine(
            "estimated latency: %s (expected %.1fms, ok=%s)".format(
                estimate?.let { "%.2fms".format(it * 1000) } ?: "null", knownLatencySec * 1000, estimateOk,
            ),
        )
        appendLine("pure silence returns no onsets (ok=$silenceOk)")
        append("too-few-matches returns null (ok=$tooFewOk)")
    }
}

@Composable
private fun RecordPlaybackSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isPolling by remember { mutableStateOf(false) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var bpmText by remember { mutableStateOf("80") }

    val takeFile = remember {
        File(context.filesDir, "takes/phase2_test.f32").also { it.parentFile?.mkdirs() }
    }
    // Recomputed each recomposition on purpose — cheap, and file existence
    // can change from a button click above without a full screen rebuild.
    val takeFileExists = takeFile.exists()

    fun beginRecording() {
        val bpm = bpmText.toDoubleOrNull()
        if (bpm == null || bpm <= 0.0) {
            statusMessage = "Enter a valid BPM before recording."
            return
        }
        context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        // Rule C: apply whatever calibration this route has saved, if any
        // — 0.0 (no correction) if this route has never been calibrated.
        val route = AudioRouteDetector(context).currentInputRoute()
        val calibrationOffsetFrames = CalibrationStore(context).load(route.routeKey)?.offsetFrames ?: 0.0
        // 4 beats of count-in, 4/4 time — fixed for this diagnostic screen;
        // a real UI for these lands with Phase 8/10.
        if (engine.armRecording(
                takeFile.absolutePath, bpm, beatsPerBar = 4, countInBeats = 4,
                calibrationOffsetFrames = calibrationOffsetFrames,
            )
        ) {
            engineState = engine.state()
            isPolling = true
            statusMessage = "Applying calibration offset for \"${route.label}\": %.2f frames".format(
                calibrationOffsetFrames,
            )
        } else {
            statusMessage = "Failed to arm recording — see Last error above."
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            beginRecording()
        } else {
            statusMessage = "Microphone permission is required to record."
        }
    }

    LaunchedEffect(isPolling) {
        while (isPolling) {
            engineState = engine.state()
            if (!engineState.isArmed && !engineState.isRecording && !engineState.isPlaying) {
                isPolling = false
            }
            delay(100)
        }
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Record & Playback (Phase 2)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "4-beat count-in against a metronome, then records real mic input to ${takeFile.name}. " +
            "Plays back through the same duplex engine, with the pre-roll already trimmed off.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = bpmText,
        onValueChange = { bpmText = it },
        label = { Text("BPM") },
        enabled = !engineState.isArmed && !engineState.isRecording,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(12.dp))

    Row {
        Button(
            enabled = !engineState.isArmed && !engineState.isRecording && !engineState.isPlaying,
            onClick = {
                if (!hasRecordPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    beginRecording()
                }
            },
        ) {
            Text("Arm & record")
        }

        Spacer(Modifier.width(12.dp))

        Button(
            enabled = engineState.isArmed || engineState.isRecording,
            onClick = {
                engine.stopRecording()
                context.stopService(Intent(context, RecordingForegroundService::class.java))
                engineState = engine.state()
                isPolling = false
            },
        ) {
            Text("Stop recording")
        }
    }

    if (engineState.isArmed) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Counting in: ${engineState.countInBeatsRemaining}",
            style = MaterialTheme.typography.titleMedium,
        )
    }

    Spacer(Modifier.height(8.dp))

    Row {
        Button(
            enabled = !engineState.isRecording && !engineState.isPlaying && takeFileExists,
            onClick = {
                if (engine.startPlayback(takeFile.absolutePath)) {
                    engineState = engine.state()
                    isPolling = true
                    statusMessage = null
                } else {
                    statusMessage = "Failed to start playback — see Last error above."
                }
            },
        ) {
            Text("Play last take")
        }

        Spacer(Modifier.width(12.dp))

        Button(
            enabled = engineState.isPlaying,
            onClick = {
                engine.stopPlayback()
                engineState = engine.state()
                isPolling = false
            },
        ) {
            Text("Stop playback")
        }
    }

    Spacer(Modifier.height(16.dp))
    CapabilityRow("Armed (counting in)", if (engineState.isArmed) "yes" else "no")
    CapabilityRow("Recording", if (engineState.isRecording) "yes" else "no")
    CapabilityRow("Playing", if (engineState.isPlaying) "yes" else "no")
    CapabilityRow("Frames recorded", "${engineState.framesRecorded}")
    CapabilityRow("Playback position", "${engineState.playbackFrame} / ${engineState.playbackTotalFrames}")
    CapabilityRow("Frames dropped", "${engineState.framesDropped}")

    statusMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

private fun effectStatusText(available: Boolean, disabled: Boolean) = when {
    !available -> "n/a on this device"
    disabled -> "disabled"
    else -> "available but failed to disable"
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
