package com.songnotes.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where each spotlit part of the Scratchpad is on screen, filled in by [tutorialTarget]. */
@Stable
class TutorialTargets {
    val bounds = mutableStateMapOf<String, Rect>()
}

/** Registers this element's on-screen bounds under [key] for the tutorial's spotlight. */
fun Modifier.tutorialTarget(targets: TutorialTargets?, key: String): Modifier =
    if (targets == null) this else onGloballyPositioned { targets.bounds[key] = it.boundsInWindow() }

private data class TutorialStep(val target: String?, val title: String, val body: String)

private val Steps = listOf(
    TutorialStep(
        "controls",
        "Your controls",
        "Tempo, time signature, the metronome (its ▾ menu holds the count-in) and the grid switch, laid out like Ableton's control bar. Tap any of them to change it.",
    ),
    TutorialStep(
        "record",
        "Record a take",
        "Arm a track with its red ● button, then tap Record. The take lands at the insert marker, the dashed line, and you can keep recording while you look at your lyrics.",
    ),
    TutorialStep(
        "ruler",
        "Find your place",
        "Tap or drag the ruler (or tap an empty spot) to move the insert marker. Play starts from it and new takes land on it. Pinch to zoom; drag empty space to scroll. The closer you zoom, the finer clips snap.",
    ),
    TutorialStep(
        "lanes",
        "Shape your clips",
        "Tap a clip to select it. Drag it to move it, even onto another track. Drag the tabs at its edges to trim. Split, Duplicate and Delete appear while it's selected, and Undo is always in the top bar.",
    ),
    TutorialStep(
        "headers",
        "Tracks",
        "Each track has Arm, Mute and Solo. Tap its name to rename or delete it. Long-press and drag a track to reorder it. Add tracks from the empty lane underneath.",
    ),
    TutorialStep(
        null,
        "Saved on this phone",
        "Every change saves automatically, on this phone and offline, as this song's own demo. You don't need an account. Demos aren't on the web app yet; that's coming soon.\n\nYou can replay this tour anytime from Settings → Scratchpad tutorial.",
    ),
)

/**
 * The first-run Scratchpad tour: a dimmed screen with a spotlight cut around
 * one part of the real UI per step and a card explaining it. Shown once ever
 * (not per song), and again on request from Settings or the ⋮ menu.
 */
@Composable
fun ScratchpadTutorial(targets: TutorialTargets, onFinish: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val step = Steps[index]
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val spotlight = step.target?.let { targets.bounds[it] }?.translate(-originInWindow)
    val fade by animateFloatAsState(if (spotlight != null) 1f else 0f, label = "spotlight")
    val ring = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { originInWindow = it.boundsInWindow().topLeft }
            // Swallow touches so the tour can't be fought with the UI under it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val pad = with(density) { 6.dp.toPx() }
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            spotlight?.let { r ->
                val hole = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
                drawRoundRect(
                    Color.Transparent, topLeft = hole.topLeft, size = hole.size,
                    cornerRadius = CornerRadius(16f), blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    ring.copy(alpha = fade), topLeft = hole.topLeft, size = hole.size,
                    cornerRadius = CornerRadius(16f), style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // The card goes below the spotlight if there's room, else above; centred without one.
        val heightPx = constraints.maxHeight.toFloat()
        val cardMaxWidth = 420.dp
        val cardY: Float? = spotlight?.let { r ->
            val gap = with(density) { 16.dp.toPx() }
            val estimatedCard = with(density) { 210.dp.toPx() }
            when {
                r.bottom + gap + estimatedCard < heightPx -> r.bottom + gap
                r.top - gap - estimatedCard > 0f -> r.top - gap - estimatedCard
                // A big target (the whole arrangement): sit at the bottom, over
                // its emptier lower part rather than on top of the clips.
                else -> heightPx - estimatedCard - gap
            }
        }
        val widthPx = constraints.maxWidth.toFloat()
        val cardWidthPx = minOf(widthPx - with(density) { 32.dp.toPx() }, with(density) { cardMaxWidth.toPx() })
        val cardX = spotlight?.let { r ->
            (r.center.x - cardWidthPx / 2f).coerceIn(with(density) { 16.dp.toPx() }, widthPx - cardWidthPx - with(density) { 16.dp.toPx() })
        }
        Box(
            modifier = if (cardY != null && cardX != null) {
                Modifier.offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }
            } else {
                Modifier.fillMaxSize().padding(24.dp)
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = cardMaxWidth),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "${index + 1} of ${Steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(step.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(step.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (index < Steps.lastIndex) {
                            TextButton(onClick = onFinish) { Text("Skip") }
                        }
                        if (index > 0) {
                            TextButton(onClick = { index-- }) { Text("Back") }
                        }
                        Button(onClick = { if (index < Steps.lastIndex) index++ else onFinish() }) {
                            Text(if (index < Steps.lastIndex) "Next" else "Got it")
                        }
                    }
                }
            }
        }
    }
}
