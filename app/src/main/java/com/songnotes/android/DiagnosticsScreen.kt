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
import com.songnotes.core.audio.CalibrationAudioEffects
import com.songnotes.core.audio.CalibrationSession
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.EngineCapabilities
import com.songnotes.core.audio.EngineState
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        EngineCalibrationCaptureSection(engine)
        CalibrationSessionSection(engine)
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
        // 4 beats of count-in, 4/4 time — fixed for this diagnostic screen;
        // a real UI for these lands with Phase 8/10.
        if (engine.armRecording(takeFile.absolutePath, bpm, beatsPerBar = 4, countInBeats = 4)) {
            engineState = engine.state()
            isPolling = true
            statusMessage = null
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
