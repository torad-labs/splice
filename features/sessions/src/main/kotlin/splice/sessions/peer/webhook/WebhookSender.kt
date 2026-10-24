// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): one POST to a peer's webhook. It reads the secret and the
// bearer from their files for each push, signs the exact body bytes, follows no redirect, and gives the request 10
// s. What the receiver answers is kept at most 300 characters, with the URL, the secret and the bearer replaced by
// [redacted].
package splice.sessions.peer.webhook

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.sessions.peer.PeerWebhook
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

// why: spec 4.4, a push has 10 seconds to connect and 10 seconds to be answered.
private const val PEER_WEBHOOK_TIMEOUT_SECONDS = 10L

// why: the answer is read to 1 KiB at most, which holds the 300 characters kept as the detail.
private const val PEER_WEBHOOK_BODY_BYTES = 1024

private const val PEER_WEBHOOK_TIMED_OUT = "the receiver did not answer in time"

// why: spec 4.4, the first 300 characters of the receiver's answer are kept as the detail of a failed push.
private const val PEER_WEBHOOK_DETAIL_CHARS = 300

// why: spec 4.4 retries 425 Too Early with 408, 429 and the 5xx statuses, and HttpStatus does not name it.
private const val PEER_WEBHOOK_STATUS_TOO_EARLY = 425

private const val PEER_REDACTED = "[redacted]"

internal class WebhookSender(
    private val webhook: PeerWebhook,
    private val peerName: String,
    private val peerDir: Path,
    private val wallClock: WallClock,
) {
    private data class Credentials(val secret: String, val bearer: String)

    private sealed class Plan {
        data class Ready(val request: HttpRequest, val hidden: List<String>) : Plan()

        data class Refused(val detail: String) : Plan()
    }

    private val transientStatuses: Set<Int> =
        setOf(HttpStatus.REQUEST_TIMEOUT, PEER_WEBHOOK_STATUS_TOO_EARLY, HttpStatus.TOO_MANY_REQUESTS)
    private val controlChars = Regex("\\p{Cntrl}")

    /** One push of [body], which carries journal record [seq], and how the receiver answered. */
    suspend fun send(seq: Long, body: ByteArray): WebhookPusher.Attempt = when (val plan = prepare(seq, body)) {
        is Plan.Refused -> WebhookPusher.Attempt.Failed(null, plan.detail, false)
        is Plan.Ready -> exchange(plan)
    }

    private fun prepare(seq: Long, body: ByteArray): Plan {
        val uri = WebhookUrl.allowed(webhook.url)
        val secret = read(webhook.secretFile)
        val bearerFile = webhook.bearerFile
        val bearer = if (bearerFile == null) "" else read(bearerFile)
        return when {
            uri == null -> Plan.Refused("the webhook URL is not allowed")
            secret == null -> Plan.Refused("the webhook secret file cannot be read")
            bearer == null -> Plan.Refused("the webhook bearer file cannot be read")
            else -> request(uri, Credentials(secret, bearer), seq, body)
        }
    }

    private fun request(uri: URI, credentials: Credentials, seq: Long, body: ByteArray): Plan =
        Cancellables.runCatchingCancellable { build(uri, credentials, seq, body) }.fold(
            onSuccess = { Plan.Ready(it, listOf(webhook.url, credentials.secret, credentials.bearer)) },
            onFailure = { Plan.Refused("the webhook request could not be built from the URL and the bearer") },
        )

    private fun build(uri: URI, credentials: Credentials, seq: Long, body: ByteArray): HttpRequest {
        val timestamp = TimeUnit.MILLISECONDS.toSeconds(wallClock())
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(PEER_WEBHOOK_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("User-Agent", "splice-peer/$GATEWAY_VERSION")
            .header("X-Splice-Peer", peerName)
            .header("X-Splice-Delivery", seq.toString())
            .header("X-Splice-Timestamp", timestamp.toString())
            .header("X-Splice-Signature", WebhookSignature.header(credentials.secret, timestamp, body))
        if (credentials.bearer.isNotEmpty()) builder.header("Authorization", "Bearer ${credentials.bearer}")
        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
    }

    private suspend fun exchange(plan: Plan.Ready): WebhookPusher.Attempt {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(PEER_WEBHOOK_TIMEOUT_SECONDS))
            .build()
        val handler = BoundedBody(PEER_WEBHOOK_BODY_BYTES)
        val patience = TimeUnit.SECONDS.toMillis(PEER_WEBHOOK_TIMEOUT_SECONDS)
        return client.use { open ->
            Cancellables.runCatchingCancellable {
                withTimeoutOrNull(patience) { open.sendAsync(plan.request, handler).await() }
            }.fold(
                onSuccess = { answer -> answer?.let { reply(it, plan.hidden) } ?: timedOut() },
                onFailure = { failure -> WebhookPusher.Attempt.Failed(null, describe(failure), true) },
            )
        }
    }

    private fun timedOut(): WebhookPusher.Attempt = WebhookPusher.Attempt.Failed(null, PEER_WEBHOOK_TIMED_OUT, true)

    private fun reply(response: HttpResponse<String>, hidden: List<String>): WebhookPusher.Attempt {
        val status = response.statusCode()
        return if (status in HttpStatus.OK..HttpStatus.MAX_SUCCESS) {
            WebhookPusher.Attempt.Pushed(status)
        } else {
            WebhookPusher.Attempt.Failed(status, detailOf(response.body(), hidden), retryable(status))
        }
    }

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

    private fun read(name: String): String? = try {
        Files.readString(peerDir.resolve(name)).trim().takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }
}
