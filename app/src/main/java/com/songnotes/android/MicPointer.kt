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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
 * A small pulsing "Mic" tag pinned to the screen edge nearest the phone's
 * built-in mic, with an arrow pointing off-screen toward it, shown while a
 * take is armed or recording so it's obvious where to sing.
 */
@Composable
fun MicPointer(fraction: Pair<Float, Float>, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "micPulse",
    )
    val (fx, fy) = fraction
    // Snap to the nearest edge; the tag sits just inside it.
    val distances = listOf(fx, 1f - fx, fy, 1f - fy)
    val edge = distances.indexOf(distances.min()) // 0 left, 1 right, 2 top, 3 bottom
    val arrowRotation = when (edge) { 0 -> 180f; 1 -> 0f; 2 -> 270f; else -> 90f }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val tagDp = 44.dp
        val tagSize = with(density) { tagDp.toPx() }
        val inset = with(density) { 6.dp.toPx() }
        val x = when (edge) {
            0 -> inset
            1 -> w - tagSize - inset
            else -> (fx * w - tagSize / 2f).coerceIn(inset, w - tagSize - inset)
        }
        val y = when (edge) {
            2 -> inset
            3 -> h - tagSize - inset
            else -> (fy * h - tagSize / 2f).coerceIn(inset, h - tagSize - inset)
        }
        // A plain Box, not a Surface: Material's Surface swallows touches, and
        // the mic often sits right where a control is (Simple mode's record
        // button), so the tag must let taps through.
        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(tagDp)
                .alpha(pulse * 0.9f)
                .clip(RoundedCornerShape(12.dp))
                .background(RecordRed),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("➜", modifier = Modifier.rotate(arrowRotation), style = MaterialTheme.typography.labelLarge, color = Color.White)
            Icon(Icons.Filled.Mic, contentDescription = "Your phone's microphone is this way", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
