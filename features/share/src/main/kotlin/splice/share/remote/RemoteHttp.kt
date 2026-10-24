// NEW: LAN share, phase 1 (docs/specs/lan-share.md). The two calls `splice remote` makes on a sharing machine:
// what it offers (GET /share/v1/info) and joining it (POST /share/v1/join). JDK HTTP, like the share relay;
// short timeouts, because a probe across a subnet asks hundreds of addresses where nothing listens. Since phase 2
// over TLS only, trusting through [trust]: a first contact to read what a machine offers, and the join pinned to the
// certificate that machine stated (RemoteTrust). Since round 14 of PR #419's review, what a machine says is read
// printable (TerminalText): its description, read before any pin exists, and its words when it refuses a join. Since
// round 15, a refused join throws what every refused read throws (RemoteUnanswered, an IOException), so the join's
// caller says it in those words instead of ending on an exception it does not catch.
package splice.share.remote

import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import splice.share.SHARE_INFO_PATH
import splice.share.SHARE_JOIN_PATH
import splice.share.ShareInfo
import splice.share.ShareJoin
import splice.share.ShareJoined
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

public class RemoteHttp(private val trust: RemoteTrust) : RemoteInfoProbe {
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(REMOTE_CONNECT_TIMEOUT_MS))
        .sslContext(trust.context)
        .build()

    /** What the machine at [host]:[port] offers; completes with null when nothing there answers as splice, an address
     *  no request can be made to among them, and exceptionally when its certificate is not the one [trust] pinned,
     *  which [refusal] then words. Never throws: a caller waits on what it answers. */
    override fun info(host: String, port: Int): CompletableFuture<ShareInfo?> {
        val asked = Cancellables.runCatchingCancellable {
            val request = HttpRequest.newBuilder(url(host, port, SHARE_INFO_PATH))
                .timeout(Duration.ofMillis(REMOTE_REQUEST_TIMEOUT_MS))
                .GET()
                .build()
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        }
        // A scope or a port no URI or request takes is an address where nothing answers.
        val sent = asked.getOrElse { return CompletableFuture.completedFuture(null) }
        return sent
            .handle { response, failure ->
                if (refusal(failure) != null) throw CompletionException(failure)
                response?.takeIf { it.statusCode() == HttpStatus.OK }?.body()?.let(::parsed)
            }
    }

    /** Joins [host] as [client]. Throws [RemoteUnanswered] with the sharing machine's own words when it refuses,
     *  printable and a line's length at most (RemoteSaid). */
    public fun join(host: String, port: Int, client: String): ShareJoined {
        val body = json.encodeToString(ShareJoin.serializer(), ShareJoin(client))
        val request = HttpRequest.newBuilder(url(host, port, SHARE_JOIN_PATH))
            .timeout(Duration.ofMillis(REMOTE_REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HttpStatus.OK) {
            throw RemoteUnanswered("$host answered ${response.statusCode()}: ${RemoteSaid.of(json, response.body())}")
        }
        return json.decodeFromString(ShareJoined.serializer(), response.body())
    }

    /** [trust]'s own words when [failure] is a certificate it refused, else null: what a person is told instead of a
     *  handshake error. */
    public fun refusal(failure: Throwable?): String? = CertificateRefusal.of(failure)

    /** What a remote's `[remotes]` entry keeps: scheme, host and port, with no path. */
    public fun base(host: String, port: Int): String = "https://${if (':' in host) "[$host]" else host}:$port"

    public fun url(host: String, port: Int, path: String): URI = URI.create(base(host, port) + path)

    private fun parsed(body: String): ShareInfo? =
        Cancellables.runCatchingCancellable { json.decodeFromString(ShareInfo.serializer(), body) }
            .fold(onSuccess = { it.cleaned() }, onFailure = { null })
}

// why: a machine on the same network accepts a connection in a few milliseconds, and a scan asks hundreds of
// addresses where nothing listens, some of which drop the packets rather than refuse; 800 ms gives up on those.
private const val REMOTE_CONNECT_TIMEOUT_MS = 800L

// why: /share/v1/info is a small answer built in memory and /share/v1/join one file write, so three seconds covers
// a busy sharing daemon and still ends a hung one well inside RemoteCommand's five-second wait.
private const val REMOTE_REQUEST_TIMEOUT_MS = 3_000L
