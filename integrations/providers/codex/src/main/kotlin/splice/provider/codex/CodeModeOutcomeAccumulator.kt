// NEW: aggregates billed upstream outcomes across hidden code-mode continuation rounds.
package splice.provider.codex

import splice.core.turn.AbsorbedRounds
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField

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

    // Local failures must not turn prior prose into an upstream re-anchor that can regenerate source.
    fun finishLocal(outcome: TurnOutcome): TurnOutcome = when (val combined = finish(outcome)) {
        is TurnOutcome.Failure -> combined.copy(partial = null)
        else -> combined
    }

    private fun mergeSuccess(prior: TurnOutcome.Success?, latest: TurnOutcome.Success): TurnOutcome.Success {
        if (prior == null) return latest
        return latest.copy(
            hasToolUse = prior.hasToolUse || latest.hasToolUse,
            incomplete = prior.incomplete || latest.incomplete,
            usage = mergeRoundUsage(prior.usage, latest.usage),
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
        usage = mergeTerminalUsage(prior.usage, latest?.usage ?: Usage(reported = emptySet())),
    )

    private fun mergeRoundUsage(prior: Usage, latest: Usage): Usage {
        val final = if (latest.localStep) prior else latest
        val observations = final.reported + prior.reported.intersect(setOf(UsageField.OUTPUT))
        val missingBills = missingBills(prior, latest, observations)
        return Usage(
            inputTokens = final.inputTokens,
            outputTokens = prior.outputTokens + latest.outputTokens,
            cachedTokens = final.cachedTokens,
            reasoningTokens = prior.reasoningTokens + latest.reasoningTokens,
            cacheWriteTokens = final.cacheWriteTokens,
            localStep = prior.localStep || latest.localStep,
            codeModeDiverged = prior.codeModeDiverged || latest.codeModeDiverged,
            recordedOutputTokens = prior.recordedOutputTokens + latest.recordedOutputTokens,
            clientContext = contextFor(prior, latest, final),
            absorbed = prior.absorbed +
                (if (latest.localStep) AbsorbedRounds() else prior.finalRound) + latest.absorbed,
            cutRounds = prior.cutRounds + latest.cutRounds + missingBills,
            reported = observations,
        )
    }

    private fun missingBills(prior: Usage, latest: Usage, observations: Set<UsageField>): Long {
        if (latest.localStep || observations.size < UsageField.entries.size) return 0
        val before = if (prior.reported.size < UsageField.entries.size) 1L else 0L
        val final = if (latest.reported.size < UsageField.entries.size) 1L else 0L
        return before + final
    }

    private fun contextFor(prior: Usage, latest: Usage, final: Usage): Usage? {
        if (latest.clientContext != null) return latest.clientContext
        if (UsageField.INPUT in final.reported) return null
        return prior.takeIf { UsageField.INPUT in it.reported } ?: prior.clientContext
    }

    private fun mergeTerminalUsage(prior: Usage, latest: Usage): Usage = mergeRoundUsage(prior, latest)
}
