package com.songnotes.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.ArrangementMath
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackTrackSpec
import com.songnotes.core.audio.PeakPyramid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val kSampleRate = 48000

/** Zoom limits in pixels per beat: a 10-minute take fits a phone screen at the low end; a few ms per pixel at the high end. */
private const val MinPxPerBeat = 0.2
private const val MaxPxPerBeat = 3000.0

private val RulerHeight = 28.dp
private val ClipHeaderHeight = 14.dp
private val ClipCorner = 4.dp
private val EdgeGrabZone = 24.dp
/** Closest grid lines (and snap units) may sit; finer zoom gives finer units, Ableton's adaptive grid. */
private val MinGridSpacing = 12.dp
private val MinRulerLabelSpacing = 48.dp

/** A clip, addressed by position in the project. */
data class ClipRef(val track: Int, val clip: Int)

/** A take in progress, drawn as a growing clip on its track. [peaks] are input levels, one per [framesPerPeak] frames. */
data class RecordingPreview(
    val trackIndex: Int,
    val startFrame: Long,
    val lengthFrames: Long,
    val peaks: List<Float>,
    val framesPerPeak: Int,
)

/**
 * Muted, earthy clip colours in the spirit of Ableton's palette, chosen to sit
 * on parchment and on the dark cozy page alike. Indexed by a track's
 * [MultitrackTrackSpec.colorIndex], so a track keeps its colour when reordered.
 */
private val TrackPalette = listOf(
    Color(0xFFD98E2B), // amber
    Color(0xFF7FA36B), // sage
    Color(0xFFC8664A), // clay
    Color(0xFF5B7FA6), // slate blue
    Color(0xFFC9A93B), // ochre
    Color(0xFF9A5F8E), // plum
)

fun trackColor(colorIndex: Int): Color = TrackPalette[Math.floorMod(colorIndex, TrackPalette.size)]

/**
 * The Scratchpad's arrangement, modelled on Ableton 12's Arrangement view:
 * track headers on the left, one lane per track on the right, a bar/beat
 * ruler on top, clips drawn in their lanes.
 *
 * Scale is a fixed pixels-per-beat zoom, independent of the project's
 * length, so an edit never rescales the view (the old timeline fit the whole
 * project to the screen, so committing a drag that changed the project's
 * length shrank everything). Pinch to zoom around your fingers; drag empty
 * lane space to pan. The ruler's labels thin out and the grid (and snap)
 * gets finer as you zoom, both from [ArrangementMath].
 *
 * Clips: tap to select (and put the insert marker there); drag to move, up
 * or down to another track too; a selected clip shows trim tabs at its
 * edges. The drag preview is computed by the same [ArrangementMath]
 * function the commit uses, so a clip lands exactly where it was shown.
 * Trimming crops, the audio stays fixed in time, because waveforms are
 * drawn at the arrangement's absolute scale rather than stretched to fit.
 *
 * Track headers are the caller's ([trackHeader]); this view lays them out
 * against their lanes, handles reordering by long-press-dragging a header,
 * and adds Ableton's empty "drop area" row below the last track as the place
 * to add a new one.
 */
@Composable
fun ArrangementView(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    bpm: Double,
    beatsPerBar: Int,
    insertFrame: Long,
    onInsertFrameChange: (Long) -> Unit,
    playheadFrame: Long?,
    recording: RecordingPreview?,
    selected: ClipRef?,
    onSelect: (ClipRef?) -> Unit,
    snapEnabled: Boolean,
    editable: Boolean,
    onMoveClip: (from: ClipRef, toTrack: Int, moved: MultitrackClipSpec) -> Unit,
    onTrimClip: (ref: ClipRef, trimmed: MultitrackClipSpec) -> Unit,
    onMoveTrack: (from: Int, to: Int) -> Unit,
    onAddTrack: () -> Unit,
    headerWidth: Dp,
    laneHeight: Dp,
    trackHeader: @Composable (index: Int, track: MultitrackTrackSpec) -> Unit,
    modifier: Modifier = Modifier,
    tutorialTargets: TutorialTargets? = null,
) {
    val density = LocalDensity.current
    val safeBpm = if (bpm > 0.0) bpm else 120.0
    val framesPerBeat = ArrangementMath.framesPerBeat(safeBpm, kSampleRate)
    val laneHeightPx = with(density) { laneHeight.toPx() }

    var pxPerBeat by remember { mutableDoubleStateOf(24.0) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var reorderFrom by remember { mutableStateOf<Int?>(null) }
    var reorderDy by remember { mutableFloatStateOf(0f) }

    val projectEnd = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L } ?: 0L
    val recordingEnd = recording?.let { it.startFrame + it.lengthFrames } ?: 0L
    val endFrame = maxOf(projectEnd, insertFrame, recordingEnd)
    val contentBeats = maxOf(endFrame / framesPerBeat + beatsPerBar * 4, beatsPerBar * 8.0)

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val headerWidthPx = with(density) { headerWidth.toPx() }
        val currentOnMoveTrack by rememberUpdatedState(onMoveTrack)
        val currentTrackCount by rememberUpdatedState(tracks.size)
        val viewportWidthPx = (constraints.maxWidth - headerWidthPx).coerceAtLeast(1f)
        val bodyHeightPx = (constraints.maxHeight - with(density) { RulerHeight.toPx() }).coerceAtLeast(1f)

        fun clampScroll() {
            val contentPx = (contentBeats * pxPerBeat).toFloat()
            scrollX = scrollX.coerceIn(0f, (contentPx - viewportWidthPx * 0.5f).coerceAtLeast(0f))
            val contentH = (tracks.size + 1) * laneHeightPx
            scrollY = scrollY.coerceIn(0f, (contentH - bodyHeightPx).coerceAtLeast(0f))
        }

        fun fitToProject() {
            val beats = maxOf(projectEnd / framesPerBeat * 1.05, beatsPerBar * 4.0)
            pxPerBeat = (viewportWidthPx / beats).coerceIn(MinPxPerBeat, MaxPxPerBeat)
            scrollX = 0f
        }

        // Fit once the project has something in it: the saved project loads
        // after the first frame, so fitting at first composition would fit
        // an empty placeholder.
        // Also refit when the width changes: the Scratchpad rotates to
        // landscape after it first lays out, and the Fold can unfold.
        LaunchedEffect(projectEnd == 0L, viewportWidthPx.roundToInt()) { fitToProject() }

        // Follow the playhead (and a take being recorded) a page at a time,
        // like Ableton's Follow.
        val followFrame = playheadFrame ?: recording?.let { it.startFrame + it.lengthFrames }
        LaunchedEffect(followFrame) {
            if (followFrame == null) return@LaunchedEffect
            val x = (followFrame / framesPerBeat * pxPerBeat).toFloat() - scrollX
            if (x > viewportWidthPx * 0.9f || x < 0f) {
                scrollX = (followFrame / framesPerBeat * pxPerBeat).toFloat() - viewportWidthPx * 0.1f
                clampScroll()
            }
        }

        val gridUnitBeats = ArrangementMath.gridUnitBeats(pxPerBeat, beatsPerBar, with(density) { MinGridSpacing.toPx() }.toDouble())
        val snapUnitFrames = if (snapEnabled) gridUnitBeats * framesPerBeat else 0.0
        val framesPerPx = framesPerBeat / pxPerBeat
        fun frameToX(frame: Long): Float = (frame / framesPerBeat * pxPerBeat).toFloat() - scrollX
        fun xToFrame(x: Float): Long = ((x + scrollX) * framesPerPx).toLong().coerceAtLeast(0L)

        Column(modifier = Modifier.fillMaxSize()) {
            // Ruler row: fit-to-project button over the header column.
            Row(modifier = Modifier.fillMaxWidth().height(RulerHeight)) {
                Box(
                    modifier = Modifier.width(headerWidth).fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    IconButton(onClick = { fitToProject(); clampScroll() }, modifier = Modifier.size(RulerHeight)) {
                        Icon(
                            Icons.Filled.FitScreen,
                            contentDescription = "Zoom to fit",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Ruler(
                    pxPerBeat = pxPerBeat,
                    scrollX = scrollX,
                    beatsPerBar = beatsPerBar,
                    framesPerBeat = framesPerBeat,
                    insertFrame = insertFrame,
                    playheadFrame = playheadFrame,
                    onScrub = { x -> onInsertFrameChange(ArrangementMath.snap(xToFrame(x), snapUnitFrames)) },
                    enabled = editable,
                    modifier = Modifier.weight(1f).fillMaxHeight().tutorialTarget(tutorialTargets, "ruler"),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Track headers, scrolled vertically in step with the lanes.
                Box(
                    modifier = Modifier.width(headerWidth).fillMaxHeight().clipToBounds()
                        .tutorialTarget(tutorialTargets, "headers"),
                ) {
                    tracks.forEachIndexed { index, track ->
                        val isDragging = reorderFrom == index
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(0, (index * laneHeightPx - scrollY + if (isDragging) reorderDy else 0f).roundToInt())
                                }
                                .width(headerWidth)
                                .height(laneHeight)
                                .then(if (isDragging) Modifier.shadow(8.dp) else Modifier)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .pointerInput(index, editable) {
                                    if (!editable) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { reorderFrom = index; reorderDy = 0f },
                                        onDrag = { change, amount -> change.consume(); reorderDy += amount.y },
                                        onDragEnd = {
                                            val to = (index + (reorderDy / laneHeightPx).roundToInt()).coerceIn(0, currentTrackCount - 1)
                                            reorderFrom = null
                                            reorderDy = 0f
                                            if (to != index) currentOnMoveTrack(index, to)
                                        },
                                        onDragCancel = { reorderFrom = null; reorderDy = 0f },
                                    )
                                },
                        ) {
                            trackHeader(index, track)
                            // Ruled line between lanes, the notebook touch.
                            Box(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp)
                                    .background(SongNotesTheme.colors.paperLine),
                            )
                        }
                    }
                    // Ableton's empty area below the last track: the place to add one.
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, (tracks.size * laneHeightPx - scrollY).roundToInt()) }
                            .width(headerWidth)
                            .height(laneHeight)
                            .clickable(enabled = editable, onClick = onAddTrack)
                            .padding(8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                "Add track",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    // Where a dragged header would land.
                    reorderFrom?.let { from ->
                        val to = (from + (reorderDy / laneHeightPx).roundToInt()).coerceIn(0, tracks.lastIndex)
                        val lineY = (if (to > from) (to + 1) * laneHeightPx else to * laneHeightPx) - scrollY
                        Box(
                            Modifier.offset { IntOffset(0, lineY.roundToInt() - 2) }.fillMaxWidth().height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                // Lanes.
                val laneColors = listOf(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.surfaceContainerLowest)
                val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                val ruleColor = SongNotesTheme.colors.paperLine
                val insertColor = MaterialTheme.colorScheme.secondary
                val playheadColor = MaterialTheme.colorScheme.primary
                val addHintColor = MaterialTheme.colorScheme.outlineVariant
                val currentOnSelect by rememberUpdatedState(onSelect)
                val currentOnInsert by rememberUpdatedState(onInsertFrameChange)
                val currentClampScroll by rememberUpdatedState(::clampScroll)
                val currentXToFrame by rememberUpdatedState(::xToFrame)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .tutorialTarget(tutorialTargets, "lanes")
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    val beatAtCentroid = (centroid.x + scrollX) / pxPerBeat
                                    pxPerBeat = (pxPerBeat * zoom).coerceIn(MinPxPerBeat, MaxPxPerBeat)
                                    scrollX = (beatAtCentroid * pxPerBeat - centroid.x).toFloat()
                                }
                                scrollX -= pan.x
                                scrollY -= pan.y
                                currentClampScroll()
                            }
                        }
                        .pointerInput(editable, snapUnitFrames) {
                            detectTapGestures { offset ->
                                currentOnSelect(null)
                                if (editable) currentOnInsert(ArrangementMath.snap(currentXToFrame(offset.x), snapUnitFrames))
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Lane stripes with a faint ruled line under each, like notebook paper.
                        for (i in 0..tracks.size) {
                            val top = i * laneHeightPx - scrollY
                            if (top > size.height || top + laneHeightPx < 0f) continue
                            if (i < tracks.size) {
                                drawRect(laneColors[i % 2], topLeft = Offset(0f, top), size = Size(size.width, laneHeightPx))
                            } else {
                                drawRoundRect(
                                    addHintColor,
                                    topLeft = Offset(8f, top + 8f),
                                    size = Size(size.width - 16f, laneHeightPx - 16f),
                                    cornerRadius = CornerRadius(12f),
                                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                                )
                            }
                            drawLine(ruleColor, Offset(0f, top + laneHeightPx - 1f), Offset(size.width, top + laneHeightPx - 1f))
                        }
                        drawGrid(pxPerBeat, scrollX, gridUnitBeats, beatsPerBar, gridColor, barColor, height = tracks.size * laneHeightPx - scrollY)
                    }

                    // Clips.
                    tracks.forEachIndexed { trackIndex, track ->
                        val color = trackColor(track.colorIndex)
                        val audible = !track.muted && (tracks.none { it.soloed } || track.soloed)
                        track.clips.forEachIndexed { clipIndex, clip ->
                            key(trackIndex, System.identityHashCode(clip.buffer), clip.startFrame, clip.lengthFrames) {
                                ArrangementClip(
                                    engine = engine,
                                    ref = ClipRef(trackIndex, clipIndex),
                                    clip = clip,
                                    trackName = track.name,
                                    color = color,
                                    audible = audible,
                                    selected = selected == ClipRef(trackIndex, clipIndex),
                                    editable = editable,
                                    trackCount = tracks.size,
                                    laneHeightPx = laneHeightPx,
                                    viewportWidthPx = viewportWidthPx,
                                    framesPerPx = framesPerPx,
                                    frameToX = ::frameToX,
                                    scrollY = scrollY,
                                    snapUnitFrames = snapUnitFrames,
                                    onSelect = { ref, frame ->
                                        onSelect(ref)
                                        onInsertFrameChange(ArrangementMath.snap(frame, snapUnitFrames))
                                    },
                                    onMove = onMoveClip,
                                    onTrim = onTrimClip,
                                )
                            }
                        }
                    }

                    recording?.let { rec ->
                        RecordingClip(rec, laneHeightPx, scrollY, framesPerPx, ::frameToX, viewportWidthPx)
                    }

                    // Insert marker and playhead.
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val insertX = frameToX(insertFrame)
                        if (insertX in 0f..size.width) {
                            drawLine(
                                insertColor, Offset(insertX, 0f), Offset(insertX, size.height), strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
                            )
                        }
                        playheadFrame?.let { frame ->
                            val x = frameToX(frame)
                            if (x in 0f..size.width) drawLine(playheadColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                        }
                    }
                }
            }
        }
    }
}

/** Beat/bar grid lines at the current adaptive [unitBeats], bars heavier. */
private fun DrawScope.drawGrid(
    pxPerBeat: Double,
    scrollX: Float,
    unitBeats: Double,
    beatsPerBar: Int,
    gridColor: Color,
    barColor: Color,
    height: Float,
) {
    if (height <= 0f) return
    val unitPx = unitBeats * pxPerBeat
    val first = floor(scrollX / unitPx).toLong()
    val last = ceil((scrollX + size.width) / unitPx).toLong()
    for (i in first..last) {
        val beat = i * unitBeats
        val x = (beat * pxPerBeat - scrollX).toFloat()
        val isBar = abs(beat / beatsPerBar - Math.round(beat / beatsPerBar)) < 1e-9
        drawLine(if (isBar) barColor else gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = if (isBar) 1.5f else 1f)
    }
}

/**
 * Bar/beat ruler. Labels thin out ([ArrangementMath.rulerLabelStepBars]) so
 * numbers never pile up on a long project; zoomed in far enough it labels
 * beats too, Ableton's "1.2" style. Tap or drag to move the insert marker.
 */
@Composable
private fun Ruler(
    pxPerBeat: Double,
    scrollX: Float,
    beatsPerBar: Int,
    framesPerBeat: Double,
    insertFrame: Long,
    playheadFrame: Long?,
    onScrub: (x: Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val insertColor = MaterialTheme.colorScheme.secondary
    val playheadColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val minLabelPx = with(density) { MinRulerLabelSpacing.toPx() }.toDouble()
    val currentOnScrub by rememberUpdatedState(onScrub)
    Canvas(
        modifier = modifier
            .background(background)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { currentOnScrub(it.x) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ -> change.consume(); currentOnScrub(change.position.x) }
            },
    ) {
        val bar = beatsPerBar.coerceAtLeast(1)
        val pxPerBar = pxPerBeat * bar
        val labelStep = ArrangementMath.rulerLabelStepBars(pxPerBeat, bar, minLabelPx)
        val labelBeats = pxPerBeat >= minLabelPx // label every beat ("1.2") once they're far enough apart
        val style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = labelColor)

        val firstBar = floor(scrollX / pxPerBar).toLong().coerceAtLeast(0L)
        val lastBar = ceil((scrollX + size.width) / pxPerBar).toLong()
        // Unlabelled bar ticks only where bars are far enough apart to read
        // as ticks; zoomed out, a tick every few pixels is just grey noise.
        val tickStep = ArrangementMath.rulerLabelStepBars(pxPerBeat, bar, 6.0 * density.density).toLong()
        for (barIndex in firstBar..lastBar) {
            val x = (barIndex * pxPerBar - scrollX).toFloat()
            val labelled = barIndex % labelStep == 0L
            if (!labelled && barIndex % tickStep != 0L) continue
            drawLine(tickColor, Offset(x, if (labelled) size.height * 0.35f else size.height * 0.65f), Offset(x, size.height), strokeWidth = 1.5f)
            if (labelled) {
                drawText(textMeasurer.measure("${barIndex + 1}", style), topLeft = Offset(x + 3f, 1f))
            }
            if (pxPerBeat >= 8.0 * density.density) {
                for (beat in 1 until bar) {
                    val bx = (x + beat * pxPerBeat).toFloat()
                    drawLine(tickColor.copy(alpha = tickColor.alpha * 0.6f), Offset(bx, size.height * 0.75f), Offset(bx, size.height))
                    if (labelBeats) drawText(textMeasurer.measure("${barIndex + 1}.${beat + 1}", style.copy(fontWeight = FontWeight.Normal)), topLeft = Offset(bx + 3f, 1f))
                }
            }
        }
        // Insert marker flag and playhead triangle in the ruler.
        val insertX = (insertFrame / framesPerBeat * pxPerBeat).toFloat() - scrollX
        if (insertX in -8f..size.width + 8f) drawMarkerFlag(insertX, insertColor)
        playheadFrame?.let {
            val x = (it / framesPerBeat * pxPerBeat).toFloat() - scrollX
            if (x in -8f..size.width + 8f) drawMarkerFlag(x, playheadColor)
        }
    }
}

private fun DrawScope.drawMarkerFlag(x: Float, color: Color) {
    val w = 7.dp.toPx()
    val path = Path().apply {
        moveTo(x - w, size.height - w * 1.4f)
        lineTo(x + w, size.height - w * 1.4f)
        lineTo(x, size.height)
        close()
    }
    drawPath(path, color)
}

private enum class ClipGesture { Move, TrimLeft, TrimRight }

@Composable
private fun ArrangementClip(
    engine: AudioEngine,
    ref: ClipRef,
    clip: MultitrackClipSpec,
    trackName: String,
    color: Color,
    audible: Boolean,
    selected: Boolean,
    editable: Boolean,
    trackCount: Int,
    laneHeightPx: Float,
    viewportWidthPx: Float,
    framesPerPx: Double,
    frameToX: (Long) -> Float,
    scrollY: Float,
    snapUnitFrames: Double,
    onSelect: (ClipRef, Long) -> Unit,
    onMove: (ClipRef, Int, MultitrackClipSpec) -> Unit,
    onTrim: (ClipRef, MultitrackClipSpec) -> Unit,
) {
    val pyramid = remember(clip.buffer) { engine.buildPeakPyramid(clip.buffer) }
    val density = LocalDensity.current
    var gesture by remember { mutableStateOf<ClipGesture?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }

    fun preview(): MultitrackClipSpec {
        val deltaFrames = (dragDx * framesPerPx).toLong()
        return when (gesture) {
            ClipGesture.Move -> ArrangementMath.moved(clip, deltaFrames, snapUnitFrames)
            ClipGesture.TrimLeft -> ArrangementMath.trimmedLeft(clip, deltaFrames, snapUnitFrames)
            ClipGesture.TrimRight -> ArrangementMath.trimmedRight(clip, deltaFrames, snapUnitFrames)
            null -> clip
        }
    }
    fun targetTrack(count: Int): Int =
        if (gesture == ClipGesture.Move) (ref.track + (dragDy / laneHeightPx).roundToInt()).coerceIn(0, count - 1) else ref.track

    val shown = preview()
    val shownTrack = targetTrack(trackCount)
    val fullLeft = frameToX(shown.startFrame)
    val fullRight = frameToX(shown.startFrame + shown.lengthFrames)
    if (fullRight < -40f || fullLeft > viewportWidthPx + 40f) return
    // Only the on-screen part of a clip is laid out: a long take at deep
    // zoom would otherwise be millions of pixels wide.
    val left = fullLeft.coerceAtLeast(-40f)
    val right = fullRight.coerceAtMost(viewportWidthPx + 40f).coerceAtLeast(left + 2f)
    val top = shownTrack * laneHeightPx - scrollY + 3f
    val height = laneHeightPx - 6f
    val grabPx = with(density) { EdgeGrabZone.toPx() }.coerceAtMost((fullRight - fullLeft) / 3f)

    // Gesture handlers outlive a single composition (scrolling recomposes
    // this clip without restarting them), so they read these, never the
    // captured values, or a drag after a scroll would grab the wrong edge
    // and commit against a stale project.
    val geometry by rememberUpdatedState(Triple(left, fullLeft, fullRight))
    val currentGrabPx by rememberUpdatedState(grabPx)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentTrackCount by rememberUpdatedState(trackCount)

    val onSurface = MaterialTheme.colorScheme.onSurface
    // Ableton's look: a light body in the track colour with a dark waveform,
    // and a slightly deeper header strip. Readable on both paper themes.
    val bodyColor = lerp(color, Color.White, 0.35f)
    val headerColor = lerp(color, Color.Black, 0.12f)
    val waveColor = Color(0xFF1C1612).copy(alpha = 0.85f)
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left).toDp() }, with(density) { height.toDp() })
            .alpha(if (audible) 1f else 0.45f)
            .pointerInput(ref, editable, selected, snapUnitFrames, framesPerPx) {
                if (!editable) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        val (l, fl, fr) = geometry
                        val xInClip = start.x + (l - fl)
                        gesture = when {
                            selected && xInClip < currentGrabPx -> ClipGesture.TrimLeft
                            selected && xInClip > (fr - fl) - currentGrabPx -> ClipGesture.TrimRight
                            else -> ClipGesture.Move
                        }
                        dragDx = 0f
                        dragDy = 0f
                    },
                    onDrag = { change, amount -> change.consume(); dragDx += amount.x; dragDy += amount.y },
                    onDragEnd = {
                        val result = preview()
                        val toTrack = targetTrack(currentTrackCount)
                        val kind = gesture
                        gesture = null
                        dragDx = 0f
                        dragDy = 0f
                        when (kind) {
                            ClipGesture.Move -> if (result != clip || toTrack != ref.track) currentOnMove(ref, toTrack, result)
                            ClipGesture.TrimLeft, ClipGesture.TrimRight -> if (result != clip) currentOnTrim(ref, result)
                            null -> Unit
                        }
                    },
                    onDragCancel = { gesture = null; dragDx = 0f; dragDy = 0f },
                )
            }
            .pointerInput(ref, framesPerPx) {
                detectTapGestures { offset ->
                    val (l, fl, _) = geometry
                    val frame = clip.startFrame + ((offset.x + (l - fl)) * framesPerPx).toLong()
                    currentOnSelect(ref, frame)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val headerPx = ClipHeaderHeight.toPx()
            val radius = CornerRadius(ClipCorner.toPx())
            drawRoundRect(bodyColor, cornerRadius = radius)
            drawRoundRect(headerColor, size = Size(size.width, headerPx), cornerRadius = radius)
            drawRect(headerColor, topLeft = Offset(0f, headerPx / 2f), size = Size(size.width, headerPx / 2f))
            drawText(
                textMeasurer.measure(
                    trackName,
                    TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    maxLines = 1,
                ),
                topLeft = Offset((fullLeft - left).coerceAtLeast(0f) + 4f, 0f),
            )
            drawClipWaveform(
                clip = shown,
                pyramid = pyramid,
                firstFrameInClip = ((left - fullLeft) * framesPerPx).toLong(),
                framesPerPx = framesPerPx,
                top = headerPx + 2f,
                height = size.height - headerPx - 4f,
                color = waveColor,
            )
            if (selected) {
                drawRoundRect(onSurface, cornerRadius = radius, style = Stroke(width = 2.dp.toPx()))
                // Trim tabs, only where the real edge is on screen.
                val tabW = 6.dp.toPx()
                val tabH = size.height * 0.45f
                val tabTop = headerPx + (size.height - headerPx - tabH) / 2f
                if (fullLeft >= left - 1f) {
                    drawRoundRect(onSurface, topLeft = Offset(2f, tabTop), size = Size(tabW, tabH), cornerRadius = CornerRadius(tabW))
                }
                if (fullRight <= right + 1f) {
                    drawRoundRect(onSurface, topLeft = Offset(size.width - tabW - 2f, tabTop), size = Size(tabW, tabH), cornerRadius = CornerRadius(tabW))
                }
            }
        }
    }
}

/**
 * Min/max waveform at the arrangement's absolute scale, starting
 * [firstFrameInClip] frames into [clip]. Uses the peak pyramid when a pixel
 * covers at least one pyramid peak, else reads the samples directly (deep
 * zoom), so it's sharp at every zoom and never stretched.
 */
private fun DrawScope.drawClipWaveform(
    clip: MultitrackClipSpec,
    pyramid: PeakPyramid,
    firstFrameInClip: Long,
    framesPerPx: Double,
    top: Float,
    height: Float,
    color: Color,
) {
    if (height <= 0f || clip.lengthFrames <= 0L) return
    val mid = top + height / 2f
    val half = height / 2f
    val level = if (pyramid.levels.isNotEmpty()) pyramid.selectLevelForZoom(framesPerPx) else null
    val usePyramid = level != null && level.samplesPerPeak <= framesPerPx
    val columns = size.width.toInt()
    for (col in 0 until columns) {
        val f0 = firstFrameInClip + (col * framesPerPx).toLong()
        if (f0 >= clip.lengthFrames) break
        val f1 = (firstFrameInClip + ((col + 1) * framesPerPx).toLong()).coerceIn(f0 + 1, clip.lengthFrames)
        val b0 = clip.bufferOffsetFrames + f0
        val b1 = clip.bufferOffsetFrames + f1
        var lo = 0f
        var hi = 0f
        if (usePyramid) {
            val spp = level!!.samplesPerPeak
            val p0 = (b0 / spp).toInt().coerceIn(0, level.peakCount - 1)
            val p1 = ((b1 - 1) / spp).toInt().coerceIn(p0, level.peakCount - 1)
            for (p in p0..p1) {
                lo = minOf(lo, level.mins[p])
                hi = maxOf(hi, level.maxes[p])
            }
        } else {
            val end = b1.coerceAtMost(clip.buffer.size.toLong()).toInt()
            for (i in b0.toInt() until end) {
                val s = clip.buffer[i]
                if (s < lo) lo = s
                if (s > hi) hi = s
            }
        }
        val yTop = mid - hi.coerceIn(-1f, 1f) * half
        val yBottom = mid - lo.coerceIn(-1f, 1f) * half
        drawRect(color, topLeft = Offset(col.toFloat(), yTop), size = Size(1f, (yBottom - yTop).coerceAtLeast(1f)))
    }
}

/** The take being recorded: a red clip that grows from the insert marker, drawn from live input levels. */
@Composable
private fun RecordingClip(
    rec: RecordingPreview,
    laneHeightPx: Float,
    scrollY: Float,
    framesPerPx: Double,
    frameToX: (Long) -> Float,
    viewportWidthPx: Float,
) {
    val density = LocalDensity.current
    val red = RecordRed
    val fullLeft = frameToX(rec.startFrame)
    val fullRight = frameToX(rec.startFrame + rec.lengthFrames).coerceAtLeast(fullLeft + 2f)
    if (fullRight < 0f || fullLeft > viewportWidthPx) return
    val left = fullLeft.coerceAtLeast(-40f)
    val right = fullRight.coerceAtMost(viewportWidthPx + 40f)
    val top = rec.trackIndex * laneHeightPx - scrollY + 3f
    val height = laneHeightPx - 6f
    Canvas(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left).coerceAtLeast(1f).toDp() }, with(density) { height.toDp() }),
    ) {
        val headerPx = ClipHeaderHeight.toPx()
        val radius = CornerRadius(ClipCorner.toPx())
        drawRoundRect(red.copy(alpha = 0.22f), cornerRadius = radius)
        drawRoundRect(red, size = Size(size.width, headerPx), cornerRadius = radius)
        drawRect(red, topLeft = Offset(0f, headerPx / 2f), size = Size(size.width, headerPx / 2f))
        val mid = headerPx + (size.height - headerPx) / 2f
        val half = (size.height - headerPx) / 2f - 2f
        val pxPerPeak = (rec.framesPerPeak / framesPerPx).toFloat()
        val offset = left - fullLeft
        rec.peaks.forEachIndexed { i, peak ->
            val x = i * pxPerPeak - offset
            if (x < -pxPerPeak || x > size.width) return@forEachIndexed
            val h = (peak.coerceIn(0f, 1f) * half).coerceAtLeast(1f)
            drawRect(red, topLeft = Offset(x, mid - h), size = Size(pxPerPeak.coerceAtLeast(1f) * 0.8f, h * 2f))
        }
    }
}
