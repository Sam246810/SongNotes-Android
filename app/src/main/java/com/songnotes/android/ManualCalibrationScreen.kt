package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.songnotes.core.audio.CalibrationAudio
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.RealCalibrationAudio
import com.songnotes.core.audio.VerificationTakeRecorder
import java.io.File
import kotlinx.coroutines.launch

private const val kSampleRateHz = 48000.0
private const val kManualBpm = 80.0
private const val kManualBeatsPerBar = 4
private const val kManualCountInBeats = 4
private const val kManualRecordBeats = 8
private const val kMaxOffsetMs = 300f

/**
 * The plan's manual fallback: "on devices where AEC can't be defeated,
 * manual isn't a fallback, it's *the* path." No sweep measurement here at
 * all — the user drags a slider to an offset, taps Test to hear a demo
 * take recorded and played back at that value (reusing
 * [VerificationTakeRecorder], the same record→pre-mix→playback loop the
 * wizard's own Verify step uses), and adjusts by ear until it sounds
 * right. Simpler than the plan's other-described tap-along +
 * onset-detection path (that algorithm isn't ported — see
 * docs/handoff/PHASE-03.md) but a genuinely working, honest fallback: the
 * user's own ears are the measurement.
 *
 * The demo recording plays its normal audible metronome — reusing the
 * same reasoning as the wizard's Verify step: this is ordinary demo
 * recording, not calibration *measurement*, so nothing here needs Rule I's
 * narrow interface for the recording half. Only the verification playback
 * goes through [CalibrationAudio.playPreMixed] exclusively.
 */
@Composable
fun ManualCalibrationScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var offsetMs by remember { mutableStateOf(80f) }
    var route by remember { mutableStateOf<AudioRoute?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val calibrationAudio: CalibrationAudio = remember(engine) { RealCalibrationAudio(engine) }

    LaunchedEffect(Unit) {
        val detected = AudioRouteDetector(context).currentInputRoute()
        route = detected
        CalibrationStore(context).load(detected.routeKey)?.let { stored ->
            offsetMs = (stored.offsetFrames / kSampleRateHz * 1000.0).toFloat()
        }
    }

    fun runTest() {
        isBusy = true
        statusText = null
        scope.launch {
            val take = File(context.filesDir, "takes/manual_calibration_test.f32").also { it.parentFile?.mkdirs() }
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
            val ok = VerificationTakeRecorder.recordAndPlayVerification(
                engine = engine,
                calibrationAudio = calibrationAudio,
                takeFile = take,
                calibrationOffsetFrames = offsetMs / 1000.0 * kSampleRateHz,
                bpm = kManualBpm,
                beatsPerBar = kManualBeatsPerBar,
                countInBeats = kManualCountInBeats,
                recordBeats = kManualRecordBeats,
                sampleRate = kSampleRateHz,
            )
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            isBusy = false
            statusText = if (ok) "Played back — adjust the slider and test again if it wasn't quite right." else null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) runTest() else statusText = "Microphone permission is required."
    }

    fun onTestTapped() {
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            runTest()
        }
    }

    fun onSaveTapped() {
        val r = route ?: return
        CalibrationStore(context).save(r.routeKey, (offsetMs / 1000.0 * kSampleRateHz).toDouble())
        statusText = "Saved for \"${r.label}\"."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Manual calibration", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "For devices where automatic calibration can't get a clean measurement. Drag the " +
                "slider, tap Test to hear a quick demo take at that setting, and adjust until your " +
                "performance lines up with the click.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Text("%.0f ms".format(offsetMs), style = MaterialTheme.typography.headlineMedium)
        Slider(
            value = offsetMs,
            onValueChange = { offsetMs = it },
            valueRange = 0f..kMaxOffsetMs,
            enabled = !isBusy,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = ::onTestTapped,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isBusy) "Recording..." else "Test")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = ::onSaveTapped,
            enabled = !isBusy && route != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
