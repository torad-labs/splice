package splice.head.wire

import splice.head.pipeline.FailureRenderer
import splice.upstream.RetryNotice

/** The retry's reason reaches the client as signed proxy status, never through the model's write port. */
internal class RetryProgress(
    private val log: RetryNotice,
    private val terminal: TurnTerminal,
) : RetryNotice {
    private val failures = FailureRenderer()

    override fun invoke(message: String) = log(message)

    override suspend fun retry(action: String, reason: String) {
        log.retry(action, reason)
        val explanation = failures.sentence(reason)
        terminal.ensureStarted()
        terminal.progress(object : ProgressLine {
            override val mayCommitResponse: Boolean = false
            override fun invoke(fresh: Boolean): String =
                (if (fresh) "" else "\n") + "[splice] $action. Reason: $explanation\n"
        })
    }
}
