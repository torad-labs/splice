// NEW: V4-92 — a module's PUBLIC surface must have a consumer in another module, or be internal.
// RATCHET. (ported from checks/public-surface.ts, restructure PR 6 §4.3.)
//
// CLASS. Every library module here runs under `explicitApi()`, so every declaration spells its
// visibility out loud — and `public` is what an author types when they are not thinking about the
// module boundary, because it is what the compiler asks for and the error that demands it says
// nothing about who the reader is. A public declaration nobody outside the module consumes is not
// an API, it is a leak of the module's internals into its ABI: `internal` is the same code with the
// boundary stated. Nothing was measuring it — detekt has no such rule, the module law governs
// GRADLE edges and not visibility, and the Konsist laws read packages rather than consumer sets —
// so the surface grew for a whole campaign under a green gate. Too many declarations offend today
// for `internal or bust` to be the gate, and that many exemptions would be the laundering a
// baseline exists to prevent, so what is enforced is the DIRECTION.
//
// SCOPE. The module universe is the BUILD's, read through [ProjectMap] — never a regex over
// settings.gradle.kts, which is a second reading of the same fact and can disagree with the build
// that actually runs. PRODUCERS are the library modules: every module the build declares, minus the
// `nonLibrary` set read off build-logic/…/splice.module-law.gradle.kts, which is the build's own
// statement of what has no explicit `public` to read. CONSUMERS are every module including the
// nonLibrary ones (:app is the composition root and consuming a library's API is its whole job),
// through main AND testFixtures sources — a fixture is shipped, cross-module code.
//
// src/test IS DELIBERATELY NOT A CONSUMER, and that is the point rather than an oversight. A
// same-module test needs no visibility at all, and a SIBLING module's test reaching a type is the
// shape the audit found six times over — each declaration public solely so a test could reach it.
// Counting test callers would make this wall green over exactly the population it exists to name.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every top-level `public` declaration in a library module's
// src/main/kotlin, parsed off disk; every Kotlin spelling is admitted, `fun interface` and
// `annotation class` included. THREE GUARDS refuse a vacuous pass: a module law that does not
// parse, zero library modules, and zero public declarations across all of them — a checker that
// silently loses its denominator is a checker that passes.
//
// PARSE. A declaration is JUSTIFIED when another module's consuming text NAMES it by
// fully-qualified name (`import splice.x.Y` and a bare `splice.x.Y` use are the same token, matched
// on whole-token boundaries) or star-imports its package. Justification then PROPAGATES through
// public signatures to a fixpoint: a public member's parameter or return type is part of the
// contract even when no source file spells it, because the call site binds it by inference and a
// name-based scan cannot see that. The signature read is the declaration's own header plus the
// headers of its public MEMBERS — headers, not first lines, because a member whose parameter list
// wraps hid its own types for a whole row. Both approximations are deliberate: over-justifying
// costs a missed declaration, under-justifying cost five good ones.
//
// VIOLATIONS. GROWTH — a declaration no other module consumes that the baseline does not record —
// is RED BY NAME on the commit that adds it. A SHRINK is RED too: a baseline entry that has stopped
// offending names the exact new surface count and the resource to edit BY HAND. The checker's
// `--ratchet` failed the same way and printed `--write-baseline` as the remedy; here the remedy is
// the reader's own edit, so the ratchet stays visible in a diff instead of being rewritten by a
// gate run. `kept` carries the entries that cannot be burned, each with its reason: a BLANK reason
// is an absence wearing a label, and a reason for an entry the baseline does not hold is a
// half-finished burn-down wearing a justification. Both are red.
//
// NOT CAUGHT, stated here rather than discovered later. MEMBERS of public types, referenced as
// `x.trimToLast(...)` with no FQN and no import: no name-based rule can attribute them to their
// owner without a resolved type graph. A declaration consumed ONLY BY A STRING — reflection, a
// serializer name, a DI key — reads as unjustified; the remedy is the same as for any false red,
// make it internal and let the compiler say so. SAME-PACKAGE CROSS-MODULE USE needs no import and
// would be invisible here; measured at the port, no package in this tree is declared by two
// modules, so the hole is empty today and this paragraph is the marker.
package splice.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The recorded debt on the test classpath, and the path a violation tells the reader to edit. */
private const val BASELINE_RESOURCE = "/public-surface-baseline.json"
private const val BASELINE_PATH = "quality/architecture/src/test/resources/public-surface-baseline.json"

/** How many lines a declaration header may span before the scan gives up. A cap rather than "until
 *  the body", because a header that never closes would otherwise swallow the rest of the file and
 *  justify every name in it — this row's own failure mode, inverted. */
private const val SIGNATURE_MAX_LINES = 20

private val HEADER_END = Regex("=\\s*$")

/** `\w`, ASCII, exactly as both the checker's JS engine and Java's default read it. */
private const val WORD_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

private fun isWord(ch: Char): Boolean = ch in WORD_CHARS

private fun isWordOrDot(ch: Char): Boolean = ch == '.' || isWord(ch)

/** The `(?<![\w.])` half of the token: start of text, or a character outside a dotted identifier. */
private fun freeStart(ch: Char?): Boolean = ch == null || !isWordOrDot(ch)

/** The `(?![\w])` half: end of text, or a non-word character. A trailing `.` PASSES, so an FQN used
 *  as the prefix of a longer path (`splice.x.Y.Companion`) is a use of `splice.x.Y`, as there. */
private fun freeEnd(ch: Char?): Boolean = ch == null || !isWord(ch)

private fun closesHeader(line: String): Boolean = line.contains("{") || HEADER_END.containsMatchIn(line)

/** A declaration's HEADER: its own line plus the continuation lines up to the body, capped. */
private fun headerOf(lines: List<String>, at: Int): List<String> {
    val out = mutableListOf<String>()
    for (index in at until minOf(lines.size, at + SIGNATURE_MAX_LINES)) {
        out += lines[index]
        if (closesHeader(lines[index])) break
    }
    return out
}

/** The text that carries a declaration's reachable type names: its own header, plus the headers of
 *  its PUBLIC members. A member's BODY is excluded, so a type named only inside a private member
 *  slips through and OVER-justifies; that direction is chosen deliberately. */
private fun signatureOf(lines: List<String>, at: Int): String {
    val out = headerOf(lines, at).toMutableList()
    var index = at + maxOf(out.size, 1)
    while (index < lines.size) {
        if (PublicSurface.DECLARATION.containsMatchIn(lines[index])) break
        val member = if (PublicSurface.MEMBER.containsMatchIn(lines[index])) headerOf(lines, index) else emptyList()
        out += member
        // Past the member's own header, so its parameter lines are not re-read as members.
        index += maxOf(member.size, 1)
    }
    return out.joinToString("\n")
}

internal object PublicSurface {
    const val MODULE_LAW = "build-logic/src/main/kotlin/splice.module-law.gradle.kts"
    const val MAIN = "src/main/kotlin"
    const val TEST_FIXTURES = "src/testFixtures/kotlin"

    /** explicitApi() makes the modifier mandatory, so `public` at column 0 IS the public top-level
     *  surface. Every Kotlin spelling is admitted — `fun interface` and `annotation class` included. */
    val DECLARATION = Regex(
        "^public\\s+(?:(?:sealed|data|abstract|open|value|enum|fun|annotation|suspend|inline|expect|external|" +
            "const)\\s+)*(class|interface|object|fun|val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)",
    )

    /** A PUBLIC MEMBER — the half that defeated the first cut of this wall. An indented `public` is
     *  a member signature and not prose, because explicitApi() makes the modifier mandatory. */
    val MEMBER = Regex("^\\s+(?:public\\s|override\\s+public\\s|public\\s+override\\s)")

    private val MODULE_PATH = Regex("\"(:[A-Za-z0-9._-]+)\"")
    private val NON_LIBRARY = Regex("val nonLibrary = setOf\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
    private val PACKAGE = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
    private val STAR_IMPORT = Regex("^import\\s+([A-Za-z0-9_.]+)\\.\\*", RegexOption.MULTILINE)
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val LINE_SPLIT = Regex("\\r\\n|\\r|\\n")

    /** One public top-level declaration, and where it lives. */
    data class Declaration(
        val module: String,
        val pkg: String,
        val name: String,
        val kind: String,
        val rel: String,
        val line: Int,
        val signature: String,
    ) {
        val fqn: String get() = if (pkg.isEmpty()) name else "$pkg.$name"

        /** Baseline identity: module + FQN. Deliberately NOT the file or the line — those churn on a
         *  move that changes nothing about the surface, and a baseline that goes stale on a rename
         *  teaches the reader to regenerate it without reading. */
        val id: String get() = "$module $fqn"
        val locus: String get() = "$rel:$line"
    }

    /** One module's consuming text and the packages it star-imports. */
    data class Consumer(val blob: String, val stars: Set<String>)

    /** The measurement: the offenders, the declarations examined, and what makes it untrusted. */
    data class Surface(val offenders: List<Declaration>, val examined: Int, val problems: List<String>)

    /** The recorded debt, and the two ways the `kept` block can lie. */
    data class Baseline(val offenders: Set<String>, val kept: Map<String, String>, val recorded: String) {
        /** A declaration no other module consumes that this baseline does not record. */
        fun growth(measured: Map<String, Declaration>): List<String> {
            val grown = measured.keys.filterNot { it in offenders }.sorted()
            if (grown.isEmpty()) return emptyList()
            val named = grown.joinToString("\n    ") { entry ->
                val declaration = measured.getValue(entry)
                "$entry  (${declaration.kind} at ${declaration.locus})"
            }
            return listOf(
                "GROWTH: ${grown.size} public declaration(s) no other module consumes are not in the baseline. " +
                    "Make each one `internal` (the same code, with the module boundary stated), or — if a " +
                    "consumer is genuinely coming — add the line to $BASELINE_PATH BY HAND, which is a dated " +
                    "diff saying the surface grew:\n    " + named,
            )
        }

        /** An entry that has stopped offending. `kept` is where a burn-proof entry carries the reason
         *  it cannot move, so the union of what is measured and what is explained is the set this
         *  baseline is allowed to hold; an entry in neither is unearned room. */
        fun stale(measured: Map<String, Declaration>): List<String> {
            val gone = offenders.filterNot { it in measured }.filterNot { it in kept }.sorted()
            if (gone.isEmpty()) return emptyList()
            return listOf(
                "STALE: ${gone.size} baseline entry(ies) no longer offend — the declaration is gone, became " +
                    "internal, or gained a real consumer. The measured surface is now ${measured.size} " +
                    "unjustified declaration(s): delete these ${gone.size} line(s) from $BASELINE_PATH BY HAND " +
                    "in the same commit, so the ratchet stays visible in a diff instead of being rewritten by a " +
                    "gate run. A baseline held above the measured surface is unearned room for the next " +
                    "regression to hide in:\n    " + gone.joinToString("\n    "),
            )
        }

        /** A reason that is blank, and a reason for something this baseline does not hold. */
        fun keptProblems(): List<String> {
            val out = mutableListOf<String>()
            val blank = kept.filterValues { it.isBlank() }.keys.sorted()
            if (blank.isNotEmpty()) {
                out += "BLANK REASON: ${blank.size} `kept` entry(ies) carry no reason. A blank reason is an " +
                    "absence wearing a label — it reads as discharged in a diff and discharges nothing, which " +
                    "is worse than no reason at all because it stops the next reader asking:\n    " +
                    blank.joinToString("\n    ")
            }
            val orphan = kept.keys.filterNot { it in offenders }.sorted()
            if (orphan.isNotEmpty()) {
                out += "ORPHAN KEPT: ${orphan.size} `kept` entry(ies) explain something the baseline does not " +
                    "hold. A burnt entry is REMOVED from `offenders`, so a reason left behind is either stale " +
                    "bookkeeping or a half-finished burn-down wearing a justification:\n    " +
                    orphan.joinToString("\n    ")
            }
            return out
        }

        companion object {
            private val RECORDED = Regex("^\\d{4}-\\d{2}-\\d{2}$")

            /** The recorded debt, or the problems that stop the ratchet from grading against it. */
            fun parse(text: String?): Pair<Baseline?, List<String>> {
                if (text == null) {
                    return null to listOf(
                        "$BASELINE_PATH: missing — the ratchet has no baseline to grade against",
                    )
                }
                val read = runCatching { readDocument(text) }
                return read.getOrNull() ?: (
                    null to listOf(
                        "$BASELINE_PATH: is not valid JSON (${read.exceptionOrNull()?.message}) — a baseline " +
                            "nobody can parse grades nothing",
                    )
                    )
            }

            /** Throws on anything the JSON cannot answer; [parse] turns that into the one problem. */
            private fun readDocument(text: String): Pair<Baseline?, List<String>> {
                val document = Json.parseToJsonElement(text).jsonObject
                val recorded = (document["recorded"] as? JsonPrimitive)?.content.orEmpty()
                val problems = mutableListOf<String>()
                if (!RECORDED.matches(recorded)) {
                    problems += "$BASELINE_PATH: `recorded` is '$recorded' — every baseline carries the ISO date " +
                        "it was measured; an undated baseline is how the next regression hides"
                }
                val entries = offendersOf(document)
                if (entries == null) {
                    problems += "$BASELINE_PATH: `offenders` must be a list of '<module> <fqn>' strings"
                    return null to problems
                }
                return Baseline(entries.toSet(), keptOf(document, problems), recorded) to problems
            }

            private fun offendersOf(document: JsonObject): List<String>? {
                val array = document["offenders"] as? JsonArray ?: return null
                val strings = array.mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
                return if (strings.size == array.size) strings else null
            }

            /** `kept` is the companion to `offenders`. Absent is legal — a baseline that needs no
             *  reasons has none; present but malformed is not, because a reason nobody can read is
             *  the absence it was written to remove. The `law` key is the block's own prose header. */
            private fun keptOf(document: JsonObject, problems: MutableList<String>): Map<String, String> {
                val raw = document["kept"] ?: return emptyMap()
                val entries = raw as? JsonObject
                if (entries == null) {
                    problems += "$BASELINE_PATH: `kept` must be an object mapping '<module> <fqn>' to a reason string"
                    return emptyMap()
                }
                return entries.filterKeys { it != "law" }
                    .mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() }
            }
        }
    }

    /** The set the BUILD exempts from explicitApi, read off the module law and never retyped here. */
    fun nonLibrary(lawText: String?): Pair<Set<String>, List<String>> {
        if (lawText == null) {
            return emptySet<String>() to listOf(
                "$MODULE_LAW: missing — nonLibrary is what tells a producer from a consumer",
            )
        }
        val match = NON_LIBRARY.find(lawText) ?: return emptySet<String>() to listOf(
            "$MODULE_LAW: `val nonLibrary = setOf(...)` not found — the producer/consumer split cannot be " +
                "derived, so no surface from this run can be trusted",
        )
        return MODULE_PATH.findAll(match.groupValues[1]).map { it.groupValues[1] }.toSet() to emptyList()
    }

    /** The module's `.kt` under [sourceSet], in path order — the per-module spelling of
     *  [KotlinText.kotlinFiles], which this law needs one module at a time because the consumer set
     *  is per module. The live test asserts the union of the two is that reader's own answer. */
    fun sources(map: ProjectMap, module: String, sourceSet: String): List<File> {
        val root = File(map.dir(module), sourceSet)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private fun declarations(map: ProjectMap, module: String): List<Declaration> =
        sources(map, module, MAIN).flatMap { file ->
            declarationsIn(module, KotlinText.rel(map, file), file.readText())
        }

    private fun declarationsIn(module: String, rel: String, text: String): List<Declaration> {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        val lines = text.split(LINE_SPLIT)
        return lines.indices.mapNotNull { index ->
            DECLARATION.find(lines[index])?.let { match ->
                Declaration(
                    module = module,
                    pkg = pkg,
                    name = match.groupValues[2],
                    kind = match.groupValues[1],
                    rel = rel,
                    line = index + 1,
                    signature = signatureOf(lines, index),
                )
            }
        }
    }

    private fun consumerOf(map: ProjectMap, module: String): Consumer {
        val texts = (sources(map, module, MAIN) + sources(map, module, TEST_FIXTURES)).map { it.readText() }
        val blob = texts.joinToString("\n")
        return Consumer(blob, STAR_IMPORT.findAll(blob).map { it.groupValues[1] }.toSet())
    }

    /** `(?<![\w.])<fqn>(?![\w])` over [blob], spelled as its own definition. The roots scan runs this
     *  for every declaration over every other module's whole consuming text — gigabytes on this tree
     *  — and a backtracking engine cannot use the literal to skip past a leading lookbehind. */
    fun names(blob: String, fqn: String): Boolean {
        var at = blob.indexOf(fqn)
        while (at >= 0) {
            val open = freeStart(blob.getOrNull(at - 1))
            val close = freeEnd(blob.getOrNull(at + fqn.length))
            if (open && close) return true
            at = blob.indexOf(fqn, at + 1)
        }
        return false
    }

    /** The offenders, the declarations examined, and the refusals. */
    fun unjustified(map: ProjectMap, lawText: String?): Surface {
        val (exempt, lawProblems) = nonLibrary(lawText)
        val libraries = map.modules.sorted().filterNot { it in exempt }
        val graded = lawProblems.isEmpty() && libraries.isNotEmpty()
        val all = if (graded) libraries.flatMap { declarations(map, it) } else emptyList()
        val refusal = refusal(lawProblems, exempt, libraries, all)
        if (refusal.isNotEmpty()) return Surface(emptyList(), 0, refusal)
        val justified = justify(all, map.modules.sorted().associateWith { consumerOf(map, it) })
        closeOver(all, justified)
        return Surface(all.filterNot { it.fqn in justified }.sortedBy { it.id }, all.size, emptyList())
    }

    /** The three ways this measurement refuses: no law, no library module, no public declaration. */
    private fun refusal(
        lawProblems: List<String>,
        exempt: Set<String>,
        libraries: List<String>,
        all: List<Declaration>,
    ): List<String> = when {
        lawProblems.isNotEmpty() -> lawProblems
        libraries.isEmpty() -> listOf(
            "the build declares zero library modules after removing nonLibrary (${exempt.sorted()}) — refusing " +
                "to pass vacuously, because a green over an empty denominator is what this wall exists to prevent",
        )
        all.isEmpty() -> listOf(
            "parsed 0 public top-level declarations across ${libraries.size} library module(s) — every one of " +
                "them runs under explicitApi(), so a zero here is a broken parser rather than a clean surface, " +
                "and it must not read as green",
        )
        else -> emptyList()
    }

    /** THE ROOTS: a declaration another module NAMES, or whose package it star-imports. */
    private fun justify(all: List<Declaration>, consumers: Map<String, Consumer>): MutableSet<String> {
        val justified = mutableSetOf<String>()
        for (declaration in all) {
            val found = consumers.entries.firstOrNull { (module, text) ->
                module != declaration.module && consumes(text, declaration)
            }
            if (found != null) justified += declaration.fqn
        }
        return justified
    }

    private fun consumes(consumer: Consumer, declaration: Declaration): Boolean =
        declaration.pkg in consumer.stars || names(consumer.blob, declaration.fqn)

    /** THE CLOSURE OVER PUBLIC SIGNATURES, iterated to a fixpoint rather than one hop: a chain
     *  (consumed type -> parameter type -> field type) is the same argument applied twice, and
     *  stopping at depth one would just relocate the blind spot. Matched on the SIMPLE name, since a
     *  signature says `List<EconomicsBucket>` and not the FQN; that width is the point. */
    private fun closeOver(all: List<Declaration>, justified: MutableSet<String>) {
        val byName = all.groupBy { it.name }
        val mentions = all.map { declaration ->
            IDENT.findAll(declaration.signature).mapTo(mutableSetOf()) { match -> match.value }
        }
        var changed = true
        while (changed) {
            changed = all.indices.count { index -> reach(all[index], mentions[index], byName, justified) } > 0
        }
    }

    /** One hop: if [declaration] is reachable from a consumer, so is everything its signature names. */
    private fun reach(
        declaration: Declaration,
        mentioned: Set<String>,
        byName: Map<String, List<Declaration>>,
        justified: MutableSet<String>,
    ): Boolean {
        if (declaration.fqn !in justified) return false
        var added = false
        for (target in mentioned.flatMap { byName[it].orEmpty() }) {
            if (justified.add(target.fqn)) added = true
        }
        return added
    }

    /** Every problem this run has, empty when the ratchet holds. */
    fun audit(map: ProjectMap, lawText: String?, baselineText: String?): List<String> {
        val surface = unjustified(map, lawText)
        val (parsed, baselineProblems) = Baseline.parse(baselineText)
        val baseline = parsed ?: Baseline(emptySet(), emptyMap(), "")
        val measured = surface.offenders.associateBy { it.id }
        return surface.problems + baselineProblems + baseline.growth(measured) +
            baseline.stale(measured) + baseline.keptProblems()
    }
}

class PublicSurfaceLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `the unjustified public surface is exactly the recorded baseline - V4-92`() {
        val surface = PublicSurface.unjustified(map, lawText())
        assertEquals(emptyList<String>(), surface.problems, "the measurement must be trusted before it is graded")
        assertTrue(surface.examined > 100) {
            "the map yielded ${surface.examined} public top-level declaration(s) — the module walk is not pointed " +
                "at the real source sets, so a green here would be a green over a tree nobody read."
        }
        // The per-module walk and the shared reader must describe ONE tree: a file one of them sees
        // and the other does not is a denominator that moved without anybody writing it down.
        assertEquals(
            KotlinText.kotlinFiles(map, PublicSurface.MAIN),
            map.modules.sorted().flatMap { PublicSurface.sources(map, it, PublicSurface.MAIN) },
            "the law's per-module main sources must be KotlinText.kotlinFiles' own answer",
        )
        val problems = PublicSurface.audit(map, lawText(), baselineText())
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "PUBLIC SURFACE RATCHET (V4-92) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: THREE directory shapes on purpose — a nested
     *  one, a bare one and a gateway/ one — so a law that guessed `<parent>/<id>` reads an empty
     *  tree for two of the three and cannot pass these arms. */
    private class Tree(val root: File, modules: String = MODULES) {
        private val synthetic = ProjectMap.parse(root, modules, setOf("build"))

        fun write(vararg files: Pair<String, String>) {
            for (home in listOf("modules", "other", "gateway")) File(root, home).deleteRecursively()
            for ((rel, body) in files) File(root, rel).apply { parentFile.mkdirs() }.writeText(body)
        }

        fun audit(baseline: String?, law: String? = LAW): List<String> = PublicSurface.audit(synthetic, law, baseline)

        fun surface(): PublicSurface.Surface = PublicSurface.unjustified(synthetic, LAW)
    }

    @Test
    fun `the law can actually fail - what justifies is green - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_API to API, OTHER_USE to USE)
            assertEquals(emptyList<String>(), audit(baseline()), "an imported public type, and `internal`, are green")

            write(LIB_API to LEAK, OTHER_FIXTURE to FIXTURE_USE)
            assertEquals(emptyList<String>(), audit(baseline()), "a sibling's testFixtures consumer is shipped code")

            write(LIB_API to LEAK, OTHER_USE to STAR_USE)
            assertEquals(emptyList<String>(), audit(baseline()), "a star import of the package justifies it")

            write(LIB_API to API, OTHER_USE to USE, LIB_LEAK to LEAK)
            assertEquals(
                emptyList<String>(),
                audit(baseline(LEAK_ID)),
                "a RECORDED offender is not growth — the ratchet holds",
            )

            // The one-item tree grades green WITH its count (§24, the boring case).
            write(LIB_API to LEAK)
            assertEquals(1, surface().examined, "one declaration examined")
            assertEquals(emptyList<String>(), audit(baseline(LEAK_ID)))
        }
    }

    @Test
    fun `the law can actually fail - growth is red BY NAME - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_API to API, OTHER_USE to USE, LIB_LEAK to LEAK)
            assertHit(audit(baseline()), "GROWTH", LEAK_ID, "class at") {
                "a synthetic unjustified public type with an empty baseline must be RED BY NAME"
            }
            assertTrue(surface().offenders.any { it.name == "SelftestLeak" }, "the synthetic leak must be named")

            // A sibling module's src/test caller is the population this wall exists to name.
            write(LIB_API to LEAK, OTHER_TEST to TEST_USE)
            assertHit(audit(baseline()), "GROWTH", LEAK_ID) {
                "a caller in a sibling's src/test is NOT a justification"
            }

            // The CLOSURE guard: the same member made internal leaves the type offending.
            write(LIB_STORE to store("internal"), OTHER_USE to STORE_USE)
            assertHit(audit(baseline()), "GROWTH", HIDDEN_ID) {
                "a type reached only through an INTERNAL member is not part of the public contract"
            }

            // ...and the wrapped-parameter shape, with its consumer gone.
            write(LIB_STORE to WRAPPED_STORE)
            assertHit(audit(baseline()), "GROWTH", HIDDEN_ID) { "with the consumer gone the type offends again" }
        }
    }

    @Test
    fun `the law can actually fail - the closure carries a member's types - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            // A type named nowhere, reachable only through a consumed class's PUBLIC member.
            write(LIB_STORE to store("public"), OTHER_USE to STORE_USE)
            assertEquals(
                emptyList<String>(),
                audit(baseline()),
                "a public member's return type rides its consumed class",
            )
            // The arm the first cut should have had: the member's parameters WRAP, so a scan that
            // captured each member's FIRST LINE only would hide `hidden: Hidden` on line three.
            write(LIB_STORE to WRAPPED_STORE, OTHER_USE to STORE_USE)
            assertEquals(
                emptyList<String>(),
                audit(baseline()),
                "a WRAPPED member parameter list carries its type to the consumer",
            )
        }
    }

    @Test
    fun `the law can actually fail - a shrink names the new number and the resource - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_API to API, OTHER_USE to USE)
            assertHit(audit(baseline(API_ID)), "STALE", API_ID, "now 0", "BY HAND", BASELINE_PATH) {
                "a baseline entry that has gained a consumer must be RED, naming the new surface count"
            }
            assertHit(audit(baseline(DELETED_ID)), "STALE", DELETED_ID, "BY HAND", BASELINE_PATH) {
                "a baseline entry whose declaration no longer exists must be RED BY NAME"
            }
            write(LIB_API to LEAK)
            assertHit(audit(baseline(LEAK_ID, DELETED_ID)), "STALE", DELETED_ID, "now 1") {
                "one stale entry beside one that still offends must name the measured count"
            }
        }
    }

    @Test
    fun `the law can actually fail - a kept reason that explains nothing - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_API to LEAK)
            assertHit(audit(kept(listOf(LEAK_ID), mapOf(LEAK_ID to "   "))), "BLANK REASON", LEAK_ID) {
                "a blank `kept` reason is an absence wearing a label"
            }
            val orphan = kept(listOf(LEAK_ID), mapOf(DELETED_ID to "explaining something absent"))
            assertHit(audit(orphan), "ORPHAN KEPT", DELETED_ID) {
                "a reason for an entry the baseline does not hold is a half-finished burn-down"
            }
            // A kept entry with a real reason holds a baseline line the measurement no longer carries.
            write(LIB_API to API, OTHER_USE to USE)
            assertEquals(
                emptyList<String>(),
                audit(kept(listOf(API_ID), mapOf(API_ID to "a consumer lands in the next commit"))),
                "an explained entry is not STALE",
            )
        }
    }

    @Test
    fun `the law can actually fail - an untrusted instrument never passes - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_API to API, OTHER_USE to USE)
            assertHit(audit(baseline(), law = null), "missing") { "a missing module law is a hard error" }
            assertHit(audit(baseline(), law = NO_NON_LIBRARY), "not found") { "a law with no nonLibrary set is red" }
            assertHit(audit(baseline(recorded = "")), "recorded") { "an undated baseline is a hard error" }
            assertHit(audit(null), "missing") { "a missing baseline refuses rather than passing" }
            assertHit(audit("""{"recorded": "2026-09-17", "offenders": "nope"}"""), "must be a list") {
                "a baseline whose offenders are not a list of strings cannot gate"
            }
            write(LIB_API to ONLY_INTERNAL)
            assertHit(audit(baseline()), "broken parser") { "a library tree with zero public declarations" }
        }
        with(Tree(root, APP_ONLY)) {
            write(APP_MAIN to APP_SOURCE)
            assertHit(audit(baseline()), "vacuously") { "a tree whose every module is nonLibrary must REFUSE" }
        }
    }

    @Test
    fun `the token boundary is the checker's own - V4-92`(@TempDir root: File) {
        assertTrue(PublicSurface.names("import fix.lib.Api\n", "fix.lib.Api"), "a whole-token use is a use")
        assertTrue(PublicSurface.names("val x = fix.lib.Api.Companion\n", "fix.lib.Api"), "a trailing dot is a use")
        assertFalse(PublicSurface.names("import otherfix.lib.Api\n", "fix.lib.Api"), "a dotted prefix is NOT a use")
        assertFalse(PublicSurface.names("import fix.lib.ApiOther\n", "fix.lib.Api"), "a longer name is NOT a use")
        with(Tree(root)) {
            write(LIB_API to API, OTHER_USE to NEAR_MISS_USE)
            assertHit(audit(baseline()), "GROWTH", API_ID) {
                "a consumer that names `fix.lib.ApiOther` does not justify `fix.lib.Api`"
            }
        }
    }

    private fun lawText(): String = File(map.root, PublicSurface.MODULE_LAW).readText()

    private fun baselineText(): String = checkNotNull(PublicSurface::class.java.getResource(BASELINE_RESOURCE)) {
        "$BASELINE_PATH is not on the test classpath — the ratchet cannot grade debt it cannot read. " +
            "quality/architecture/build.gradle.kts is what declares it."
    }.readText()

    private companion object {
        const val MODULES = ":app=gateway/app;:lib=modules/lib;:other=other"
        const val APP_ONLY = ":app=gateway/app"
        const val LAW = "val moduleLaw: Map<String, Set<String>> = mapOf(\":lib\" to emptySet())\n" +
            "val nonLibrary = setOf(\":app\")\n"
        const val NO_NON_LIBRARY = "val moduleLaw: Map<String, Set<String>> = mapOf(\":lib\" to emptySet())\n"

        const val LIB_API = "modules/lib/src/main/kotlin/Api.kt"
        const val LIB_LEAK = "modules/lib/src/main/kotlin/Leak.kt"
        const val LIB_STORE = "modules/lib/src/main/kotlin/Store.kt"
        const val OTHER_USE = "other/src/main/kotlin/Use.kt"
        const val OTHER_TEST = "other/src/test/kotlin/T.kt"
        const val OTHER_FIXTURE = "other/src/testFixtures/kotlin/F.kt"
        const val APP_MAIN = "gateway/app/src/main/kotlin/M.kt"

        const val API_ID = ":lib fix.lib.Api"
        const val LEAK_ID = ":lib fix.lib.SelftestLeak"
        const val HIDDEN_ID = ":lib fix.lib.Hidden"
        const val DELETED_ID = ":lib fix.lib.DeletedLongAgo"

        const val API = "package fix.lib\npublic class Api\ninternal class Hidden\n"
        const val LEAK = "package fix.lib\npublic class SelftestLeak(val v: Int)\n"
        const val ONLY_INTERNAL = "package fix.lib\ninternal class OnlyInternal\n"
        const val USE = "package fix.other\nimport fix.lib.Api\ninternal class Use(val a: Api)\n"
        const val NEAR_MISS_USE = "package fix.other\nimport fix.lib.ApiOther\ninternal class Use(val a: ApiOther)\n"
        const val STAR_USE = "package fix.other\nimport fix.lib.*\ninternal class Use(val s: SelftestLeak)\n"
        const val TEST_USE = "package fix.other\nimport fix.lib.SelftestLeak\nclass T { fun t() = SelftestLeak(1) }\n"
        const val FIXTURE_USE = "package fix.other\nimport fix.lib.SelftestLeak\npublic class F(val s: SelftestLeak)\n"
        const val STORE_USE = "package fix.other\nimport fix.lib.Store\ninternal class Use(val s: Store)\n"
        const val APP_SOURCE = "package fix.app\nclass M\n"

        /** A type named nowhere, reachable only through [Store]'s member — `public` or `internal`. */
        fun store(member: String): String =
            "package fix.lib\n\npublic class Store {\n    $member fun read(): Hidden = Hidden()\n}\n\n" +
                "public class Hidden\n"

        /** The same, with the member's parameter list WRAPPED — the shape a first-line-only scan hides. */
        const val WRAPPED_STORE = "package fix.lib\n\npublic class Store {\n    public fun read(\n" +
            "        flag: Boolean,\n        hidden: Hidden,\n    ): Int = 0\n}\n\npublic class Hidden\n"

        fun baseline(vararg entries: String, recorded: String = "2026-09-17"): String =
            """{"recorded": "$recorded", "offenders": [${entries.joinToString(", ") { "\"$it\"" }}]}"""

        fun kept(entries: List<String>, reasons: Map<String, String>): String {
            val offenders = entries.joinToString(", ") { "\"$it\"" }
            val block = reasons.entries.joinToString(", ") { (entry, reason) -> "\"$entry\": \"$reason\"" }
            return """{"recorded": "2026-09-17", "offenders": [$offenders], "kept": {$block}}"""
        }
    }
}
