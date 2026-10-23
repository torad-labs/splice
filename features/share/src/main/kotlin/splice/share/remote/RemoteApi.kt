// NEW: LAN share, phase 2, milestone 3 (docs/specs/lan-share.md). The reads a daemon makes on a machine it joined,
// outside a turn: a head's roster and plan windows, and whether the machine and its heads are up. Each presents this
// machine's token there and trusts only that machine's pinned certificate (RemoteLinks hands both).
package splice.share.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.share.SHARE_HEALTH_PATH
import splice.share.ShareHealth
import splice.share.SharedHead
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** The guarded reads on one joined machine, each with this machine's token there and trusting only its pin. Blocking:
 *  a caller on a coroutine runs them on the IO dispatcher. Each throws [RemoteUnanswered] in words a person can act on. */
public class RemoteApi internal constructor(
    private val name: String,
    trust: RemoteTrust,
    private val store: RemotesStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(REMOTE_API_CONNECT_MS))
        .sslContext(trust.context)
        .build()

    /** The picker of the head whose relay is [base] (`<url>/share/v1/heads/<key>`), as that machine serves it now. */
    public fun roster(base: String): SharedHead = json.decodeFromString(SharedHead.serializer(), get("$base/roster"))

    /** That head's plan windows now, or null when it holds none. */
    public fun usage(base: String): QuotaSnapshot? = QuotaJson().decode(get("$base/usage"))

    /** Whether the machine at [url] and each head it offers is up. */
    internal fun health(url: String): ShareHealth =
        json.decodeFromString(ShareHealth.serializer(), get(url.trimEnd('/') + SHARE_HEALTH_PATH))

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(REMOTE_API_REQUEST_MS))
            .header("Authorization", "Bearer ${token()}")
            .GET()
            .build()
        val response = Cancellables.runCatchingCancellable { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { failure -> throw RemoteUnanswered("$name did not answer: ${why(failure)}", failure) }
        if (response.statusCode() != HttpStatus.OK) {
            throw RemoteUnanswered("$name answered ${response.statusCode()}: ${said(response.body())}")
        }
        return response.body()
    }

    /** Why a request found nothing to talk to, in words: the pin's own refusal first, then the connection's. */
    private fun why(failure: Throwable): String = CertificateRefusal.of(failure) ?: when (failure) {
        is ConnectException -> "nothing listens on its share port (is splice running there, with sharing on?)"
        is UnknownHostException -> "its name does not resolve on this network"
        is HttpTimeoutException -> "no answer within ${Duration.ofMillis(REMOTE_API_REQUEST_MS).toSeconds()} s"
        else -> SafeFailureText.render(failure)
    }

    /** This machine's token on [name], read per request so a re-join lands with no restart. */
    private fun token(): String =
        store.token(name) ?: throw RemoteUnanswered("no token is kept for $name; run `splice remote add` for it")

    /** The message of a share refusal (the Anthropic error shape), else the body's start. */
    private fun said(body: String): String {
        val message = Cancellables.runCatchingCancellable {
            val error = json.parseToJsonElement(body) as? JsonObject
            ((error?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        }.fold(onSuccess = { it }, onFailure = { null })
        return message ?: body.take(SAID_MAX_CHARS)
    }
}

/** A read on a joined machine that got no answer it could use, and [said], why, in words built here from the
 *  connection's failure or the machine's own refusal: the one text of it that is safe to log and show. */
public class RemoteUnanswered internal constructor(public val said: String, cause: Throwable? = null) :
    IOException(said, cause)

// why: a machine this one joined is known to be there; three seconds tells a down one from a slow one on a LAN or a
// tailnet without holding a roster refresh or a quota poll for long.
private const val REMOTE_API_CONNECT_MS = 3_000L

// why: every guarded read is a small answer built in memory on the sharing machine.
private const val REMOTE_API_REQUEST_MS = 10_000L

// why: enough of an unexpected body to recognize what answered, never a page of it.
private const val SAID_MAX_CHARS = 200
