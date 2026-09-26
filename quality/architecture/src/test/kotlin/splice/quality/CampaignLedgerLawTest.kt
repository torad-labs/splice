// NEW: no campaign ledger silently loses memory (DR-181/DR-189, restructure PR 6 §4.3).
// (ported from checks/campaign-ledger-floor.ts; oracle checks/campaign-ledger-floor-selftest.sh.)
//
// CLASS. A RATCHET, not a wall — the same shape as the silent-constants and public-surface laws:
// growth is free, a shrink is a violation named by the exact number that fell. On 2026-09-01
// .dev/campaigns/drift-repair.toml went from 164 rows to 15 and lost 2604 lines, was committed and
// pushed, and the FULL gate passed 13 of 13 legs on that tip — no leg read the file at all, so a
// green gate said nothing about campaign memory, the one artifact in this repo whose whole purpose
// is to survive the session that wrote it. DR-189 widened the walk to recurse: the first version
// globbed one level, which quietly meant "campaign memory" was defined as "whatever sits at the top
// of the directory", and proxy-hardening/walls/law_registry.toml — which carries its own
// never-delete law in its own header and is graded by nothing else — went 19 rows to 1 with every
// other leg green.
//
// SCOPE. .dev/campaigns/**/*.toml, RECURSIVELY, off [ProjectMap.root] — never the floor file's own
// keys, or a ledger the floor does not yet know about could vanish in the one gap that matters.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every `.toml` under .dev/campaigns, keyed by the path
// RELATIVE TO .dev/campaigns (so a nested registry's key is
// `proxy-hardening/walls/law_registry.toml`, and two campaigns' files sharing a basename cannot
// collide into one floor entry). Zero ledgers found is a violation of its own: the walk refuses to
// pass vacuously over an empty directory, exactly the shape a checker hardcoded to `exit 0` would
// satisfy by accident.
//
// PARSE. Two instruments per ledger. `rows` — lines matching `^\[\[items?\]\]\s*$`, the same header
// the ledger CLI itself writes, so this counts what the CLI calls an item. `lines` — Python's
// `str.splitlines()` count: a trailing newline does not create an empty final line, so the naive
// `text.split("\n").size` would report one line too many for every ledger that ends with a newline
// — which is all of them — and every floor would move on a no-op port. See
// [CampaignLedgerFloor.lineCount].
//
// VIOLATIONS, each BY NAME with the checker's own diagnostic words kept, so a reader who knows the
// checker's vocabulary reads the same diagnosis here: a ledger on disk with no recorded floor
// ("no recorded floor"), a recorded ledger gone from disk ("RECORDED BUT GONE"), and rows or lines
// below the floor ("rows fell", "lines fell"). Growth never fails.
//
// RECORDING. The law cannot write, so `--record` / `--allow-shrink` retire with the checker: the
// remedy named in every violation is now a JSON edit BY HAND in the same commit as the change that
// caused it. A new ledger prints its own measured `{"lines": N, "rows": N}` in the violation, ready
// to paste into campaign-ledger-floor.json; a deliberate shrink (a `remove`, a retired campaign) is
// a lowered entry next to the deletion that caused it — exactly the reviewable diff line the
// checker's own header wanted. The JSON's format (sorted keys at both levels, two-space indent, a
// trailing newline) is unchanged by the move; this law only reads it.
//
// NOT CAUGHT: a floor entry missing a `rows` or `lines` key fails the load here rather than
// defaulting the missing unit to 0, which is what the checker's `?? 0` did — stricter, deliberately,
// since a floor this law can no longer regenerate should never be silently half-read.
package splice.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The recorded floor on the test classpath, and the path a violation tells the reader to edit. */
private const val FLOOR_RESOURCE = "/campaign-ledger-floor.json"
private const val FLOOR_PATH = "quality/architecture/src/test/resources/campaign-ledger-floor.json"
private const val LEDGER_DIR = ".dev/campaigns"

internal object CampaignLedgerFloor {
    /** The same shape the ledger CLI's own header matches, so this counts what the CLI calls an item. */
    private val HDR = Regex("^\\[\\[items?\\]\\]\\s*$")

    /** rows — `[[items]]` blocks, a work unit. lines — the file's total lines, where the dated notes
     *  under each row live; a truncation that kept every row while deleting every note would still
     *  destroy the campaign, so row count alone is not enough. */
    data class Measurement(val rows: Int, val lines: Int)

    /** Python's `str.splitlines()` count, which is NOT `text.split("\n").size`: a trailing newline
     *  does not create an empty final line there, so the naive port would report one line too many
     *  for every ledger that ends with a newline — which is all of them — and every floor would move. */
    fun lineCount(text: String): Int {
        val newlines = text.count { it == '\n' }
        return if (text.isNotEmpty() && !text.endsWith("\n")) newlines + 1 else newlines
    }

    fun measure(file: File): Measurement {
        val text = file.readText()
        val rows = text.split("\n").count { HDR.matches(it) }
        return Measurement(rows = rows, lines = lineCount(text))
    }

    /** Every ledger under [dir] — the denominator, read from the source (§24), keyed by the path
     *  relative to [dir] rather than by basename, so a nested registry cannot collide with a
     *  sibling campaign's file of the same name. */
    fun survey(dir: File): Map<String, Measurement> {
        if (!dir.isDirectory) return emptyMap()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "toml" }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to measure(it) }
    }

    /** The recorded floor, or an empty one when there is none to read — the same fallback the
     *  checker's `loadFloor` used for a floor file that does not exist. */
    fun parseFloor(text: String?): Map<String, Measurement> {
        if (text == null) return emptyMap()
        val document = Json.parseToJsonElement(text).jsonObject
        return document.mapValues { (_, value) ->
            val entry = value.jsonObject
            Measurement(
                rows = entry.getValue("rows").jsonPrimitive.int,
                lines = entry.getValue("lines").jsonPrimitive.int,
            )
        }
    }

    private fun noFloorMessage(name: String, have: Measurement): String =
        "$name: on disk with no recorded floor — every ledger needs a disposition. Add \"$name\": " +
            "{\"lines\": ${have.lines}, \"rows\": ${have.rows}} to $FLOOR_PATH BY HAND."

    private fun goneMessage(name: String): String =
        "$name: RECORDED BUT GONE — a whole campaign ledger has disappeared. If the deletion is " +
            "deliberate, remove its entry from $FLOOR_PATH BY HAND in the same commit."

    private fun fellMessage(name: String, unit: String, have: Int, want: Int): String =
        "$name: $unit fell ${want - have} below the recorded floor ($have < $want) — campaign memory " +
            "was lost, not added to. If this shrink is deliberate (a `remove`, a retired campaign), " +
            "lower the entry in $FLOOR_PATH BY HAND in the same commit as the deletion."

    /** [current] against [floor], BY NAME, over every key either side carries — an absence on
     *  either side is its own violation, never a skip. */
    fun violations(current: Map<String, Measurement>, floor: Map<String, Measurement>): List<String> {
        val found = mutableListOf<String>()
        for (name in (current.keys + floor.keys).toSortedSet()) {
            found += violationsFor(name, current[name], floor[name])
        }
        return found
    }

    /** One key's diagnosis: present only on disk, present only in the floor, or graded on both
     *  instruments. The `else` is unreachable by construction (every [name] passed in comes from
     *  the union of both maps' keys) and exists only so the `when` is an exhaustive expression. */
    private fun violationsFor(name: String, have: Measurement?, want: Measurement?): List<String> = when {
        have != null && want == null -> listOf(noFloorMessage(name, have))
        want != null && have == null -> listOf(goneMessage(name))
        have != null && want != null -> fellMessages(name, have, want)
        else -> emptyList()
    }

    private fun fellMessages(name: String, have: Measurement, want: Measurement): List<String> {
        val found = mutableListOf<String>()
        if (have.rows < want.rows) found += fellMessage(name, "rows", have.rows, want.rows)
        if (have.lines < want.lines) found += fellMessage(name, "lines", have.lines, want.lines)
        return found
    }

    /** The full grade: survey [dir], refuse a vacuous pass, then compare against [floorText]. */
    fun audit(dir: File, floorText: String?): List<String> {
        val current = survey(dir)
        if (current.isEmpty()) {
            return listOf(
                "no ledgers found in ${dir.invariantSeparatorsPath} — refusing to pass vacuously, exactly the " +
                    "silent gap DR-181 exists to catch.",
            )
        }
        return violations(current, parseFloor(floorText))
    }
}

private const val MIN_LEDGERS = 10

private const val ALPHA_REL = "alpha.toml"
private const val BETA_REL = "beta.toml"
private const val NESTED_REL = "nested/registry.toml"
private const val GAMMA_REL = "gamma.toml"

private const val ALPHA = """[[items]]
id = "A1"
status = "todo"
# 2026-09-01 a note about A1

[[items]]
id = "A2"
status = "todo"
# 2026-09-02 a note about A2

[[items]]
id = "A3"
status = "todo"
# 2026-09-03 a note about A3

"""

// ALPHA with exactly its trailing blank line removed: same 3 rows, one fewer line — the boring
// case a check keyed on row count alone cannot see.
private const val ALPHA_ONE_LINE_SHORT = """[[items]]
id = "A1"
status = "todo"
# 2026-09-01 a note about A1

[[items]]
id = "A2"
status = "todo"
# 2026-09-02 a note about A2

[[items]]
id = "A3"
status = "todo"
# 2026-09-03 a note about A3
"""

// Only the first block survives: 3 rows -> 1, and lines fall hard alongside it — THE INCIDENT,
// to scale (drift-repair.toml went 189 rows to 15 the same way).
private const val ALPHA_ROWS_TRUNCATED = """[[items]]
id = "A1"
status = "todo"
# 2026-09-01 a note about A1
"""

// Every `[[items]]` header and field survives; every dated note is gone. Rows must stay exactly 3.
private const val ALPHA_NO_NOTES = """[[items]]
id = "A1"
status = "todo"

[[items]]
id = "A2"
status = "todo"

[[items]]
id = "A3"
status = "todo"
"""

private const val BETA = """[[items]]
id = "B1"
status = "done"
# 2026-09-04 a note about B1
"""

private const val NESTED = """[[items]]
id = "N1"
tag = "alpha"

[[items]]
id = "N2"
tag = "beta"

[[items]]
id = "N3"
tag = "gamma"
"""

// DR-189's own shape, one level down: 3 rows -> 1, at a NESTED path.
private const val NESTED_TRUNCATED = """[[items]]
id = "N1"
tag = "alpha"
"""

private const val GAMMA = """[[items]]
id = "G1"
status = "todo"
"""

class CampaignLedgerLawTest {
    private val map = ProjectMap.fromSystemProperties()

    // NESTED, not top-level: `Arm` and `Tree` are names several sibling law files also declare
    // for their own red-proof DSL, and a top-level CLASS — unlike a top-level function or
    // property — has no per-file namespace in Kotlin, so two files each declaring one collide at
    // compile time across the whole package (measured: SafeFailureRenderLawTest.kt's own
    // top-level `Arm` and ReleaseReadinessLawTest.kt's own top-level `Tree`). Nesting both under
    // this class, as SilentConstantsLawTest.kt already does for its own Tree, scopes them under a
    // name this file already owns.

    /** A synthetic `.dev/campaigns` under a fresh root — every arm gets its own, so one mutation
     *  can never leak into another arm's assertion. */
    private class Tree(root: File) {
        private val campaigns = File(root, LEDGER_DIR)

        fun compliant() {
            write(ALPHA_REL, ALPHA)
            write(BETA_REL, BETA)
            write(NESTED_REL, NESTED)
        }

        fun write(rel: String, text: String) {
            File(campaigns, rel).apply { parentFile.mkdirs() }.writeText(text)
        }

        fun delete(rel: String) {
            File(campaigns, rel).delete()
        }

        /** The floor exactly as `--record` would have written it from what is on disk right now. */
        fun snapshotFloor(): String {
            val measured = CampaignLedgerFloor.survey(campaigns)
            val entries = measured.keys.sorted().joinToString(",\n") { rel ->
                val m = measured.getValue(rel)
                "  \"$rel\": {\"lines\": ${m.lines}, \"rows\": ${m.rows}}"
            }
            return "{\n$entries\n}\n"
        }

        fun audit(floorText: String?): List<String> = CampaignLedgerFloor.audit(campaigns, floorText)
    }

    /** One red-proof scenario: [mutate] runs after the floor is recorded from the compliant tree,
     *  and the result must either stay GREEN or go RED naming every string in [needles]. */
    private data class Arm(
        val label: String,
        val needles: List<String> = emptyList(),
        val expectGreen: Boolean = false,
        val mutate: Tree.() -> Unit,
    )

    private fun arms(): List<Arm> = listOf(
        Arm("the tree as committed, unmutated", expectGreen = true) {},
        Arm("one ledger one line short", listOf(ALPHA_REL, "lines fell 1 below")) {
            write(ALPHA_REL, ALPHA_ONE_LINE_SHORT)
        },
        Arm("a ledger truncated to a fraction of its rows", listOf(ALPHA_REL, "rows fell")) {
            write(ALPHA_REL, ALPHA_ROWS_TRUNCATED)
        },
        Arm("a ledger present on disk with no recorded floor", listOf(GAMMA_REL, "no recorded floor")) {
            write(GAMMA_REL, GAMMA)
        },
        Arm("a recorded ledger deleted outright", listOf(ALPHA_REL, "RECORDED BUT GONE")) {
            delete(ALPHA_REL)
        },
        Arm("a NESTED registry truncated to one row", listOf(NESTED_REL, "rows fell")) {
            write(NESTED_REL, NESTED_TRUNCATED)
        },
        Arm("an empty campaigns directory", listOf("no ledgers found")) {
            delete(ALPHA_REL)
            delete(BETA_REL)
            delete(NESTED_REL)
        },
    )

    @Test
    fun `live - every campaign ledger is at or above its recorded floor - DR-181 DR-189`() {
        val dir = File(map.root, LEDGER_DIR)
        val current = CampaignLedgerFloor.survey(dir)
        assertTrue(current.size >= MIN_LEDGERS) {
            "found ${current.size} ledger(s) under $LEDGER_DIR — the tree carries at least $MIN_LEDGERS today; " +
                "a count this low means the walk is broken, not that memory shrank"
        }
        val problems = CampaignLedgerFloor.violations(current, CampaignLedgerFloor.parseFloor(floorText()))
        assertTrue(problems.isEmpty()) {
            problems.joinToString(
                separator = "\n  - ",
                prefix = "CAMPAIGN LEDGER FLOOR (DR-181/DR-189) violated:\n  - ",
            )
        }
    }

    /** Row count alone cannot see a note-only truncation, and the notes are where a campaign's
     *  reasoning actually lives — the exact gap `lines` exists to close. */
    @Test
    fun `every row kept every note deleted is red under lines, never rows - DR-181 DR-189`(@TempDir root: File) {
        val tree = Tree(root)
        tree.compliant()
        val floorText = tree.snapshotFloor()
        tree.write(ALPHA_REL, ALPHA_NO_NOTES)
        val result = tree.audit(floorText)
        assertHit(result, ALPHA_REL, "lines fell") { "every note gone must be RED under lines" }
        assertTrue(result.none { it.contains(ALPHA_REL) && it.contains("rows fell") }) {
            "rows must stay exactly intact when only notes are stripped, got: ${KotlinText.pyReprList(result)}"
        }
    }

    @TestFactory
    fun `the law can actually fail - one arm per DR-181 DR-189 diagnosis`(@TempDir root: File): List<DynamicTest> =
        arms().mapIndexed { index, arm ->
            DynamicTest.dynamicTest(arm.label) {
                val tree = Tree(File(root, index.toString()))
                tree.compliant()
                val floorText = tree.snapshotFloor()
                arm.mutate(tree)
                val result = tree.audit(floorText)
                if (arm.expectGreen) {
                    assertEquals(emptyList<String>(), result, "${arm.label} must stay GREEN")
                } else {
                    assertHit(result, *arm.needles.toTypedArray()) { "${arm.label} must be RED, naming ${arm.needles}" }
                }
            }
        }

    private fun floorText(): String = checkNotNull(CampaignLedgerFloor::class.java.getResource(FLOOR_RESOURCE)) {
        "$FLOOR_PATH is not on the test classpath — the ratchet cannot grade a floor it cannot read. " +
            "quality/architecture/build.gradle.kts already declares src/test/resources/**/*.json as an input."
    }.readText()
}
