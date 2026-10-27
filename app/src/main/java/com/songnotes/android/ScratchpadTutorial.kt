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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

private const val SavedOnPhone =
    "Every change saves automatically, on this phone and offline, as this song's own demo. You don't need an " +
        "account. Demos aren't on the web app yet; that's coming soon."

/** Simple mode's tour; its last step offers Advanced mode. */
private val SimpleSteps = listOf(
    TutorialStep(
        "simple_record",
        "Record a layer",
        "Tap the big red button and sing, hum or play. Tap it again to stop. Every take becomes a new layer that plays along with the ones before it, so you can stack harmonies and parts.",
    ),
    TutorialStep(
        "simple_metronome",
        "Keep time",
        "Tap the metronome to switch the click on or off, even mid-take. Tap the tempo to change it; the little ▾ sets a count-in before you start.",
    ),
    TutorialStep(
        "simple_layers",
        "Your layers",
        "Each layer has mute, solo and a ⋮ menu for volume, renaming and deleting. Tap a waveform to move the playhead: play starts there, and so does your next layer.",
    ),
    TutorialStep(null, "Saved on this phone", SavedOnPhone),
    TutorialStep(
        null,
        "Want more control?",
        "Advanced mode works like a small DAW: bars and beats, a snap grid, and moving, trimming and splitting clips across tracks. Both modes edit the same demo, so you can switch any time from the ⋮ menu.\n\nReplay these tours from Settings → Scratchpad tutorials.",
    ),
)

/** Advanced mode's tour, shown the first time someone opens Advanced. */
private val AdvancedSteps = listOf(
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
        "Move the insert marker",
        "The insert marker is grabbed up here, in the ruler: tap anywhere along it, or put your finger on the ruler and drag, to move it. Play starts from the marker and new takes land on it. Pinch to zoom; drag empty space to scroll. The closer you zoom, the finer clips snap.",
    ),
    TutorialStep(
        "lanes",
        "Shape your clips",
        "Tap a clip to select it. Drag it to move it, even onto another track. Drag the tabs at its edges to trim. Split, Duplicate and Delete appear while it's selected, and Undo is always in the top bar.",
    ),
    TutorialStep(
        "headers",
        "Tracks",
        "Each track has Arm, Mute and Solo. Tap its name to rename or delete it. Swipe the track list to scroll; long-press and drag a track to reorder it. Add tracks from the empty lane underneath.",
    ),
    TutorialStep(
        null,
        "Saved on this phone",
        SavedOnPhone + "\n\nSimple mode is in the ⋮ menu, and you can replay this tour from Settings → Scratchpad tutorials.",
    ),
)

/**
 * A Scratchpad tour: a dimmed screen with a spotlight cut around one part of
 * the real UI per step and a card explaining it. Each mode has its own tour,
 * shown once ever (not per song) the first time that mode is used, and again
 * on request from Settings or the ⋮ menu.
 *
 * The Simple tour ends by offering Advanced mode: [onFinish] gets true when
 * the user takes it up.
 */
@Composable
fun ScratchpadTutorial(mode: ScratchpadMode, targets: TutorialTargets, onFinish: (switchToAdvanced: Boolean) -> Unit) {
    val steps = if (mode == ScratchpadMode.SIMPLE) SimpleSteps else AdvancedSteps
    val offersAdvanced = mode == ScratchpadMode.SIMPLE
    var index by remember(mode) { mutableIntStateOf(0) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
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
                // Round targets (the record button) get a round spotlight.
                val corner = if (abs(hole.width - hole.height) < 0.2f * maxOf(hole.width, hole.height)) {
                    minOf(hole.width, hole.height) / 2f
                } else {
                    16f
                }
                drawRoundRect(
                    Color.Transparent, topLeft = hole.topLeft, size = hole.size,
                    cornerRadius = CornerRadius(corner), blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    ring.copy(alpha = fade), topLeft = hole.topLeft, size = hole.size,
                    cornerRadius = CornerRadius(corner), style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // The card goes below the spotlight if there's room, else above; centred without one.
        val heightPx = constraints.maxHeight.toFloat()
        val cardMaxWidth = 420.dp
        val cardY: Float? = spotlight?.let { r ->
            val gap = with(density) { 16.dp.toPx() }
            // The card's real height once measured; a guess for its first frame.
            val estimatedCard = if (cardHeightPx > 0) cardHeightPx.toFloat() else with(density) { 220.dp.toPx() }
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
                Modifier
                    .offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }
                    .width(with(density) { cardWidthPx.toDp() })
            } else {
                Modifier.fillMaxSize().padding(24.dp)
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = cardMaxWidth).onSizeChanged { cardHeightPx = it.height },
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "${index + 1} of ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(step.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(step.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (index < steps.lastIndex) {
                            TextButton(onClick = { onFinish(false) }) { Text("Skip") }
                        }
                        if (index > 0) {
                            TextButton(onClick = { index-- }) { Text("Back") }
                        }
                        if (index == steps.lastIndex && offersAdvanced) {
                            TextButton(onClick = { onFinish(false) }) { Text("Stay simple") }
                        }
                        Button(onClick = { if (index < steps.lastIndex) index++ else onFinish(offersAdvanced) }) {
                            Text(
                                when {
                                    index < steps.lastIndex -> "Next"
                                    offersAdvanced -> "Try it"
                                    else -> "Got it"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
