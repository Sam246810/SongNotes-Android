package com.songnotes.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Where the phone's built-in mic is, as fractions of the screen in the
 * device's natural (portrait) orientation: (0,0) top-left, (1,1)
 * bottom-right. From [AudioManager.getMicrophones], which needs no
 * permission. Phones list several mics; the one nearest the bottom edge is
 * taken, the usual primary for recording. Null when the device doesn't
 * report positions.
 */
fun locateBuiltinMic(context: Context): Pair<Float, Float>? {
    val audio = context.getSystemService(AudioManager::class.java) ?: return null
    val mics = runCatching { audio.microphones }.getOrNull() ?: return null
    val builtin = mics.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.position != MicrophoneInfo.POSITION_UNKNOWN }
    val primary = builtin.minByOrNull { it.position.y } ?: return null

    // Natural-orientation screen size in metres, to turn the mic's position
    // (metres from the bottom-left corner, y up) into screen fractions.
    val wm = context.getSystemService(WindowManager::class.java) ?: return null
    val bounds = wm.maximumWindowMetrics.bounds
    val rotation = context.display?.rotation ?: Surface.ROTATION_0
    val metrics = context.resources.displayMetrics
    val (wPx, hPx) = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        bounds.height() to bounds.width()
    } else {
        bounds.width() to bounds.height()
    }
    val (xDpi, yDpi) = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        metrics.ydpi to metrics.xdpi
    } else {
        metrics.xdpi to metrics.ydpi
    }
    val widthM = wPx / xDpi * 0.0254f
    val heightM = hPx / yDpi * 0.0254f
    if (widthM <= 0f || heightM <= 0f) return null
    val fx = (primary.position.x / widthM).coerceIn(0f, 1f)
    val fy = (1f - primary.position.y / heightM).coerceIn(0f, 1f)
    return fx to fy
}

/** [natural] (a [locateBuiltinMic] fraction) as a fraction of the screen in its current [rotation]. */
fun rotateNaturalFraction(natural: Pair<Float, Float>, rotation: Int): Pair<Float, Float> {
    val (fx, fy) = natural
    return when (rotation) {
        Surface.ROTATION_90 -> fy to (1f - fx)
        Surface.ROTATION_180 -> (1f - fx) to (1f - fy)
        Surface.ROTATION_270 -> (1f - fy) to fx
        else -> fx to fy
    }
}

/**
 * Where to sing: a slim pulsing red glow on the very edge of the screen
 * nearest the phone's built-in mic, shown for a few seconds from the
 * Scratchpad's "Where's the mic?" menu item. Drawn in
 * the screen's outer strip (under the navigation bar, not inset from it),
 * because the mic often sits right where a control is (on the Fold, Simple
 * mode's record button); the tag this replaced covered it. A Canvas with no
 * pointer input, so taps pass straight through.
 */
@Composable
fun MicPointer(fraction: Pair<Float, Float>, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "micPulse",
    )
    val (fx, fy) = fraction
    // Snap to the nearest edge.
    val distances = listOf(fx, 1f - fx, fy, 1f - fy)
    val edge = distances.indexOf(distances.min()) // 0 left, 1 right, 2 top, 3 bottom
    Canvas(modifier = modifier.fillMaxSize().semantics { contentDescription = "Your phone's microphone is here" }) {
        val length = 72.dp.toPx()
        val thickness = 5.dp.toPx()
        val glow = 48.dp.toPx()
        val along = if (edge <= 1) fy * size.height else fx * size.width
        val centre = when (edge) {
            0 -> Offset(0f, along)
            1 -> Offset(size.width, along)
            2 -> Offset(along, 0f)
            else -> Offset(along, size.height)
        }
        drawCircle(
            Brush.radialGradient(listOf(RecordRed.copy(alpha = 0.45f * pulse), Color.Transparent), center = centre, radius = glow),
            radius = glow,
            center = centre,
        )
        val barSize = if (edge <= 1) Size(thickness, length) else Size(length, thickness)
        val topLeft = when (edge) {
            0 -> Offset(0f, along - length / 2f)
            1 -> Offset(size.width - thickness, along - length / 2f)
            2 -> Offset(along - length / 2f, 0f)
            else -> Offset(along - length / 2f, size.height - thickness)
        }
        drawRoundRect(RecordRed.copy(alpha = pulse), topLeft = topLeft, size = barSize, cornerRadius = CornerRadius(thickness / 2f))
    }
}
