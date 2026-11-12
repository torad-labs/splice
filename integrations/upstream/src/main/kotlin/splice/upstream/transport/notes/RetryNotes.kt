package splice.upstream.transport.notes

import splice.core.failure.FailureCause
import splice.core.util.ERR_SNIPPET
import splice.upstream.retry.MAX_ATTEMPTS_OUTSIDE_HOLDS
import splice.upstream.retry.RetryStep
import splice.upstream.transport.HeaderRedaction
import splice.upstream.transport.PostContext
import splice.upstream.transport.RetryOutcome
import splice.upstream.transport.RetryState
import splice.upstream.transport.TransportFailurePhase
import splice.upstream.transport.TransportFaultLine

/** Diagnostic wording observes the retry executor without making or counting its decisions. */
internal object RetryNotes {
    fun thrown(e: Throwable, ctx: PostContext, state: RetryState, phase: TransportFailurePhase?) {
        val n = "${state.ledger.attempts}/$MAX_ATTEMPTS_OUTSIDE_HOLDS"
        val line = TransportFaultLine.of(e, ctx.url).take(ERR_SNIPPET)
        val note = when (phase) {
            null -> "stream torn before first client frame, reissue $n: ${e::class.simpleName} $line"
            TransportFailurePhase.POST_SEND -> "transport-possible-duplicate ${e::class.simpleName} attempt $n: $line"
            TransportFailurePhase.CONNECT -> "transport ${e::class.simpleName} attempt $n: $line"
        }
        ctx.onRetry(note)
    }

    /** Provider bodies are redacted before they leave retry handling. */
    fun failed(ctx: PostContext, failed: RetryOutcome.Failed, step: RetryStep, state: RetryState) {
        val snippet = HeaderRedaction.redactText(failed.evidence.body.orEmpty()).take(ERR_SNIPPET)
        val stale = (failed.cause as? FailureCause.Provider.Rejected)?.staleReasoning == true
        val status = failed.evidence.status
        val line = when {
            step is RetryStep.Again ->
                "upstream $status attempt ${state.ledger.attempts}/$MAX_ATTEMPTS_OUTSIDE_HOLDS: $snippet"
            stale -> "upstream rejected this turn's stale reasoning, an encrypted_content error (no retry): $snippet"
            else -> "upstream $status ${failed.cause} (no retry: ${stepWords(step)}): $snippet"
        }
        ctx.onRetry(line)
    }

    private fun stepWords(step: RetryStep): String = when (step) {
        is RetryStep.Again -> "sent again"
        RetryStep.SwitchLogin -> "moved to another login"
        is RetryStep.Hold -> "held for its reset"
        is RetryStep.End -> "ended"
        RetryStep.Keep -> "kept"
    }
}
