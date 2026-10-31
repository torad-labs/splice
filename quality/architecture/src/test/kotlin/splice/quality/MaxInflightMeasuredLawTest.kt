// NEW: NF-02 — the shipped maxInflight default may not exceed the measured-good ceiling (ported
// from the nf_02_max_inflight_default wall, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. Knob.kt once shipped MAX_INFLIGHT at 100 while the example config's own
// measurement line read "0.3% turn failure at inflight<=14, 11% at 38, 67% at 100": the default
// WAS the value measured as catastrophic, and it ran live for weeks (92% of turns at inflight>=2,
// 32% errors in one hour, 2026-07-26).
//
// THE CEILING IS READ FROM THE CONFIG'S OWN MEASUREMENT LINE, never hardcoded here, so
// re-measuring is the only sanctioned way to move this law and editing the law cannot buy
// headroom. The wall this replaces carried a fallback ceiling for a missing line; this law
// refuses instead — a ceiling nobody measured is not a ceiling, and a green over it is the
// vacuous pass §24 exists against.
//
// Both inputs are read comment-stripped: a commented-out old entry is history, not the default.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

internal object MaxInflightMeasured {
    // The default is a typed count, `KnobDefault.Count(12L)`, or a bare literal from before the type.
    private val KNOB = Regex(
        "MAX_INFLIGHT\\(\\s*\"maxInflight\"[\\s\\S]*?,\\s*" +
            "(?:KnobDefault\\.Count\\()?(\\d+)L\\s*\\)",
    )
    private val MEASURE = Regex("([\\d.]+)%\\s+turn failure at inflight\\s*<=\\s*(\\d+)")

    /** One side's reading: the number, or why it could not be read. [detail] carries the measured
     *  failure rate, which the verdict quotes back so a red says what the ceiling was measured from. */
    private data class Read(val value: Long?, val problems: List<String>, val detail: String = "")

    private fun shippedDefault(source: String?, rel: String): Read {
        if (source == null) return Read(null, listOf("$rel: missing — the knob enum carries the default under audit"))
        val knob = KNOB.find(KotlinText.stripComments(source))
            ?: return Read(
                null,
                listOf("$rel: MAX_INFLIGHT knob declaration not found (shape changed?) — refusing to pass vacuously"),
            )
        return Read(knob.groupValues[1].toLong(), emptyList())
    }

    private fun measuredCeiling(example: String?, rel: String): Read {
        if (example == null) {
            return Read(null, listOf("$rel: missing — the measured ceiling lives there and nowhere else"))
        }
        val measure = MEASURE.find(example)
            ?: return Read(
                null,
                listOf(
                    "$rel: no '<pct>% turn failure at inflight<=<n>' measurement line — the ceiling is read from " +
                        "the config's own measurement, never hardcoded, so without it there is no ceiling to " +
                        "grade against",
                ),
            )
        return Read(measure.groupValues[2].toLong(), emptyList(), measure.groupValues[1])
    }

    /** Pure: (knob source, example config text) -> problems. Both sides are read before either is
     *  judged, so a run where neither is readable says so about BOTH rather than about the first. */
    fun audit(knobSource: String?, knobRel: String, example: String?, exampleRel: String): List<String> {
        val default = shippedDefault(knobSource, knobRel)
        val ceiling = measuredCeiling(example, exampleRel)
        val unreadable = default.problems + ceiling.problems
        val shipped = default.value
        val bound = ceiling.value
        return when {
            unreadable.isNotEmpty() -> unreadable
            shipped == null || bound == null -> emptyList()
            shipped <= bound -> emptyList()
            else -> listOf(
                "shipped maxInflight default $shipped exceeds the measured-good ceiling $bound " +
                    "(${ceiling.detail}% turn failure at inflight<=$bound, $exampleRel). Re-measure, " +
                    "or reconcile Knob.kt with splice's own measurement.",
            )
        }
    }
}

class MaxInflightMeasuredLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `the shipped maxInflight default sits at or below the measured ceiling - NF-02`() {
        val knob = File(map.mainSources(":core"), KnobKeysDocumented.SOURCE_IN_CORE)
        val example = File(map.root, KnobKeysDocumented.EXAMPLE_CONFIG)
        val problems = MaxInflightMeasured.audit(
            knob.takeIf { it.isFile }?.readText(),
            KotlinText.rel(map, knob),
            example.takeIf { it.isFile }?.readText(),
            KnobKeysDocumented.EXAMPLE_CONFIG,
        )
        assertEquals(emptyList<String>(), problems) {
            problems.joinToString(separator = "\n  - ", prefix = "MAX INFLIGHT MEASURED (NF-02) violated:\n  - ")
        }
    }

    @Test
    fun `a default over the ceiling is reported, and an unreadable side is refused`() {
        val commented =
            "    // MAX_INFLIGHT(\"maxInflight\", KnobKind.NUMBER, listOf(\"CLAUDEX_MAX_INFLIGHT\"), 100L),\n" +
                knob(12)
        val renamed = "enum class Knob { PORT(\"port\", KnobKind.NUMBER, listOf(), 1L) }"
        // [problem count]: the boundary is green, one over is red, a commented-out old entry is history.
        listOf(
            audit(knob(12), EXAMPLE) to 0,
            audit(knob(14), EXAMPLE) to 0,
            audit(commented, EXAMPLE) to 0,
            audit(knob(15), EXAMPLE) to 1,
            audit(knob(100), EXAMPLE) to 1,
            audit(knob(12), "# no measurement here\n") to 1,
            audit(null, EXAMPLE) to 1,
            audit(knob(12), null) to 1,
            audit(renamed, EXAMPLE) to 1,
        ).forEachIndexed { index, (hits, count) -> assertEquals(count, hits.size, "case $index: $hits") }
    }

    // Both sides are read before either is judged: a tree missing both inputs names both absences.
    @Test
    fun `neither side readable names BOTH absences, not whichever was read first`() {
        val hits = audit(null, null)
        assertEquals(2, hits.size, "both sides unreadable is two problems, got: $hits")
        assertHit(hits, "Knob.kt") { "the knob source must be named" }
        assertHit(hits, "splice.example.toml") { "the example config must be named" }
    }

    private fun audit(knob: String?, example: String?) =
        MaxInflightMeasured.audit(
            knob,
            "core/src/main/kotlin/splice/core/config/Knob.kt",
            example,
            "config/splice.example.toml",
        )

    private fun knob(default: Long) =
        "    MAX_INFLIGHT(\"maxInflight\", KnobKind.NUMBER, listOf(\"CLAUDEX_MAX_INFLIGHT\"), " +
            "KnobDefault.Count(${default}L)),\n"

    private companion object {
        const val EXAMPLE = "# box: 0.3% turn failure at inflight<=14, 11% at 38, 67% at 100.\n"
    }
}
