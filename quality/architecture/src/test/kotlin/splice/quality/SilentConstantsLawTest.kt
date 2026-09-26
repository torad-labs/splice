// NEW: V4-88 — a numeric constant carries the reason it is that number. RATCHET.
// (ported from checks/silent-constants.ts, restructure PR 6 §4.3.)
//
// CLASS. A RATCHET, not a wall. Naming a magic number is half the job: this tree names them well and
// then leaves the number itself unexplained — `MAX_TEXT_BYTES = 65_536` (why 64 KiB?),
// `PROACTIVE_WINDOW_MS = 300_000L` (why five minutes?). The number was chosen against something, and
// once that reasoning is gone the next session either leaves a wrong number alone because it looks
// deliberate or changes a right one because it looks arbitrary. Failing the build on all of them
// would be reverted by lunchtime, so the census is RECORDED and the gate fails on GROWTH; the
// baseline is a debt meter with a one-way valve, and it is meant to fall to nothing.
//
// SCOPE. Production Kotlin only: every `.kt` under `<module>/src/main` of every module the BUILD
// declares, read through [KotlinText.kotlinFiles] — never a glob. The checker this replaces listed
// nine `<home>/*/src/main/**/*.kt` patterns by hand, which is a denominator that shrinks in silence
// the day a module home is added; measured at the port the two file sets are identical (744 files),
// and only the map survives. Test and testFixtures sources are out, per the row.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every `const val` is parsed off disk; the ones whose value is
// a numeric literal — or arithmetic over numeric literals, `64 * 1024`, `8L * 24 * 60 * 60 * 1000` —
// are the denominator. A file added tomorrow is in scope with no edit here. THREE GUARDS refuse a
// vacuous pass: zero source files, a parse that yields zero declarations, and a parsed count that
// disagrees with the count of `const val` lines the source carries (the parser and the source
// disagreeing means no census from the run can be trusted). The baseline's own recorded denominator
// is REPORTED and not gated when it moves: the gate reads the silent COUNT, never the ratio.
//
// PARSE, text-level on purpose — the wall grades what is WRITTEN. A declaration is one line matched
// by the checker's own `const val` regex, its value taken from the same line or, when the line ends
// at `=`, from the next. A REASON is the CONTIGUOUS comment block immediately above the declaration
// plus its own trailing `//`; contiguity matters, because a blank line between a comment and a
// declaration means the comment belongs to whatever is above it, and crediting it would let one
// explained constant launder the six unexplained ones beneath it. That text is a reason when it
// opens with `why:` or holds at least [SilentConstants.MIN_REASON_WORDS] words. A NAME is not a
// reason: `HTTP_TOO_MANY = 429` is perfectly clear and still counts as silent, because exempting
// "self-evident" names means hand-judging six hundred constants — the hand list this wall avoids.
//
// VIOLATIONS, both directions. GROWTH — a file above its baseline count, or a tree total above the
// baseline total — is RED by file and by constant name. A SHRINK is RED too, naming the exact new
// number and telling the reader to lower the resource BY HAND: a baseline held above the
// measurement is unearned room for the next regression to hide in, and the checker's `--ratchet`
// said exactly this (`STALE: the tree total fell X -> Y … record the win by lowering it`) rather
// than rewriting the file, so the ratchet stays visible in a diff instead of in a gate run.
//
// NOT CAUGHT, stated rather than implied: an INLINE literal (`if (status == 429)`) — detekt's
// MagicNumber owns that; a reason that is WRONG, or one that restates the name in four words, which
// a word count cannot read; non-numeric constants, because a wire word documents itself and a
// magnitude does not.
package splice.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The recorded census on the test classpath, and the path a violation tells the reader to edit. */
private const val BASELINE_RESOURCE = "/silent-constants-baseline.json"
private const val BASELINE_PATH = "quality/architecture/src/test/resources/silent-constants-baseline.json"

internal object SilentConstants {
    /** A sentence. Measured on this tree the distribution is bimodal (no adjacent comment at all, or
     *  twelve words and up), so the threshold is not a knife-edge: 2 -> 6 moves the census by four. */
    const val MIN_REASON_WORDS = 4

    private val DECL = Regex(
        "^[ \\t]*(?:(?:public|internal|private|protected)\\s+)?const\\s+val\\s+([A-Za-z_][A-Za-z0-9_]*)" +
            "\\s*(?::\\s*[^=]+?)?\\s*=[ \\t]*(.*)$",
    )
    private val CONST_VAL_LINE = Regex("\\bconst\\s+val\\b")
    private val NUM_TOKEN = Regex("^(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*$")
    private val ARITH_SPLIT = Regex("\\s*(?:\\*|/|\\+|-|%|shl|shr|and|or|\\(|\\))\\s*")
    private val WORD = Regex("[A-Za-z]{2,}")
    private val WHY_MARKER = Regex("^\\s*why:", RegexOption.IGNORE_CASE)

    /** The comment delimiters stripped off one line of an adjacent block. The checker's `re.sub`
     *  replaced EVERY occurrence, so a non-global replace here would leave `// //` in the reason. */
    private val COMMENT_MARKERS = Regex("^/\\*+|^\\*+/?|^//|\\*/$")

    /** One numeric `const val` that carries no adjacent reason. */
    data class Silent(val name: String, val rel: String, val line: Int, val value: String) {
        val where: String get() = "$rel:$line"
    }

    /** The measurement: the silent declarations, the numeric denominator, and what makes it untrusted. */
    data class Census(val silent: List<Silent>, val numeric: Int, val problems: List<String>) {
        /** rel -> how many silent numeric consts that file carries, the shape the baseline records. */
        val perFile: Map<String, Int> get() = silent.groupingBy { it.rel }.eachCount()
    }

    /** The recorded census the ratchet holds the tree to, and the two directions it grades. */
    data class Baseline(val recorded: String, val denominator: Int, val total: Int, val files: Map<String, Int>) {
        /** A file above its recorded count, named by every silent constant it now carries. */
        fun growth(census: Census): List<String> {
            val measured = census.perFile
            return measured.keys.filter { measured.getValue(it) > (files[it] ?: 0) }.sorted().map { rel ->
                val names = census.silent.filter { it.rel == rel }
                    .joinToString(", ") { "${it.name} = ${it.value} (${it.where})" }
                "GROWTH: $rel carries ${measured.getValue(rel)} silent numeric const(s), baseline " +
                    "${files[rel] ?: 0} — $names. Give the new one an adjacent reason (`// why: ...` or a " +
                    "sentence), or lower another entry in $BASELINE_PATH in the same commit"
            }
        }

        /** An entry held ABOVE the measurement, or naming a file the tree no longer has. */
        fun stale(census: Census, root: File): List<String> {
            val measured = census.perFile
            val out = mutableListOf<String>()
            for (rel in files.keys.sorted()) {
                val now = measured[rel] ?: 0
                val was = files.getValue(rel)
                if (now < was) {
                    out += "STALE: $BASELINE_PATH claims $was silent const(s) in $rel but only $now remain — lower " +
                        "that entry to $now BY HAND. A baseline held above the measurement is unearned room for " +
                        "the next regression to hide in"
                }
                if (!File(root, rel).exists()) {
                    out += "STALE: $BASELINE_PATH names $rel, which no longer exists — delete the entry"
                }
            }
            return out
        }

        /** The tree total, in both directions. A shrink names the new number and the file to edit. */
        fun totals(census: Census): List<String> = when {
            census.silent.size > total -> listOf("GROWTH: the tree total rose $total -> ${census.silent.size}")
            census.silent.size < total -> listOf(
                "STALE: the tree total fell $total -> ${census.silent.size}, and $BASELINE_PATH still claims " +
                    "$total — record the win by lowering `total` to ${census.silent.size} BY HAND in the same " +
                    "commit; the ratchet stays visible in a diff instead of being rewritten by a gate run",
            )
            else -> emptyList()
        }

        companion object {
            private val REQUIRED_KEYS = listOf("recorded", "total", "denominator", "files")

            /** The recorded census, or the problems that stop the ratchet from grading at all. */
            fun parse(text: String?): Pair<Baseline?, List<String>> {
                if (text == null) {
                    return null to listOf(
                        "$BASELINE_PATH: missing — the ratchet has no recorded census to hold the tree to",
                    )
                }
                val read = runCatching { readDocument(text) }
                return read.getOrNull() ?: (
                    null to listOf(
                        "$BASELINE_PATH: unreadable (${read.exceptionOrNull()?.message}) — a ratchet that cannot " +
                            "read its baseline cannot gate",
                    )
                    )
            }

            /** Throws on anything the JSON cannot answer; [parse] turns that into the one problem. */
            private fun readDocument(text: String): Pair<Baseline?, List<String>> {
                val document = Json.parseToJsonElement(text).jsonObject
                val missing = REQUIRED_KEYS.firstOrNull { it !in document }
                if (missing != null) return null to listOf("$BASELINE_PATH: missing required key '$missing'")
                val baseline = Baseline(
                    recorded = document.getValue("recorded").jsonPrimitive.content,
                    denominator = document.getValue("denominator").jsonPrimitive.int,
                    total = document.getValue("total").jsonPrimitive.int,
                    files = document.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.int },
                )
                return baseline to emptyList()
            }
        }
    }

    /** What one file's walk accumulates. `rawTotal` is the SOURCE's own `const val` line count, which
     *  must agree with `parsed` or the parser has stopped seeing the shapes the tree actually writes. */
    private class Tally {
        val silent = mutableListOf<Silent>()
        var numeric = 0
        var parsed = 0
        var rawTotal = 0
    }

    /** The census over [files], each named relative to [root]. */
    fun scan(files: List<File>, root: File): Census {
        if (files.isEmpty()) {
            return Census(
                emptyList(),
                0,
                listOf("the project map named no production Kotlin under src/main — the denominator is absent"),
            )
        }
        val tally = Tally()
        for (file in files) {
            scanFile(file.relativeTo(root).invariantSeparatorsPath, KotlinText.splitLines(file.readText()), tally)
        }
        val problems = mutableListOf<String>()
        if (tally.parsed == 0) {
            problems += "parsed 0 const declarations from ${files.size} main source file(s) — refusing to pass " +
                "vacuously, because a green over an empty denominator is what this ratchet exists to prevent"
        }
        if (tally.rawTotal != tally.parsed) {
            problems += "parsed ${tally.parsed} declarations but the tree holds ${tally.rawTotal} `const val` " +
                "lines — the parser and the source disagree, so no census from this run can be trusted"
        }
        return Census(tally.silent, tally.numeric, problems)
    }

    private fun scanFile(rel: String, lines: List<String>, tally: Tally) {
        lines.forEachIndexed { index, line ->
            val stripped = line.trim()
            val prose = stripped.startsWith("//") || stripped.startsWith("*")
            if (!prose && CONST_VAL_LINE.containsMatchIn(line)) tally.rawTotal += 1
            val match = DECL.find(line)
            if (match != null) declaration(rel, lines, index, match, tally)
        }
    }

    /** One parsed `const val`: counted, then measured for a numeric value and an adjacent reason. */
    private fun declaration(rel: String, lines: List<String>, index: Int, match: MatchResult, tally: Tally) {
        tally.parsed += 1
        val declared = match.groupValues[2].trim()
        // A declaration whose `=` ends the line takes its value from the next one, exactly as there.
        val value = if (declared.isEmpty()) lines.getOrElse(index + 1) { "" }.trim() else declared
        if (!isNumeric(value)) return
        tally.numeric += 1
        if (!hasReason(adjacentReason(lines, index))) {
            tally.silent += Silent(match.groupValues[1], rel, index + 1, stripLineComment(value).first.trim())
        }
    }

    /** (code, comment) — split at a `//` that is not inside a string literal. The checker carried its
     *  own copy of this state machine; here it is [KotlinText.kinds]'s string-only reading. */
    private fun stripLineComment(text: String): Pair<String, String> {
        val kinds = KotlinText.kinds(text, comments = false)
        val at = (0 until text.length - 1).firstOrNull { i ->
            kinds[i] != KotlinText.STRING && text.startsWith("//", i)
        } ?: return text to ""
        return text.substring(0, at) to text.substring(at + 2)
    }

    /** A numeric literal, or arithmetic over numeric literals. Strings, booleans and chars are out. */
    fun isNumeric(raw: String): Boolean {
        // `trimEnd(',')` is the checker's `/,+$/`: the trailing run of commas and nothing else, so a
        // value written `5 ,,` keeps its inner space and stays non-numeric exactly as it did there.
        val code = stripLineComment(raw).first.trim().trimEnd(',')
        if (code.isEmpty()) return false
        val parts = code.split(ARITH_SPLIT).filter { it.isNotBlank() }
        return parts.isNotEmpty() && parts.all { NUM_TOKEN.matches(it) }
    }

    /** The CONTIGUOUS comment block above [index], plus that line's own trailing comment. */
    fun adjacentReason(lines: List<String>, index: Int): String {
        val parts = mutableListOf<String>()
        var above = index - 1
        while (above >= 0 && isCommentLine(lines[above].trim())) {
            parts += COMMENT_MARKERS.replace(lines[above].trim(), "")
            above -= 1
        }
        parts.reverse()
        val trailing = stripLineComment(lines[index]).second
        if (trailing.isNotEmpty()) parts += trailing
        return parts.joinToString(" ") { it.trim() }.trim()
    }

    private fun isCommentLine(stripped: String): Boolean {
        val opens = stripped.startsWith("//") || stripped.startsWith("*")
        return opens || stripped.startsWith("/*") || stripped.endsWith("*/")
    }

    private fun hasReason(comment: String): Boolean {
        if (comment.isEmpty()) return false
        return WHY_MARKER.containsMatchIn(comment) || WORD.findAll(comment).count() >= MIN_REASON_WORDS
    }

    /** Every problem this run has, empty when the ratchet holds. An untrusted measurement is
     *  TERMINAL: a growth report over a census nobody can trust is a red wearing the wrong number. */
    fun audit(files: List<File>, root: File, baselineText: String?): List<String> {
        val census = scan(files, root)
        val (baseline, baselineProblems) = Baseline.parse(baselineText)
        val untrusted = census.problems + baselineProblems
        if (untrusted.isNotEmpty()) return untrusted.map { "UNTRUSTWORTHY: $it" }
        if (baseline == null) return listOf("UNTRUSTWORTHY: $BASELINE_PATH could not be read")
        return baseline.growth(census) + baseline.stale(census, root) + baseline.totals(census)
    }
}

class SilentConstantsLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every numeric const val with no adjacent reason is exactly the recorded census - V4-88`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(files.size > 10) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a ratchet that reads no " +
                "files holds a tree it never measured."
        }
        val problems = SilentConstants.audit(files, map.root, baselineText())
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "SILENT CONSTANTS RATCHET (V4-88) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: two module homes the map declares, rewritten
     *  per arm, plus the baseline document the arm grades against. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))

        fun write(vararg files: Pair<String, String>) {
            for (home in listOf("app", "core")) File(root, home).deleteRecursively()
            for ((rel, body) in files) File(root, rel).apply { parentFile.mkdirs() }.writeText(body)
        }

        fun audit(baseline: String?): List<String> =
            SilentConstants.audit(KotlinText.kotlinFiles(synthetic), root, baseline)

        fun census(): SilentConstants.Census = SilentConstants.scan(KotlinText.kotlinFiles(synthetic), root)
    }

    @Test
    fun `the law can actually fail - the boring cases are green WITH their count - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A to COMPLIANT)
            assertEquals(emptyList<String>(), audit(baseline(0, 3)), "`why:`, a KDoc sentence and a trailing sentence")
            assertEquals(3, census().numeric, "the denominator is the three numeric consts, arithmetic included")

            write(A to ONE_SILENT)
            assertEquals(emptyList<String>(), audit(baseline(1, 1, mapOf(A to 1))), "one silent const, baseline one")
            // The numeric denominator MOVING is reported by the checker and NOT gated — the gate
            // reads the silent COUNT, never the ratio. This tree carries that case live (566 -> 615).
            assertEquals(
                emptyList<String>(),
                audit(baseline(1, 99, mapOf(A to 1))),
                "a denominator that no longer matches is reported, not gated",
            )

            write(A to NO_NUMERIC)
            assertEquals(0, census().numeric, "a wire word is not in the denominator")
            assertEquals(emptyList<String>(), audit(baseline(0, 0)), "no numeric const at all, baseline zero")
        }
    }

    @Test
    fun `the law can actually fail - growth is red BY NAME - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A to COMPLIANT + "\nprivate const val SNEAKED = 13\n")
            assertHit(audit(baseline(0, 3)), "GROWTH", "SNEAKED = 13", A) {
                "a synthetic silent const added to an explained file must be RED BY NAME"
            }

            write(A to COMPLIANT, B to ONE_SILENT)
            assertHit(audit(baseline(0, 3)), "GROWTH", "LONELY", B) { "a NEW file carrying one must be RED BY NAME" }

            // The two-item control: the ratchet must COUNT, not merely detect. A file already on the
            // list gaining a second silent const is growth.
            write(A to TWO_SILENT)
            assertHit(audit(baseline(1, 2, mapOf(A to 1))), "GROWTH", "carries 2 silent") {
                "a file already on the list gaining a second silent const must be RED"
            }

            write(A to DETACHED)
            assertHit(audit(baseline(0, 1)), "GROWTH", "ORPHANED") {
                "a comment separated from its declaration by a blank line belongs to what is above it"
            }
        }
    }

    @Test
    fun `the law can actually fail - a shrink names the new number and the resource - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A to ONE_SILENT)
            assertHit(audit(baseline(2, 1, mapOf(A to 2))), "STALE", "only 1 remain", "BY HAND", BASELINE_PATH) {
                "a baseline entry held ABOVE the measurement must be RED, with the number to write"
            }
            val fell = audit(baseline(2, 2, mapOf(A to 1)))
            assertHit(fell, "STALE", "fell 2 -> 1", "lowering `total` to 1", BASELINE_PATH) {
                "a tree total that FELL must be RED, naming the new number and the resource to lower by hand"
            }
            assertHit(audit(baseline(1, 1, mapOf(A to 1, GONE to 0))), "STALE", GONE, "no longer exists") {
                "a baseline naming a file the tree no longer has must be RED BY NAME"
            }
        }
    }

    @Test
    fun `the law can actually fail - an untrusted instrument never passes - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A to EMPTY)
            assertHit(audit(baseline(0, 0)), "refusing to pass vacuously") {
                "a tree with no const at all must refuse rather than agree with a baseline of zero"
            }

            write(A to DRIFT)
            assertHit(audit(baseline(0, 1)), "the parser and the source disagree") {
                "a `const val` line the parser cannot read must be a parser/source disagreement"
            }

            write(A to ONE_SILENT)
            assertHit(audit(null), "has no recorded census") { "a missing baseline cannot gate" }
            assertHit(audit(NO_FILES_KEY), "missing required key 'files'") { "a half-written baseline cannot gate" }
            assertHit(audit("not json at all"), "unreadable") { "a baseline nobody can parse cannot gate" }
        }
    }

    @Test
    fun `what counts as a reason - V4-88`(@TempDir root: File) {
        assertTrue(SilentConstants.isNumeric("64 * 1024"), "arithmetic over literals is numeric")
        assertTrue(SilentConstants.isNumeric("0xFF"), "a hex literal is numeric")
        assertTrue(SilentConstants.isNumeric("300_000L"), "a suffixed literal is numeric")
        assertFalse(SilentConstants.isNumeric("\"role\""), "a string is not numeric")
        assertFalse(SilentConstants.isNumeric("true"), "a boolean is not numeric")
        val lines = KotlinText.splitLines(COMPLIANT)
        val kdoc = lines.indexOfFirst { it.contains("PROACTIVE_WINDOW_MS") }
        assertTrue(
            SilentConstants.adjacentReason(lines, kdoc).startsWith("Five minutes"),
            "a KDoc block above the declaration is its reason, with the delimiters stripped",
        )
        val trailing = lines.indexOfFirst { it.contains("CHUNK") }
        assertTrue(
            SilentConstants.adjacentReason(lines, trailing).startsWith("64 KiB matches"),
            "a trailing `//` on the declaration's own line is its reason",
        )
        with(Tree(root)) {
            write(A to SHORT_REASON)
            assertHit(audit(baseline(0, 1)), "GROWTH", "TERSE") {
                "a comment under ${SilentConstants.MIN_REASON_WORDS} words and without `why:` is not a reason"
            }
        }
    }

    private fun baselineText(): String = checkNotNull(SilentConstants::class.java.getResource(BASELINE_RESOURCE)) {
        "$BASELINE_PATH is not on the test classpath — the ratchet cannot grade a census it cannot read. " +
            "quality/architecture/build.gradle.kts is what declares it."
    }.readText()

    private companion object {
        const val A = "app/src/main/kotlin/splice/A.kt"
        const val B = "core/src/main/kotlin/splice/B.kt"
        const val GONE = "core/src/main/kotlin/splice/Gone.kt"
        const val NO_FILES_KEY = """{"recorded": "selftest", "total": 1, "denominator": 1}"""

        fun baseline(total: Int, denominator: Int, files: Map<String, Int> = emptyMap()): String {
            val entries = files.entries.joinToString(", ") { (rel, count) -> "\"$rel\": $count" }
            return """{"recorded": "selftest", "total": $total, "denominator": $denominator, "files": {$entries}}"""
        }

        const val COMPLIANT = """package splice.a

// why: the provider's observed ceiling
private const val MAX_TEXT_BYTES = 65_536

/** Five minutes is one client-retry cycle, so a recovered account resumes without operator help. */
private const val PROACTIVE_WINDOW_MS = 300_000L

private const val CHUNK = 64 * 1024 // 64 KiB matches the transport's own buffer, measured 2026-09
"""

        const val ONE_SILENT = "package splice.a\n\nprivate const val LONELY = 7\n"

        const val TWO_SILENT = "package splice.a\n\nprivate const val ONE = 7\n\nprivate const val TWO = 9\n"

        const val NO_NUMERIC = """package splice.a

// a wire word documents itself
private const val FIELD_ROLE = "role"
"""

        // A comment separated from its declaration by a blank line belongs to whatever is above it.
        const val DETACHED = """package splice.a

// why: this explains the constant above, not the one below
internal fun f(): Int = 1

private const val ORPHANED = 41
"""

        const val SHORT_REASON = """package splice.a

// a cap
private const val TERSE = 41
"""

        const val EMPTY = "package splice.a\n\ninternal fun f(): Int = 7\n"

        // The second line is a `const val` the declaration regex cannot read, which is the shape that
        // makes a parser/source disagreement: the census would silently lose a declaration.
        const val DRIFT = """package splice.a

// why: readable
private const val OK = 1

@Suppress("MagicNumber") private const val ANNOTATED = 3
"""
    }
}
