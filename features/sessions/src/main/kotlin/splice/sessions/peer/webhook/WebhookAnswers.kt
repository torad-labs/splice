// NEW: Oct 25, 2026 (docs/specs/peer-messaging.md 4.4): how the receiver's answer to one push becomes its attempt.
// A 2xx is pushed. 408, 425, 429, the 5xx statuses, a failed connection and a push that ran out of time are retried.
// What the receiver answers is kept at most 300 characters, with the URL, the secret and the bearer replaced by
// [redacted].
package splice.sessions.peer.webhook

import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import java.net.ConnectException
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

private const val PEER_WEBHOOK_TIMED_OUT = "the receiver did not answer in time"

// why: spec 4.4, the first 300 characters of the receiver's answer are kept as the detail of a failed push.
private const val PEER_WEBHOOK_DETAIL_CHARS = 300

// why: spec 4.4 retries 425 Too Early with 408, 429 and the 5xx statuses, and HttpStatus does not name it.
private const val PEER_WEBHOOK_STATUS_TOO_EARLY = 425

private const val PEER_REDACTED = "[redacted]"

internal class WebhookAnswers {
    private val transientStatuses: Set<Int> =
        setOf(HttpStatus.REQUEST_TIMEOUT, PEER_WEBHOOK_STATUS_TOO_EARLY, HttpStatus.TOO_MANY_REQUESTS)
    private val controlChars = Regex("\\p{Cntrl}")

    /** The receiver answered [response]. [hidden] holds the URL, the secret and the bearer, kept out of the detail. */
    fun reply(response: HttpResponse<String>, hidden: List<String>): WebhookPusher.Attempt {
        val status = response.statusCode()
        return if (status in HttpStatus.OK..HttpStatus.MAX_SUCCESS) {
            WebhookPusher.Attempt.Pushed(status)
        } else {
            WebhookPusher.Attempt.Failed(status, detailOf(response.body(), hidden), retryable(status))
        }
    }

    fun timedOut(): WebhookPusher.Attempt = WebhookPusher.Attempt.Failed(null, PEER_WEBHOOK_TIMED_OUT, true)

    /** The push failed before any answer arrived, and it is retried. */
    fun failed(failure: Throwable): WebhookPusher.Attempt = WebhookPusher.Attempt.Failed(null, describe(failure), true)

    private fun retryable(status: Int): Boolean =
        status in transientStatuses || status >= HttpStatus.INTERNAL_SERVER_ERROR

    private fun describe(failure: Throwable): String = when (failure) {
        is HttpTimeoutException -> PEER_WEBHOOK_TIMED_OUT
        is ConnectException -> "could not connect to the receiver"
        else -> SafeFailureText.render(failure)
    }

    private fun detailOf(body: String?, hidden: List<String>): String {
        val redacted = hidden.filter { it.isNotEmpty() }
            .fold(body.orEmpty()) { text, secret -> text.replace(secret, PEER_REDACTED) }
        return redacted.take(PEER_WEBHOOK_DETAIL_CHARS).replace(controlChars, " ").trim()
    }
}
