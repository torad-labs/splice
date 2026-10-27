// NEW: failure-handling slice 3 (2026-10-26). What a failed reply states the same way whatever its API type, read the
// one way every reader reads it: HTTP's own Retry-After header (RFC 7231, both forms), the words a context overflow is
// written in with the window they name, and the model a missing-model message names. Each API type's own fields
// (OpenAI's reset headers and codes, Anthropic's unified reset and error types) stay in its reader.
package splice.upstream.failure

import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.util.WallClock
import splice.upstream.retry.ProviderReset
import splice.upstream.retry.RetryAfter
import java.time.Instant

/** Facts a failed reply states in HTTP's own terms or in plain words, whatever its API type. */
public object ReplyFacts {
    private val retryAfter = RetryAfter()

    /** The pushback a reply's Retry-After asks for, in milliseconds from when it came; null when it names none. */
    public fun retryAfterMs(reply: FailedReply): Long? =
        retryAfter.retryAfterMs(reply.header("retry-after"), WallClock { reply.answeredAt.toEpochMilli() })

    /** When a reply's Retry-After says to come back, as the instant it names. */
    public fun retryAfterLift(reply: FailedReply): Lift.At? =
        retryAfterMs(reply)?.let { liftAfter(reply, it, LiftSource.RETRY_AFTER) }

    /** The instant [ms] after [reply] came, stated by [source]. An absurd value is held to a year, which is past any
     *  wait a turn makes and still an instant a line can say. [firm] is false where the provider's own record shows
     *  requests served before the instant it named, so the limit holds with probes (MuseReplies). */
    public fun liftAfter(reply: FailedReply, ms: Long, source: LiftSource, firm: Boolean = true): Lift.At =
        Lift.At(reply.answeredAt.plusMillis(ms.coerceIn(0L, LONGEST_LIFT_MS)), firm, source)

    /** The reset [reply]'s own body states, as the instant it names: `resets_at`, "resets at <instant>",
     *  `resets_in_seconds`, or an echoed X-RateLimit-Reset (ProviderReset, V4-47). Null where the body states none,
     *  or names one already past when the reply came. */
    public fun statedReset(reply: FailedReply): Instant? {
        val answeredAt = reply.answeredAt.toEpochMilli()
        return ProviderReset.wallMs(reply.body, answeredAt)
            ?.takeIf { it > answeredAt }
            ?.let(Instant::ofEpochMilli)
    }

    /** Whether [text] says the request outgrew the model's context, in any of the ways providers write it
     *  ("prompt is too long", "maximum context length", "context_length_exceeded", "too many tokens" and the rest,
     *  the wording the shared classifier keyed on before slice 3). */
    public fun overflows(text: String): Boolean = OVERFLOW.containsMatchIn(text)

    /** The window an overflow [message] names, in tokens: Anthropic's "N tokens > M maximum" or OpenAI's "maximum
     *  context length is M tokens"; null when it names none. */
    public fun contextLimit(message: String?): Long? {
        val text = message ?: return null
        val match = ANTHROPIC_LIMIT.find(text) ?: OPENAI_LIMIT.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toLongOrNull()
    }

    /** The model a missing-model [message] names: the first quoted name after the word model (OpenAI writes
     *  "The model `gpt-5.9` does not exist"), else the name after "model:" (Anthropic writes "model: claude-x"), else
     *  empty, which a line says as the model this request asked for. */
    public fun modelNamed(message: String?): String {
        val text = message ?: return ""
        return QUOTED_MODEL.find(text)?.groupValues?.get(1)
            ?: LABELLED_MODEL.find(text)?.groupValues?.get(1)
            ?: ""
    }
}

/** A year: the furthest a stated lift is taken at its word. */
private const val LONGEST_LIFT_MS = 31_536_000_000L

private val OVERFLOW = Regex(
    "prompt is too long|context.{0,40}window|maximum context|too many tokens|token limit|context_length|" +
        "exceeds? (?:the )?context",
    RegexOption.IGNORE_CASE,
)
private val ANTHROPIC_LIMIT = Regex("""(?i)tokens?\s*>\s*([\d,]{1,12})\s*maximum""")
private val OPENAI_LIMIT = Regex("""(?i)maximum context length is\s*([\d,]{1,12})""")
private val QUOTED_MODEL = Regex("""(?i)\bmodel\b[^`'"\n]{0,40}?[`'"]([^`'"\s]{1,120})[`'"]""")
private val LABELLED_MODEL = Regex("""(?i)\bmodel:\s*([A-Za-z0-9][\w.:/@-]{0,119})""")
