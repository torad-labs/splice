// NEW: LAN share (docs/specs/lan-share.md). The one way this machine asks a sharing machine anything: a GET with a
// short connect and answer timeout, whose answer is its status and body, or why it could not be had. A node's launch
// (RemoteLaunches) and `splice remote` (ShareDirectory) both ask through it, so a server that is off is said in the
// same words by both. A server-mode server answers only keys, so an ask carries the laptop's key there when it holds
// one. Every ask reads its answer through [read], so a machine that is off, or refuses, is said the same way everywhere.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration

/** What asking a sharing machine came to. */
internal sealed class ShareAnswer {
    data class Answered(val status: Int, val body: String) : ShareAnswer()

    /** No answer at all: [why] is the connection's own reason (refused, timed out, no route). */
    data class Unreachable(val why: String) : ShareAnswer()
}

/** What asking another machine over HTTP came to: what it answered, read, or why it could not be had, in words
 *  naming that machine. A laptop asking a sharing machine or a team's server ([ShareHttp.read]) and a server asking
 *  its identity provider (server mode's SigninIssuer) all answer this one shape. */
public sealed class Asked<out T> {
    public data class Had<T>(val value: T) : Asked<T>()

    public data class Refused(val why: String) : Asked<Nothing>()

    /** What [next] makes of what was had, or this refusal as it is. */
    public inline fun <R> then(next: (T) -> Asked<R>): Asked<R> = when (this) {
        is Refused -> this
        is Had -> next(value)
    }
}

/** What an ask makes of the body of a 200: the value it asked for, or null when the body is not one. */
internal fun interface BodyReader<T> {
    operator fun invoke(body: String): T?
}

internal class ShareHttp(private val io: CoroutineDispatcher) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(SHARE_CONNECT_MS))
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val connectSeconds = Duration.ofMillis(SHARE_CONNECT_MS).toSeconds()
    private val answerSeconds = Duration.ofMillis(SHARE_ANSWER_MS).toSeconds()

    /** [uri]'s answer; with [key], a key a server issued this laptop (server mode, F2), sent as its bearer. */
    suspend fun get(uri: URI, key: String? = null): ShareAnswer {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(SHARE_ANSWER_MS)).GET()
        if (key != null) builder.header("authorization", "Bearer $key")
        return sent(uri, builder.build())
    }

    /** [uri]'s answer to [body] posted as JSON. A server-mode sign-in's trade (F2) waits on the identity provider
     *  too, so it is given longer than a read. */
    suspend fun post(uri: URI, body: String): ShareAnswer {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(SHARE_TRADE_MS))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sent(uri, request)
    }

    private suspend fun sent(uri: URI, request: HttpRequest): ShareAnswer {
        val sent = withContext(io) {
            Cancellables.runCatchingCancellable { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        }
        return sent.fold(
            onSuccess = { ShareAnswer.Answered(it.statusCode(), it.body()) },
            onFailure = { ShareAnswer.Unreachable(why(uri, it)) },
        )
    }

    /** Why [uri] gave no answer, in words the person can act on. java.net.http says a refused port and a name that
     *  doesn't resolve both as a bare ConnectException, told apart only by its causes, and each timeout by its
     *  class. Any other refusal the OS gave (a network or a host that is down) sits at the end of the chain as a plain
     *  SocketException carrying the OS's own words, which are said as they are. */
    internal fun why(uri: URI, failure: Throwable): String = when (failure) {
        is HttpConnectTimeoutException ->
            "nothing answered within $connectSeconds s; is it on, and on this network?"
        is HttpTimeoutException -> "it took the connection but sent no answer within $answerSeconds s"
        else -> refused(uri, failure)
    }

    /** Why the connection to [uri] was refused, read from [failure]'s causes. */
    private fun refused(uri: URI, failure: Throwable): String {
        val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }
            .take(UNREACHABLE_CAUSE_DEPTH)
        val os = causes.lastOrNull { it is SocketException && it !is ConnectException }
        val host = hostOf(uri.toString())
        return when {
            causes.any { it is UnresolvedAddressException || it is UnknownHostException } ->
                "$host does not resolve on this network"
            causes.any { it is NoRouteToHostException } -> "this network has no route to $host"
            os?.message != null -> "this machine can't reach $host: ${os.message}"
            failure is ConnectException ->
                "nothing is listening on ${uri.authority}; is sharing on there? (`splice share status` on it)"
            else -> SafeFailureText.render(failure)
        }
    }

    /** [server]'s [answer] read: [reader]'s value of a 200's body; or why not, said the same for every ask, naming
     *  [server]: it can't be reached, it refused (in its own words, with [unauthorized] added to a 401), or its 200 is
     *  [not] what was asked for, as in "not with a key". */
    fun <T> read(
        server: String,
        answer: ShareAnswer,
        not: String,
        unauthorized: String = "",
        reader: BodyReader<T>,
    ): Asked<T> {
        val answered = when (answer) {
            is ShareAnswer.Unreachable -> return Asked.Refused("$server can't be reached: ${answer.why}")
            is ShareAnswer.Answered -> answer
        }
        val read = answered.takeIf { it.status == HttpStatus.OK }?.let { reader(it.body) }
        val more = unauthorized.takeIf { answered.status == HttpStatus.UNAUTHORIZED }.orEmpty()
        return when {
            answered.status != HttpStatus.OK -> Asked.Refused("$server refused: ${said(answered.body)}$more")
            read == null -> Asked.Refused("$server answered, but $not")
            else -> Asked.Had(read)
        }
    }

    /** The message of a sharing machine's error [body], without the prefix this machine's own words already say, or
     *  a short note when it sent none. */
    fun said(body: String): String {
        val error = JsonScalars.objectOrNull(json, body)?.get("error") as? JsonObject
        return JsonScalars.str(error, "message")?.removePrefix(SHARE_ERROR_PREFIX) ?: "it gave no reason"
    }

    /** [body] decoded as [T], or null when it is not one: a sharing machine's answer from another build, or not a
     *  sharing machine at all. */
    fun <T> decoded(body: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, body)
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** The host [server] names (`devbox.lan`, `192.168.1.50`), as said to the person. */
    fun hostOf(server: String): String = URI.create(server).host?.removeSurrounding("[", "]") ?: server
}

// why: a machine on the same LAN accepts in milliseconds; three seconds covers a sleeping Wi-Fi radio waking, and a
// machine that is off is said that soon, before Claude Code starts.
private const val SHARE_CONNECT_MS = 3_000L

// why: the server answers from memory; ten seconds covers a busy machine and still says a hung one.
private const val SHARE_ANSWER_MS = 10_000L

// why: a sign-in's trade is the server asking the identity provider, which it gives fifteen seconds; thirty covers that
// and the server's own part, so the laptop hears the server's reason rather than its own timeout.
private const val SHARE_TRADE_MS = 30_000L

// why: java.net.http wraps a connect failure twice over its cause; a few more links than that still end a chain that
// loops back on itself.
private const val UNREACHABLE_CAUSE_DEPTH = 8
