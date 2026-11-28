// NEW: aggregates billed upstream outcomes across hidden code-mode continuation rounds.
package splice.provider.codex.stream

import kotlinx.serialization.json.JsonObject
import splice.core.turn.NativeDeliverySnapshot
import splice.core.turn.ResponseShape
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.provider.codex.history.CodeModeContinuityCapture

internal class CodeModeOutcomeAccumulator {
    private var success: TurnOutcome.Success? = null

    fun absorb(value: TurnOutcome.Success) {
        success = mergeSuccess(success, value.copy(handoffs = value.handoffs.copy(customCalls = emptyList())))
    }

    fun finish(outcome: TurnOutcome): TurnOutcome {
        val prior = success ?: return outcome
        return when (outcome) {
            is TurnOutcome.Success -> mergeSuccess(prior, outcome)
            is TurnOutcome.Failure -> {
                val usage = mergeTerminalUsage(prior.usage, terminalUsage(outcome))
                val partial = outcome.partial?.takeUnless { outcome.cause.permanent }
                outcome.copy(
                    partial = partial?.let { mergePartial(prior, it, usage) },
                    salvagedUsage = usage,
                )
            }
            is TurnOutcome.ClientAbandoned -> outcome.copy(
                salvagedUsage = mergeTerminalUsage(prior.usage, outcome.salvagedUsage),
            )
        }
    }

    // Suppress the source re-anchor, but keep the request ownership declared by the ending's producer.
    fun finishLocal(outcome: TurnOutcome): TurnOutcome = when (outcome) {
        is TurnOutcome.Success -> mergeSuccess(success, outcome)
        is TurnOutcome.Failure -> outcome.copy(partial = null, salvagedUsage = accumulatedUsage(outcome.salvagedUsage))
        is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = accumulatedUsage(outcome.salvagedUsage))
    }

    private fun accumulatedUsage(latest: Usage): Usage = success?.usage?.followedBy(latest) ?: latest

    private fun mergeSuccess(
        prior: TurnOutcome.Success?,
        latest: TurnOutcome.Success,
        usage: Usage = prior?.let { mergeRoundUsage(it.usage, latest.usage) } ?: latest.usage,
    ): TurnOutcome.Success {
        if (prior == null) return latest.copy(usage = usage)
        return latest.copy(
            hasToolUse = prior.hasToolUse || latest.hasToolUse,
            incomplete = prior.incomplete || latest.incomplete,
            usage = usage,
            text = RoundText(
                thinkingText = listOf(prior.text.thinkingText, latest.text.thinkingText)
                    .filter(String::isNotEmpty)
                    .joinToString("\n\n"),
                bodyText = prior.text.bodyText + latest.text.bodyText,
                emittedText = prior.text.emittedText || latest.text.emittedText,
                emittedThinking = prior.text.emittedThinking || latest.text.emittedThinking,
            ),
            shape = ResponseShape(
                messageClosed = prior.shape.messageClosed || latest.shape.messageClosed,
                outputShape = listOf(prior.shape.outputShape, latest.shape.outputShape)
                    .filter(String::isNotEmpty)
                    .joinToString("; "),
            ),
            handoffs = latest.handoffs.copy(
                reasoningEnvelopes = prior.handoffs.reasoningEnvelopes + latest.handoffs.reasoningEnvelopes,
                toolSearches = prior.handoffs.toolSearches + latest.handoffs.toolSearches,
                nativePrefix = CodeModeContinuityCapture.followedBy(prior, latest),
            ),
        )
    }

    private fun terminalUsage(failure: TurnOutcome.Failure): Usage =
        failure.partial?.usage?.takeIf { it.reported.isNotEmpty() } ?: failure.salvagedUsage

    private fun mergePartial(
        prior: TurnOutcome.Success,
        latest: TurnOutcome.PartialRound,
        usage: Usage,
    ): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        text = RoundText(
            thinkingText = listOf(prior.text.thinkingText, latest.text.thinkingText)
                .filter(String::isNotEmpty)
                .joinToString("\n\n"),
            bodyText = prior.text.bodyText + latest.text.bodyText,
            emittedText = prior.text.emittedText || latest.text.emittedText,
            emittedThinking = prior.text.emittedThinking || latest.text.emittedThinking,
        ),
        hasToolUse = prior.hasToolUse || latest.hasToolUse,
        reasoningEnvelopes = prior.handoffs.reasoningEnvelopes + latest.reasoningEnvelopes,
        toolTearOpen = latest.toolTearOpen,
        usage = usage,
        nativePrefix = CodeModeContinuityCapture.followedBy(prior, latest),
        nativeDelivery = NativeDeliverySnapshot(
            afterExec = latest.nativeAfterExec,
            deliveredPrefix = deliveredPrefix(prior, latest),
        ),
    )

    /** A prior exec or prose needs its own canonical history; never guess its delivery order here. */
    private fun deliveredPrefix(prior: TurnOutcome.Success, latest: TurnOutcome.PartialRound): List<JsonObject>? {
        if (prior.text.bodyText.isNotEmpty() || prior.handoffs.nativeAfterExec != null) return null
        val prefix = CodeModeContinuityCapture.successItems(prior)
        if (prefix.any { JsonScalars.str(it, "type") != "reasoning" }) return null
        return latest.deliveredNativePrefix?.let { prefix + it }
    }

    private fun mergeRoundUsage(prior: Usage, latest: Usage): Usage = prior.followedBy(latest)

    private fun mergeTerminalUsage(prior: Usage, latest: Usage): Usage = mergeRoundUsage(prior, latest)
}
