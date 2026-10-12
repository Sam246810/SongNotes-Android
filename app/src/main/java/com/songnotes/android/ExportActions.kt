package com.songnotes.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.formatSongAsText
import com.songnotes.core.domain.wrapSongToColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Width plain-text exports wrap lines to. A saved line is a whole paragraph,
 * as long as the user typed it, and its chords only line up while the chord
 * line and the lyric line each stay on one line -- if whatever opens the file
 * soft-wraps them, the chords end up over the wrong words. Wrapping ourselves
 * at a width most viewers show unwrapped keeps them aligned; 60 is narrower
 * than a desktop editor or a printed page and wide enough for a tablet or a
 * phone in landscape.
 */
private const val TEXT_EXPORT_COLUMNS = 60

/** The text Copy and .txt share: [song] wrapped to [TEXT_EXPORT_COLUMNS], formatted by [formatSongAsText]. */
private fun songAsText(song: Song): String = formatSongAsText(wrapSongToColumns(song, TEXT_EXPORT_COLUMNS))

/**
 * Copies [song] as plain text (via [songAsText]) to the system
 * clipboard. Android 13+ already shows its own "Copied to clipboard" system
 * toast on [ClipboardManager.setPrimaryClip] -- the explicit [Toast] here is
 * still shown for older versions, which have no such built-in feedback.
 */
fun copySongTextToClipboard(context: Context, song: Song) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label = song.title.ifBlank { "Song" }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, songAsText(song)))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

// US Letter at 72dpi -- PdfDocument's page-size unit is points, so this maps
// directly to Canvas draw coordinates with no further conversion.
private const val PAGE_WIDTH = 612
private const val PAGE_HEIGHT = 792
private const val MARGIN = 54f

private val TitlePaint = TextPaint().apply {
    color = Color.BLACK
    textSize = 20f
    typeface = Typeface.DEFAULT_BOLD
    isAntiAlias = true
}

// Same chord/lyric colors SongEditorScreen.kt uses on-screen in its Warm Light
// theme, so the exported PDF looks like the same document, not a generic reformat.
//
// Deliberately NOT wired to the app theme (Theme.kt), even though the on-screen
// palette now is: a PDF is paper. Cozy Dark's lighter amber and cream exist to
// hold contrast against a dark page, and printing them onto a white one would
// make an export unreadable for anyone whose phone happened to be in dark mode
// when they tapped the button. These stay fixed at the light values.
//
// Both monospace, like the editor: a chord's position is a lyric character
// index, and drawing each chord at that character's column only lines up when
// every lyric character is the same width.
private val ChordPaint = Paint().apply {
    color = Color.parseColor("#B45309")
    textSize = 11f
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    isAntiAlias = true
}
private val LyricPaint = Paint().apply {
    color = Color.parseColor("#2A221B")
    textSize = 12f
    typeface = Typeface.MONOSPACE
    isAntiAlias = true
}

/**
 * Shares [song] as a real `.txt` file, through the same [FileProvider] +
 * share-sheet path as [shareSongAsPdf].
 *
 * Distinct from [copySongTextToClipboard], which produces the same text but
 * only as far as the clipboard. The web app's export menu offers both a
 * clipboard-free `.txt` download and a PDF (`downloadText` / `exportToPdf` in
 * `src/utils/export.js`); Android had the PDF and the clipboard but no way to
 * actually hand someone a text file -- awkward for the exact thing plain text
 * is good at, like mailing a chord sheet to a bandmate or dropping it into a
 * setlist folder. Same [songAsText] output as the clipboard path, so the
 * two can never drift.
 */
fun shareSongAsText(context: Context, song: Song) {
    val title = song.title.ifBlank { "Untitled" }
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(exportsDir, "${sanitizeFilename(title)}.txt")
    file.writeText(songAsText(song))

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Some targets (mail clients especially) read the subject rather than
        // the filename -- without this the share arrives titled "exports".
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share $title"))
}

/**
 * Renders [song] as a paginated PDF (US Letter; title, then each line's
 * chords directly above its lyrics, wrapped to the page width the same way
 * the plain-text export wraps to [TEXT_EXPORT_COLUMNS]) and opens Android's
 * share sheet so the user can save, print, or send it. This is the mobile equivalent of the web app's
 * `window.print()` "print to PDF" flow -- Android has no OS print-preview
 * dialog to lean on the same way, so this builds a real PDF file instead and
 * hands it to whatever app the user picks (Files, a printer app, Drive,
 * messaging, ...) via [FileProvider].
 */
fun shareSongAsPdf(context: Context, song: Song) {
    val file = writeSongPdf(context, song)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${song.title.ifBlank { "song" }}"))
}

private fun writeSongPdf(context: Context, song: Song): File {
    val document = PdfDocument()
    val title = song.title.ifBlank { "Untitled" }
    val contentWidth = PAGE_WIDTH - 2 * MARGIN
    val glyphWidth = LyricPaint.measureText("M")
    val columns = (contentWidth / glyphWidth).toInt()
    val chordLineHeight = ChordPaint.textSize * 1.4f
    val lyricLineHeight = LyricPaint.textSize * 1.4f

    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas = page.canvas
    // A long title wraps rather than running off the page.
    val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, TitlePaint, contentWidth.toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .build()
    canvas.save()
    canvas.translate(MARGIN, MARGIN)
    titleLayout.draw(canvas)
    canvas.restore()
    var y = MARGIN + titleLayout.height + TitlePaint.textSize * 0.5f

    fun newPage() {
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        canvas = page.canvas
        y = MARGIN
    }

    for (line in wrapSongToColumns(song, columns).lines) {
        val hasChords = line.chords.isNotEmpty()
        val hasLyrics = line.lyrics.isNotBlank()
        if (!hasChords && !hasLyrics) {
            y += lyricLineHeight
            continue
        }
        val entryHeight = (if (hasChords) chordLineHeight else 0f) + (if (hasLyrics) lyricLineHeight else 0f)
        if (y + entryHeight > PAGE_HEIGHT - MARGIN) newPage()
        if (hasChords) {
            y += chordLineHeight
            // Each chord at its lyric character's column. One that would touch
            // the chord before it moves right a column past it instead, so two
            // chord names never print as one.
            var nextFree = 0
            for (anchor in line.chords.sortedBy { it.i }) {
                val column = maxOf(anchor.i, nextFree)
                canvas.drawText(anchor.c, MARGIN + column * glyphWidth, y, ChordPaint)
                nextFree = column + anchor.c.length + 1
            }
        }
        if (hasLyrics) {
            y += lyricLineHeight
            canvas.drawText(line.lyrics, MARGIN, y, LyricPaint)
        }
    }
    document.finishPage(page)

    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(exportsDir, "${sanitizeFilename(title)}.pdf")
    FileOutputStream(file).use { document.writeTo(it) }
    document.close()
    return file
}

/** Same sanitization rule as the web app's `sanitizeFilename` in `src/utils/export.js`. */
private fun sanitizeFilename(name: String): String {
    val cleaned = name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim().replace(Regex("\\s+"), "_")
    return cleaned.ifEmpty { "song" }
}
