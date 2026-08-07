package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine

/**
 * Phase 9 piano — Compose keyboard driving [AudioEngine]'s piano voices
 * (native, sample-based, additive over every engine mode — see
 * `docs/handoff/PHASE-09.md` for why this isn't its own `EngineMode`).
 * Structured from the web app's `PianoPanel.jsx` key layout (`KEY_MAP`):
 * 1.5 octaves (C through the next octave's F), octave-shift controls,
 * same note-name/offset grid.
 *
 * Attribution: the piano samples themselves are Salamander Grand Piano
 * (Alexander Holm, CC BY 3.0) — see `core/audio/src/main/assets/piano/NOTICE.md`.
 */
@Composable
fun PianoScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var octave by remember { mutableStateOf(4) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        isLoaded = engine.loadPianoSamples(context)
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1B1B1F))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("Done", color = Color(0xFFB45309)) }
            Text(
                when {
                    isLoading -> "Loading piano samples…"
                    isLoaded -> "Piano — Octave $octave"
                    else -> "Failed to load piano samples"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                TextButton(onClick = { octave = (octave - 1).coerceIn(0, 6) }) { Text("Oct −", color = Color.White) }
                TextButton(onClick = { octave = (octave + 1).coerceIn(0, 6) }) { Text("Oct +", color = Color.White) }
            }
        }

        Text(
            "Salamander Grand Piano by Alexander Holm, CC BY 3.0",
            color = Color(0xFF8892B0),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.weight(1f))

        PianoKeyboard(
            octave = octave,
            enabled = isLoaded,
            onNoteOn = engine::pianoNoteOn,
            onNoteOff = engine::pianoNoteOff,
            modifier = Modifier.fillMaxWidth().height(260.dp).navigationBarsPadding(),
        )
    }
}

private data class PianoKeySpec(val name: String, val offset: Int, val isBlack: Boolean)

// Matches the web app's KEY_MAP exactly: 1.5 octaves, C through the next
// octave's F, 11 white keys + 7 black keys.
private val KEY_MAP = listOf(
    PianoKeySpec("C", 0, false), PianoKeySpec("C#", 1, true),
    PianoKeySpec("D", 2, false), PianoKeySpec("D#", 3, true),
    PianoKeySpec("E", 4, false),
    PianoKeySpec("F", 5, false), PianoKeySpec("F#", 6, true),
    PianoKeySpec("G", 7, false), PianoKeySpec("G#", 8, true),
    PianoKeySpec("A", 9, false), PianoKeySpec("A#", 10, true),
    PianoKeySpec("B", 11, false),
    PianoKeySpec("C+", 12, false), PianoKeySpec("C#+", 13, true),
    PianoKeySpec("D+", 14, false), PianoKeySpec("D#+", 15, true),
    PianoKeySpec("E+", 16, false),
    PianoKeySpec("F+", 17, false),
)

private val WHITE_KEYS = KEY_MAP.filter { !it.isBlack }

/** For each black key, how many white keys precede it in [KEY_MAP] order — the standard "sits between these two white keys" positioning rule. */
private val BLACK_KEY_PRECEDING_WHITE_INDEX: Map<PianoKeySpec, Int> = run {
    val map = mutableMapOf<PianoKeySpec, Int>()
    var whiteCount = 0
    for (spec in KEY_MAP) {
        if (spec.isBlack) map[spec] = whiteCount else whiteCount++
    }
    map
}

/**
 * White keys laid out edge-to-edge in a [Row]; black keys absolutely
 * positioned on top via [BoxWithConstraints]'s measured width, standard
 * piano-layout math (a black key's left edge sits at
 * `(precedingWhiteKeyIndex + 1) * whiteKeyWidth - blackKeyWidth / 2`).
 * Each key gets its own [pointerInput] scope — Compose tracks each one's
 * pointer independently, so multiple keys held at once (a chord) just
 * works without any manual multi-pointer bookkeeping.
 */
@Composable
private fun PianoKeyboard(
    octave: Int,
    enabled: Boolean,
    onNoteOn: (Int) -> Boolean,
    onNoteOff: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val whiteKeyWidth = maxWidth / WHITE_KEYS.size
        val blackKeyWidth = whiteKeyWidth * 0.6f

        Row(modifier = Modifier.fillMaxSize()) {
            for (spec in WHITE_KEYS) {
                val midi = (octave + 1) * 12 + spec.offset
                PianoKey(
                    isBlack = false,
                    enabled = enabled,
                    onNoteOn = { onNoteOn(midi) },
                    onNoteOff = { onNoteOff(midi) },
                    modifier = Modifier.width(whiteKeyWidth).fillMaxHeight(),
                )
            }
        }

        for (spec in KEY_MAP) {
            if (!spec.isBlack) continue
            val precedingWhiteIndex = BLACK_KEY_PRECEDING_WHITE_INDEX.getValue(spec)
            val midi = (octave + 1) * 12 + spec.offset
            PianoKey(
                isBlack = true,
                enabled = enabled,
                onNoteOn = { onNoteOn(midi) },
                onNoteOff = { onNoteOff(midi) },
                modifier = Modifier
                    .offset(x = whiteKeyWidth * (precedingWhiteIndex + 1) - blackKeyWidth / 2)
                    .width(blackKeyWidth)
                    .fillMaxHeight(0.6f),
            )
        }
    }
}

@Composable
private fun PianoKey(
    isBlack: Boolean,
    enabled: Boolean,
    onNoteOn: () -> Unit,
    onNoteOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
    Box(
        modifier = modifier
            .background(
                color = when {
                    isPressed -> Color(0xFFB45309)
                    isBlack -> Color(0xFF1B1B1F)
                    else -> Color.White
                },
                shape = shape,
            )
            .border(1.dp, Color(0xFF8892B0), shape)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                onNoteOn()
                                tryAwaitRelease()
                                isPressed = false
                                onNoteOff()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    )
}
