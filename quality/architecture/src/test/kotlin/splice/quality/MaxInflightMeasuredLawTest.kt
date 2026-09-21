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
    private val KNOB = Regex("MAX_INFLIGHT\\(\\s*\"maxInflight\"[\\s\\S]*?,\\s*(\\d+)L\\s*\\)")
    private val MEASURE = Regex("([\\d.]+)%\\s+turn failure at inflight\\s*<=\\s*(\\d+)")

    /** Pure: (knob source, example config text) -> problems. */
    fun audit(knobSource: String?, knobRel: String, example: String?, exampleRel: String): List<String> {
        if (knobSource == null) return listOf("$knobRel: missing — the knob enum carries the default under audit")
        val knob = KNOB.find(KotlinText.stripComments(knobSource))
            ?: return listOf("$knobRel: MAX_INFLIGHT knob declaration not found (shape changed?) — refusing to pass vacuously")
        val default = knob.groupValues[1].toLong()
        if (example == null) return listOf("$exampleRel: missing — the measured ceiling lives there and nowhere else")
        val measure = MEASURE.find(example)
            ?: return listOf(
                "$exampleRel: no '<pct>% turn failure at inflight<=<n>' measurement line — the ceiling is read from " +
                    "the config's own measurement, never hardcoded, so without it there is no ceiling to grade against",
            )
        val ceiling = measure.groupValues[2].toLong()
        if (default > ceiling) {
            return listOf(
                "shipped maxInflight default $default exceeds the measured-good ceiling $ceiling " +
                    "(${measure.groupValues[1]}% turn failure at inflight<=$ceiling, $exampleRel). Re-measure, " +
                    "or reconcile Knob.kt with splice's own measurement.",
            )
        }
        return emptyList()
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
    fun `the law can actually fail - the gap, the boundary, the missing line, the commented entry`() {
        assertEquals(emptyList<String>(), audit(knob(12), EXAMPLE), "12 under a ceiling of 14 is GREEN")
        assertEquals(emptyList<String>(), audit(knob(14), EXAMPLE), "the boundary value is GREEN")
        assertHit(audit(knob(100), EXAMPLE), "default 100 exceeds", "ceiling 14") { "the original gap must be RED by name" }
        assertHit(audit(knob(15), EXAMPLE), "default 15 exceeds") { "one over the ceiling must be RED" }

        // A commented-out old entry is history: only the live entry is the default.
        val commented = "    // MAX_INFLIGHT(\"maxInflight\", KnobKind.NUMBER, listOf(\"CLAUDEX_MAX_INFLIGHT\"), 100L),\n" + knob(12)
        assertEquals(emptyList<String>(), audit(commented, EXAMPLE), "a commented-out 100 is not the default")

        assertHit(audit(knob(12), "# no measurement here\n"), "no", "measurement line") { "a missing line must REFUSE" }
        assertHit(audit(null, EXAMPLE), "missing") { "a missing knob source must be RED" }
        assertHit(audit(knob(12), null), "missing") { "a missing example config must be RED" }
        assertHit(audit("enum class Knob { PORT(\"port\", KnobKind.NUMBER, listOf(), 1L) }", EXAMPLE), "not found") {
            "a renamed or removed MAX_INFLIGHT entry must REFUSE, never pass over an empty match"
        }
    }

    private fun audit(knob: String?, example: String?) =
        MaxInflightMeasured.audit(knob, "core/src/main/kotlin/splice/core/config/Knob.kt", example, "config/splice.example.toml")

    private fun knob(default: Long) =
        "    MAX_INFLIGHT(\"maxInflight\", KnobKind.NUMBER, listOf(\"CLAUDEX_MAX_INFLIGHT\"), ${default}L),\n"

    private companion object {
        const val EXAMPLE = "# box: 0.3% turn failure at inflight<=14, 11% at 38, 67% at 100.\n"
    }
}
