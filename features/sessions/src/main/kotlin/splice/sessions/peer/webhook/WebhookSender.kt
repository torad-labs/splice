// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): one POST to a peer's webhook. It reads the secret and the
// bearer from their files for each push, signs the exact body bytes, follows no redirect, and gives the request 10
// s. WebhookAnswers turns what the receiver answers into the push's attempt.
package splice.sessions.peer.webhook

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.sessions.peer.PeerWebhook
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

// why: spec 4.4, a push has 10 seconds to connect and 10 seconds to be answered.
private const val PEER_WEBHOOK_TIMEOUT_SECONDS = 10L

// why: the answer is read to 1 KiB at most, which holds the 300 characters kept as the detail.
private const val PEER_WEBHOOK_BODY_BYTES = 1024

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

    private val answers = WebhookAnswers()

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
                onSuccess = { answer -> answer?.let { answers.reply(it, plan.hidden) } ?: answers.timedOut() },
                onFailure = { failure -> answers.failed(failure) },
            )
        }
    }

    private fun read(name: String): String? = try {
        Files.readString(peerDir.resolve(name)).trim().takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }
}
