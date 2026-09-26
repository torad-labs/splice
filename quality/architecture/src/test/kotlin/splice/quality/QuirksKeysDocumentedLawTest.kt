// NEW: V4-44 — every operator-facing quirk KEY has a disposition in the documentation (ported from
// checks/config/quirks-keys-documented.ts, restructure PR 6).
//
// WHY THIS EXISTS. QuirksConfig.kt is the finite quirk surface: the keys an operator may write
// under [providers.X.quirks]. Its documentation lives in OTHER files — the example config an
// operator copies, and the `splice add PROFILE` emitter — and nothing paired the two. A key added to
// the type but documented nowhere, or a key retired while the doc still describes it, drifts in
// silence. This is the shape that let six of DeepSeek's nine accepted block types stay dropped for a
// whole campaign: a hand-authored list checked against another hand-authored list, agreeing with
// each other and disagreeing with reality. Sweeping the keys once closes the instance; this closes
// the class.
//
// DENOMINATOR, from the SOURCE, never a hand list. The primary constructor of every data class in
// QuirksConfig.kt is parsed on disk, and each parameter yields its TOML key (@SerialName when
// present, else the property name). A key added tomorrow is in scope with no edit to this file. Two
// guards refuse a vacuous pass: the parsed @SerialName count must equal the count in the
// comment-stripped file (parser drift), and a parse yielding zero keys is a failure rather than a
// pass.
//
// DISPOSITION. Every parsed key must be accounted for, in one of three forms:
//   documented — the key appears as a TOML key token (`key = ...`) in a surface file, line-leading
//                or inside an inline table, commented or live;
//   tabled     — the key's own nested table is declared (`[....tool_surface]`);
//   retired    — a machine-readable marker, `# retired: <key> — <reason>`, with a non-empty reason.
// Absence is not a disposition. An undocumented key fails by name, naming its class and property.
//
// WHAT IS NOT A DISPOSITION SURFACE: README.md is not a quirk reference, and the doctor's runtime
// map of quirk keys is a report, not documentation. The surfaces are exactly the two files an
// operator reads to write a quirk. NOT CAUGHT: a key documented in the WRONG place (the check
// proves the key token exists in a surface, not that it sits under its own table), and a key
// documented only in a THIRD file — adding an emitter is a change to this law too.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object QuirksKeysDocumented {
    /** The denominator: :core's quirk surface, spelled through the map so a moved module keeps it. */
    const val SOURCE_IN_CORE = "splice/core/topology/QuirksConfig.kt"

    /** The two files an operator reads to write a quirk: the copyable example config (repository
     *  root) and the `splice add PROFILE` emitter (:features-configuration's main sources). */
    const val EXAMPLE_CONFIG = "app/src/main/resources/splice.example.toml"
    const val PROFILE_EMITTER_IN_CONFIGURATION = "splice/configuration/add/AddProfileCatalog.kt"

    private val DATA_CLASS = Regex("(?:public\\s+)?data class\\s+(\\w+)\\s*\\(")
    private val SERIAL_NAME = Regex("@SerialName\\(\\s*\"([^\"]+)\"\\s*\\)")
    private val PARAM = Regex(
        "\\bval\\s+(\\w+)\\s*:\\s*([^=]+?)(?:\\s*=\\s*(.*))?\\s*$",
        RegexOption.DOT_MATCHES_ALL,
    )

    data class QuirkKey(val klass: String, val table: String, val key: String, val prop: String)

    /** The TOML table a class's keys are written under: QuirksConfig -> quirks,
     *  ToolSurfaceConfig -> quirks.tool_surface. Derived, never a hand list. */
    fun tableFor(klass: String): String {
        var snake = klass.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()
        snake = snake.replace(Regex("_config$"), "")
        return if (snake != "quirks") "quirks.$snake" else "quirks"
    }

    /** The keys of one data class body, and how many of them carried @SerialName. */
    private fun classKeys(klass: String, body: String): Pair<List<QuirkKey>, Int> {
        val table = tableFor(klass)
        val keys = mutableListOf<QuirkKey>()
        var serials = 0
        for (raw in KotlinText.splitTopLevel(body, ',', angles = true)) {
            val text = raw.trim()
            val param = PARAM.find(text) ?: continue
            val prop = param.groupValues[1]
            val named = SERIAL_NAME.find(text)
            if (named != null) serials += 1
            keys += QuirkKey(klass, table, named?.groupValues?.get(1) ?: prop, prop)
        }
        return keys to serials
    }

    /** (keys, problems): problems only on a parse that cannot be trusted, never on an undocumented key. */
    fun parseSource(source: String, label: String): Pair<List<QuirkKey>, List<String>> {
        val problems = mutableListOf<String>()
        val keys = mutableListOf<QuirkKey>()
        var classes = 0
        var serialsParsed = 0
        for (m in DATA_CLASS.findAll(source)) {
            val span = KotlinText.balancedSpan(source, m.range.last, '(', ')')
            if (span == null) {
                problems += "$label: ${m.groupValues[1]} constructor could not be parsed"
                continue
            }
            val (parsed, serials) = classKeys(m.groupValues[1], source.substring(span.bodyStart, span.closerAt))
            classes += 1
            keys += parsed
            serialsParsed += serials
        }
        if (classes == 0) problems += "$label: no data class found — the denominator is absent"
        val serialsRaw = SERIAL_NAME.findAll(KotlinText.stripComments(source)).count()
        if (serialsRaw != serialsParsed) {
            problems += "$label: parsed $serialsParsed @SerialName keys but the file holds $serialsRaw — the parser and " +
                "the source disagree, so no key list from this run can be trusted"
        }
        return keys to problems
    }

    /** `key =` anywhere: line-leading live key, commented key, or inline-table entry. */
    private fun keyToken(key: String) = Regex("(?<![\\w-])${Regex.escape(key)}\\s*=")

    /** `[providers.X.quirks.tool_surface]` as the disposition for the tool_surface key. */
    private fun tableHeader(key: String) = Regex(
        "^[ \\t]*\\[\\[?[^\\[\\]\\n]*\\.${Regex.escape(key)}[ \\t]*\\]\\]?[ \\t]*$",
        RegexOption.MULTILINE,
    )

    /** A surface: the path a violation names, and the file. */
    data class Surface(val rel: String, val file: File)

    private fun readSurfaces(surfaces: List<Surface>, problems: MutableList<String>): Map<String, String> {
        val texts = linkedMapOf<String, String>()
        for (surface in surfaces) {
            if (surface.file.isFile) {
                texts[surface.rel] = surface.file.readText()
            } else {
                problems += "${surface.rel}: disposition surface missing — a surface that cannot be read cannot " +
                    "document anything"
            }
        }
        return texts
    }

    /** True when [text] carries the key's retirement marker; an unreasoned marker is its own problem. */
    private fun retired(rel: String, text: String, key: String, problems: MutableList<String>): Boolean {
        val (marked, reason) = KotlinText.retiredReason(text, key)
        if (marked && reason.isEmpty()) {
            problems += "$rel: $key is retired with NO reason — a retirement without a written reason is an absence " +
                "wearing a label"
        }
        return marked
    }

    private fun mentioned(text: String, key: String): Boolean =
        keyToken(key).containsMatchIn(text) || tableHeader(key).containsMatchIn(text)

    /** Whether any surface disposes of [quirk]: a retirement marker (reasoned or not) or the key itself. */
    private fun disposed(quirk: QuirkKey, texts: Map<String, String>, problems: MutableList<String>): Boolean {
        var disposed = false
        for ((rel, text) in texts) {
            val marked = retired(rel, text, quirk.key, problems)
            if (marked || mentioned(text, quirk.key)) disposed = true
        }
        return disposed
    }

    fun audit(source: File, sourceRel: String, surfaces: List<Surface>): List<String> {
        if (!source.isFile) {
            return listOf("$sourceRel: missing — the quirk source IS the denominator, so its absence cannot pass")
        }
        val (keys, parseProblems) = parseSource(source.readText(), sourceRel)
        val problems = parseProblems.toMutableList()
        if (keys.isEmpty()) {
            problems += "$sourceRel: parsed 0 quirk keys — refusing to pass vacuously, because a green over an empty " +
                "denominator is what this wall exists to prevent"
            return problems
        }
        val texts = readSurfaces(surfaces, problems)
        val surfaceNames = surfaces.joinToString(" or ") { it.rel }
        for (quirk in keys) {
            if (disposed(quirk, texts, problems)) continue
            problems += "NO DISPOSITION: ${quirk.table}.${quirk.key} (${quirk.klass}.${quirk.prop}) is documented nowhere " +
                "in $surfaceNames; document it there, or retire it with `# retired: ${quirk.key} — <reason>`"
        }
        return problems
    }

    /** The live surfaces, spelled through the map. */
    fun liveSurfaces(map: ProjectMap): List<Surface> = listOf(
        Surface(EXAMPLE_CONFIG, File(map.root, EXAMPLE_CONFIG)),
        File(map.mainSources(":features-configuration"), PROFILE_EMITTER_IN_CONFIGURATION)
            .let { Surface(KotlinText.rel(map, it), it) },
    )
}

class QuirksKeysDocumentedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every quirk key in QuirksConfig has a disposition in the example config or the profile emitter - V4-44`() {
        val source = File(map.mainSources(":core"), QuirksKeysDocumented.SOURCE_IN_CORE)
        val problems = QuirksKeysDocumented.audit(
            source,
            KotlinText.rel(map, source),
            QuirksKeysDocumented.liveSurfaces(map),
        )
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "QUIRK KEYS DOCUMENTED (V4-44) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: the quirk source and its two surfaces. */
    private class Tree(root: File) {
        val source = File(root, "core/src/main/kotlin/${QuirksKeysDocumented.SOURCE_IN_CORE}")
        val doc = File(root, QuirksKeysDocumented.EXAMPLE_CONFIG)
        private val emitterRel = "features/configuration/src/main/kotlin/" +
            QuirksKeysDocumented.PROFILE_EMITTER_IN_CONFIGURATION
        val emitter = File(root, emitterRel)
        val surfaces = listOf(
            QuirksKeysDocumented.Surface(QuirksKeysDocumented.EXAMPLE_CONFIG, doc),
            QuirksKeysDocumented.Surface(emitterRel, emitter),
        )

        fun write(src: String, docText: String, emitterText: String = "") {
            source.parentFile.mkdirs()
            source.writeText(src)
            doc.parentFile.mkdirs()
            doc.writeText(docText)
            emitter.parentFile.mkdirs()
            emitter.writeText(emitterText)
        }

        fun audit() = QuirksKeysDocumented.audit(
            source,
            "core/src/main/kotlin/${QuirksKeysDocumented.SOURCE_IN_CORE}",
            surfaces,
        )
    }

    @Test
    fun `the law can actually fail - parse, the mutation and a reasoned retirement - V4-44`(@TempDir root: File) {
        with(Tree(root)) {
            write(COMPLIANT_SOURCE, COMPLIANT_DOC, RUNTIME_MAP)
            assertEquals(
                emptyList<String>(),
                audit(),
                "compliant tree must be GREEN (key token, table header, commented key)",
            )
            assertEquals(
                5,
                QuirksKeysDocumented.parseSource(COMPLIANT_SOURCE, "fixture").first.size,
                "the fixture parses to its five keys",
            )

            // The mutation the row requires: a fake key appended to a temp copy of the source.
            val mutated = COMPLIANT_SOURCE.replace(
                "    @SerialName(\"tool_surface\") val toolSurface: ToolSurfaceConfig? = null,\n)",
                "    @SerialName(\"tool_surface\") val toolSurface: ToolSurfaceConfig? = null,\n$FAKE_KEY_PARAM",
            )
            assertTrue(
                mutated != COMPLIANT_SOURCE,
                "the mutation did not apply — the fake key never reached the temp copy",
            )
            write(mutated, COMPLIANT_DOC)
            assertHit(audit(), "fake_new_knob") { "synthetic fake key must be RED BY NAME" }

            write(COMPLIANT_SOURCE, RETIRED_OK_DOC)
            assertEquals(emptyList<String>(), audit(), "a reasoned retirement is a disposition, so the tree is GREEN")
        }
    }

    @Test
    fun `the law can actually fail - dispositions and refusals - V4-44`(@TempDir root: File) {
        with(Tree(root)) {
            write(COMPLIANT_SOURCE, RETIRED_NOREASON_DOC)
            var hits = audit()
            assertHit(hits, "min_deferred", "NO reason") { "a retirement with an empty reason must be RED by name" }
            assertEquals(
                1,
                hits.count {
                    it.contains("min_deferred")
                },
                "an unreasoned retirement is ONE problem, not a duplicate pair, got: $hits",
            )

            write(COMPLIANT_SOURCE, RUNTIME_MAP_DOC)
            hits = audit()
            assertHit(hits, "NO DISPOSITION", "min_deferred") { "a key with no disposition at all must be RED by name" }

            write(COMPLIANT_SOURCE, RUNTIME_MAP_DOC, RUNTIME_MAP)
            hits = audit()
            assertHit(hits, "NO DISPOSITION", "min_deferred") { "a key named only in a runtime map is not documented" }

            write(EMPTY_SOURCE, COMPLIANT_DOC)
            assertHit(audit(), "refusing to pass vacuously") { "a parse with no keys must be RED" }

            write(DRIFT_SOURCE, COMPLIANT_DOC)
            assertHit(audit(), "disagree") { "a @SerialName the parser cannot attribute must be RED" }

            write(COMPLIANT_SOURCE, COMPLIANT_DOC)
            doc.delete()
            assertHit(audit(), "disposition surface missing") { "a missing surface must be RED" }
            source.delete()
            assertHit(audit(), "missing — the quirk source IS the denominator") { "a missing source must be RED" }
        }
    }

    private companion object {
        const val COMPLIANT_SOURCE = """package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    /** A KDoc between parameters, which a naive paren walk would choke on. */
    @SerialName("cache_key") val cacheKey: String = "first-message-hash",
    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,
)

@Serializable
public data class ToolSurfaceConfig(
    val enabled: Boolean = true,
    @SerialName("min_deferred") val minDeferred: Int = 8,
)
"""
        const val COMPLIANT_DOC = """[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
# The sub-table form is how TOML documents a nested table.
[providers.codex.quirks.tool_surface]
enabled = true
# min_deferred = 8
"""
        const val RETIRED_OK_DOC = """[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
# retired: min_deferred — folded into the surface defaults; the key is still parsed
"""
        val RETIRED_NOREASON_DOC = RETIRED_OK_DOC.replace(
            "# retired: min_deferred — folded into the surface defaults; the key is still parsed",
            "# retired: min_deferred —",
        )
        const val RUNTIME_MAP_DOC = """[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
"""
        const val RUNTIME_MAP = """private fun shape(q: QuirksConfig) = buildJsonObject {
    put("min_deferred", q.toolSurface?.minDeferred)
}
"""
        const val EMPTY_SOURCE = """package splice.core.topology

public data class QuirksConfig()
"""

        // A @SerialName outside any constructor: the comment stripper keeps it, the parser cannot
        // attribute it, so the two counts disagree and the run must refuse.
        const val DRIFT_SOURCE = COMPLIANT_SOURCE + "\n@SerialName(\"orphan\")\npublic val orphan: String = \"x\"\n"
        const val FAKE_KEY_PARAM = "    @SerialName(\"fake_new_knob\") val fakeNewKnob: Boolean? = null,\n)"
    }
}
