// NEW: LAN share, phase 1 (docs/specs/lan-share.md). The two calls `splice remote` makes on a sharing machine:
// what it offers (GET /share/v1/info) and joining it (POST /share/v1/join). JDK HTTP, like the share relay;
// short timeouts, because a probe across a subnet asks hundreds of addresses where nothing listens.
package splice.app.cli.share

import kotlinx.serialization.json.Json
import splice.app.share.SHARE_INFO_PATH
import splice.app.share.SHARE_JOIN_PATH
import splice.app.share.ShareInfo
import splice.app.share.ShareJoin
import splice.app.share.ShareJoined
import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

internal class RemoteHttp {
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .build()

    /** What the machine at [host]:[port] offers; completes with null when nothing there answers as splice. */
    fun info(host: String, port: Int): CompletableFuture<ShareInfo?> {
        val request = HttpRequest.newBuilder(url(host, port, SHARE_INFO_PATH))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .GET()
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response -> response.takeIf { it.statusCode() == HttpStatus.OK }?.body()?.let(::parsed) }
            .exceptionally { null }
    }

    /** Joins [host] as [client]. Throws with the sharing machine's own words when it refuses. */
    fun join(host: String, port: Int, client: String): ShareJoined {
        val body = json.encodeToString(ShareJoin.serializer(), ShareJoin(client))
        val request = HttpRequest.newBuilder(url(host, port, SHARE_JOIN_PATH))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HttpStatus.OK) { "$host answered ${response.statusCode()}: ${response.body()}" }
        return json.decodeFromString(ShareJoined.serializer(), response.body())
    }

    fun url(host: String, port: Int, path: String): URI =
        URI.create("http://${if (':' in host) "[$host]" else host}:$port$path")

    private fun parsed(body: String): ShareInfo? =
        Cancellables.runCatchingCancellable { json.decodeFromString(ShareInfo.serializer(), body) }
            .fold(onSuccess = { it }, onFailure = { null })
}

private const val CONNECT_TIMEOUT_MS = 800L
private const val REQUEST_TIMEOUT_MS = 3_000L
