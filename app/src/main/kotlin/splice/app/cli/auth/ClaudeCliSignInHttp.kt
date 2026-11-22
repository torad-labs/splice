// HTTP helper for `splice login <client-head>` (signin-claude-cli), calling the local daemon's
// /api/auth/{head}/login endpoints with the daemon's management key.
package splice.app.cli.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.cli.AdminSupport
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.daemonclient.MgmtKeyRead
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// why: bounds each control-plane poll during a CLI Claude sign-in so a stuck daemon cannot hang forever.
private const val SIGNIN_HTTP_TIMEOUT_S = 15L

internal class ClaudeCliSignInHttp {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(SIGNIN_HTTP_TIMEOUT_S))
        .build()

    fun mgmtKey(): String? = when (val read = AdminSupport.readMgmtKey()) {
        is MgmtKeyRead.Present -> read.key
        is MgmtKeyRead.Absent, is MgmtKeyRead.Unreadable -> null
    }

    fun getJson(url: String, key: String): JsonObject? = sendJson(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(SIGNIN_HTTP_TIMEOUT_S))
            .header("Authorization", "Bearer $key")
            .GET()
            .build(),
    )

    fun postJson(url: String, key: String, body: String): JsonObject? = sendJson(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(SIGNIN_HTTP_TIMEOUT_S))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
    )

    private fun sendJson(req: HttpRequest): JsonObject? = Cancellables.runCatchingBestEffort {
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in HttpStatus.OK until HttpStatus.INTERNAL_SERVER_ERROR) {
            return@runCatchingBestEffort null
        }
        json.parseToJsonElement(res.body()) as? JsonObject
    }.onFailure {
        SafeFailureText.render(it)
    }.getOrNull()
}
