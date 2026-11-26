// NEW: failure-handling slice 3 (2026-10-26). What a failed reply states the same way whatever its API type, read the
// one way every reader reads it: HTTP's own Retry-After header (RFC 7231, both forms), the words a context overflow is
// written in with the window they name, and the model a missing-model message names. Each API type's own fields
// (OpenAI's reset headers and codes, Anthropic's unified reset and error types) stay in its reader.
package splice.upstream.failure

import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.util.WallClock
import splice.upstream.retry.RetryAfter

/** Facts a failed reply states in HTTP's own terms or in plain words, whatever its API type. */
public object ReplyFacts {
    /** HTTP's own Retry-After header, by the lowercase name every reader looks it up by. */
    public const val RETRY_AFTER_HEADER: String = "retry-after"

    private val retryAfter = RetryAfter()

    /** The pushback a reply's Retry-After asks for, in milliseconds from when it came; null when it names none.
     *  RFC 7231 strict seconds and HTTP-dates go through [RetryAfter] first; a decimal seconds value (e.g. `6.2`)
     *  is accepted as a fallback so overload/limit pushback rounds up to whole seconds cleanly. */
    public fun retryAfterMs(reply: FailedReply): Long? {
        val raw = reply.header(RETRY_AFTER_HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return retryAfter.retryAfterMs(raw, WallClock { reply.answeredAt.toEpochMilli() }) ?: decimalSecondsMs(raw)
    }

    private fun decimalSecondsMs(raw: String): Long? {
        if (!DECIMAL_SECONDS.matches(raw)) return null
        val seconds = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val ms = kotlin.math.ceil(seconds * RETRY_MS_PER_SECOND)
        return if (ms >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else ms.toLong()
    }

    /** When a reply's Retry-After says to come back, as the instant it names. */
    public fun retryAfterLift(reply: FailedReply): Lift.At? =
        retryAfterMs(reply)?.let { liftAfter(reply, it, LiftSource.RETRY_AFTER) }

    /** The instant [ms] after [reply] came, stated by [source]. An absurd value is held to a year, which is past any
     *  wait a turn makes and still an instant a line can say. [firm] is false where the provider's own record shows
     *  requests served before the instant it named, so the limit holds with probes (MuseReplies). */
    public fun liftAfter(reply: FailedReply, ms: Long, source: LiftSource, firm: Boolean = true): Lift.At =
        Lift.At(reply.answeredAt.plusMillis(ms.coerceIn(0L, LONGEST_LIFT_MS)), firm, source)

    /** Whether [text] says the request outgrew the model's context, in any of the ways providers write it
     *  ("prompt is too long", "maximum context length", "context_length_exceeded", "too many tokens" and the rest,
     *  the wording the shared classifier keyed on before slice 3). */
    public fun overflows(text: String): Boolean = OVERFLOW.containsMatchIn(text)

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

// why: milliseconds per second when converting fractional Retry-After seconds to milliseconds.
private const val RETRY_MS_PER_SECOND = 1_000.0
private val DECIMAL_SECONDS = Regex("""^\d+\.\d+$""")

private val OVERFLOW = Regex(
    "prompt is too long|context.{0,40}window|maximum context|too many tokens|token limit|context_length|" +
        "exceeds? (?:the )?context",
    RegexOption.IGNORE_CASE,
)
private val QUOTED_MODEL = Regex("""(?i)\bmodel\b[^`'"\n]{0,40}?[`'"]([^`'"\s]{1,120})[`'"]""")
private val LABELLED_MODEL = Regex("""(?i)\bmodel:\s*([A-Za-z0-9][\w.:/@-]{0,119})""")
