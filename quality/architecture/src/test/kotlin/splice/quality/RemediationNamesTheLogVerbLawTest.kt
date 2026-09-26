// NEW: JW-08's negative half — a remediation that sends the operator to daemon.log must name the
// verb that reads it (ported from the jw_08_splice_logs_verb wall, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. `splice logs` was built because every remediation string in the tree ended at
// "daemon.log" — a file whose path the operator had to know, in a directory doctor was printing
// wrongly. Shipping the verb fixes nothing if the next remediation written still says "check
// daemon.log", so the guarantee is a ban, not a feature: telling someone to go read the file by
// hand, without naming the command that does it, is the defect.
//
// SCOPE IS THE WHOLE PRODUCTION TREE, not one package. The wall it replaces watched the control
// plane, having already been re-aimed once when the remediation strings moved out of
// ControlServer.kt into api/AuthRoutes.kt. A law that sweeps every module cannot be outrun by the
// next move, and the sweep costs the same.
//
// WHAT COUNTS. String LITERALS only, from comment-stripped sources: prose in a comment is prose,
// and the path literals the logger and doctor build ("daemon.log", "daemon.log.1") are not
// remediations. A literal violates when it names the file, tells the reader to go at it with an
// imperative (check, see, look at, tail, read, inspect, open, cat), and does not also name
// `splice logs`.
//
// ANTI-VACUITY. A sweep that finds no literal mentioning daemon.log has either lost its extractor
// or is grading an empty tree; either way it must not pass. The guard is source-derived: the tree
// does mention the file, so zero mentions is a broken law rather than a clean one.
//
// NOT CAUGHT, and deliberately. A remediation that reaches the file through a template hole —
// "check ${logsDir.resolve("daemon.log")} for details" — splits into three runs for this reader,
// and the run carrying the filename holds no imperative. The obvious widening is a LINE scan, and
// it is wrong: DoctorReportTail.kt reads `val read = files.tails(... "daemon.log" ...)`, where
// `read` is a binding, not an instruction to the operator, and a line scan reds it. A law that
// cries wolf on correct code teaches readers to ignore it, so this one keeps the narrower
// denominator it can defend and says so here rather than implying it catches every shape.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object RemediationNamesTheLogVerb {
    const val LOG_FILE: String = "daemon.log"
    const val VERB: String = "splice logs"

    private val IMPERATIVE = Regex("\\b(check|see|look at|tail|read|inspect|open|cat)\\b", RegexOption.IGNORE_CASE)

    /** The string literals of one comment-stripped source, quotes excluded. */
    fun literals(source: String): List<String> {
        val stripped = KotlinText.stripComments(source)
        val kinds = KotlinText.kinds(stripped, comments = false)
        val out = mutableListOf<String>()
        var i = 0
        while (i < stripped.length) {
            if (kinds[i] != KotlinText.STRING) {
                i += 1
                continue
            }
            val start = i
            while (i < stripped.length && kinds[i] == KotlinText.STRING) i += 1
            out += stripped.substring(start, i).trim('"', '\'')
        }
        return out
    }

    /** Pure: repo-relative path -> source text. */
    fun audit(sources: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        var mentions = 0
        for ((rel, text) in sources.toSortedMap()) {
            for (literal in literals(text)) {
                if (!literal.contains(LOG_FILE)) continue
                mentions += 1
                if (IMPERATIVE.containsMatchIn(literal) && !literal.contains(VERB)) {
                    problems += "$rel: a remediation sends the operator to $LOG_FILE by hand — " +
                        "\"${literal.trim()}\". Name `$VERB`, which is the command that reads it; the operator " +
                        "should not have to know the path, and doctor printed the wrong directory for years."
                }
            }
        }
        if (mentions == 0) {
            problems += "no string literal in the swept tree mentions $LOG_FILE — this law greps literals, so zero " +
                "mentions means the extractor or the denominator broke, not that the tree is clean"
        }
        return problems
    }
}

class RemediationNamesTheLogVerbLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `no remediation sends the operator to the log file by hand - JW-08`() {
        val sources = KotlinText.kotlinFiles(map).associate { file -> KotlinText.rel(map, file) to file.readText() }
        val problems = RemediationNamesTheLogVerb.audit(sources)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(
                separator = "\n  - ",
                prefix = "REMEDIATION NAMES THE LOG VERB (JW-08) violated:\n  - ",
            )
        }
    }

    @Test
    fun `the law can actually fail - the imperative, the exemptions, and the empty sweep`() {
        val path = "app/src/main/kotlin/Logger.kt"
        assertEquals(
            emptyList<String>(),
            RemediationNamesTheLogVerb.audit(mapOf(path to PATH_ONLY)),
            "a path literal is not a remediation",
        )

        val banned = RemediationNamesTheLogVerb.audit(mapOf(path to PATH_ONLY, "app/src/main/kotlin/R.kt" to BANNED))
        assertHit(banned, "R.kt", "by hand") { "an imperative remediation naming the file must be RED" }

        val cured = RemediationNamesTheLogVerb.audit(mapOf(path to PATH_ONLY, "app/src/main/kotlin/R.kt" to CURED))
        assertEquals(emptyList<String>(), cured, "the same sentence naming the verb is the fix, not a finding")

        val commentedSources = mapOf(path to PATH_ONLY, "app/src/main/kotlin/R.kt" to COMMENTED)
        val commented = RemediationNamesTheLogVerb.audit(commentedSources)
        assertEquals(emptyList<String>(), commented, "prose in a comment is prose")

        assertHit(RemediationNamesTheLogVerb.audit(emptyMap()), "extractor or the denominator broke") {
            "a sweep that mentions the file nowhere must REFUSE, never pass"
        }
        assertHit(RemediationNamesTheLogVerb.audit(mapOf(path to "val x = 1\n")), "extractor") {
            "a tree with no mention at all is the same refusal"
        }
    }

    @Test
    fun `every imperative in the list is caught, so the class is not one word`() {
        for (verb in listOf("check", "see", "look at", "tail", "read", "inspect", "open", "cat")) {
            val source = "val fix = \"$verb daemon.log for the reason\"\n"
            assertHit(RemediationNamesTheLogVerb.audit(mapOf("app/src/main/kotlin/R.kt" to source)), "R.kt") {
                "'$verb daemon.log' must be RED"
            }
        }
    }

    private companion object {
        const val PATH_ONLY = "val file = logsDir.resolve(\"daemon.log\")\nval rolled = \"daemon.log.1\"\n"
        const val BANNED = "val fix = \"check daemon.log for the upstream error\"\n"
        const val CURED = "val fix = \"check daemon.log, or run splice logs --tail 50\"\n"
        const val COMMENTED =
            "// the old advice was: check daemon.log for the upstream error\nval fix = \"splice logs\"\n"
    }
}
