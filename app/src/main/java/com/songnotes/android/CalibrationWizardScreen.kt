package com.songnotes.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.AudioRoute
import com.songnotes.core.audio.AudioRouteDetector
import com.songnotes.core.audio.CalibrationAudio
import com.songnotes.core.audio.CalibrationSession
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.RealCalibrationAudio
import com.songnotes.core.audio.VerificationTakeRecorder
import java.io.File
import kotlinx.coroutines.launch

private const val kRepetitionCount = 5
private const val kSampleRateHz = 48000.0
private const val kVerifyBpm = 80.0
private const val kVerifyBeatsPerBar = 4
private const val kVerifyCountInBeats = 4
private const val kVerifyRecordBeats = 8 // 2 bars of actual performance after count-in

private sealed interface WizardStep {
    data object Intro : WizardStep
    data class BluetoothWarning(val route: AudioRoute) : WizardStep
    data class Running(val completed: Int, val total: Int, val lastPnrDb: Double?) : WizardStep
    data class Results(val result: CalibrationSession.Result, val route: AudioRoute) : WizardStep
    data class Verifying(val secondsElapsed: Int, val totalSeconds: Int) : WizardStep
    data object VerifyPlayback : WizardStep
    data class Saved(val route: AudioRoute, val offsetFrames: Double) : WizardStep
    data class Failed(val message: String) : WizardStep
}

/**
 * The Phase 3 automatic-calibration wizard's primary flow — the manual
 * slider fallback is a separate screen (see docs/handoff/PHASE-03.md's
 * "What's left"). The sweep *measurement* step ([beginCalibration]) talks
 * to audio exclusively through [CalibrationAudio] (Rule I) — never
 * [AudioEngine] directly — so it is architecturally incapable of
 * scheduling a competing click during measurement no matter what a future
 * edit does to it.
 *
 * The [Verifying] step is a deliberate, narrower exception: it records a
 * short demo take through [AudioEngine.armRecording] directly, complete
 * with its normal audible metronome — this is not calibration
 * *measurement* in Rule I's sense (nothing is being swept or measured
 * here), it's the same ordinary Phase 1/2 recording path the ViewModel
 * would use for any real take, just invoked from the wizard for
 * demonstration, now with the just-measured offset applied. What Rule I
 * actually still holds throughout: the *verification playback* that
 * follows recording goes exclusively through
 * [CalibrationAudio.playPreMixed] (Rules A/B/C), never a second,
 * independently-scheduled click layered on top at playback time.
 *
 * Rules F/G ("every control laid out at mount in a constant-size slot;
 * state toggles enabled, never presence... nothing pops mid-flow") are
 * applied within the [Running] step specifically, since that's the step
 * the plan's own example (the manual path's tap pad) is about: the
 * indicator + progress text occupy the same fixed slot from the moment
 * the step mounts, showing neutral placeholder content before the first
 * repetition completes rather than only appearing once data exists.
 * Applying literal simultaneous-mount-of-every-step's-UI to the six very
 * different informational screens below (Intro/Results/Saved are nothing
 * alike) would be over-engineering the rule past the bug it was written to
 * prevent — noted here so the reasoning is explicit, not silently assumed.
 */
@Composable
fun CalibrationWizardScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var step: WizardStep by remember { mutableStateOf(WizardStep.Intro) }
    val scope = rememberCoroutineScope()
    val calibrationAudio: CalibrationAudio = remember(engine) { RealCalibrationAudio(engine) }

    fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun beginCalibration() {
        step = WizardStep.Running(completed = 0, total = kRepetitionCount, lastPnrDb = null)
        scope.launch {
            vibrate(100) // starting cue — Rule D: visual + haptic only, no audible click
            val route = AudioRouteDetector(context).currentInputRoute()
            val result = calibrationAudio.runSweeps(repetitionCount = kRepetitionCount) { index, total, repetition ->
                step = WizardStep.Running(completed = index + 1, total = total, lastPnrDb = repetition.pnrDb)
                vibrate(60)
            }
            step = if (result.acceptedDelayFrames.isEmpty()) {
                WizardStep.Failed(
                    "Couldn't get a trustworthy measurement in this room. Try somewhere quieter, or use " +
                        "manual calibration instead (coming soon).",
                )
            } else {
                WizardStep.Results(result, route)
            }
        }
    }

    fun beginVerification(result: CalibrationSession.Result, route: AudioRoute) {
        val countInSeconds = kVerifyCountInBeats * 60.0 / kVerifyBpm
        val recordSeconds = kVerifyRecordBeats * 60.0 / kVerifyBpm
        val totalSeconds = countInSeconds + recordSeconds
        step = WizardStep.Verifying(secondsElapsed = 0, totalSeconds = totalSeconds.toInt())
        scope.launch {
            val verifyFile = File(context.filesDir, "takes/calibration_verify.f32").also { it.parentFile?.mkdirs() }
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
            // Return value ignored — whether or not a usable take was
            // captured, Results is the right place to land either way.
            VerificationTakeRecorder.recordAndPlayVerification(
                engine = engine,
                calibrationAudio = calibrationAudio,
                takeFile = verifyFile,
                calibrationOffsetFrames = result.meanAcceptedDelayFrames,
                bpm = kVerifyBpm,
                beatsPerBar = kVerifyBeatsPerBar,
                countInBeats = kVerifyCountInBeats,
                recordBeats = kVerifyRecordBeats,
                sampleRate = kSampleRateHz,
                onProgress = { elapsed, total -> step = WizardStep.Verifying(elapsed, total) },
                onPlaybackStart = { step = WizardStep.VerifyPlayback },
            )
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            step = WizardStep.Results(result, route)
        }
    }

    fun proceedPastPermissionCheck() {
        val route = AudioRouteDetector(context).currentInputRoute()
        if (route.isBluetooth) {
            step = WizardStep.BluetoothWarning(route)
        } else {
            beginCalibration()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) proceedPastPermissionCheck() else step = WizardStep.Failed("Microphone permission is required.")
    }

    fun startFlow() {
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            proceedPastPermissionCheck()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val current = step) {
            WizardStep.Intro -> IntroStep(onStart = ::startFlow)
            is WizardStep.BluetoothWarning -> BluetoothWarningStep(
                route = current.route,
                onCancel = { step = WizardStep.Intro },
                onMeasureAnyway = ::beginCalibration,
            )
            is WizardStep.Running -> RunningStep(current)
            is WizardStep.Results -> ResultsStep(
                result = current.result,
                route = current.route,
                onSave = {
                    CalibrationStore(context).save(current.route.routeKey, current.result.meanAcceptedDelayFrames)
                    step = WizardStep.Saved(current.route, current.result.meanAcceptedDelayFrames)
                },
                onRetry = { step = WizardStep.Intro },
                onVerify = { beginVerification(current.result, current.route) },
            )
            is WizardStep.Verifying -> VerifyingStep(current)
            WizardStep.VerifyPlayback -> VerifyPlaybackStep()
            is WizardStep.Saved -> SavedStep(route = current.route, offsetFrames = current.offsetFrames, onDone = onDone)
            is WizardStep.Failed -> FailedStep(message = current.message, onRetry = { step = WizardStep.Intro })
        }
    }
}

@Composable
private fun ColumnScope.IntroStep(onStart: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Calibrate your microphone", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "We'll play a few short sweeping tones through your speaker and listen through your " +
            "microphone to measure your device's exact audio delay — this only takes a few seconds " +
            "and makes your recordings line up perfectly with the beat.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("Start calibration")
    }
}

@Composable
private fun ColumnScope.BluetoothWarningStep(route: AudioRoute, onCancel: () -> Unit, onMeasureAnyway: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Bluetooth detected", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "\"${route.label}\" is connected over Bluetooth. Bluetooth audio latency is typically " +
            "higher and less consistent than wired or built-in audio — calibrating against it may " +
            "not hold up from one session to the next. For best results, disconnect it and use " +
            "your device's built-in speaker and microphone, or wired headphones.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onMeasureAnyway, modifier = Modifier.fillMaxWidth()) {
        Text("Measure anyway")
    }
}

@Composable
private fun ColumnScope.RunningStep(state: WizardStep.Running) {
    Spacer(Modifier.weight(1f))
    // Fixed-size slot, present unconditionally the instant this step
    // mounts — only its fill color and the text inside change with
    // progress, nothing new appears or resizes as repetitions complete.
    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(
                if (state.completed == 0) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${state.completed}/${state.total}",
            style = MaterialTheme.typography.headlineMedium,
            color = if (state.completed == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        if (state.completed == 0) "Getting ready..." else "Measuring...",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Stay quiet and still — listening through the microphone.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ResultsStep(
    result: CalibrationSession.Result,
    route: AudioRoute,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onVerify: () -> Unit,
) {
    val delayMs = result.meanAcceptedDelayFrames / kSampleRateHz * 1000.0
    Spacer(Modifier.weight(1f))
    Text("Calibration complete", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text("Route: ${route.label}", style = MaterialTheme.typography.bodyMedium)
    Text("Measured delay: %.1f ms".format(delayMs), style = MaterialTheme.typography.bodyMedium)
    Text(
        "Consistency: ${result.acceptedDelayFrames.size}/${result.repetitions.size} repetitions agreed",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (result.aecDefeatSuspected) {
        Spacer(Modifier.height(12.dp))
        Text(
            "This device's echo cancellation may be adapting over repeated measurements — this " +
                "result might not be fully reliable. Consider using manual calibration instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text("Retry")
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
        Text("Verify — record a quick take")
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Save")
    }
}

@Composable
private fun ColumnScope.VerifyingStep(state: WizardStep.Verifying) {
    Spacer(Modifier.weight(1f))
    // Same fixed-slot treatment as RunningStep — present from the instant
    // this step mounts, only its fill/text change as recording proceeds.
    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${state.secondsElapsed}s",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
    Spacer(Modifier.height(24.dp))
    Text("Recording a quick take...", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Play or sing along with the click — we'll play it back afterward so you can hear " +
            "how it lines up.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.VerifyPlaybackStep() {
    Spacer(Modifier.weight(1f))
    Text("Playing it back...", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "One mixed track — your take and a reference click, already aligned. No count-in.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.SavedStep(route: AudioRoute, offsetFrames: Double, onDone: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Saved", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "Calibration for \"${route.label}\" is saved. Recordings through this route will now " +
            "line up automatically.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text("Done")
    }
}

@Composable
private fun ColumnScope.FailedStep(message: String, onRetry: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Calibration didn't complete", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text("Try again")
    }
}
