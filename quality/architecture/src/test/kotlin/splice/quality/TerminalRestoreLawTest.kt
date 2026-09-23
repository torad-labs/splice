// NEW: CW-9 — raw-mode entry is only legal inside TerminalMode.raw (ported from
// checks/config/terminal-restore-bracketed.ts, restructure PR 6).
//
// THE CLASS. A wizard that leaves the terminal raw with echo off is strictly worse than no wizard,
// because the damage outlives the process. TerminalMode.raw is the restoring bracket: capture,
// enter, try/finally restore, shutdown hook. This law closes the class so a widget cannot invoke
// stty on its own or enter raw outside that bracket.
//
// SCOPE. Kotlin sources of splice.terminal under :integrations-terminal's src/main/kotlin, where the
// prompt toolkit moved from :app's splice.app.cli.prompt (2026-09-23, LAYOUT-01). Tests are out of
// scope — they inject SttyCommand and never talk to a real tty.
//
// DENOMINATOR. Every .kt file in that directory on disk. Zero files, or a missing package
// directory, is a FAILURE, not a pass. There is no allowlist and no exemption table.
//
// PARSE. Sources are tokenized (comments dropped, string literals kept as STRING tokens,
// identifiers as IDENT). This is not a substring grep: stty in a comment does not count; the
// command string "stty" does.
//
// VIOLATIONS, failed BY NAME:
//   1. STRING token stty in any file other than TerminalMode.kt
//   2. STRING token -icanon (the raw-mode entry flags) outside the body of TerminalMode.raw —
//      including in TerminalMode.kt itself if it is not inside fun raw
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object TerminalRestore {
    const val TERMINAL_MODULE = ":integrations-terminal"
    const val PROMPT_PACKAGE = "splice/terminal"
    const val TERMINAL_MODE = "TerminalMode.kt"
    private const val STTY = "stty"
    private const val RAW_FLAG = "-icanon"
    private const val NOT_RAW = -1
    private const val NO_BODY = -2

    data class Token(val kind: String, val value: String, val line: Int)

    /** Tokens of kind STRING, IDENT, LBRACE/RBRACE or LT/GT; comments dropped, everything else skipped. */
    fun tokenize(source: String): List<Token> = Tokenizer(source).run()

    /** The checker's lexer, one method per lexeme class; every step leaves the cursor advanced. */
    private class Tokenizer(private val source: String) {
        private val tokens = mutableListOf<Token>()
        private var i = 0
        private var line = 1

        fun run(): List<Token> {
            while (i < source.length) step()
            return tokens
        }

        private fun step() {
            val ch = source[i]
            when {
                ch.isWhitespace() -> whitespace(ch)
                source.startsWith("//", i) -> lineComment()
                source.startsWith("/*", i) -> blockComment()
                source.startsWith("\"\"\"", i) -> rawString()
                ch == '"' -> quotedString()
                isAlpha(ch) || ch == '_' -> identifier()
                else -> punctuation(ch)
            }
        }

        private fun whitespace(ch: Char) {
            if (ch == '\n') line += 1
            i += 1
        }

        /** The newline itself is left for [whitespace]; a comment with no newline ends the walk. */
        private fun lineComment() {
            val newline = source.indexOf('\n', i)
            i = if (newline < 0) source.length else newline
        }

        /** An unterminated block comment ends the walk, as it did in the checker. */
        private fun blockComment() {
            val end = source.indexOf("*/", i + 2)
            if (end < 0) i = source.length else advanceTo(end + 2)
        }

        /** The token carries the line the literal OPENS on; an unterminated one ends the walk. */
        private fun rawString() {
            val end = source.indexOf("\"\"\"", i + 3)
            if (end < 0) {
                tokens += Token("STRING", source.substring(i + 3), line)
                i = source.length
            } else {
                tokens += Token("STRING", source.substring(i + 3, end), line)
                advanceTo(end + 3)
            }
        }

        /** Escapes are dropped from the value (`\"` is not a closer); the token opens on this line. */
        private fun quotedString() {
            var j = i + 1
            val bits = StringBuilder()
            while (j < source.length && source[j] != '"') {
                if (source[j] == '\\') {
                    j += 2
                } else {
                    bits.append(source[j])
                    j += 1
                }
            }
            tokens += Token("STRING", bits.toString(), line)
            line += source.substring(i, minOf(j, source.length)).count { it == '\n' }
            i = if (j < source.length) j + 1 else source.length
        }

        private fun identifier() {
            var j = i + 1
            while (j < source.length && isIdentifierChar(source[j])) j += 1
            tokens += Token("IDENT", source.substring(i, j), line)
            i = j
        }

        private fun punctuation(ch: Char) {
            val kind = when (ch) {
                '{' -> "LBRACE"
                '}' -> "RBRACE"
                '<' -> "LT"
                '>' -> "GT"
                else -> null
            }
            if (kind != null) tokens += Token(kind, ch.toString(), line)
            i += 1
        }

        private fun advanceTo(end: Int) {
            line += source.substring(i, end).count { it == '\n' }
            i = end
        }
    }

    // Kotlin identifiers are ASCII here, and a non-ASCII identifier would not be a token this law
    // has an opinion about either way.
    private fun isAlpha(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z'
    private fun isAlnum(ch: Char): Boolean = isAlpha(ch) || ch in '0'..'9'
    private fun isIdentifierChar(ch: Char): Boolean = isAlnum(ch) || ch == '_'

    private fun Token.isIdent(name: String): Boolean = kind == "IDENT" && value == name
    private fun List<Token>.identAt(j: Int, name: String): Boolean = j in indices && this[j].isIdent(name)
    private fun List<Token>.kindAt(j: Int, kind: String): Boolean = j in indices && this[j].kind == kind

    /** Line numbers of STRING tokens that sit inside `fun raw { ... }`. */
    fun rawFunctionStringLines(tokens: List<Token>): Set<Int> {
        val lines = mutableSetOf<Int>()
        var i = 0
        while (i < tokens.size) {
            val open = rawBodyOpen(tokens, i)
            i = when (open) {
                NOT_RAW -> i + 1
                // The checker stopped its walk at a `fun raw` whose signature never reaches a brace.
                NO_BODY -> tokens.size
                else -> collectBody(tokens, open, lines)
            }
        }
        return lines
    }

    /** Where `fun raw`'s body opens when [at] starts one: the LBRACE index, [NO_BODY] when the
     *  signature never reaches a brace, [NOT_RAW] when this is not `fun raw` at all. */
    private fun rawBodyOpen(tokens: List<Token>, at: Int): Int {
        val name = if (tokens[at].isIdent("fun")) afterTypeParameters(tokens, at + 1) else -1
        if (!tokens.identAt(name, "raw")) return NOT_RAW
        var j = name
        while (j < tokens.size && tokens[j].kind != "LBRACE") j += 1
        return if (j < tokens.size) j else NO_BODY
    }

    /** [j] itself unless it opens a `<...>` type-parameter list (`fun <T> raw`), then the index
     *  after its closing `>` — the end of the tokens when it never closes. */
    private fun afterTypeParameters(tokens: List<Token>, j: Int): Int {
        if (!tokens.kindAt(j, "LT")) return j
        var depth = 0
        var k = j
        while (k < tokens.size) {
            val kind = tokens[k].kind
            k += 1
            if (kind == "LT") depth += 1
            if (kind == "GT") depth -= 1
            if (kind == "GT" && depth == 0) break
        }
        return k
    }

    /** Adds the lines of STRING tokens between the brace at [open] and its match to [lines];
     *  returns the index after the closing brace (past the end when it never closes). */
    private fun collectBody(tokens: List<Token>, open: Int, lines: MutableSet<Int>): Int {
        var depth = 0
        var k = open
        while (k < tokens.size) {
            val (kind, _, line) = tokens[k]
            if (kind == "LBRACE") depth += 1
            if (kind == "RBRACE") depth -= 1
            if (kind == "STRING") lines += line
            if (kind == "RBRACE" && depth == 0) break
            k += 1
        }
        return k + 1
    }

    fun checkFile(name: String, source: String): List<String> {
        val tokens = tokenize(source)
        val inTerminalMode = name == TERMINAL_MODE
        val rawLines = if (inTerminalMode) rawFunctionStringLines(tokens) else emptySet()
        return tokens.filter { it.kind == "STRING" }.flatMap { problemsFor(name, it, inTerminalMode, rawLines) }
    }

    /** The violations one STRING token carries: a stray stty, a raw-mode flag outside the bracket. */
    private fun problemsFor(name: String, token: Token, inTerminalMode: Boolean, rawLines: Set<Int>): List<String> {
        val problems = mutableListOf<String>()
        if (token.value == STTY && !inTerminalMode) {
            problems += "$name:${token.line}: stty invocation outside $TERMINAL_MODE"
        }
        val bracketed = inTerminalMode && token.line in rawLines
        if (token.value == RAW_FLAG && !bracketed) {
            problems += "$name:${token.line}: raw-mode entry (-icanon) is not inside TerminalMode.raw"
        }
        return problems
    }

    /** The prompt package's .kt files, sorted by name; null when the directory is missing. */
    fun promptFiles(promptDir: File): List<File>? {
        if (!promptDir.isDirectory) return null
        return promptDir.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty().sortedBy { it.name }
    }

    /** Every violation under [promptDir], naming files relative to [root]. Empty means GREEN. */
    fun checkTree(promptDir: File, promptRel: String, root: File): List<String> {
        val files = promptFiles(promptDir) ?: return listOf("prompt package missing: $promptRel")
        if (files.isEmpty()) return listOf("scanned zero files under $promptRel")
        val problems = mutableListOf<String>()
        for (path in files) {
            val rel = path.relativeTo(root).invariantSeparatorsPath
            val source = runCatching { path.readText() }.getOrElse { exc ->
                problems += "$rel: unreadable ($exc)"
                continue
            }
            for (hit in checkFile(path.name, source)) {
                problems += if (hit.startsWith(path.name)) hit else "$rel: $hit"
            }
        }
        return problems
    }
}

class TerminalRestoreLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `raw-mode entry is only legal inside TerminalMode raw - CW-9`() {
        val promptDir = File(map.mainSources(TerminalRestore.TERMINAL_MODULE), TerminalRestore.PROMPT_PACKAGE)
        val promptRel = promptDir.relativeTo(map.root).invariantSeparatorsPath
        val problems = TerminalRestore.checkTree(promptDir, promptRel, map.root)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "TERMINAL RESTORE (CW-9) violated:\n  - ")
        }
        val bracket = TerminalRestore.promptFiles(promptDir).orEmpty().any { it.name == TerminalRestore.TERMINAL_MODE }
        assertTrue(bracket) {
            "$promptRel holds no ${TerminalRestore.TERMINAL_MODE} — the bracket this law protects is gone, " +
                "so the law guards nothing"
        }
    }

    // The red proof, out of tree: an empty tree refuses to pass vacuously; an unbracketed stty is
    // RED naming its file; the compliant TerminalMode alone is GREEN.
    @Test
    fun `the law can actually fail - CW-9`(@TempDir root: File) {
        val promptDir = File(root, "integrations/terminal/src/main/kotlin/${TerminalRestore.PROMPT_PACKAGE}")
        val promptRel = "integrations/terminal/src/main/kotlin/${TerminalRestore.PROMPT_PACKAGE}"
        val empty = TerminalRestore.checkTree(promptDir, promptRel, root)
        assertTrue(empty.any { it.contains("missing") || it.contains("zero files") }) {
            "empty tree must refuse to pass vacuously, got: ${KotlinText.pyReprList(empty)}"
        }
        promptDir.mkdirs()
        File(promptDir, TerminalRestore.TERMINAL_MODE).writeText(COMPLIANT_TERMINAL)
        val bad = File(promptDir, "LooseStty.kt").apply { writeText(VIOLATION) }
        val red = TerminalRestore.checkTree(promptDir, promptRel, root)
        assertEquals(
            listOf(
                "LooseStty.kt:5: stty invocation outside TerminalMode.kt",
                "LooseStty.kt:5: raw-mode entry (-icanon) is not inside TerminalMode.raw",
            ),
            red,
            "synthetic unbracketed stty must be RED naming LooseStty.kt",
        )
        bad.delete()
        assertEquals(
            emptyList<String>(),
            TerminalRestore.checkTree(promptDir, promptRel, root),
            "compliant TerminalMode-only tree must be GREEN",
        )
        // and a comment is prose, not an invocation
        File(promptDir, "Prose.kt").writeText(
            "package splice.terminal\n// stty -icanon is what TerminalMode.raw does\ninternal object Prose\n",
        )
        assertEquals(
            emptyList<String>(),
            TerminalRestore.checkTree(promptDir, promptRel, root),
            "stty in a comment does not count",
        )
    }

    private companion object {
        const val COMPLIANT_TERMINAL = """
package splice.terminal
internal class TerminalMode {
    fun <T> raw(block: () -> T): T {
        stty.run(listOf("stty", "-g"))
        stty.run(listOf("stty", "-icanon", "-echo", "min", "1", "time", "0"))
        return block()
    }
}
"""

        const val VIOLATION = """
package splice.terminal
internal class LooseStty {
    fun go() {
        ProcessBuilder(listOf("stty", "-icanon", "-echo")).start()
    }
}
"""
    }
}
