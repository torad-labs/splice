// NEW: Oct 10, 2026 — every knob's LIVENESS is documented where the operator reads it, and the
// marker is derived from the flag rather than remembered.
//
// WHY THIS EXISTS. `Knob.restartRequired` decides what /api/config answers for a knob: a knob
// without the flag is reported `live`, which tells an operator that a PATCH reaches the running
// daemon. The example config they are told to copy says the same thing in prose, with a `HOT`
// marker on the knob's line. Nothing paired the two, and on Oct 10 the count was taken: twenty-one
// knobs were live and six carried a marker. maxInflight, the oldest live knob in the tree and the
// one the config route's own test uses as its example, was unmarked. Two knobs made live earlier the
// same day were unmarked by the seat that made them live — me.
//
// So the marker was a habit, and a habit is not a disposition. This is the liveness twin of the
// knob-keys law (V4-87), whose parser, guards and red-proof idiom it reuses deliberately: it calls
// [KnobKeysDocumented.parseKnobs] for the denominator rather than running a second parse that could
// drift from it, which is also where the flag is read from.
//
// THE RULE, both directions. A knob WITHOUT restartRequired must carry `HOT` on at least one line
// that documents it. A knob WITH the flag must carry it on NONE. At least one, not every, because a
// knob may be documented more than once — maxInflight appears in an account example and again under
// a head — and a per-head override lives in splice.toml, which is parsed once at start and never
// re-read, so `HOT` on that line would be the opposite of true.
//
// NOT CAUGHT: whether the marker's own explanation is accurate (prose is not machine-checkable),
// whether the knob is live in FACT rather than by its flag (that is Knob.kt's honesty note, counted
// by hand against the readers), and a marker in a third file.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object KnobLivenessDocumented {
    const val MARKER = "HOT"

    private fun snake(key: String): String = KnobKeysDocumented.snake(key)

    /** Every line of [text] that documents [key] in either operator spelling, live or commented. */
    private fun documenting(key: String, text: String): List<String> {
        val spellings = setOf(key, snake(key)).sorted()
        val token = Regex("(?<![\\w-])(?:${spellings.joinToString("|") { Regex.escape(it) }})\\s*=")
        return text.lines().filter { token.containsMatchIn(it) }
    }

    /** The marker problems for ONE knob, given every line that documents it. A key documented nowhere is the
     *  KEYS law's finding (V4-87), reported there by name; reporting it twice would make one fix read as two. */
    private fun markerProblems(knob: KnobKeysDocumented.KnobKey, lines: List<String>, surfaceRel: String): String? {
        val marked = lines.count { it.contains(MARKER) }
        return when {
            lines.isEmpty() -> null
            !knob.restartRequired && marked == 0 ->
                "NOT MARKED LIVE: ${knob.key} (Knob.${knob.entry}) carries no restartRequired flag, so " +
                    "/api/config answers `live` for it — mark $MARKER on its line in $surfaceRel, or give the knob " +
                    "the flag if a running daemon does not in fact re-read it"
            knob.restartRequired && marked > 0 ->
                "MARKED LIVE BUT RESTART-ONLY: ${knob.key} (Knob.${knob.entry}) is flagged restartRequired, so " +
                    "/api/config answers `restart` for it, but $surfaceRel marks it $MARKER on $marked line(s) — " +
                    "drop the marker, or make the knob live and drop the flag in that same commit"
            else -> null
        }
    }

    fun audit(source: File, sourceRel: String, surface: File, surfaceRel: String): List<String> {
        val unreadable = when {
            !source.isFile -> "$sourceRel: missing — the knob enum IS the denominator, so its absence cannot pass"
            !surface.isFile -> "$surfaceRel: the example config cannot be read, so no marker in it can be checked"
            else -> null
        }
        if (unreadable != null) return listOf(unreadable)
        val (knobs, parseProblems) = KnobKeysDocumented.parseKnobs(source.readText(), sourceRel)
        val problems = parseProblems.toMutableList()
        if (knobs.isEmpty()) {
            problems += "$sourceRel: parsed 0 knob keys — refusing to pass vacuously over an empty denominator"
            return problems
        }
        val text = surface.readText()
        knobs.forEach { knob -> markerProblems(knob, documenting(knob.key, text), surfaceRel)?.let { problems += it } }
        return problems
    }
}

class KnobLivenessDocumentedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every knob's liveness is marked in the example config exactly as its flag says`() {
        val source = File(map.mainSources(":core"), KnobKeysDocumented.SOURCE_IN_CORE)
        val surface = File(map.root, KnobKeysDocumented.EXAMPLE_CONFIG)
        val problems = KnobLivenessDocumented.audit(
            source,
            KotlinText.rel(map, source),
            surface,
            KnobKeysDocumented.EXAMPLE_CONFIG,
        )
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "KNOB LIVENESS DOCUMENTED violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into, mirroring the keys law's fixture. */
    private class Tree(root: File) {
        val source = File(root, "core/src/main/kotlin/${KnobKeysDocumented.SOURCE_IN_CORE}")
        val surface = File(root, KnobKeysDocumented.EXAMPLE_CONFIG)

        fun write(src: String, doc: String) {
            source.parentFile.mkdirs()
            source.writeText(src)
            surface.parentFile.mkdirs()
            surface.writeText(doc)
        }

        fun audit() = KnobLivenessDocumented.audit(
            source,
            "core/src/main/kotlin/${KnobKeysDocumented.SOURCE_IN_CORE}",
            surface,
            KnobKeysDocumented.EXAMPLE_CONFIG,
        )
    }

    @Test
    fun `a compliant tree is green, and a missing or wrong marker is reported by name`(@TempDir root: File) {
        with(Tree(root)) {
            write(SOURCE, DOC)
            assertEquals(emptyList<String>(), audit())

            // The flag itself is the denominator, so it is read off the entry, not from a list here.
            assertEquals(
                listOf("port" to true, "maxInflight" to false, "effort" to true),
                KnobKeysDocumented.parseKnobs(SOURCE, "fixture").first.map { it.key to it.restartRequired },
            )

            write(SOURCE, DOC.replace("(HOT)", ""))
            val unmarked = audit()
            assertTrue(unmarked.any { it.contains("NOT MARKED LIVE") && it.contains("maxInflight") }, "$unmarked")

            write(SOURCE, DOC.replace("""port = "8080"""", """port = "8080" # (HOT)"""))
            val overmarked = audit()
            assertTrue(
                overmarked.any { it.contains("MARKED LIVE BUT RESTART-ONLY") && it.contains("port") },
                "$overmarked",
            )

            // A knob documented on two lines passes on ONE marker: the per-head line stays unmarked on
            // purpose, because splice.toml's layers are never re-read.
            write(SOURCE, "$DOC\n[heads.alt.overrides]\nmaxInflight = \"4\"\n")
            assertEquals(emptyList<String>(), audit(), "a second, unmarked line must not red a marked knob")
        }
    }

    private companion object {
        val SOURCE = """
            package splice.core.config
            public enum class Knob(
                public val key: String,
                public val kind: KnobKind,
                public val restartRequired: Boolean = false,
            ) {
                PORT("port", KnobKind.NUMBER, restartRequired = true),
                MAX_INFLIGHT("maxInflight", KnobKind.NUMBER),
                EFFORT("effort", KnobKind.STRING, restartRequired = true),
            }
        """.trimIndent()

        val DOC = """
            port = "8080"
            maxInflight = "64"  # requests in flight at once (HOT)
            effort = "medium"
        """.trimIndent()
    }
}
