// NEW: V4-93 — a primary constructor's WIDTH is billed, because detekt does not bill it
// (ported from checks/constructor-width.ts, restructure PR 6).
//
// CLASS. A RATCHET over a recorded baseline, not a threshold. Sixteen constructors are already over
// a width, so `12 or bust` cannot be the gate leg without finishing the fix row first; what IS
// enforceable is the DIRECTION. The recorded numbers live in
// quality/architecture/src/test/resources/constructor-width-baseline.json and are edited BY HAND —
// no run of this law rewrites them, so every movement of the ratchet is a line in a diff a human
// wrote rather than a number a gate quietly re-measured.
//
// WHY IT EXISTS. quality/detekt/detekt.yml:38-43 configures LongParameterList with
// `constructorThreshold: 8` and then turns it off for the two shapes this tree actually uses:
// `ignoreDefaultParameters: true` (every collaborator bundle defaults its seams) and
// `ignoreDataClasses: true` (every config and wire type is a data class). So the widest
// constructors in the tree are billed by NOTHING — detekt reports zero LongParameterList findings
// on ResponsesQuirks's 23 parameters or TurnDrive's 17. That is not detekt being wrong: a defaulted
// seam and a data-class field are cheap individually. What the ignores cannot see is the total, and
// the total is the coupling.
//
// SCOPE. Every `.kt` under `<module>/src/main` of every module the BUILD declares
// (KotlinText.kotlinFiles), never a glob. The checker named its module homes by hand in one
// SRC_GLOBS line; the map is the same 744 files, proven file-for-file at the port.
//
// DENOMINATOR, FROM THE SOURCE (§24), never a hand list. Every `class` declaration whose NAME is
// followed — before any newline — by the primary-constructor paren: top-level and nested, every
// modifier spelling (`data`, `value`, `sealed`, `inner`, `annotation`, `expect`, `actual`,
// `private`), with optional type parameters, leading annotations and an explicit `constructor`
// keyword. 986 today. A class added tomorrow is in scope with no edit to this file. The paren must
// follow the class NAME, never merely appear on the line: `class X : Super(a, b)` is a supertype
// call, and admitting it would bill a class for arguments it passes rather than parameters it
// takes. Two guards refuse a vacuous pass: a parse yielding zero constructors FAILS, and a class
// whose constructor text cannot be walked FAILS by name.
//
// PARSE. The paren walk is comment- and string-aware (KotlinText.balancedSpan) because KDoc is
// interleaved BETWEEN parameters all over this tree, and a naive walk either stops at the first `)`
// inside a default value or runs past the constructor. The comma split is top-level, and ANGLE
// BRACKETS ARE NOT BRACKETS: `<` opens a type argument list only when it IMMEDIATELY follows an
// identifier (`Map<`, never `a < b`), and `>` closes one only while a list is open and the
// character is not the tail of `->` or `>=`. Counting every `<`/`>` as depth undercounted eight
// constructors against tree-sitter's own grammar, and undercounting is the direction that hides an
// offender.
//
// THREE WIDTHS, because they fail differently.
//   PARAMETERS  — more than 12. A constructor this wide has no call site a reader can check. Data
//                 classes are IN SCOPE here, deliberately, against detekt's ignore.
//   SUBSYSTEMS  — more than 6 distinct `splice.*` packages named by the parameter TYPES, resolved
//                 through the file's own single-type import lines. The architectural half: how many
//                 parts of the system one constructor has to know at once.
//   CONFIG KEYS — more than 32, and ONLY for a config record: a @Serializable class whose every
//                 parameter is a defaulted `val` and which names no subsystem. Its parameters are
//                 TOML keys, not collaborators, so grading them at 12 would count a config surface
//                 as a dependency list. The budget's job is to catch a dumping ground, not to cap a
//                 vendor surface. All three clauses are required: deleting the annotation puts the
//                 class back on the ordinary widths, which is what keeps this an exemption rather
//                 than a hole.
//
// VIOLATIONS. GROWTH — a constructor over a width that nothing records. WIDENED — a recorded one
// that grew past its entry; without it a baseline is a licence. PADDED — an entry recorded ABOVE
// the measurement, red with the exact new numbers and the resource to lower by hand. STALE — an
// entry naming a class that is gone or no longer offends. A shrink is red for the same reason a
// growth is: a baseline held above the measurement is unearned room for the next regression to
// hide in.
//
// NOT CAUGHT. SECONDARY constructors and factory functions — only the PRIMARY constructor is
// measured (detekt's functionThreshold 6 does bill plain functions, so the hole is narrow). A
// BUNDLE ONE LEVEL DOWN — replacing 25 parameters with one `HeadDeps` moves the count without
// moving the knowledge. A TYPE NAMED BY AN ALIAS OR A STAR IMPORT resolves to no subsystem and is
// simply not counted; no splice package is star-imported in production today.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object ConstructorWidth {
    const val MAX_PARAMS = 12
    const val MAX_SUBSYSTEMS = 6
    const val MAX_CONFIG_KEYS = 32

    /** The baseline's name on the test classpath, and the path a violation tells a human to edit. */
    const val BASELINE_RESOURCE = "constructor-width-baseline.json"
    const val BASELINE_PATH = "quality/architecture/src/test/resources/$BASELINE_RESOURCE"

    private val CLASS_DECL = Regex(
        "^[ \\t]*(?:(?:public|internal|private|protected|sealed|data|abstract|open|value|enum|inner|" +
            "annotation|expect|actual)[ \\t]+)*class[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*" +
            "(?:<[^<>\\n]*>)?[ \\t]*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)\\n]*\\))?[ \\t]*)*" +
            "(?:(?:private|protected|internal|public)[ \\t]+)*(?:constructor[ \\t]*)?\\(",
        RegexOption.MULTILINE,
    )

    // The same shape as checks/concentration.ts's SPLICE_IMPORT, so `subsystem` means one thing in
    // this repo: the `splice.<pkg>` a single-type import line resolves to.
    private val SPLICE_IMPORT = Regex("^import (splice\\.[A-Za-z0-9_.]+)\\.([A-Za-z0-9_]+)\\s*$", RegexOption.MULTILINE)
    private val TYPE_NAME = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")

    /** An ISO date, whole — Java's `$` would also match before a trailing newline, so `matches`. */
    private val RECORDED = Regex("\\d{4}-\\d{2}-\\d{2}")

    // A parameter that is a `val` WITH a default, annotations allowed in front. `[^=]*` is the type,
    // so the `=` it must reach is the default's, never an `=` inside a type expression.
    private val DEFAULTED_VAL = Regex(
        "^\\s*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)\\n]*\\))?\\s*)*val\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:[^=]*=",
    )

    /** One measured primary constructor. */
    data class Ctor(
        val name: String,
        val rel: String,
        val line: Int,
        val params: Int,
        val subsystems: List<String>,
        val configRecord: Boolean,
    ) {
        /** Baseline identity: the class name plus its file. `Success` and `Nested` repeat often
         *  enough in this tree that the name alone is not an identity, and the file is what makes a
         *  moved class read as a move rather than as a new offender. */
        val id: String get() = "$rel $name"

        /** Why this constructor is over, in the words the baseline's remedy quotes back. */
        fun over(): List<String> {
            // A CONFIG RECORD is measured against the config budget ALONE — it cannot reach the
            // other two by construction, because naming no subsystem is part of its definition.
            if (configRecord) {
                return if (params > MAX_CONFIG_KEYS) {
                    listOf(
                        "$params config keys (max $MAX_CONFIG_KEYS)",
                    )
                } else {
                    emptyList()
                }
            }
            val reasons = mutableListOf<String>()
            if (params > MAX_PARAMS) reasons += "$params parameters (max $MAX_PARAMS)"
            if (subsystems.size > MAX_SUBSYSTEMS) {
                reasons += "${subsystems.size} subsystems (max $MAX_SUBSYSTEMS): ${subsystems.joinToString(", ")}"
            }
            return reasons
        }
    }

    /** What one file, or the whole tree, measured — and what made the measurement untrustworthy. */
    data class Census(val constructors: List<Ctor>, val problems: List<String>)

    /** One class declaration's coordinates, so the measurement is not a six-parameter call. */
    data class Site(val rel: String, val text: String, val at: Int, val line: Int, val name: String)

    /** The numbers one baseline entry records. */
    data class Entry(val params: Int, val subsystems: Int)

    /** The recorded ratchet. A null entry is one the file spells wrongly: it is a problem already,
     *  and its widths are not compared, exactly as the checker skipped an unreadable entry. */
    data class Baseline(val recorded: String, val offenders: Map<String, Entry?>, val problems: List<String>)

    /** Bracket and type-argument depth for [splitParams]. Angles are read Kotlin's way: see PARSE. */
    private class ParamDepth {
        private var brackets = 0
        private var angles = 0

        val atTop: Boolean get() = brackets == 0 && angles == 0

        fun track(body: String, at: Int, ch: Char) {
            when {
                ch in "({[" -> brackets += 1
                ch in ")}]" -> brackets -= 1
                ch == '<' && opens(body, at) -> angles += 1
                ch == '>' && closes(body, at) -> angles -= 1
            }
        }

        /** `Map<` opens a type argument list; `a < b` does not, and `<=` is a comparison. */
        private fun opens(body: String, at: Int): Boolean {
            if (at == 0) return false
            val before = body[at - 1]
            val afterName = before.alnum() || before == '_' || before == '>'
            return afterName && body.getOrNull(at + 1) != '='
        }

        /** A `>` closes one only while a list is open and it is not the tail of `->` or `>=`. */
        private fun closes(body: String, at: Int): Boolean {
            val arrow = at > 0 && body[at - 1] == '-'
            val comparison = body.getOrNull(at + 1) == '='
            return angles > 0 && !arrow && !comparison
        }

        /** Python's `str.isalnum()` over ASCII, which is what the lookbehind above was written as. */
        private fun Char.alnum(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }

    /** Split a constructor body on TOP-LEVEL commas, dropping comments.
     *
     *  Top-level is what makes `Map<String, QuotaTracker>` one parameter rather than two, and
     *  dropping comments is what keeps a KDoc sentence containing a comma from minting one. */
    fun splitParams(body: String): List<String> {
        val kinds = KotlinText.kinds(body)
        val depth = ParamDepth()
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        for (i in body.indices) {
            if (kinds[i] == KotlinText.COMMENT) continue
            val ch = body[i]
            val code = kinds[i] == KotlinText.CODE
            if (code) depth.track(body, i, ch)
            val separates = code && ch == ',' && depth.atTop
            if (separates) {
                parts += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) parts += buf.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A CONFIG RECORD — the shape [MAX_CONFIG_KEYS] governs. All three clauses are required, and
     *  each is doing work: @Serializable says the class is a wire or config SURFACE; every
     *  parameter a defaulted `val` says every field is a key the config may omit (one required
     *  parameter means a required COLLABORATOR, and `var` is state rather than a key); no subsystem
     *  says it wires nothing. */
    private fun isConfigRecord(text: String, classStart: Int, params: List<String>, subsystems: List<String>): Boolean {
        if (subsystems.isNotEmpty() || params.isEmpty()) return false
        if (!params.all { DEFAULTED_VAL.containsMatchIn(it) }) return false
        val above = text.substring(0, classStart).trimEnd('\n').split("\n")
        return above.asReversed()
            .takeWhile { it.trim().startsWith("@") }
            .any { it.trim().startsWith("@Serializable") }
    }

    /** One declaration's width, or null when the parens hold no parameter at all (`class X()`). */
    private fun measured(site: Site, body: String, imports: Map<String, String>): Ctor? {
        val params = splitParams(body)
        if (params.isEmpty()) return null
        val subsystems = params
            .flatMap { param -> TYPE_NAME.findAll(param).map { it.groupValues[1] }.toList() }
            .mapNotNull { imports[it] }
            .distinct()
            .sorted()
        return Ctor(
            site.name,
            site.rel,
            site.line,
            params.size,
            subsystems,
            isConfigRecord(site.text, site.at, params, subsystems),
        )
    }

    /** Every primary constructor in one file, and the declarations whose parens do not close. */
    fun measureFile(rel: String, text: String): Census {
        val imports = SPLICE_IMPORT.findAll(text).associate { it.groupValues[2] to it.groupValues[1] }
        val found = mutableListOf<Ctor>()
        val problems = mutableListOf<String>()
        for (match in CLASS_DECL.findAll(text)) {
            val at = match.range.first
            val site = Site(rel, text, at, KotlinText.lineOf(text, at), match.groupValues[1])
            val span = KotlinText.balancedSpan(text, at + match.value.length - 1, '(', ')')
            if (span == null) {
                problems += "$rel:${site.line}: ${site.name}'s primary constructor could not be walked — " +
                    "no width from this run can be trusted"
            } else {
                val ctor = measured(site, text.substring(span.bodyStart, span.closerAt), imports)
                if (ctor != null) found += ctor
            }
        }
        return Census(found, problems)
    }

    /** The whole tree, named relative to [root]. An empty parse REFUSES rather than passing. */
    fun collect(files: List<File>, root: File): Census {
        val constructors = mutableListOf<Ctor>()
        val problems = mutableListOf<String>()
        for (file in files) {
            val measured = measureFile(file.relativeTo(root).invariantSeparatorsPath, file.readText())
            constructors += measured.constructors
            problems += measured.problems
        }
        if (constructors.isEmpty()) {
            problems += "parsed 0 primary constructors under the project map's src/main trees — refusing to " +
                "pass vacuously, because a green over an empty denominator is what this wall exists to prevent"
        }
        return Census(constructors, problems)
    }

    /** Widest first, then most subsystems, then by identity — the order the report prints. */
    fun offendersOf(constructors: List<Ctor>): List<Ctor> = constructors
        .filter { it.over().isNotEmpty() }
        .sortedWith(compareByDescending<Ctor> { it.params }.thenByDescending { it.subsystems.size }.thenBy { it.id })

    /** The baseline document, or the reason it cannot be graded against. */
    private fun parsed(text: String?): Pair<JsonObject?, String?> {
        if (text == null) {
            return null to "$BASELINE_PATH: missing — the ratchet has no baseline to grade against. " +
                "Write one entry per constructor already over a width, by hand."
        }
        return try {
            Json.parseToJsonElement(text).jsonObject to null
        } catch (unreadable: IllegalArgumentException) {
            null to "$BASELINE_PATH: is not valid JSON ($unreadable) — a baseline nobody can parse grades nothing"
        }
    }

    /** `{ "params": <int>, "subsystems": <int> }`, or null when the entry records no numbers. */
    private fun entryOf(element: JsonElement): Entry? {
        val fields = element as? JsonObject ?: return null
        val params = (fields["params"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        val subsystems = (fields["subsystems"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        return if (params == null || subsystems == null) null else Entry(params, subsystems)
    }

    fun readBaseline(text: String?): Baseline {
        val (document, unreadable) = parsed(text)
        if (document == null) return Baseline("", emptyMap(), listOfNotNull(unreadable))
        val recorded = (document["recorded"] as? JsonPrimitive)?.content.orEmpty()
        val problems = mutableListOf<String>()
        if (!RECORDED.matches(recorded)) {
            problems += "$BASELINE_PATH: `recorded` is ${KotlinText.pyRepr(recorded)} — every baseline carries " +
                "the ISO date it was measured; an undated baseline is how the next regression hides."
        }
        val offenders = document["offenders"] as? JsonObject
        if (offenders == null) {
            problems += "$BASELINE_PATH: `offenders` must be an object keyed '<file> <ClassName>'"
            return Baseline(recorded, emptyMap(), problems)
        }
        val entries = linkedMapOf<String, Entry?>()
        for ((key, value) in offenders) {
            val entry = entryOf(value)
            if (entry == null) {
                problems += "$BASELINE_PATH: entry ${KotlinText.pyRepr(key)} must carry integer `params` and " +
                    "`subsystems` — an entry with no measured numbers records nothing and can neither grow nor shrink"
            }
            entries[key] = entry
        }
        return Baseline(recorded, entries, problems)
    }

    /** A constructor over a width that nothing records. */
    private fun growth(offender: Ctor): String =
        "GROWTH: ${offender.rel}:${offender.line} ${offender.name} is over a constructor width and nothing " +
            "records it — ${offender.over().joinToString("; ")}. detekt cannot see this " +
            "(quality/detekt/detekt.yml:38-43 ignores data classes and defaulted parameters), which is why this " +
            "wall exists. Take the bundle apart, or add `${KotlinText.pyRepr(offender.id)}: { \"params\": " +
            "${offender.params}, \"subsystems\": ${offender.subsystems.size} }` to $BASELINE_PATH by hand — a " +
            "diff saying the tree got wider."

    /** A recorded offender that moved: WIDER is a regression, NARROWER is a win to be written down. */
    private fun moved(offender: Ctor, was: Entry): String? {
        val grew = offender.params > was.params || offender.subsystems.size > was.subsystems
        val shrank = offender.params < was.params || offender.subsystems.size < was.subsystems
        return when {
            grew ->
                "WIDENED: ${offender.rel}:${offender.line} ${offender.name} grew past its recorded width — " +
                    "params ${was.params} -> ${offender.params}, subsystems ${was.subsystems} -> " +
                    "${offender.subsystems.size}. A recorded offender is DEBT, not permission to keep adding parameters."
            shrank ->
                "PADDED: ${offender.rel}:${offender.line} ${offender.name} measures params " +
                    "${offender.params} / subsystems ${offender.subsystems.size} but its entry records ${was.params} / " +
                    "${was.subsystems}. Lower it BY HAND in $BASELINE_PATH to `\"params\": ${offender.params}, " +
                    "\"subsystems\": ${offender.subsystems.size}` — nothing here rewrites the file, so the ratchet " +
                    "stays visible in a diff. A baseline held above the measurement is unearned room for the next " +
                    "regression to hide in."
            else -> null
        }
    }

    /** The GATE: growth, widening, padding and stale entries over [baseline]. */
    fun ratchet(census: Census, baseline: Baseline): List<String> {
        val offenders = offendersOf(census.constructors).associateBy { it.id }
        val problems = (census.problems + baseline.problems).toMutableList()
        for (key in offenders.keys.sorted()) {
            val offender = offenders.getValue(key)
            if (key !in baseline.offenders) {
                problems += growth(offender)
            } else {
                val was = baseline.offenders[key]
                val regression = if (was == null) null else moved(offender, was)
                if (regression != null) problems += regression
            }
        }
        for (key in baseline.offenders.keys.filterNot { it in offenders }.sorted()) {
            problems += "STALE: the baseline lists ${KotlinText.pyRepr(key)}, which is no longer over any width " +
                "(taken apart, renamed, or deleted) — delete the entry from $BASELINE_PATH by hand, so the list " +
                "keeps meaning 'known debt'."
        }
        return problems
    }

    /** The shipped baseline, read off the test classpath. */
    fun baselineText(): String? =
        ConstructorWidth::class.java.getResourceAsStream("/$BASELINE_RESOURCE")?.use { it.readBytes().decodeToString() }
}

class ConstructorWidthLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every primary constructor's width is at or under its recorded ratchet - V4-93`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(files.size > 10) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that reads no " +
                "files passes vacuously."
        }
        val census = ConstructorWidth.collect(files, map.root)
        assertTrue(census.constructors.size > 200) {
            "measured ${census.constructors.size} primary constructor(s) — the parse has lost the tree."
        }
        val problems = ConstructorWidth.ratchet(census, ConstructorWidth.readBaseline(ConstructorWidth.baselineText()))
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "CONSTRUCTOR WIDTH (V4-93) violated:\n  - ")
        }
    }

    /** THE PREMISE, re-read on every gate run (checks/constructor-width-selftest.sh's first arm).
     *  This wall exists because detekt's LongParameterList is turned off for the two shapes this
     *  tree uses. If someone deletes the RULE, this wall is the only thing left and its subject must
     *  not disappear silently. If someone deletes the two IGNORES instead, detekt starts billing
     *  some of these widths itself and this law's header is stale rather than wrong — that is a
     *  re-read, not a failure, exactly as the checker's harness graded it. */
    @Test
    fun `detekt still cannot see a constructor's width - V4-93`() {
        val detekt = File(map.root, DETEKT_CONFIG)
        assertTrue(detekt.isFile) { "$DETEKT_CONFIG is missing — this law's premise cannot be read" }
        assertTrue(detekt.readText().contains("LongParameterList:")) {
            "$DETEKT_CONFIG no longer configures LongParameterList — the instrument this wall is written " +
                "against has moved, so re-read this file's header before trusting either one."
        }
    }

    /** §24: CLASS_DECL is a REGEX, and a regex census cannot cross-check itself — a class it cannot
     *  see is a constructor this law never measures and never reports, which reads as a green. The
     *  denominator is taken a SECOND time from the Kotlin compiler frontend this module already
     *  depends on, and a vacuous agreement at zero is refused. `measured` drops a match whose
     *  parameter list is empty, so the AST side counts classes with a NON-EMPTY primary constructor
     *  — the same subset, taken a different way. */
    @Test
    fun `the text census equals Konsist's independent AST census - V4-93`() {
        val ast = map.modules
            .filter { module -> map.mainSources(module).isDirectory }
            .sumOf { module ->
                Konsist.scopeFromDirectory("${map.relativeDir(module)}/src/main/kotlin")
                    .classes(includeNested = true)
                    .count { it.primaryConstructor?.parameters?.isNotEmpty() == true }
            }
        assertTrue(ast > 0) { "the AST census found ZERO primary constructors — refusing a vacuous agreement" }
        assertEquals(
            ast,
            ConstructorWidth.collect(KotlinText.kotlinFiles(map), map.root).constructors.size,
            "the regex census and Konsist's AST census disagree — one of the two is wrong, and a " +
                "denominator nobody can reproduce is not a denominator",
        )
    }

    /** The synthetic tree the red proofs write into: one production file per arm, plus the baseline
     *  resource handed in as text so every baseline shape is provable without touching the real one. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))

        fun write(vararg sources: Pair<String, String>) {
            File(root, "app/src/main/kotlin").deleteRecursively()
            for ((rel, text) in sources) {
                File(root, "app/src/main/kotlin/$rel").apply { parentFile.mkdirs() }.writeText(text)
            }
        }

        fun census() = ConstructorWidth.collect(KotlinText.kotlinFiles(synthetic), root)

        fun ratchet(baseline: String) = ConstructorWidth.ratchet(census(), ConstructorWidth.readBaseline(baseline))
    }

    @Test
    fun `the widths themselves are the boundary, and a bare class is out of the denominator - V4-93`(
        @TempDir root: File,
    ) {
        with(Tree(root)) {
            write(
                "Fixture.kt" to params(ConstructorWidth.MAX_PARAMS),
                "Wide.kt" to subsystems(ConstructorWidth.MAX_SUBSYSTEMS),
            )
            assertEquals(emptyList<String>(), ratchet(baseline()), "12 parameters and 6 subsystems are AT the limit")

            // THE BORING CASE: one constructor, one parameter, and the count must come out at one.
            write("Fixture.kt" to "package splice.selftest\n\npublic class SelftestOne(val p0: Int)\n")
            assertEquals(emptyList<String>(), ratchet(baseline()), "the one-item tree grades green")
            assertEquals(1, census().constructors.size)

            // A class with NO primary constructor is out of the denominator, not an offender — so a
            // tree holding only those refuses rather than reporting a clean sweep.
            write(
                "Fixture.kt" to "package splice.selftest\n\npublic class SelftestNoCtor\npublic object SelftestObject\n",
            )
            assertHit(ratchet(baseline()), "vacuously") { "a tree with no constructors must REFUSE" }
            assertHit(Tree(File(root, "empty")).ratchet(baseline()), "vacuously") { "an empty tree must REFUSE" }
        }
    }

    @Test
    fun `the law can actually fail - growth past either width - V4-93`(@TempDir root: File) {
        with(Tree(root)) {
            // The mutation this row requires, in the exact shape detekt ignores: a data class whose
            // every parameter is defaulted, one parameter over.
            write("Fixture.kt" to params(ConstructorWidth.MAX_PARAMS + 1))
            var hits = ratchet(baseline())
            assertHit(hits, "GROWTH", "SelftestData13") { "a 13-parameter defaulted DATA class must be RED by name" }
            assertEquals(ConstructorWidth.MAX_PARAMS + 1, census().constructors.single().params, "the count is exact")

            write("Wide.kt" to subsystems(ConstructorWidth.MAX_SUBSYSTEMS + 1))
            hits = ratchet(baseline())
            assertHit(hits, "GROWTH", "SelftestWide7", "subsystems (max 6)") { "7 subsystems must be RED by name" }

            // ...and the same tree with the offender RECORDED at its measured width holds.
            write("Fixture.kt" to params(ConstructorWidth.MAX_PARAMS + 1))
            assertEquals(
                emptyList<String>(),
                ratchet(baseline("\"$FIXTURE_REL SelftestData13\": { \"params\": 13, \"subsystems\": 0 }")),
                "a RECORDED offender at its measured width is not growth",
            )
        }
    }

    @Test
    fun `the law can actually fail - a recorded offender that moved - V4-93`(@TempDir root: File) {
        with(Tree(root)) {
            write("Fixture.kt" to params(ConstructorWidth.MAX_PARAMS + 5))
            assertHit(
                ratchet(baseline("\"$FIXTURE_REL SelftestData17\": { \"params\": 13, \"subsystems\": 0 }")),
                "WIDENED",
                "SelftestData17",
            ) { "a recorded offender that grew past its entry must be RED" }

            // THE SHRINK, which is red for the same reason: the entry is above the measurement.
            write("Fixture.kt" to params(ConstructorWidth.MAX_PARAMS + 1))
            val padded = ratchet(baseline("\"$FIXTURE_REL SelftestData13\": { \"params\": 99, \"subsystems\": 0 }"))
            assertHit(padded, "PADDED", "SelftestData13", "params 13", "Lower it BY HAND") {
                "an entry recorded above the measured width must be RED with the new number"
            }
            assertTrue(padded.single().contains(ConstructorWidth.BASELINE_PATH), padded.single())

            // ...and an entry naming a class the tree no longer has is the other half of the shrink.
            assertHit(
                ratchet(baseline("\"app/src/main/kotlin/Gone.kt WasWideOnce\": { \"params\": 20, \"subsystems\": 0 }")),
                "STALE",
                "WasWideOnce",
                ConstructorWidth.BASELINE_PATH,
            ) { "an entry naming a constructor that is gone must be RED" }
        }
    }

    @Test
    fun `the law can actually fail - an unreadable baseline - V4-93`(@TempDir root: File) {
        with(Tree(root)) {
            write("Fixture.kt" to params(ConstructorWidth.MAX_PARAMS))
            assertHit(ratchet(baseline(recorded = "")), "recorded") { "an undated baseline is a hard error" }
            assertHit(ConstructorWidth.readBaseline(null).problems, "missing") { "a missing baseline refuses" }
            assertHit(
                ConstructorWidth.readBaseline("{").problems,
                "not valid JSON",
            ) { "an unparsable baseline refuses" }
            assertHit(
                ConstructorWidth.readBaseline("{\"recorded\": \"2026-09-21\", \"offenders\": []}").problems,
                "must be an object keyed",
            ) { "an offenders list is not an offenders map" }
            assertHit(
                ConstructorWidth.readBaseline(baseline("\"a b\": { \"params\": \"13\" }")).problems,
                "must carry integer",
            ) { "an entry with no measured numbers records nothing" }
        }
    }

    @Test
    fun `the config-record budget is an exemption, not a hole - V4-93`(@TempDir root: File) {
        with(Tree(root)) {
            // 33 keys: one past MAX_CONFIG_KEYS, and the class satisfies all three clauses.
            write("Config.kt" to configRecord(ConstructorWidth.MAX_CONFIG_KEYS + 1))
            assertHit(ratchet(baseline()), "config keys", "SelftestConfig33") { "a record one key over is RED" }

            // The same defaulted vals at 22 — over MAX_PARAMS, well under MAX_CONFIG_KEYS — WITHOUT
            // the annotation: still graded at the ordinary width, so the exemption cannot be reached
            // by deleting one line.
            write("Config.kt" to configRecord(22).replace("@Serializable\n", ""))
            assertHit(ratchet(baseline()), "parameters (max 12)", "SelftestConfig22") {
                "the same vals without @Serializable stay red at the ordinary width"
            }

            // ...and a config record inside the budget is green, which is what makes it an exemption.
            write("Config.kt" to configRecord(ConstructorWidth.MAX_CONFIG_KEYS))
            assertEquals(emptyList<String>(), ratchet(baseline()), "32 keys are AT the config budget, not over")
        }
    }

    @Test
    fun `the parse survives interleaved KDoc, generics and lambda defaults - V4-93`(@TempDir root: File) {
        with(Tree(root)) {
            write("Fixture.kt" to KDOC_SOURCE)
            val census = census()
            assertEquals(emptyList<String>(), census.problems, "the constructor must parse")
            val kdoc = census.constructors.single { it.name == "SelftestKdoc" }
            assertEquals(4, kdoc.params, "a KDoc comma, a `Map<String, Int>` and a lambda default are one each")
            assertEquals(listOf("splice.sub0"), kdoc.subsystems)
            assertEquals(emptyList<String>(), ratchet(baseline()), "four parameters are not a width")

            // A supertype call is not a primary constructor: admitting it would bill a class for the
            // arguments it passes rather than the parameters it takes.
            write("Fixture.kt" to "package splice.selftest\n\npublic class SelftestSub : Base(1, 2, 3)\n")
            assertHit(ratchet(baseline()), "vacuously") { "`class X : Super(...)` is not a primary constructor" }
        }
    }

    private companion object {
        const val FIXTURE_REL = "app/src/main/kotlin/Fixture.kt"
        const val DETEKT_CONFIG = "quality/detekt/detekt.yml"

        /** A baseline document with [entries] spelled the way the resource spells them. */
        fun baseline(entries: String = "", recorded: String = "2026-09-21"): String =
            "{\n  \"recorded\": \"$recorded\",\n  \"offenders\": {$entries}\n}\n"

        /** `n` defaulted parameters in the shape detekt's two ignores cover together. */
        fun params(n: Int): String {
            val body = (0 until n).joinToString(",\n") { "    val p$it: Int = 0" }
            return "package splice.selftest\n\npublic data class SelftestData$n(\n$body,\n)\n"
        }

        /** `n` parameters whose types resolve to `n` distinct splice subsystems. */
        fun subsystems(n: Int): String {
            val imports = (0 until n).joinToString("\n") { "import splice.sub$it.Type$it" }
            val body = (0 until n).joinToString(",\n") { "    val p$it: Type$it" }
            return "package splice.selftest\n\n$imports\n\npublic class SelftestWide$n(\n$body,\n)\n"
        }

        /** A @Serializable record of `n` defaulted `val` keys naming no subsystem. */
        fun configRecord(n: Int): String {
            val body = (0 until n).joinToString(",\n") { "    val k$it: Int = 0" }
            return "package splice.selftest\n\nimport kotlinx.serialization.Serializable\n\n" +
                "@Serializable\npublic data class SelftestConfig$n(\n$body,\n)\n"
        }

        const val KDOC_SOURCE = """package splice.selftest

import splice.sub0.Type0

public data class SelftestKdoc(
    val a: Type0,
    /** A KDoc, with a comma and a ) in it, between parameters. */
    val b: Map<String, Int> = mapOf("x" to 1),
    val c: Int = 0,
    val d: (Int) -> Int = { it + 1 },
)
"""
    }
}
