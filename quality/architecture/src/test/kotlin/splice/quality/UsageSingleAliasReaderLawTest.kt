// NEW: CX-18's negative half — splice.core.util.JsonScalars.firstLong is the ONE multi-key usage
// alias reader, and everything else delegates to it (ported from the cx_18_usage_aliases wall's
// forbidden-reader ban, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. Usage drives `used_percentage`, which drives Claude Code's auto-compaction
// trigger, so a second reader with different numeric parsing does not produce a cosmetic error —
// it makes a head compact constantly or never. Three hand-rolled readers disagreeing is the defect
// CX-18 closed; a FOURTH regrowing quietly is what this law exists to prevent.
//
// WHAT THE WALL ENFORCED, MEASURED (2026-09-21, proof tree at b7b671ec). The ban was
// `/fun\s+num\s*\([^)]*vararg\s+keys/` swept over five package directories derived from the
// carrier paths. Both halves of that are narrower than the sentence it implements:
//   · `internal fun firstOf(obj: JsonObject, vararg keys: String): Long` appended to ChatUsage.kt —
//     inside a banned directory, a second reader by every meaning of the rule — printed WALL GREEN.
//   · `internal fun num(obj: JsonObject, vararg keys: String): Long` appended to
//     upstream/.../RetryAfter.kt — the wall's exact banned spelling, one package outside the swept
//     set — printed WALL GREEN.
//   · the same `fun num(` inside ChatUsage.kt printed WALL RED, so the wall could fire; it was the
//     scope that was short, not the mechanism.
// This law is name-independent and repository-wide, which is the difference.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every production Kotlin file of every module the BUILD
// declares, walked through KotlinText.kotlinFiles and comment-stripped. A file is in scope when it
// DECLARES a multi-key numeric reader: a function taking `vararg <name>: String` and returning a
// number. Nothing about the name, so a reader called firstOf, pick or readNum is seen.
//
// DELEGATION IS WHAT MAKES A SECOND DECLARATION LEGAL. UsageJson.firstNum exists and should: it is
// a one-line wrapper (`= JsonScalars.firstLong(obj, *keys)`) that keeps a local name while the
// parsing stays single-sourced. So a reader is a violation only when its own body does not call the
// chain. "Its own body" is the span from the declaration to the next blank line — expression bodies
// in this tree are one line, and a reader long enough to contain a blank line reds and is examined,
// which is the fail-closed direction.
//
// THE ANCHOR REFUSES VACUITY. JsonScalars must itself declare the chain. If it stops — renamed,
// moved, reshaped — then a reader elsewhere could not be recognised as a non-delegating copy
// either, so the law fails BY NAME rather than grading nothing.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

internal object UsageSingleAliasReader {
    const val CHAIN_IN_CORE: String = "splice/core/util/JsonScalars.kt"

    /** A multi-key numeric reader: a vararg of String keys in, a number out. Deliberately blind to
     *  the function's NAME — that blindness is the whole delta against the wall this replaces. */
    private val MULTI_KEY_READER =
        Regex("""fun\s+(\w+)\s*\([^)]*vararg\s+\w+\s*:\s*String[^)]*\)\s*:\s*(?:Long|Double|Int|Number)\??""")

    /** The one chain, by the name every call site spells. */
    private val DELEGATES = Regex("""\bfirstLong\s*\(""")

    /** The declaration's own body: up to the next blank line, or the end of the file. */
    private fun bodyOf(text: String, from: Int): String {
        val end = text.indexOf("\n\n", from)
        return if (end == -1) text.substring(from) else text.substring(from, end)
    }

    /** The anchor's own verdict: null when JsonScalars still declares the chain this law looks for. */
    private fun anchorProblem(chain: String?, chainRel: String): String? = when {
        chain == null ->
            "$chainRel: missing — the one alias chain anchors this law, and a law that cannot see it must not pass"
        !MULTI_KEY_READER.containsMatchIn(chain) || !DELEGATES.containsMatchIn(chain) ->
            "$chainRel: does not declare the firstLong multi-key chain — the shape this law recognises no longer " +
                "describes the chain it anchors, so a private copy elsewhere could not be recognised; re-derive " +
                "the pattern before trusting this law"
        else -> null
    }

    /** Every non-delegating multi-key reader declared in one file. */
    private fun copiesIn(rel: String, text: String): List<String> =
        MULTI_KEY_READER.findAll(text)
            .filterNot { DELEGATES.containsMatchIn(bodyOf(text, it.range.first)) }
            .map { match ->
                "SECOND ALIAS READER: $rel declares `${match.groupValues[1]}`, a multi-key numeric reader that " +
                    "does not call firstLong — splice.core.util.JsonScalars.firstLong is the ONE usage alias " +
                    "chain; delegate to it, do not re-derive it"
            }
            .toList()

    /** Audit over repository-relative path -> COMMENT-STRIPPED source. Pure, so every refusal is
     *  provable against synthetic input. */
    fun audit(sources: Map<String, String>, chainRel: String): List<String> {
        val chain = sources[chainRel]
        val anchor = listOfNotNull(anchorProblem(chain, chainRel))
        // A missing anchor stops the sweep: with no chain to delegate to, every reader in the tree
        // would be reported, which buries the one problem that has to be fixed first.
        if (chain == null) return anchor
        return anchor + sources.toSortedMap().filterKeys { it != chainRel }
            .flatMap { (rel, text) -> copiesIn(rel, text) }
    }
}

class UsageSingleAliasReaderLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `JsonScalars firstLong is the one multi-key usage alias reader - CX-18`() {
        val sources = KotlinText.kotlinFiles(map).associate { file ->
            KotlinText.rel(map, file) to KotlinText.stripComments(file.readText())
        }
        val chainFile = File(map.mainSources(":core"), UsageSingleAliasReader.CHAIN_IN_CORE)
        val chainRel = KotlinText.rel(map, chainFile)
        val problems = UsageSingleAliasReader.audit(sources, chainRel)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "USAGE SINGLE ALIAS READER (CX-18) violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - the two shapes the wall was blind to, the anchor, and delegation`() {
        assertEquals(
            emptyList<String>(),
            UsageSingleAliasReader.audit(COMPLIANT, CHAIN),
            "compliant tree must be GREEN",
        )

        // The wall's first blind spot: a second reader under a DIFFERENT NAME. Measured GREEN on the
        // wall inside a banned directory; named here.
        val renamed = COMPLIANT + (CHAT to RENAMED_READER)
        assertHit(UsageSingleAliasReader.audit(renamed, CHAIN), "SECOND ALIAS READER", "firstOf", CHAT) {
            "a private alias reader under another name must be RED by name"
        }

        // The wall's second blind spot: the wall's OWN banned spelling, outside the swept packages.
        val faraway = COMPLIANT + (FAR to FAR_READER)
        assertHit(UsageSingleAliasReader.audit(faraway, CHAIN), "SECOND ALIAS READER", "num", FAR) {
            "a private alias reader outside the usage packages must be RED — the denominator is the repository"
        }

        // Delegation is what makes a second DECLARATION legal: UsageJson.firstNum is the live shape.
        val delegating = COMPLIANT + (HUD to DELEGATING_READER)
        assertEquals(
            emptyList<String>(),
            UsageSingleAliasReader.audit(delegating, CHAIN),
            "a thin wrapper that calls firstLong is GREEN — the local name is not the defect",
        )

        // A file delegating SOMEWHERE ELSE does not launder a re-deriving reader beside it: the
        // delegation must be in the reader's own body.
        val mixed = COMPLIANT + (HUD to DELEGATING_READER + "\n\n" + RENAMED_READER)
        assertHit(UsageSingleAliasReader.audit(mixed, CHAIN), "SECOND ALIAS READER", "firstOf") {
            "a delegating sibling in the same file must not cover a re-deriving reader"
        }

        // A reader that survives only in a comment was stripped before the live test ever saw it.
        val stripped = COMPLIANT + (HUD to KotlinText.stripComments(COMMENTED_READER))
        assertEquals(
            emptyList<String>(),
            UsageSingleAliasReader.audit(stripped, CHAIN),
            "a reader in a comment is prose",
        )

        // A vararg helper that returns something other than a number is not an alias reader.
        val unrelated = COMPLIANT + ("app/src/main/kotlin/Args.kt" to UNRELATED_VARARG)
        assertEquals(
            emptyList<String>(),
            UsageSingleAliasReader.audit(unrelated, CHAIN),
            "a non-numeric vararg helper is out of scope",
        )

        assertHit(UsageSingleAliasReader.audit(COMPLIANT - CHAIN, CHAIN), "missing") {
            "a missing chain must be RED"
        }
        val hollow = COMPLIANT + (CHAIN to "public object JsonScalars { public fun str(e: JsonElement?): String? = null }\n")
        assertHit(UsageSingleAliasReader.audit(hollow, CHAIN), "does not declare the firstLong multi-key chain") {
            "an unanchored chain must be RED"
        }
    }

    private companion object {
        const val CHAIN = "core/src/main/kotlin/splice/core/util/JsonScalars.kt"
        const val CHAT = "integrations/dialects/openai-chat/src/main/kotlin/splice/dialect/chat/ChatUsage.kt"
        const val HUD = "features/turns/src/main/kotlin/splice/head/usage/UsageJson.kt"
        const val FAR = "integrations/upstream/src/main/kotlin/splice/upstream/retry/RetryAfter.kt"

        const val CHAIN_SOURCE = """
            public object JsonScalars {
                public fun firstLong(obj: JsonObject?, vararg keys: String): Long? =
                    keys.firstNotNullOfOrNull { key -> str(obj?.get(key))?.toDoubleOrNull()?.toLong() }
            }
        """

        const val CALLER_SOURCE = """
            val input = JsonScalars.firstLong(u, "prompt_tokens", "input_tokens") ?: 0
        """

        // The exact injection measured GREEN on the wall inside a banned directory.
        const val RENAMED_READER = """
            internal fun firstOf(obj: JsonObject, vararg keys: String): Long =
                keys.firstNotNullOfOrNull { obj[it]?.toString()?.toDoubleOrNull()?.toLong() } ?: 0L
        """

        // The wall's own banned spelling, in a file one package outside the swept set.
        const val FAR_READER = """
            internal fun num(obj: JsonObject, vararg keys: String): Long =
                keys.firstNotNullOfOrNull { obj[it]?.toString()?.toDoubleOrNull()?.toLong() } ?: 0L
        """

        // UsageJson.firstNum as it actually ships: a local name over the single definition.
        const val DELEGATING_READER = """
            private fun firstNum(obj: JsonObject, vararg keys: String): Long? = JsonScalars.firstLong(obj, *keys)
        """

        const val COMMENTED_READER = """
            // internal fun num(obj: JsonObject, vararg keys: String): Long = 0L
            val input = JsonScalars.firstLong(u, "input_tokens") ?: 0
        """

        const val UNRELATED_VARARG = """
            private fun joined(vararg parts: String): String = parts.joinToString(" ")
        """

        val COMPLIANT: Map<String, String> = mapOf(
            CHAIN to CHAIN_SOURCE.trimIndent(),
            CHAT to CALLER_SOURCE.trimIndent(),
        )
    }
}
