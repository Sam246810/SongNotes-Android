package com.songnotes.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songnotes.core.domain.ChordVoicing

/**
 * Compose [Canvas] port of the web app's `ChordDiagram.jsx` SVG rendering --
 * same layout constants (down to the pixel/dp), same colors, same
 * row/barre/dot geometry math, just Canvas draw calls instead of SVG
 * elements. Deliberately excludes the web version's editing UI (the
 * text-input voicing editor, "+ Add voicing"/"Edit voicing" buttons) --
 * that's `ChordVoicingPopup` in `SongEditorScreen.kt`, which wraps this
 * pure-rendering composable the same way `ChordDiagram.jsx` wraps its own
 * `<svg>` inside a popup `<div>` with editing controls beside it.
 */

private const val W = 110f
private const val H = 155f
private const val LEFT = 15f
private const val RIGHT = 95f
private const val STR_GAP = (RIGHT - LEFT) / 5f
private const val NUT_Y = 28f
private const val FRET_GAP = 26f
private const val FRETS_SHOWN = 4
private const val MARKER_Y = 14f
private const val DOT_R = 7f
private const val BARRE_R = 7f

private val FretY = FloatArray(FRETS_SHOWN + 1) { NUT_Y + it * FRET_GAP }
private fun dotY(row: Int): Float = FretY[row - 1] + FRET_GAP / 2f
private fun strX(s: Int): Float = LEFT + s * STR_GAP

private val DotColor = Color(0xFFA78BFA)
private val BarreColor = Color(0xFFA78BFA)
private val OpenColor = Color(0xFF6EE7B7)
private val MutedColor = Color(0xFFF87171)
private val NutColor = Color(0xFFE8EAF6)
private val FretColor = Color(0xFF353D57)
private val StringColor = Color(0xFF353D57)
private val LabelColor = Color(0xFF8892B0)
private val FretNumColor = Color(0xFFE8EAF6)

private val StringNames = listOf("E", "A", "D", "G", "B", "e")

/** Converts an absolute fret to a 1–4 display row, leaving 0 (open)/-1 (muted) as-is. */
private fun toRow(f: Int, baseFret: Int): Int = if (f <= 0) f else f - baseFret + 1

@Composable
fun ChordDiagram(voicing: ChordVoicing, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.size(W.dp, H.dp)) {
        val scaleX = size.width / W
        val scaleY = size.height / H
        drawStrings(scaleX, scaleY)
        drawFretLines(voicing.baseFret, scaleX, scaleY)
        voicing.barre?.let { drawBarre(it.fret, it.fromString, it.toString, voicing.baseFret, scaleX, scaleY) }
        drawFingerDots(voicing, scaleX, scaleY)
        drawOpenMutedMarkers(voicing.frets, textMeasurer, scaleX, scaleY)
        if (voicing.baseFret > 1) drawFretNumberLabel(voicing.baseFret, textMeasurer, scaleX, scaleY)
        drawStringNames(textMeasurer, scaleX, scaleY)
    }
}

private fun DrawScope.drawStrings(scaleX: Float, scaleY: Float) {
    for (s in 0..5) {
        val x = strX(s) * scaleX
        drawLine(
            color = StringColor,
            start = Offset(x, NUT_Y * scaleY),
            end = Offset(x, FretY[FRETS_SHOWN] * scaleY),
            strokeWidth = 1.5f,
        )
    }
}

private fun DrawScope.drawFretLines(baseFret: Int, scaleX: Float, scaleY: Float) {
    for (i in FretY.indices) {
        val isNut = i == 0 && baseFret == 1
        drawLine(
            color = if (isNut) NutColor else FretColor,
            start = Offset(LEFT * scaleX, FretY[i] * scaleY),
            end = Offset(RIGHT * scaleX, FretY[i] * scaleY),
            strokeWidth = if (isNut) 3f else 1.5f,
        )
    }
}

private fun DrawScope.drawBarre(fret: Int, fromString: Int, toString: Int, baseFret: Int, scaleX: Float, scaleY: Float) {
    val row = toRow(fret, baseFret)
    if (row < 1 || row > FRETS_SHOWN) return
    val x1 = strX(fromString) * scaleX
    val x2 = strX(toString) * scaleX
    val cy = dotY(row) * scaleY
    val r = BARRE_R * scaleY
    drawRoundRect(
        color = BarreColor,
        topLeft = Offset(x1, cy - r),
        size = androidx.compose.ui.geometry.Size(x2 - x1, r * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
}

private fun DrawScope.drawFingerDots(voicing: ChordVoicing, scaleX: Float, scaleY: Float) {
    val barre = voicing.barre
    voicing.frets.forEachIndexed { s, f ->
        val row = toRow(f, voicing.baseFret)
        if (f <= 0 || row < 1 || row > FRETS_SHOWN) return@forEachIndexed
        if (barre != null && row == toRow(barre.fret, voicing.baseFret) && s >= barre.fromString && s <= barre.toString) return@forEachIndexed
        drawCircle(color = DotColor, radius = DOT_R * scaleY, center = Offset(strX(s) * scaleX, dotY(row) * scaleY))
    }
}

private fun DrawScope.drawCenteredText(measurer: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
}

private fun DrawScope.drawOpenMutedMarkers(frets: List<Int>, measurer: TextMeasurer, scaleX: Float, scaleY: Float) {
    frets.forEachIndexed { s, f ->
        val x = strX(s) * scaleX
        val y = MARKER_Y * scaleY
        when (f) {
            0 -> drawCenteredText(measurer, "O", x, y, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpenColor))
            -1 -> drawCenteredText(measurer, "✕", x, y, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedColor))
        }
    }
}

private fun DrawScope.drawFretNumberLabel(baseFret: Int, measurer: TextMeasurer, scaleX: Float, scaleY: Float) {
    val layout = measurer.measure("${baseFret}fr", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = FretNumColor))
    drawText(layout, topLeft = Offset((RIGHT + 8) * scaleX, dotY(1) * scaleY - layout.size.height / 2f))
}

private fun DrawScope.drawStringNames(measurer: TextMeasurer, scaleX: Float, scaleY: Float) {
    StringNames.forEachIndexed { s, name ->
        val layout = measurer.measure(name, TextStyle(fontSize = 9.sp, color = LabelColor))
        val x = strX(s) * scaleX - layout.size.width / 2f
        val y = (H - 4) * scaleY - layout.size.height
        drawText(layout, topLeft = Offset(x, y))
    }
}
