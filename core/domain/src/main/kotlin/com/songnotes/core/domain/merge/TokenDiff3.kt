package com.songnotes.core.domain.merge

/**
 * Word-level three-way merge of one line's lyrics (docs/SYNC-MERGE.md).
 *
 * [tokenize] splits text into words, whitespace runs and single punctuation
 * marks, using explicit character tables (not regex classes or locale rules)
 * so the web app splits every string identically. [diff3] lines base/local/
 * remote token lists up into chunks that are either stable (same on all
 * three) or changed; a changed chunk resolves to whichever side changed it,
 * or is a conflict when both changed it differently.
 *
 * Kotlin twin of the web app's `src/sync/tokenDiff3.js` -- keep them in step;
 * spec/song-merge.json's tokenizer vectors pin both.
 */

private val WHITESPACE: Set<Int> = setOf(
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, 0xa0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
    0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff,
)

// Each is a token of its own. The apostrophes (' and U+2019) are deliberately
// absent: they stay inside words ("don't", "rock'n'roll").
private val PUNCTUATION: Set<Int> = "!\"#$%&()*+,-./:;<=>?@[\\]^_`{|}~".map { it.code }.toSet() +
    setOf(0x2018, 0x201c, 0x201d, 0x2026, 0x2013, 0x2014, 0xa1, 0xbf, 0xab, 0xbb)

/** Tokens whose concatenation is exactly [text]. */
fun tokenize(text: String): List<String> {
    val tokens = ArrayList<String>()
    var k = 0
    while (k < text.length) {
        val u = text[k].code
        var end = k + 1
        if (u in WHITESPACE) {
            while (end < text.length && text[end].code in WHITESPACE) end++
        } else if (u !in PUNCTUATION) {
            while (end < text.length) {
                val v = text[end].code
                if (v in WHITESPACE || v in PUNCTUATION) break
                end++
            }
        }
        tokens.add(text.substring(k, end))
        k = end
    }
    return tokens
}

/** Above this many LCS cells a line isn't word-merged; it's treated as a clash. */
const val LCS_CELL_BUDGET = 160_000

/**
 * For each index of [a], the index of [b] it's matched to by a longest common
 * subsequence (-1 if unmatched), or null if the line is too long to diff
 * within [LCS_CELL_BUDGET]. Common prefix and suffix are matched first; ties
 * in the LCS walk prefer advancing through [a] -- the web's exact order.
 */
fun matchTokens(a: List<String>, b: List<String>): IntArray? {
    val map = IntArray(a.size) { -1 }
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) {
        map[pre] = pre
        pre++
    }
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) {
        map[a.size - 1 - suf] = b.size - 1 - suf
        suf++
    }
    val n = a.size - pre - suf
    val m = b.size - pre - suf
    if (n == 0 || m == 0) return map
    if (n.toLong() * m > LCS_CELL_BUDGET) return null

    val width = m + 1
    val t = IntArray((n + 1) * width)
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            t[i * width + j] = if (a[pre + i] == b[pre + j]) {
                t[(i + 1) * width + j + 1] + 1
            } else {
                maxOf(t[(i + 1) * width + j], t[i * width + j + 1])
            }
        }
    }
    var i = 0
    var j = 0
    while (i < n && j < m) {
        if (a[pre + i] == b[pre + j]) {
            map[pre + i] = pre + j
            i++
            j++
        } else if (t[(i + 1) * width + j] >= t[i * width + j + 1]) {
            i++
        } else {
            j++
        }
    }
    return map
}

/** Which side a changed chunk takes: [L] or [R], or null for a conflict. */
enum class Side { B, L, R }

/**
 * One chunk of a diff3: token ranges `[start, end)` on each side. A stable
 * chunk is the same on all three; a changed one takes [take]'s tokens, or is a
 * conflict when [take] is null.
 */
data class Chunk(
    val stable: Boolean,
    val b: IntRange2,
    val l: IntRange2,
    val r: IntRange2,
    val take: Side? = null,
) {
    fun on(side: Side): IntRange2 = when (side) {
        Side.B -> b
        Side.L -> l
        Side.R -> r
    }
}

/** A half-open token range `[start, end)`. */
data class IntRange2(val start: Int, val end: Int)

private fun sameTokens(x: List<String>, xs: Int, xe: Int, y: List<String>, ys: Int, ye: Int): Boolean {
    if (xe - xs != ye - ys) return false
    for (k in 0 until xe - xs) if (x[xs + k] != y[ys + k]) return false
    return true
}

/** Chunks covering [base], [local] and [remote] in order, or null over the LCS budget. */
fun diff3(base: List<String>, local: List<String>, remote: List<String>): List<Chunk>? {
    val mL = matchTokens(base, local) ?: return null
    val mR = matchTokens(base, remote) ?: return null

    val chunks = ArrayList<Chunk>()
    var iB = 0
    var iL = 0
    var iR = 0
    while (iB < base.size || iL < local.size || iR < remote.size) {
        if (iB < base.size && mL[iB] == iL && mR[iB] == iR) {
            var k = iB
            while (k < base.size && mL[k] == iL + (k - iB) && mR[k] == iR + (k - iB)) k++
            val len = k - iB
            chunks.add(Chunk(true, IntRange2(iB, k), IntRange2(iL, iL + len), IntRange2(iR, iR + len)))
            iB = k
            iL += len
            iR += len
            continue
        }
        var k = iB
        while (k < base.size && (mL[k] < 0 || mR[k] < 0)) k++
        val endL = if (k < base.size) mL[k] else local.size
        val endR = if (k < base.size) mR[k] else remote.size
        val take = when {
            sameTokens(local, iL, endL, base, iB, k) -> Side.R
            sameTokens(remote, iR, endR, base, iB, k) -> Side.L
            sameTokens(local, iL, endL, remote, iR, endR) -> Side.L
            else -> null
        }
        chunks.add(Chunk(false, IntRange2(iB, k), IntRange2(iL, endL), IntRange2(iR, endR), take))
        iB = k
        iL = endL
        iR = endR
    }
    return chunks
}
