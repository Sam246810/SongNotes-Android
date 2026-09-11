package com.songnotes.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.anchorsToChordsLine
import com.songnotes.core.domain.formatSongAsText
import java.io.File
import java.io.FileOutputStream

/**
 * Copies [song] as plain text (via [formatSongAsText]) to the system
 * clipboard. Android 13+ already shows its own "Copied to clipboard" system
 * toast on [ClipboardManager.setPrimaryClip] -- the explicit [Toast] here is
 * still shown for older versions, which have no such built-in feedback.
 */
fun copySongTextToClipboard(context: Context, song: Song) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label = song.title.ifBlank { "Song" }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, formatSongAsText(song)))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

// US Letter at 72dpi -- PdfDocument's page-size unit is points, so this maps
// directly to Canvas draw coordinates with no further conversion.
private const val PAGE_WIDTH = 612
private const val PAGE_HEIGHT = 792
private const val MARGIN = 54f

private val TitlePaint = Paint().apply {
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
private val ChordPaint = Paint().apply {
    color = Color.parseColor("#B45309")
    textSize = 12f
    typeface = Typeface.MONOSPACE
    isAntiAlias = true
}
private val LyricPaint = Paint().apply {
    color = Color.parseColor("#2A221B")
    textSize = 13f
    typeface = Typeface.DEFAULT
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
 * setlist folder. Same [formatSongAsText] output as the clipboard path, so the
 * two can never drift.
 */
fun shareSongAsText(context: Context, song: Song) {
    val title = song.title.ifBlank { "Untitled" }
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(exportsDir, "${sanitizeFilename(title)}.txt")
    file.writeText(formatSongAsText(song))

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
 * chords directly above its lyrics, same structure as [formatSongAsText]'s
 * plain-text export) and opens Android's share sheet so the user can save,
 * print, or send it. This is the mobile equivalent of the web app's
 * `window.print()` "print to PDF" flow -- Android has no OS print-preview
 * dialog to lean on the same way, so this builds a real PDF file instead and
 * hands it to whatever app the user picks (Files, a printer app, Drive,
 * messaging, ...) via [FileProvider].
 *
 * No text wrapping: a chord/lyric line wider than the page simply runs off
 * the right edge. Matches this project's own "don't build what nothing
 * needs yet" discipline -- revisit only if a real song's lines are wide
 * enough to hit it in practice.
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
    val chordLineHeight = ChordPaint.textSize * 1.4f
    val lyricLineHeight = LyricPaint.textSize * 1.4f

    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas = page.canvas
    var y = MARGIN + TitlePaint.textSize
    canvas.drawText(title, MARGIN, y, TitlePaint)
    y += TitlePaint.textSize * 1.5f

    fun newPage() {
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        canvas = page.canvas
        y = MARGIN + lyricLineHeight
    }

    for (line in song.lines) {
        val chords = anchorsToChordsLine(line.lyrics.length, line.chords)
        val hasChords = chords.isNotBlank()
        val hasLyrics = line.lyrics.isNotBlank()
        if (!hasChords && !hasLyrics) {
            y += lyricLineHeight
            continue
        }
        val entryHeight = (if (hasChords) chordLineHeight else 0f) + (if (hasLyrics) lyricLineHeight else 0f)
        if (y + entryHeight > PAGE_HEIGHT - MARGIN) newPage()
        if (hasChords) {
            y += chordLineHeight
            canvas.drawText(chords, MARGIN, y, ChordPaint)
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
