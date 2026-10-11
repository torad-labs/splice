// NEW: aggregates billed upstream outcomes across hidden code-mode continuation rounds.
package splice.provider.codex

import splice.core.turn.ResponseShape
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage

internal class CodeModeOutcomeAccumulator {
    private var success: TurnOutcome.Success? = null

    fun absorb(value: TurnOutcome.Success) {
        success = mergeSuccess(success, value.copy(handoffs = value.handoffs.copy(customCalls = emptyList())))
    }

    fun finish(outcome: TurnOutcome): TurnOutcome {
        val prior = success ?: return outcome
        return when (outcome) {
            is TurnOutcome.Success -> mergeSuccess(prior, outcome)
            is TurnOutcome.Failure -> outcome.copy(
                partial = mergePartial(prior, outcome.partial),
                salvagedUsage = mergeTerminalUsage(prior.usage, outcome.salvagedUsage),
            )
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
            ),
        )
    }

    private fun mergePartial(
        prior: TurnOutcome.Success,
        latest: TurnOutcome.PartialRound?,
    ): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        text = RoundText(
            thinkingText = listOf(prior.text.thinkingText, latest?.text?.thinkingText.orEmpty())
                .filter(String::isNotEmpty)
                .joinToString("\n\n"),
            bodyText = prior.text.bodyText + latest?.text?.bodyText.orEmpty(),
            emittedText = prior.text.emittedText || latest?.text?.emittedText == true,
            emittedThinking = prior.text.emittedThinking || latest?.text?.emittedThinking == true,
        ),
        hasToolUse = prior.hasToolUse || latest?.hasToolUse == true,
        reasoningEnvelopes = prior.handoffs.reasoningEnvelopes + latest?.reasoningEnvelopes.orEmpty(),
        toolTearOpen = latest?.toolTearOpen == true,
        usage = mergeTerminalUsage(prior.usage, latest?.usage ?: noRequestUsage),
    )

    private fun mergeRoundUsage(prior: Usage, latest: Usage): Usage = prior.followedBy(latest)

    private fun mergeTerminalUsage(prior: Usage, latest: Usage): Usage = mergeRoundUsage(prior, latest)
}
