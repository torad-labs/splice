// NEW: V4-87 — every operator-facing KNOB KEY has a disposition in the documentation (ported from
// checks/config/knob-keys-documented.ts, restructure PR 6).
//
// WHY THIS EXISTS. Knob.kt is the finite knob surface: the keys an operator may write in
// `[defaults]`, in `[heads.<key>.overrides]`, in state `config.json`, or PATCH through
// /mgmt/config. Its documentation lives in ANOTHER file — the example config an operator copies —
// and nothing paired the two. The 2026-09-17 architecture audit counted the gap by hand; a hand
// count closes the instance, this closes the class.
//
// This is the KNOB twin of the quirks-key law (V4-44), whose shape, guards and red-proof idiom it
// mirrors deliberately. The two laws are separate classes because the denominators are parsed out
// of structurally different Kotlin — a data-class primary constructor there, an enum-entry argument
// list here.
//
// DENOMINATOR, from the SOURCE, never a hand list. Every entry of the `Knob` enum in Knob.kt is
// parsed on disk and its FIRST constructor argument — the `key` property — is the TOML key. Three
// guards refuse a vacuous pass: the enum declaration must be found (a moved or renamed enum fails
// rather than yielding an empty denominator that passes); a parse yielding zero keys is a failure;
// the parsed entry count must equal the number of `KnobKind.` mentions in the comment-stripped file.
//
// TWO OPERATOR SPELLINGS, ONE KNOB. `Knob.key` is camelCase; the `[daemon]` table deserializes
// through DaemonConfig, whose @SerialName spells the same knob snake_case. Both are the operator's
// spelling, so either satisfies this law, and the snake form is TRANSLITERATED from the key rather
// than mapped by hand.
//
// DISPOSITION: documented (the key token, `key = ...`, in config/splice.example.toml in EITHER
// spelling, commented or live) or retired (`# retired: <key> — <reason>`, reason non-empty).
// Absence is not a disposition. NOT CAUGHT: "default + unit + semantics" (only the key token is
// machine-checkable), a key documented in the WRONG place (a section-aware scan is a different
// law), and a key documented only in a THIRD file.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object KnobKeysDocumented {
    const val SOURCE_IN_CORE = "splice/core/config/Knob.kt"
    const val EXAMPLE_CONFIG = "config/splice.example.toml"

    private val ENUM_DECL = Regex("\\benum\\s+class\\s+Knob\\b")
    private val ENTRY_HEAD = Regex("^\\s*([A-Z][A-Z0-9_]*)\\s*\\(", RegexOption.DOT_MATCHES_ALL)
    private val KIND_MENTION = Regex("\\bKnobKind\\.")
    private val STRING_LITERAL = Regex("^\\s*\"([^\"]*)\"\\s*$")

    data class KnobKey(val entry: String, val key: String)

    /** The enum's entry-list text (a Kotlin enum's entries end at the first top-level `;`; members
     *  follow), or the problem that stops the parse. */
    private fun enumEntries(source: String, label: String): Pair<String?, String?> {
        val decl = ENUM_DECL.find(source)
        val ctor = decl?.let { KotlinText.balancedSpan(source, it.range.last + 1, '(', ')') }
        val body = ctor?.let { KotlinText.balancedSpan(source, it.closerAt, '{', '}') }
        val problem = when {
            decl == null ->
                "$label: no `enum class Knob` declaration found — the knob enum has moved or been renamed, " +
                    "so this run has no denominator and must not pass"
            ctor == null -> "$label: the Knob primary constructor could not be parsed"
            body == null -> "$label: the Knob enum body could not be parsed"
            else -> null
        }
        val entries = body?.let { KotlinText.splitTopLevel(source.substring(it.bodyStart, it.closerAt), ';')[0] }
        return entries to problem
    }

    /** The key of one enum entry, or the problem that hides it; (null, null) when [raw] is no entry. */
    private fun entryKey(raw: String, label: String): Pair<KnobKey?, String?> {
        val head = ENTRY_HEAD.find(raw) ?: return null to null
        val entry = head.groupValues[1]
        val args = KotlinText.balancedSpan(raw, head.range.last, '(', ')')
        val first = args?.let { KotlinText.splitTopLevel(raw.substring(it.bodyStart, it.closerAt), ',').firstOrNull() }
        val literal = first?.let { STRING_LITERAL.find(it) }
        val problem = when {
            args == null -> "$label: $entry argument list could not be parsed"
            first == null -> "$label: $entry declares no arguments — where is its key?"
            literal == null ->
                "$label: $entry's first argument is not a string literal " +
                    "(${KotlinText.pyRepr(first.trim())}) — the key cannot be read from the source"
            else -> null
        }
        return literal?.let { KnobKey(entry, it.groupValues[1]) } to problem
    }

    /** (keys, problems): problems only on a parse that cannot be trusted. */
    fun parseKnobs(source: String, label: String): Pair<List<KnobKey>, List<String>> {
        val (entriesText, stop) = enumEntries(source, label)
        if (entriesText == null) return emptyList<KnobKey>() to listOfNotNull(stop)
        val problems = mutableListOf<String>()
        val keys = mutableListOf<KnobKey>()
        for (raw in KotlinText.splitTopLevel(entriesText, ',')) {
            val (key, problem) = entryKey(raw, label)
            if (key != null) keys += key
            if (problem != null) problems += problem
        }
        if (keys.isEmpty()) return keys to problems
        val kinds = KIND_MENTION.findAll(KotlinText.stripComments(source)).count()
        if (kinds != keys.size) {
            problems += "$label: parsed ${keys.size} enum entries but the file holds $kinds `KnobKind.` mentions — every " +
                "entry passes exactly one kind, so the parser and the source disagree and no key list from this run " +
                "can be trusted"
        }
        return keys to problems
    }

    /** The knob's OTHER operator-facing spelling, by transliteration — never a hand map. */
    fun snake(key: String): String = key.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()

    /** `key =` in EITHER spelling: live, commented, or inline-table entry. */
    private fun keyToken(key: String): Regex {
        val spellings = setOf(key, snake(key)).sorted()
        return Regex("(?<![\\w-])(?:${spellings.joinToString("|") { Regex.escape(it) }})\\s*=")
    }

    /** Whether any surface disposes of [key]: a retirement marker (an unreasoned one is its own
     *  problem) or the key in either spelling. */
    private fun disposed(key: String, texts: Map<String, String>, problems: MutableList<String>): Boolean {
        var disposed = false
        for ((rel, text) in texts) {
            val (marked, reason) = KotlinText.retiredReason(text, key)
            if (marked && reason.isEmpty()) {
                problems += "$rel: $key is retired with NO reason — a retirement without a written reason is an absence " +
                    "wearing a label"
            }
            if (marked || keyToken(key).containsMatchIn(text)) disposed = true
        }
        return disposed
    }

    fun audit(source: File, sourceRel: String, surface: File, surfaceRel: String): List<String> {
        if (!source.isFile) {
            return listOf("$sourceRel: missing — the knob enum IS the denominator, so its absence cannot pass")
        }
        val (keys, parseProblems) = parseKnobs(source.readText(), sourceRel)
        val problems = parseProblems.toMutableList()
        if (keys.isEmpty()) {
            problems += "$sourceRel: parsed 0 knob keys — refusing to pass vacuously, because a green over an empty " +
                "denominator is what this wall exists to prevent"
            return problems
        }
        val texts = linkedMapOf<String, String>()
        if (surface.isFile) {
            texts[surfaceRel] = surface.readText()
        } else {
            problems += "$surfaceRel: disposition surface missing — a surface that cannot be read cannot document anything"
        }
        for (knob in keys) {
            if (disposed(knob.key, texts, problems)) continue
            problems += "NO DISPOSITION: ${knob.key} (Knob.${knob.entry}) is documented nowhere in $surfaceRel; document it " +
                "there with its default, its unit and what it does, or retire it with `# retired: ${knob.key} — <reason>`"
        }
        return problems
    }
}

class KnobKeysDocumentedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every knob key in Knob has a disposition in the example config - V4-87`() {
        val source = File(map.mainSources(":core"), KnobKeysDocumented.SOURCE_IN_CORE)
        val surface = File(map.root, KnobKeysDocumented.EXAMPLE_CONFIG)
        val problems = KnobKeysDocumented.audit(
            source,
            KotlinText.rel(map, source),
            surface,
            KnobKeysDocumented.EXAMPLE_CONFIG,
        )
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "KNOB KEYS DOCUMENTED (V4-87) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: the Knob enum and the example config. */
    private class Tree(root: File) {
        val source = File(root, "core/src/main/kotlin/${KnobKeysDocumented.SOURCE_IN_CORE}")
        val surface = File(root, KnobKeysDocumented.EXAMPLE_CONFIG)

        fun write(src: String, doc: String, runtime: String = "") {
            source.parentFile.mkdirs()
            source.writeText(src)
            surface.parentFile.mkdirs()
            surface.writeText(doc + runtime)
        }

        fun audit() = KnobKeysDocumented.audit(
            source,
            "core/src/main/kotlin/${KnobKeysDocumented.SOURCE_IN_CORE}",
            surface,
            KnobKeysDocumented.EXAMPLE_CONFIG,
        )
    }

    @Test
    fun `the law can actually fail - parse, spellings and the mutation - V4-87`(@TempDir root: File) {
        with(Tree(root)) {
            write(COMPLIANT_SOURCE, COMPLIANT_DOC)
            assertEquals(
                emptyList<String>(),
                audit(),
                "compliant tree must be GREEN (live key, commented key, snake_case spelling, reasoned retirement)",
            )
            assertEquals(
                listOf("port", "maxInflight", "debug", "showReasoning", "grokPort"),
                KnobKeysDocumented.parseKnobs(COMPLIANT_SOURCE, "fixture").first.map { it.key },
            )

            // The snake_case alternative must not loosen into a fuzzy match: deleting the ONE line that
            // documents showReasoning reds it by name again.
            write(COMPLIANT_SOURCE, COMPLIANT_DOC.replace("show_reasoning = \"text\"", ""))
            assertHit(
                audit(),
                "NO DISPOSITION",
                "showReasoning",
            ) { "removing the only snake_case doc line must be RED by name" }

            // The BORING case: exactly one knob, and the count must come out as one.
            write(BORING_SOURCE, BORING_DOC)
            assertEquals(emptyList<String>(), audit(), "the one-knob tree must be GREEN")
            val boring = KnobKeysDocumented.parseKnobs(BORING_SOURCE, "boring")
            assertEquals(
                listOf(KnobKeysDocumented.KnobKey("PORT", "port")) to emptyList<String>(),
                boring,
                "the one-knob tree must parse to exactly 1 key named port",
            )

            val mutated = COMPLIANT_SOURCE.replace(
                "    GROK_PORT(\"grokPort\", KnobKind.NUMBER, listOf(\"GROK_PROXY_PORT\"), 3100L, restartRequired = true),\n}",
                "    GROK_PORT(\"grokPort\", KnobKind.NUMBER, listOf(\"GROK_PROXY_PORT\"), 3100L, restartRequired = true),\n$FAKE_KEY_ENTRY",
            )
            assertTrue(
                mutated != COMPLIANT_SOURCE,
                "the mutation did not apply — the fake key never reached the temp copy",
            )
            write(mutated, COMPLIANT_DOC)
            assertHit(audit(), "fakeNewKnob") { "synthetic fake key must be RED BY NAME" }
        }
    }

    @Test
    fun `the law can actually fail - dispositions and refusals - V4-87`(@TempDir root: File) {
        with(Tree(root)) {
            write(COMPLIANT_SOURCE, RETIRED_NOREASON_DOC)
            var hits = audit()
            assertHit(hits, "grokPort", "NO reason") { "a retirement with an empty reason must be RED by name" }
            assertEquals(
                1,
                hits.count { it.contains("grokPort") },
                "an unreasoned retirement is ONE problem, not a duplicate pair, got: $hits",
            )

            write(COMPLIANT_SOURCE, RUNTIME_MAP_DOC)
            assertHit(audit(), "NO DISPOSITION", "grokPort") { "a key with no disposition at all must be RED by name" }

            write(COMPLIANT_SOURCE, RUNTIME_MAP_DOC, RUNTIME_MAP)
            assertHit(
                audit(),
                "NO DISPOSITION",
                "grokPort",
            ) { "a key named only in a runtime report map is not documented" }

            write(EMPTY_SOURCE, COMPLIANT_DOC)
            assertHit(audit(), "refusing to pass vacuously") { "an enum with no entries must be RED" }

            write(COMPLIANT_SOURCE.replace("enum class Knob(", "enum class Tuning("), COMPLIANT_DOC)
            assertHit(audit(), "moved or") { "a renamed/moved enum must be RED" }

            write(COMPLIANT_SOURCE + "\npublic val orphanKind: KnobKind = KnobKind.STRING\n", COMPLIANT_DOC)
            assertHit(audit(), "disagree") { "a KnobKind mention the entry parser cannot attribute must be RED" }

            write(COMPLIANT_SOURCE, COMPLIANT_DOC)
            surface.delete()
            hits = audit()
            assertHit(hits, "disposition surface missing") { "a missing surface must be RED" }
        }
    }

    private companion object {
        const val COMPLIANT_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT"), 3099L, restartRequired = true),
    // A prose comment between entries, with (parens) and {braces} a naive walk would choke on.
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),
    DEBUG(
        "debug",
        KnobKind.BOOL,
        listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"),
        false,
        restartRequired = true,
    ),
    SHOW_REASONING("showReasoning", KnobKind.STRING, listOf("CLAUDEX_SHOW_REASONING"), "text"),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, restartRequired = true),
}
"""

        // Every sanctioned spelling in one tree: port / maxInflight live camelCase, debug commented,
        // showReasoning ONLY in its snake_case `[daemon]` spelling, grokPort retired with a reason.
        const val COMPLIANT_DOC = """[daemon]
show_reasoning = "text"      # "text" | "thinking" | "off"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"   # daemon-wide verbose logging
# retired: grokPort — the grok head now takes its port from [heads.*.port]
"""
        val RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
            "# retired: grokPort — the grok head now takes its port from [heads.*.port]",
            "# retired: grokPort —",
        )
        const val RUNTIME_MAP_DOC = """[daemon]
show_reasoning = "text"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"
"""
        const val RUNTIME_MAP = """private fun shape(c: Config) = buildJsonObject {
    put("grokPort", c.grokPort)
}
"""
        const val BORING_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
    PORT("port", KnobKind.NUMBER),
}
"""
        const val BORING_DOC = """[defaults]
port = "3099"
"""
        const val EMPTY_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
}
"""
        const val FAKE_KEY_ENTRY = "    FAKE_NEW_KNOB(\"fakeNewKnob\", KnobKind.BOOL, listOf(\"CLAUDEX_FAKE\"), false),\n}"
    }
}
