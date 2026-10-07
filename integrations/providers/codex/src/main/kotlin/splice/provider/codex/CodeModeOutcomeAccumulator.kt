// NEW: aggregates billed upstream outcomes across hidden code-mode continuation rounds.
package splice.provider.codex

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageRequest
import splice.core.turn.noRequestUsage

internal class CodeModeOutcomeAccumulator {
    private var success: TurnOutcome.Success? = null

    fun absorb(value: TurnOutcome.Success) {
        success = mergeSuccess(success, value.copy(customCalls = emptyList()))
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

    // A local ending posts no additional request and cannot turn prior prose into a source re-anchor.
    fun finishLocal(outcome: TurnOutcome): TurnOutcome = when (outcome) {
        is TurnOutcome.Success -> mergeSuccess(success, outcome, localUsage(outcome.usage))
        is TurnOutcome.Failure -> outcome.copy(partial = null, salvagedUsage = localUsage(outcome.salvagedUsage))
        is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = localUsage(outcome.salvagedUsage))
    }

    private fun localUsage(latest: Usage): Usage {
        val local = latest.copy(history = latest.history.copy(request = UsageRequest.NONE))
        return success?.usage?.followedBy(local) ?: local
    }

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
            thinkingText = listOf(prior.thinkingText, latest.thinkingText)
                .filter(String::isNotEmpty)
                .joinToString("\n\n"),
            bodyText = prior.bodyText + latest.bodyText,
            emittedText = prior.emittedText || latest.emittedText,
            emittedThinking = prior.emittedThinking || latest.emittedThinking,
            messageClosed = prior.messageClosed || latest.messageClosed,
            outputShape = listOf(prior.outputShape, latest.outputShape)
                .filter(String::isNotEmpty)
                .joinToString("; "),
            reasoningEnvelopes = prior.reasoningEnvelopes + latest.reasoningEnvelopes,
            toolSearches = prior.toolSearches + latest.toolSearches,
        )
    }

    private fun mergePartial(
        prior: TurnOutcome.Success,
        latest: TurnOutcome.PartialRound?,
    ): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        thinkingText = listOf(prior.thinkingText, latest?.thinkingText.orEmpty())
            .filter(String::isNotEmpty)
            .joinToString("\n\n"),
        bodyText = prior.bodyText + latest?.bodyText.orEmpty(),
        emittedText = prior.emittedText || latest?.emittedText == true,
        emittedThinking = prior.emittedThinking || latest?.emittedThinking == true,
        hasToolUse = prior.hasToolUse || latest?.hasToolUse == true,
        reasoningEnvelopes = prior.reasoningEnvelopes + latest?.reasoningEnvelopes.orEmpty(),
        toolTearOpen = latest?.toolTearOpen == true,
        usage = mergeTerminalUsage(prior.usage, latest?.usage ?: noRequestUsage),
    )

    private fun mergeRoundUsage(prior: Usage, latest: Usage): Usage = prior.followedBy(latest)

    private fun mergeTerminalUsage(prior: Usage, latest: Usage): Usage = mergeRoundUsage(prior, latest)
}
