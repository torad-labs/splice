// NEW: failure-handling slice 3 (2026-10-26). Renamed from MuseRefusals, which rode on the login provider: what Muse
// says differently from the Responses standard, which claude-muse's Replies reads before the standard does
// (final-design.md section 12). MUSE_CONTINUES_REASONING_ONLY stays what it was, beside these rows.
//
// Deleted with MuseRefusals: its 403 plan rule, since the standard reads a 403 as Forbidden anyway. What is kept of it
// is the reading of a plan limit at a status that is NOT 403, which the standard would read as a dead login.
package splice.provider.muse

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.wire.HttpStatus
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.ReplyFacts
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Muse's two answers of its own, and its key mint's verdicts.
 *
 * A 429's stated reset is SOFT. The 429 of Oct 25 named a reset 2 h 8 min out and carried Retry-After
 * 7,728,000 ms, and four of the fourteen requests sent over the next seven minutes came back 200, two of them 15 s and
 * 20 s after a 429 naming that reset (audit-verdicts.md:186-197). So the instant is Muse's word about its window, not
 * a closed door: the limit holds with probes, and a probe may be served well before it.
 *
 * A body naming the account's plan limit is Muse's verdict on the plan, whatever status spells it. Muse sent it as a
 * 403, which the standard already reads as Forbidden; a 401 spelling of it would read as a dead login and spend a
 * refresh on a plan a renewed login meets again, which the mint's own hold already refuses (MuseAuthProvider's
 * allowRefreshAfterFailure, kept).
 *
 * Its key mint refuses a login with a reason of its own ([mintRefusal]). Before slice 3 a mint that granted nothing
 * returned no credentials, so the turn ended as NoLogin and told the person to sign in, even where the account was
 * signed in and its subscription was inactive (MuseMintVerdict.kt:15-37).
 */
public object MuseReplies : ProviderDeviation {
    private val oauth = MuseOAuth()

    override fun http(reply: FailedReply): FailureCause.Provider? = when {
        reply.status == HttpStatus.TOO_MANY_REQUESTS ->
            FailureCause.Provider.Limited(softLift(reply), window = null, ErrorMessage.said(reply.body))
        oauth.isPlanTierRejection(reply.body) -> FailureCause.Provider.Forbidden(ErrorMessage.said(reply.body))
        else -> null
    }

    /** What a key mint that granted nothing refuses this login for, at [nowMs]; null for a mint that granted a key.
     *  Each verdict's hold is MuseMintHolds', and the cause says the same thing to the person: an inactive
     *  subscription says to renew it, a rate-limited mint is a soft limit until its hold ends, a denied mint is
     *  forbidden, and an account token the mint rejected is the rejected login it is. */
    internal fun mintRefusal(attempt: MuseMintAttempt, nowMs: Long): FailureCause.Provider? = when (attempt) {
        is MuseMintAttempt.Granted -> null
        MuseMintAttempt.InvalidAccountToken -> FailureCause.Provider.AuthRejected(LoginOwner.SPLICE)
        is MuseMintAttempt.SubscriptionRequired ->
            FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = true)
        is MuseMintAttempt.RateLimited -> FailureCause.Provider.Limited(
            Lift.At(Instant.ofEpochMilli(nowMs + heldMs(attempt.retryAfterMs)), firm = false, LiftSource.BODY),
            window = null,
        )
        is MuseMintAttempt.Denied -> FailureCause.Provider.Forbidden()
        // No verdict on the login: nothing refuses it.
        is MuseMintAttempt.Unavailable -> null
    }

    /** The reset the answer states, held softly: the body's own first, then its Retry-After, then none. */
    private fun softLift(reply: FailedReply): Lift =
        statedReset(reply)?.let { Lift.At(it, firm = false, LiftSource.BODY) }
            ?: ReplyFacts.retryAfterMs(reply)?.let { ReplyFacts.liftAfter(reply, it, LiftSource.RETRY_AFTER, false) }
            ?: Lift.Unstated

    /** The reset a 429's body states in Muse's two spellings, which the 429 of Oct 25 carried both of: `resets_at` in
     *  epoch seconds, else the instant its message names ("Your usage window resets at 2026-10-26T05:25:35Z.").
     *  Null where it states neither, or names one already past when the answer came. */
    private fun statedReset(reply: FailedReply): Instant? {
        val epoch = RESETS_AT.find(reply.body)?.groupValues?.get(1)?.toLong()?.let(Instant::ofEpochSecond)
        val stated = epoch ?: RESETS_AT_IN_WORDS.find(reply.body)?.groupValues?.get(1)?.let(::instantOrNull)
        return stated?.takeIf { it > reply.answeredAt }
    }

    /** [text] as an instant. A malformed one is Muse's slip, not splice's failure: it reads as no reset stated. */
    private fun instantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    /** `resets_at` in epoch seconds: ten digits until 2286, twelve at most, so the instant is always one Java holds. */
    private val RESETS_AT = Regex(""""resets_at"\s*:\s*(\d{9,12})(?!\d)""")

    /** The instant a message names after "resets at". */
    private val RESETS_AT_IN_WORDS = Regex("""resets?\s+at\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?)""")

    /** How long a rate-limited mint holds, exactly as [MuseMintVerdict] records it. */
    private fun heldMs(retryAfterMs: Long?): Long =
        (retryAfterMs ?: DEFAULT_RATE_HOLD_MS).coerceIn(DEFAULT_RATE_HOLD_MS, MAX_MINT_HOLD_MS)
}

/** Muse completes rounds that hold one encrypted reasoning item and nothing else
 *  (status=completed items=[reasoning(summary=0,enc=~1400)]): 418 of 3,538 turns on 2026-10-13, in runs of up to 25 in
 *  one session. The same bytes give the same empty, so the identical re-send healed 39 of 451; carrying on from that
 *  reasoning with a request for the answer changes the input. Muse accepts the shape: its mid-stream re-anchor already
 *  sends reasoning plus a commentary marker and was answered. The app sets the Responses dialect's
 *  continue_reasoning_only quirk from this, so the fact stays in this module. */
public const val MUSE_CONTINUES_REASONING_ONLY: Boolean = true

/** Muse refuses the `additional_tools` input item. On 2026-10-27, once Claude Code added three tools in a
 *  system message, Muse answered 400 "`input[8]` did not match any supported type", and input[8] was the item splice
 *  sent for them. The app sets the Responses dialect's tool_changes quirk from this, so the fact stays in this
 *  module. How a tool change reaches Muse instead is [MUSE_CALLS_ARRIVED_TOOLS_THROUGH_A_CALLER]. */
public const val MUSE_TAKES_INLINE_TOOL_CHANGES: Boolean = false

/** A tool that arrives in the middle of a Muse conversation, one ToolSearch loaded or a server added, never joins the
 *  request's tool list. Meta caches a request by prefix, in the order instructions, tools, input, so a tool that
 *  joins the list moves the whole conversation out of the cache: on 2026-11-16 each of 26 loads in one five-hour
 *  window made Meta read a conversation again at the full price, 2.69 million tokens. The list holds one tool of the
 *  head's own from the first request, the caller, the arrived tool is written into the conversation where it arrived,
 *  and the model calls it through the caller.
 *
 *  What the real head showed, each time in short conversations sent by a build of the head (2026-11-16):
 *  - Handed over as Meta's client-executed tool-search pair (a `tool_search_call` and a `tool_search_output` whose
 *    `tools` hold the tool), a tool cannot be used. From 04:04 to 04:17 CT Meta answered 200, the request that carried
 *    the pair read the conversation before it from the cache (7,412 cached tokens of the 7,514 sent before it), and
 *    the model named the tool's parameters and called it under its own name. But every call of such a tool, 10 of 10,
 *    came back with `arguments` "{}", while the listed tool beside it kept its arguments. It was the same with the
 *    pair alone, beside the head's hosted tool search, with Meta's client-executed `tool_search` tool in the tool
 *    list, with `status` on the call, and without `defer_loading` on the handed-over tool. Meta's documentation
 *    (dev.meta.ai/docs/tool-search, read that day) says a tool loaded this way "is callable like any other function".
 *  - Written as text and called through a listed tool that takes a tool's name and its arguments, a tool is called
 *    whole. Meta answered 200, the request that carried the load read 6,772 tokens from the cache of the 6,815 sent
 *    before it, and the model called the listed tool with the loaded tool's name and arguments that fit its schema,
 *    parameter names nobody would guess included. Two tools loaded by one search, one with a nested schema, came back
 *    as two whole calls, and the request after them read 7,156 of 7,278.
 *  - The head built this way, at 05:56 CT: a ToolSearch load of a tool whose parameter names nobody would guess, a
 *    call of it, its result and a second call. Every request was answered 200. The request that carried the load read
 *    7,412 tokens from the cache of the 7,473 sent before it, the one after the first call 7,668 of 7,711, and the one
 *    after the second 7,924 of 7,956. Both calls reached the client under the tool's own name, with arguments that
 *    fit its schema.
 *
 *  The app sets the Responses dialect's tool_changes quirk from this, so the fact stays in this module. */
public const val MUSE_CALLS_ARRIVED_TOOLS_THROUGH_A_CALLER: Boolean = true

/** Muse holds every answer to a json_schema text format to that schema, strict or not: Meta's structured-output docs
 *  (https://dev.meta.ai/docs/structured-output) say output is always schema-constrained, that strict false constrains
 *  it the same way, and that strict true only adds a check of the schema against a subset modeled on OpenAI's, where
 *  every object sets additionalProperties false and lists every key in required. On 2026-10-27 Muse
 *  refused 27 requests in two seconds with 400 "'additionalProperties' is required to be supplied and to be false.",
 *  each a strict format over a schema whose objects did not close. The app sets the Responses dialect's
 *  constrains_output_without_strict quirk from this, so the fact stays in this module. */
public const val MUSE_CONSTRAINS_OUTPUT_WITHOUT_STRICT: Boolean = true
