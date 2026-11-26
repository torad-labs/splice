package splice.head.transport

import splice.core.turn.ObservedTurn
import splice.upstream.transport.ProviderAnswerObserver

/** The HTTP round has a transport attempt; quota tracking does not need to know about it. */
internal class ObservedProviderAnswers(
    private val answers: ProviderAnswerObserver,
    private val turn: ObservedTurn,
) : ProviderAnswerObserver by answers {
    override fun observed(status: Int, observedAtEpochMs: Long, attempt: Long?) {
        if (attempt != null) turn.responseReceived(status, attempt, observedAtEpochMs)
        answers.observed(status, observedAtEpochMs, attempt)
    }
}
