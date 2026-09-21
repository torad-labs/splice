// NEW: NF-04's architecture half — splice.upstream.retry.RetryAfter is the ONE Retry-After parser
// (ported from the nf_04_retry_after_date_form wall's second-parser leg, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. V4-100 found MuseRefresh carrying its own private copy of both RFC 7231 forms —
// a mirror no wall read, so its seconds/date ordering and its past-date clamp were free to drift
// from the real parser with nothing noticing. The parser's own contract is pinned in
// upstream's RetryAfterTest; this law is the other half: nobody else may re-derive it.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every production Kotlin file of every module the BUILD
// declares, walked through KotlinText.kotlinFiles. A file is in scope when it MENTIONS the header
// (any spelling); it violates when it also carries a token only a parser carries: the RFC 7231
// date formatter, the digit-only seconds guard (either polarity), or the leading-zero
// normaliser. A file that merely hands the header to RetryAfter carries none of them, which is
// what keeps delegating free and re-implementing red. Comments are stripped first: a comment
// naming the formatter is prose, not a parser.
//
// THE ANCHOR REFUSES VACUITY. The one parser must itself carry every marker. If it stops — the
// parse was rewritten and the markers no longer describe a parser — a mirror elsewhere could not
// be recognised either, so the law fails BY NAME instead of grading nothing.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

internal object RetryAfterSingleParser {
    const val PARSER_IN_UPSTREAM = "splice/upstream/retry/RetryAfter.kt"

    private val HEADER_MENTION = Regex("Retry-After|retry_after|retryAfter", RegexOption.IGNORE_CASE)
    private val PARSER_MARKERS = listOf(
        Regex("RFC_1123_DATE_TIME"),
        Regex("it\\s+!?in\\s+'0'\\.\\.'9'"),
        Regex("trimStart\\('0'\\)\\s*\\.ifEmpty"),
    )

    /** Audit over repository-relative path -> COMMENT-STRIPPED source. Pure, so every refusal is
     *  provable against synthetic input. */
    fun audit(sources: Map<String, String>, parserRel: String): List<String> {
        val parser = sources[parserRel]
            ?: return listOf(
                "$parserRel: missing — the one parser anchors this law's markers, and a law that cannot " +
                    "see it must not pass",
            )
        val problems = mutableListOf<String>()
        val anchored = PARSER_MARKERS.count { it.containsMatchIn(parser) }
        if (anchored != PARSER_MARKERS.size) {
            problems += "$parserRel: carries $anchored of ${PARSER_MARKERS.size} parser markers — the markers no " +
                "longer describe the parser they anchor, so a mirror elsewhere could not be recognised; " +
                "re-derive the markers before trusting this law"
        }
        for ((rel, text) in sources.toSortedMap()) {
            if (rel == parserRel || !HEADER_MENTION.containsMatchIn(text)) continue
            val markers = PARSER_MARKERS.filter { it.containsMatchIn(text) }.map { it.pattern }
            if (markers.isNotEmpty()) {
                problems += "SECOND PARSER: $rel parses the Retry-After header itself (${markers.joinToString(", ")}) — " +
                    "splice.upstream.retry.RetryAfter is the ONE parser; call it, do not re-derive it"
            }
        }
        return problems
    }
}

class RetryAfterSingleParserLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `splice upstream retry RetryAfter is the one Retry-After parser - NF-04`() {
        val sources = KotlinText.kotlinFiles(map).associate { file ->
            KotlinText.rel(map, file) to KotlinText.stripComments(file.readText())
        }
        val parserFile = File(map.mainSources(":upstream"), RetryAfterSingleParser.PARSER_IN_UPSTREAM)
        val parserRel = KotlinText.rel(map, parserFile)
        val problems = RetryAfterSingleParser.audit(sources, parserRel)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "RETRY-AFTER SINGLE PARSER (NF-04) violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - each marker, the anchor, and the out-of-scope file`() {
        assertEquals(
            emptyList<String>(),
            RetryAfterSingleParser.audit(COMPLIANT, PARSER),
            "compliant tree must be GREEN",
        )

        // Three variants of the same class: one marker each, in a file that names the header.
        for ((label, body) in MIRRORS) {
            val hits = RetryAfterSingleParser.audit(COMPLIANT + ("app/src/main/kotlin/Mirror.kt" to body), PARSER)
            assertHit(hits, "SECOND PARSER", "Mirror.kt") { "a mirror carrying the $label marker must be RED by name" }
        }

        // A parser token in a file that never names the header is out of scope: the class is
        // "re-deriving THIS header", not "using a date formatter".
        val unrelated = COMPLIANT + ("core/src/main/kotlin/Clock.kt" to "val f = DateTimeFormatter.RFC_1123_DATE_TIME\n")
        assertEquals(
            emptyList<String>(),
            RetryAfterSingleParser.audit(unrelated, PARSER),
            "an unrelated formatter user is GREEN",
        )

        // A marker that survives only in a comment was stripped before this audit ever saw it —
        // the live test strips; the pure audit grades what it is handed.
        val stripped = COMPLIANT + ("app/src/main/kotlin/Delegator.kt" to KotlinText.stripComments(COMMENTED_MIRROR))
        assertEquals(
            emptyList<String>(),
            RetryAfterSingleParser.audit(stripped, PARSER),
            "a marker in a comment is prose",
        )

        assertHit(RetryAfterSingleParser.audit(COMPLIANT - PARSER, PARSER), "missing") {
            "a missing parser must be RED"
        }
        val hollow = COMPLIANT + (PARSER to "class RetryAfter { fun retryAfterMs(h: String?): Long? = null }\n")
        assertHit(RetryAfterSingleParser.audit(hollow, PARSER), "0 of 3 parser markers") {
            "an unanchored parser must be RED"
        }
    }

    private companion object {
        const val PARSER = "upstream/src/main/kotlin/splice/upstream/retry/RetryAfter.kt"
        val COMPLIANT: Map<String, String> = mapOf(
            PARSER to """
                class RetryAfter {
                    fun retryAfterMs(value: String): Long? = value.toLongOrNull()?.takeIf { it >= 0 } ?: oversized(value)
                    private fun oversized(value: String): Long? {
                        if (value.any { it !in '0'..'9' }) return null
                        return value.trimStart('0').ifEmpty { "0" }.toLongOrNull()
                    }
                    private fun httpDateMs(value: String): Long? =
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                }
            """.trimIndent(),
            "app/src/main/kotlin/Delegator.kt" to """
                val ms = RetryAfter().retryAfterMs(response.headers["Retry-After"])
            """.trimIndent(),
        )
        val MIRRORS: List<Pair<String, String>> = listOf(
            "date formatter" to "val h = headers[\"Retry-After\"]\nval at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME)\n",
            "digit guard (reject)" to "val h = headers[\"retry_after\"]\nif (h.any { it !in '0'..'9' }) return null\n",
            "digit guard (accept)" to "val retryAfter = h\nif (h.all { it in '0'..'9' }) return h.toLong()\n",
            "leading-zero normaliser" to "val retryAfterS = raw.trimStart('0').ifEmpty { \"0\" }\n",
        )
        const val COMMENTED_MIRROR = """
            // Retry-After is parsed by RetryAfter (RFC_1123_DATE_TIME lives there, not here).
            val ms = RetryAfter().retryAfterMs(h)
        """
    }
}
