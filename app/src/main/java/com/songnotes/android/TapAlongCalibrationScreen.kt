package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.AudioRoute
import com.songnotes.core.audio.AudioRouteDetector
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.TapAlongCalibrationRecorder
import java.io.File
import kotlinx.coroutines.launch

private const val kSampleRateHz = 48000.0
private const val kBpm = 80.0
private const val kBeatsPerBar = 4
private const val kCountInBeats = 4
private const val kTapBeats = 8

private sealed interface TapAlongStep {
    data object Intro : TapAlongStep
    data class Recording(val elapsedSeconds: Int, val totalSeconds: Int) : TapAlongStep
    data class Results(val result: TapAlongCalibrationRecorder.Result, val route: AudioRoute) : TapAlongStep
    data class Saved(val route: AudioRoute, val offsetFrames: Double) : TapAlongStep
}

/**
 * The plan's other-described manual fallback (distinct from
 * [ManualCalibrationScreen]'s ear-adjusted slider): an *objective
 * measurement*, from the user physically tapping the device in time with
 * a metronome, via [TapAlongCalibrationRecorder]. Useful specifically
 * when the automatic sweep can't get a trustworthy measurement (a noisy
 * room, hardware AEC that can't be fully defeated) but the user can still
 * tap reliably — see `dsp/onset_detection.h`'s doc comment for why a
 * direct tap's assumptions hold where the sweep path's don't.
 */
@Composable
fun TapAlongCalibrationScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var step: TapAlongStep by remember { mutableStateOf(TapAlongStep.Intro) }
    val scope = rememberCoroutineScope()

    fun beginTapAlong() {
        step = TapAlongStep.Recording(0, 0)
        scope.launch {
            val route = AudioRouteDetector(context).currentInputRoute()
            val takeFile = File(context.filesDir, "takes/tap_along_calibration.f32").also { it.parentFile?.mkdirs() }
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
            val result = TapAlongCalibrationRecorder.recordAndMeasure(
                engine = engine,
                takeFile = takeFile,
                bpm = kBpm,
                beatsPerBar = kBeatsPerBar,
                countInBeats = kCountInBeats,
                tapBeats = kTapBeats,
                sampleRate = kSampleRateHz,
                onProgress = { elapsed, total -> step = TapAlongStep.Recording(elapsed, total) },
            )
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            step = if (result != null) {
                TapAlongStep.Results(result, route)
            } else {
                // armRecording itself failed (engine couldn't start) — genuinely
                // exceptional, unlike a low-confidence-but-completed measurement,
                // which Results already renders its own "try again" copy for.
                TapAlongStep.Intro
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) beginTapAlong()
    }

    fun onStartTapped() {
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            beginTapAlong()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val current = step) {
            TapAlongStep.Intro -> IntroStep(onStart = ::onStartTapped, onDone = onDone)
            is TapAlongStep.Recording -> RecordingStep(current)
            is TapAlongStep.Results -> ResultsStep(
                result = current.result,
                route = current.route,
                onSave = {
                    val offsetFrames = current.result.estimatedLatencySeconds!! * kSampleRateHz
                    CalibrationStore(context).save(current.route.routeKey, offsetFrames)
                    step = TapAlongStep.Saved(current.route, offsetFrames)
                },
                onRetry = { step = TapAlongStep.Intro },
            )
            is TapAlongStep.Saved -> SavedStep(route = current.route, offsetFrames = current.offsetFrames, onDone = onDone)
        }
    }
}

@Composable
private fun ColumnScope.IntroStep(onStart: () -> Unit, onDone: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Tap-along calibration", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "For rooms where automatic calibration can't get a clean measurement. You'll hear a " +
            "4-beat count-in, then $kTapBeats steady clicks. Tap firmly on your phone — the back " +
            "or the screen — exactly in time with each of those $kTapBeats clicks, starting with " +
            "the first one.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("Start")
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text("Done")
    }
}

@Composable
private fun ColumnScope.RecordingStep(step: TapAlongStep.Recording) {
    Spacer(Modifier.weight(1f))
    Text("Tap along now...", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        if (step.totalSeconds > 0) "${step.elapsedSeconds} / ${step.totalSeconds}s" else "Starting...",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ResultsStep(
    result: TapAlongCalibrationRecorder.Result,
    route: AudioRoute,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    Spacer(Modifier.weight(1f))
    val latencySec = result.estimatedLatencySeconds
    if (latencySec != null) {
        Text("Measured", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "%.0f ms round-trip latency for \"%s\" — detected %d/%d taps clearly.".format(
                latencySec * 1000, route.label, result.detectedOnsetCount, result.scheduledTapCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
    } else {
        Text("Not enough taps detected", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Only detected %d/%d taps clearly enough to trust. Try tapping more firmly, or in a " +
                "quieter space.".format(result.detectedOnsetCount, result.scheduledTapCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text("Try again")
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.SavedStep(route: AudioRoute, offsetFrames: Double, onDone: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("Saved", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "Calibration for \"${route.label}\" is saved (%.0f ms). Recordings through this route will " +
            "now line up automatically.".format(offsetFrames / kSampleRateHz * 1000),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text("Done")
    }
}
