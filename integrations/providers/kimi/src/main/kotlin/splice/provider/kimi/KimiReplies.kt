// NEW: failure-handling slice 3 (2026-10-26). Moved from KimiRefusals, which rode on the login provider: what Kimi
// says differently from Anthropic's Messages standard, which claude-kimi's Replies reads before the standard does
// (final-design.md section 12). Everything else Kimi answers reads as every Anthropic head reads it.
package splice.provider.kimi

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LimitWindow
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.dialect.anthropic.failure.AnthropicReplies
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.ReplyFacts

/**
 * Kimi's two documented deviations from the Messages standard.
 *
 * A plan-tier answer (the subscription does not include the model, or caps it at 256k) is Kimi's verdict on the
 * account's plan, whatever status spells it. Kimi sends it as a 401, which the standard reads as a dead login and
 * renews; a renewed login meets the same plan. It reads as Forbidden in Kimi's words, so it spends no refresh and the
 * line says the plan excludes the model (KimiRefusals.kt:8-10 before slice 3).
 *
 * Kimi's 5-hour limit names its window. The 429 of Oct 23, 3:41:54 PM CT read "You've reached your 5-hour usage limit.
 * Your quota will reset when the current 5-hour window ends" with no Retry-After (audit-maps.md:1075), and none of 62
 * retried turns recovered (audit-maps.md:1078). It reads as Limited with the FIVE_HOUR window, at the lift the reply
 * states, which is none.
 */
public object KimiReplies : ProviderDeviation {
    private val oauth = KimiOAuth()

    override fun http(reply: FailedReply): FailureCause.Provider? = when {
        oauth.isPlanTierRejection(reply.body) -> FailureCause.Provider.Forbidden(ErrorMessage.said(reply.body))
        reply.status == HttpStatus.TOO_MANY_REQUESTS && FIVE_HOUR.containsMatchIn(reply.body) ->
            fiveHour(ReplyFacts.retryAfterLift(reply) ?: Lift.Unstated, ErrorMessage.said(reply.body))
        else -> null
    }

    override fun event(event: FailedEvent): FailureCause.Provider? {
        if (event.type != AnthropicReplies.ERROR_EVENT) return null
        val said = ProviderWordsFilter.of(ErrorMessage.inObject(event.payload))
        val sent = event.payload.toString()
        return when {
            oauth.isPlanTierRejection(sent) -> FailureCause.Provider.Forbidden(said)
            errorType(event.payload) == RATE_LIMIT_ERROR && FIVE_HOUR.containsMatchIn(sent) ->
                fiveHour(Lift.Unstated, said)
            else -> null
        }
    }

    private fun fiveHour(lift: Lift, said: ProviderWords?): FailureCause.Provider =
        FailureCause.Provider.Limited(lift, LimitWindow.FIVE_HOUR, said)

    private fun errorType(payload: JsonObject): String? = JsonScalars.str(payload["error"] as? JsonObject, "type")
}

private const val RATE_LIMIT_ERROR = "rate_limit_error"

/** How Kimi names its 5-hour window in a limit's message. */
private val FIVE_HOUR = Regex("""5-hour usage limit""", RegexOption.IGNORE_CASE)
