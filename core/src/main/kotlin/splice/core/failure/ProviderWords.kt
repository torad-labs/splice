// NEW: failure-handling slice 1 (2026-10-26). The provider's own words about a failure, once filtered. Only core
// constructs one, and ProviderWordsFilter is the one author (slice 2): a line quotes a provider only through it, so
// nothing a person cannot use (a URL, a host, an ISO instant, an id, a key, a field path, advice to retry) and none of
// the phrases that end a persistent Claude Code's wait ever reaches a line from a provider's body.
package splice.core.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Words the provider said about a failure, cut to what a person may read. */
@JvmInline
public value class ProviderWords internal constructor(public val text: String)

/** Builds every [ProviderWords] (final-design.md section 6). The caller hands it the provider's own sentence, the
 *  message field of its error body where it has one; a body that is still JSON as a whole yields nothing. */
public object ProviderWordsFilter {
    /** The longest words a line may quote, period included. */
    private const val MAX_CHARS = 160

    /** The most of a provider's text the rules below read. A quote is cut from its first readable sentence, and the
     *  host and key rules backtrack across a run with no break, so a failed body of tens of kilobytes (a gateway
     *  page, a run of one character) held a turn's ending for minutes when read whole. The sentence the read stops
     *  inside is left out, since what is read of it is not what the provider said. */
    private const val MAX_READ = 2_000

    private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+""")
    private val EM_DASH_CLAUSE = Regex("""\s*\x{2014}\s*""")

    /** A colon inside a sentence is said as a clause break, so the line quoting it keeps its own one colon. */
    private val COLON_CLAUSE = Regex("""\s*:\s+""")
    private val SPACES = Regex("""\s+""")

    /** A sentence holding one of these goes whole: what it says is about a place, a time or a client's wait. */
    private val CUT_WHOLE: List<Regex> = listOf(
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S*|\bwww\.\S+"""),
        // A host: dotted labels that each hold a letter, ending in a name. A field path's number labels do not.
        Regex("""(?i)\b(?:(?=[a-z0-9-]*[a-z])[a-z0-9-]+\.)+[a-z]{2,24}\b"""),
        Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""),
        Regex("""(?i)\blocalhost\b|\b[a-z0-9-]*[a-z][a-z0-9-]*:\d{2,5}\b"""),
        Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}"""),
        Regex("""(?i)\bupstream\b"""),
        Regex("""(?i)\battempt"""),
        // Advice to retry, try again or wait: what the client does next is splice's line to say.
        Regex("""(?i)\b(?:retr(?:y|ies|ying|ied)|try again|wait(?:s|ing)?)\b"""),
    )

    /** Claude Code's stop phrases (PlanLimitRefusalZoneTest): words that end a persistent client's wait. */
    private val STOP_PHRASES = listOf(
        "service_spend_limit_reached",
        "exceeded_limit",
        "credits_required",
        "usage credits are required",
        "extra usage is required",
        "out_of_credits",
    )

    /** Dropped from a sentence that stays: ids, keys and paths, which a person cannot use and must not see. */
    private val DROP: List<Regex> = listOf(
        Regex("""(?i)\s*\([^()]*\b(?:[a-z]+[_-])?id\b[^()]*\)"""),
        Regex("""(?i)\brequest[ _-]?id\b\s*[:=]?\s*\S+"""),
        Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""),
        Regex("""[A-Za-z0-9_-]*\*{4,}[A-Za-z0-9_-]*"""),
        Regex("""[A-Za-z0-9_-]*[A-Za-z0-9]{24,}[A-Za-z0-9_-]*"""),
        Regex("""\b[A-Za-z_]\w*(?:\.\w+){2,}\b|\b[A-Za-z_]\w*(?:\.\w+)*\.\d+(?:\.\w+)*\b"""),
    )

    /** What is left of a dump after the drops: braces, brackets, assignments, markup. */
    private val DUMP = Regex("""[{}\[\]=<>]""")

    private val SPACE_BEFORE_MARK = Regex("""\s+([,.;:!?])""")
    private val EMPTY_PARENS = Regex("""\(\s*\)""")

    /** The words a person may read out of [raw], or null when none are left. */
    public fun of(raw: String?): ProviderWords? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && json(it) == null } ?: return null
        val spoken = SPACES.replace(EM_DASH_CLAUSE.replace(text.take(MAX_READ), ", "), " ")
        val sentences = spoken.split(SENTENCE_BREAK).let { if (text.length > MAX_READ) it.dropLast(1) else it }
        val kept = sentences.firstNotNullOfOrNull(::kept) ?: return null
        return ProviderWords(fitted(kept))
    }

    /** [text] read as JSON, or null when it is not JSON as a whole. */
    private fun json(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** [sentence] with its ids, keys and paths dropped, or null when it must go whole or nothing readable is left. */
    private fun kept(sentence: String): String? {
        val goes = CUT_WHOLE.any { it.containsMatchIn(sentence) } ||
            STOP_PHRASES.any { sentence.contains(it, ignoreCase = true) }
        if (goes) return null
        val dropped = DROP.fold(sentence) { text, rule -> rule.replace(text, " ") }
        if (DUMP.containsMatchIn(dropped)) return null
        val tidy = SPACE_BEFORE_MARK.replace(EMPTY_PARENS.replace(SPACES.replace(dropped, " "), ""), "$1").trim()
        val body = COLON_CLAUSE.replace(tidy.trimEnd(':', ' '), ", ").trimEnd('.', '!', '?', ',', ';', ':', '-', ' ')
        return body.takeIf { it.any(Char::isLetter) }
    }

    /** [body] as one sentence of at most [MAX_CHARS], cut at a word boundary, ending with a period. */
    private fun fitted(body: String): String {
        if (body.length < MAX_CHARS) return "$body."
        val cut = body.lastIndexOf(' ', MAX_CHARS - 1).takeIf { it > 0 } ?: (MAX_CHARS - 1)
        return body.substring(0, cut).trimEnd(',', ';', ':', '-', ' ') + "."
    }
}
