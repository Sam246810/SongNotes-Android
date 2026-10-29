package com.songnotes.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
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

/**
 * The animated cue for a step, within its target [r]: a pulsing touch point
 * for a tap, a touch point sliding along a drag, or the left and right
 * edges flashing (a clip's trim tabs). [t] runs 0..1 and repeats.
 */
private fun DrawScope.drawTourHint(hint: TourHint, r: Rect, t: Float, color: Color) {
    val finger = Color.White
    val radius = 14.dp.toPx()
    fun touch(at: Offset, alpha: Float) {
        drawCircle(color.copy(alpha = 0.35f * alpha), radius = radius * 1.8f, center = at)
        drawCircle(finger.copy(alpha = 0.9f * alpha), radius = radius, center = at)
        drawCircle(color.copy(alpha = alpha), radius = radius, center = at, style = Stroke(width = 3.dp.toPx()))
    }
    when (hint) {
        TourHint.None -> Unit
        TourHint.Tap -> {
            // Press, hold, lift: a ripple growing out from the centre.
            val press = if (t < 0.5f) t * 2f else 1f
            drawCircle(color.copy(alpha = 0.5f * (1f - press)), radius = radius * (1f + 2f * press), center = r.center)
            touch(r.center, 1f)
        }
        TourHint.DragSideways -> {
            val travel = (t * 1.25f).coerceAtMost(1f) // short pause at the end
            val x = r.left + r.width * (0.2f + 0.6f * travel)
            val y = r.center.y
            drawLine(color.copy(alpha = 0.5f), Offset(r.left + r.width * 0.2f, y), Offset(x, y), strokeWidth = 4.dp.toPx())
            touch(Offset(x, y), 1f - (t - 0.8f).coerceAtLeast(0f) * 5f)
        }
        TourHint.DragDown -> {
            val travel = (t * 1.25f).coerceAtMost(1f)
            val x = r.center.x
            val y = r.top + r.height * (0.2f + 0.5f * travel)
            drawLine(color.copy(alpha = 0.5f), Offset(x, r.top + r.height * 0.2f), Offset(x, y), strokeWidth = 4.dp.toPx())
            touch(Offset(x, y), 1f - (t - 0.8f).coerceAtLeast(0f) * 5f)
        }
        TourHint.Edges -> {
            // Flash both edges, twice a cycle.
            val flash = (kotlin.math.sin(t * 4f * Math.PI.toFloat()) * 0.5f + 0.5f)
            val w = 10.dp.toPx()
            val inset = 2.dp.toPx()
            val top = r.top + r.height * 0.15f
            val h = r.height * 0.7f
            listOf(r.left + inset, r.right - inset - w).forEach { x ->
                drawRoundRect(color.copy(alpha = 0.25f + 0.75f * flash), topLeft = Offset(x, top), size = Size(w, h), cornerRadius = CornerRadius(w / 2f))
            }
        }
    }
}

/** Registers this element's on-screen bounds under [key] for the tutorial's spotlight. */
fun Modifier.tutorialTarget(targets: TutorialTargets?, key: String): Modifier =
    if (targets == null) this else onGloballyPositioned { targets.bounds[key] = it.boundsInWindow() }

/** An animated cue drawn over a step's spotlight, showing the gesture the card describes. */
private enum class TourHint { None, Tap, DragSideways, DragDown, Edges }

private data class TutorialStep(val target: String?, val title: String, val body: String, val hint: TourHint = TourHint.None)

private const val SavedOnPhone =
    "Every change saves automatically, on this phone and offline, as this song's own demo. You don't need an " +
        "account. Demos aren't on the web app yet; that's coming soon."

/** Simple mode's tour; its last step offers Advanced mode. */
private val SimpleSteps = listOf(
    TutorialStep(
        "simple_record",
        "Record a voice note",
        "Tap the big red button and sing, hum or play. Tap it again to stop. Record again and it carries on from the playhead: at the end unless you move it, and over what's there if you move it back (Undo brings it back). For more tracks, use Advanced.",
        TourHint.Tap,
    ),
    TutorialStep(
        "simple_metronome",
        "Keep time",
        "Tap the metronome to switch the click on or off, even mid-take. Tap the tempo to change it; the little ▾ sets a count-in before you start.",
        TourHint.Tap,
    ),
    TutorialStep(
        "voice_note_wave",
        "Scrub through your note",
        "This is your voice note (here, an example; yours will look like this). Drag along it, or along the bar under the clock, to move the playhead: that's where play, and your next take, start.",
        TourHint.DragSideways,
    ),
    TutorialStep(
        "voice_note_actions",
        "Trim or start over",
        "Trim cuts the note's start or end; Delete starts over. Undo brings anything back. Tracks you add in Advanced can play along: tap the chip under the note.",
        TourHint.Tap,
    ),
    TutorialStep(null, "Saved on this phone", SavedOnPhone),
    TutorialStep(
        null,
        "Want more control?",
        "Advanced mode works like a small DAW: bars and beats, a snap grid, and moving, trimming and splitting clips across tracks. Both modes edit the same demo, so you can switch any time with the Simple | Advanced switch at the top.\n\nReplay these tours from Settings → Scratchpad tutorials.",
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
        TourHint.Tap,
    ),
    TutorialStep(
        "ruler",
        "Move the insert marker",
        "The insert marker is grabbed up here, in the ruler: tap anywhere along it, or put your finger on the ruler and drag, to move it. Play starts from the marker and new takes land on it. Pinch to zoom; drag empty space to scroll. The closer you zoom, the finer clips snap.",
        TourHint.DragSideways,
    ),
    TutorialStep(
        "first_clip",
        "This is a clip",
        "Each take is a clip (this one's an example; yours will look the same). Tap a clip to select it, then drag it to move it, even onto another track.",
        TourHint.DragSideways,
    ),
    TutorialStep(
        "first_clip",
        "Trim from the edges",
        "A selected clip has a tab at each edge (flashing here). Drag a tab inward to trim that end; the audio stays put, you're just hiding part of it. Split, Duplicate and Delete appear while a clip is selected, and Undo is always in the top bar.",
        TourHint.Edges,
    ),
    TutorialStep(
        "headers",
        "Tracks",
        "Each track has Arm, Mute and Solo (mute and solo work mid-playback). Tap its name to rename or delete it. Swipe the track list to scroll; long-press and drag a track to reorder it. Add tracks from the empty lane underneath.",
        TourHint.DragDown,
    ),
    TutorialStep(
        null,
        "Saved on this phone",
        SavedOnPhone + "\n\nThe Simple | Advanced switch at the top takes you back to Simple mode, and you can replay this tour from Settings → Scratchpad tutorials.",
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
fun ScratchpadTutorial(
    mode: ScratchpadMode,
    targets: TutorialTargets,
    onFinish: (switchToAdvanced: Boolean) -> Unit,
    /** Each step's spotlight key as it shows, so the screen can stage it (e.g. select the example clip). */
    onStep: (target: String?) -> Unit = {},
) {
    val steps = if (mode == ScratchpadMode.SIMPLE) SimpleSteps else AdvancedSteps
    val offersAdvanced = mode == ScratchpadMode.SIMPLE
    var index by remember(mode) { mutableIntStateOf(0) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]
    LaunchedEffect(step) { onStep(step.target) }
    val hintAnim = rememberInfiniteTransition(label = "hint")
    val hintT by hintAnim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "hintT",
    )
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
                drawTourHint(step.hint, r, hintT, ring)
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
