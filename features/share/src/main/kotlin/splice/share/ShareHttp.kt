// NEW: LAN share (docs/specs/lan-share.md). The one way this machine asks a sharing machine anything: a GET with a
// short connect and answer timeout, whose answer is its status and body, or why it could not be had. A node's launch
// (RemoteLaunches) and `splice remote` (ShareDirectory) both ask through it, so a server that is off is said in the
// same words by both.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.net.ConnectException
import java.net.NoRouteToHostException
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

internal class ShareHttp(private val io: CoroutineDispatcher) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(SHARE_CONNECT_MS))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(uri: URI): ShareAnswer {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(SHARE_ANSWER_MS)).GET().build()
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
     *  class. */
    internal fun why(uri: URI, failure: Throwable): String {
        val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(CAUSE_DEPTH)
        val host = hostOf(uri.toString())
        val connect = Duration.ofMillis(SHARE_CONNECT_MS).toSeconds()
        val answer = Duration.ofMillis(SHARE_ANSWER_MS).toSeconds()
        return when {
            failure is HttpConnectTimeoutException ->
                "nothing answered within $connect s; is it on, and on this network?"
            failure is HttpTimeoutException -> "it took the connection but sent no answer within $answer s"
            causes.any { it is UnresolvedAddressException || it is UnknownHostException } ->
                "$host does not resolve on this network"
            causes.any { it is NoRouteToHostException } -> "this network has no route to $host"
            failure is ConnectException ->
                "nothing is listening on ${uri.authority}; is sharing on there? (`splice share status` on it)"
            else -> SafeFailureText.render(failure)
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

    /** The host [server] names (`torad-server.lan`, `192.168.1.236`), as said to the person. */
    fun hostOf(server: String): String = URI.create(server).host?.removeSurrounding("[", "]") ?: server
}

// why: a machine on the same LAN accepts in milliseconds; three seconds covers a sleeping Wi-Fi radio waking, and a
// machine that is off is said that soon, before Claude Code starts.
private const val SHARE_CONNECT_MS = 3_000L

// why: the server answers from memory; ten seconds covers a busy machine and still says a hung one.
private const val SHARE_ANSWER_MS = 10_000L

// why: java.net.http wraps a connect failure twice over its cause; a few more links than that still end a chain that
// loops back on itself.
private const val CAUSE_DEPTH = 8
