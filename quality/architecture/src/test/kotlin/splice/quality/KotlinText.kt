// NEW: the text-level Kotlin readers shared by the laws ported from checks/ (restructure PR 6, §4.3).
//
// Each function keeps the EXACT semantics of the TypeScript it replaces — comment- and string-aware
// walks, top-level splits, Python's line splitting — because every law's red proof was measured
// against those semantics and its live census was diffed against the checker's own `report` at the
// port. A "better" reader is a different denominator, and a denominator that moves silently is the
// one failure every law here exists to prevent. The readers are text-level on purpose: the walls
// grade what is WRITTEN (a comment above a constant, a literal handed to a seam, a `stty` inside a
// string), which the compiler's model does not carry.
//
// The checkers each carried their own copy of one string/comment state machine; here it runs once,
// in [KotlinText.kinds], and every reader is a short pass over its verdict.
package splice.quality

import java.io.File

internal object KotlinText {
    /** What a character is to a reader: code, part of a string literal (quotes included), or part
     *  of a comment (delimiters included; a line comment stops BEFORE its newline). */
    const val CODE: Byte = 0
    const val STRING: Byte = 1
    const val COMMENT: Byte = 2

    /** Python's `str.splitlines()`: no trailing empty element, and separators beyond `\n`. */
    private val LINE_BOUNDARY = Regex("\r\n|[\n\r\u000B\u000C\u001C-\u001E\u0085  ]")
    private val ENDS_WITH_BOUNDARY = Regex("(?:\r\n|[\n\r\u000B\u000C\u001C-\u001E\u0085  ])$")

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(LINE_BOUNDARY)
        return if (ENDS_WITH_BOUNDARY.containsMatchIn(text)) parts.dropLast(1) else parts
    }

    /** Every `.kt` under `<module dir>/<sourceSet>` of every module the build declares, in path
     *  order — the build-derived denominator the checkers' `<home>/src/main` double-star globs were
     *  standing in for. */
    fun kotlinFiles(map: ProjectMap, sourceSet: String = "src/main"): List<File> =
        map.modules.sorted().flatMap { module ->
            val root = File(map.dir(module), sourceSet)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
            } else {
                emptyList()
            }
        }

    /** The path a violation names: relative to the repository root, forward slashes. */
    fun rel(map: ProjectMap, file: File): String = file.relativeTo(map.root).invariantSeparatorsPath

    /**
     * One pass from [start]: the kind of every character at or after it (positions before [start]
     * read as code and are never consulted). Strings open on `"` or `'`, honour backslash escapes
     * and run to the end when unterminated. Comments, when [comments] is set, are `//` up to (not
     * including) the newline and `/*` through `*/` with no nesting, unterminated to the end. This is
     * the state machine every checker carried; the readers below differ only in what they do with
     * its verdict.
     */
    fun kinds(source: String, start: Int = 0, comments: Boolean = true): ByteArray {
        val out = ByteArray(source.length)
        var i = start
        while (i < source.length) {
            val ch = source[i]
            i = when {
                ch == '"' || ch == '\'' -> markString(source, i, out)
                comments && source.startsWith("//", i) -> markLineComment(source, i, out)
                comments && source.startsWith("/*", i) -> markBlockComment(source, i, out)
                else -> i + 1
            }
        }
        return out
    }

    /** Marks the literal opening at [at] and returns the index after its closing quote. */
    private fun markString(source: String, at: Int, out: ByteArray): Int {
        val quote = source[at]
        out[at] = STRING
        var i = at + 1
        var escape = false
        while (i < source.length) {
            out[i] = STRING
            val ch = source[i]
            i += 1
            if (escape) {
                escape = false
            } else if (ch == '\\') {
                escape = true
            } else if (ch == quote) {
                break
            }
        }
        return i
    }

    private fun markLineComment(source: String, at: Int, out: ByteArray): Int {
        var i = at
        while (i < source.length && source[i] != '\n') {
            out[i] = COMMENT
            i += 1
        }
        return i
    }

    private fun markBlockComment(source: String, at: Int, out: ByteArray): Int {
        val close = source.indexOf("*/", at + 2)
        val end = if (close < 0) source.length else close + 2
        for (i in at until end) out[i] = COMMENT
        return end
    }

    /** Remove line and block comments, respecting string literals (quirks-keys-documented.ts). */
    fun stripComments(source: String): String {
        val kinds = kinds(source)
        return buildString(source.length) {
            source.forEachIndexed { i, ch -> if (kinds[i] != COMMENT) append(ch) }
        }
    }

    /** Replace comment bodies with spaces, preserving length and newlines; literals intact
     *  (env-vars-documented.ts). Length-preserving so every finding's line number is the file's. */
    fun blankComments(source: String): String {
        val kinds = kinds(source)
        return buildString(source.length) {
            source.forEachIndexed { i, ch -> append(if (kinds[i] == COMMENT && ch != '\n') ' ' else ch) }
        }
    }

    /** Blank comment bodies AND string literal bodies (raw and escaped), preserving offsets and
     *  newlines (autocloseable-closed.ts). Char literals are left alone, exactly as there. */
    fun blankCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            i = when {
                source.startsWith("//", i) -> blankUntilNewline(source, i, out)
                source.startsWith("/*", i) -> blankThrough(source, i, "*/", out)
                source.startsWith("\"\"\"", i) -> blankRawString(source, i, out)
                source[i] == '"' -> blankQuoted(source, i, out)
                else -> {
                    out.append(source[i])
                    i + 1
                }
            }
        }
        // The TS pushed one blank per consumed char and could overrun by the closer of an unclosed
        // literal; offsets only ever matter INSIDE the source, so the tail is cut to its length.
        return if (out.length > source.length) out.substring(0, source.length) else out.toString()
    }

    private fun blankUntilNewline(source: String, at: Int, out: StringBuilder): Int {
        var i = at
        while (i < source.length && source[i] != '\n') {
            out.append(' ')
            i += 1
        }
        return i
    }

    /** Blanks from [from] up to [closer], then the closer itself, newlines kept; the closer's
     *  blanks are appended even when it never comes (the TS overran the same way). */
    private fun blankThrough(source: String, from: Int, closer: String, out: StringBuilder): Int {
        var i = from
        while (i < source.length && !source.startsWith(closer, i)) {
            out.append(if (source[i] == '\n') '\n' else ' ')
            i += 1
        }
        repeat(closer.length) { out.append(' ') }
        return i + closer.length
    }

    private fun blankRawString(source: String, at: Int, out: StringBuilder): Int {
        out.append("   ")
        return blankThrough(source, at + 3, "\"\"\"", out)
    }

    /** An escaped literal: the backslash and the character after it are both blanked, a newline
     *  elsewhere in the body is kept. Returns the index after the closing quote. */
    private fun blankQuoted(source: String, at: Int, out: StringBuilder): Int {
        out.append(' ')
        var i = at + 1
        while (i < source.length && source[i] != '"') {
            val escaped = source[i] == '\\'
            val step = if (escaped) minOf(2, source.length - i) else 1
            repeat(step) { k -> out.append(if (!escaped && source[i + k] == '\n') '\n' else ' ') }
            i += step
        }
        out.append(' ')
        return i + 1
    }

    /** +1 on an opening bracket, -1 on a closing one, 0 otherwise; `<>` count only when [angles]
     *  is set (shared-quirks reads generic types; the knob and env readers do not). */
    private fun depthDelta(ch: Char, angles: Boolean): Int = when {
        ch in "({[" -> 1
        ch in ")}]" -> -1
        angles && ch == '<' -> 1
        angles && ch == '>' -> -1
        else -> 0
    }

    /** The balanced pair beginning at or after [start]: the body's first index and the closer's
     *  index, comment- and string-aware (knob-keys-documented.ts `walk`). */
    data class Span(val bodyStart: Int, val closerAt: Int)

    fun balancedSpan(source: String, start: Int, opener: Char, closer: Char): Span? {
        val kinds = kinds(source, start)
        var depth = 0
        var bodyStart = -1
        for (i in start until source.length) {
            if (kinds[i] != CODE) continue
            val ch = source[i]
            if (ch == opener) {
                depth += 1
                if (depth == 1) bodyStart = i + 1
            } else if (ch == closer) {
                depth -= 1
                if (depth == 0 && bodyStart >= 0) return Span(bodyStart, i)
            }
        }
        return null
    }

    /** Split on top-level [separator], dropping comments and respecting strings. Depth counts `({[`
     *  and, when [angles] is set, `<>` too. */
    fun splitTopLevel(body: String, separator: Char = ',', angles: Boolean = false): List<String> {
        val kinds = kinds(body)
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (i in body.indices) {
            if (kinds[i] == COMMENT) continue
            val ch = body[i]
            val code = kinds[i] == CODE
            if (code) depth += depthDelta(ch, angles)
            val separates = code && ch == separator
            if (separates && depth == 0) {
                parts += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) parts += buf.toString()
        return parts
    }

    /** Index of the bracket closing the one at [openIndex], string-aware, over `({[` alike
     *  (env-vars-documented.ts). Comments are NOT skipped here, exactly as there. */
    fun closeParen(text: String, openIndex: Int): Int? {
        val kinds = kinds(text, openIndex, comments = false)
        var depth = 0
        for (i in openIndex until text.length) {
            if (kinds[i] != CODE) continue
            depth += depthDelta(text[i], angles = false)
            if (depth == 0 && text[i] in ")}]") return i
        }
        return null
    }

    fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

    /** `# retired: <key> — <reason>` in a surface: (marker present, reason). An empty reason means
     *  the marker is present and the disposition is NOT. */
    fun retiredReason(text: String, key: String): Pair<Boolean, String> {
        val pattern = Regex(
            "^[ \\t]*#[ \\t]*retired:[ \\t]*${Regex.escape(key)}(?![A-Za-z0-9_-])(.*)$",
            RegexOption.MULTILINE,
        )
        val match = pattern.find(text) ?: return false to ""
        return true to match.groupValues[1].trim().replace(Regex("^[—:-]+"), "").trim()
    }

    /** Python's repr for the one value a message embeds, so a failure path reads as it did. */
    fun pyRepr(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun pyReprList(items: List<String>): String = items.joinToString(", ", "[", "]") { pyRepr(it) }
}
