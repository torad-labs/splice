// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ 4ca99f7 — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); headers-phase (first-byte) timeout is the
// long firstByteTimeout (a near-window prompt / compaction prefills for minutes); the body phase
// is governed by the stream watchdog, NOT here; retry on 502/503/529/429 with exponential
// backoff; a 401 triggers a SINGLE single-flight refresh that does not consume a normal attempt;
// abort() is the only lock-safe kill (Ktor: cancel the calling coroutine → channel closes).
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider

public class UpstreamClient(
    private val firstByteTimeoutMs: Long,
    private val totalTimeoutMs: Long,
    private val maxRetries: Int,
    private val client: HttpClient = defaultClient(firstByteTimeoutMs, totalTimeoutMs),
    private val backoff: suspend (attempt: Int) -> Unit = { attempt ->
        delay(BACKOFF_BASE_MS * (1L shl attempt))
    },
) {
    /**
     * Prepare an upstream POST and run [block] with the streaming response. Handles retries
     * and one single-flight 401 refresh. [applyAuth] writes the current credentials onto the
     * request builder; [auth] supplies + refreshes them. Cancelling the calling coroutine
     * aborts the in-flight body (the lock-safe kill).
     */
    public suspend fun <T> post(
        url: String,
        bodyJson: String,
        auth: RefreshableAuthProvider,
        extraHeaders: suspend (Credentials) -> Map<String, String>,
        onRetry: (String) -> Unit = {},
        block: suspend (UpstreamResponse) -> T,
    ): T {
        var refreshedOnce = false
        var lastErrText = ""
        var attempt = 0
        while (attempt < maxRetries) {
            val creds = auth.credentials() ?: throw UpstreamAuthMissing()
            when (val outcome = attemptRequest(url, bodyJson, creds, extraHeaders, block)) {
                is RetryOutcome.Done -> return outcome.value
                is RetryOutcome.Failed -> {
                    lastErrText = outcome.text
                    val plan = planRetry(outcome, attempt, refreshedOnce, auth, onRetry)
                    refreshedOnce = plan.refreshedOnce
                    when (plan.decision) {
                        RetryDecision.RETRY -> Unit // refresh succeeded — does not consume a normal attempt
                        RetryDecision.BACKOFF -> {
                            backoff(attempt)
                            attempt += 1
                        }
                        RetryDecision.GIVE_UP -> break
                    }
                }
            }
        }
        throw UpstreamFailed(lastErrText)
    }

    private suspend fun <T> attemptRequest(
        url: String,
        bodyJson: String,
        creds: Credentials,
        extraHeaders: suspend (Credentials) -> Map<String, String>,
        block: suspend (UpstreamResponse) -> T,
    ): RetryOutcome<T> {
        val allHeaders = applyAuth(creds, extraHeaders(creds)) // resolve suspend fn outside the builder
        val statement = client.preparePost(url) {
            contentType(ContentType.Application.Json)
            headers { allHeaders.forEach { (k, v) -> append(k, v) } }
            setBody(bodyJson)
        }
        return statement.execute { resp ->
            if (resp.status.isSuccess()) {
                RetryOutcome.Done(block(UpstreamResponse(resp)))
            } else {
                RetryOutcome.Failed(resp.status.value, resp.bodyText())
            }
        }
    }

    private suspend fun planRetry(
        failed: RetryOutcome.Failed,
        attempt: Int,
        refreshedOnce: Boolean,
        auth: RefreshableAuthProvider,
        onRetry: (String) -> Unit,
    ): RetryPlan {
        // a 401 spends the single refresh regardless of result; a non-401 leaves it unused
        val refreshable = failed.status == UNAUTHORIZED && !refreshedOnce
        // refresh persists the new token; the loop re-reads auth.credentials()
        if (refreshable && auth.refresh() != null) return RetryPlan(RetryDecision.RETRY, refreshedOnce = true)
        onRetry(
            "upstream ${failed.status} attempt ${attempt + 1}/$maxRetries: " +
                failed.text.take(ERR_SNIPPET),
        )
        val retryable = failed.status in RETRYABLE_STATUSES
        val decision = if (!retryable || attempt == maxRetries - 1) RetryDecision.GIVE_UP else RetryDecision.BACKOFF
        return RetryPlan(decision, refreshedOnce = refreshedOnce || refreshable)
    }

    private data class RetryPlan(val decision: RetryDecision, val refreshedOnce: Boolean)

    private fun applyAuth(creds: Credentials, extra: Map<String, String>): Map<String, String> {
        val base = when (creds) {
            // NB: the account-id header is NOT added here — the PROVIDER's extraHeaders is its sole
            // controller (CodexProvider gates it on the account_id_header quirk). Adding it here too
            // made `account_id_header = false` a no-op, since `base + extra` can add but never remove.
            is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
            is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        }
        return base + extra
    }

    private enum class RetryDecision { RETRY, BACKOFF, GIVE_UP }

    private sealed class RetryOutcome<out T> {
        data class Done<T>(val value: T) : RetryOutcome<T>()
        data class Failed(val status: Int, val text: String) : RetryOutcome<Nothing>()
    }

    public companion object {
        private const val BACKOFF_BASE_MS = 200L
        private const val UNAUTHORIZED = 401
        private const val ERR_SNIPPET = 160
        private val RETRYABLE_STATUSES = setOf(502, 503, 529, 429)

        public fun defaultClient(firstByteTimeoutMs: Long, totalTimeoutMs: Long): HttpClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    // A reasoning turn can be SILENT for minutes before the first token, and a big
                    // prefill likewise. A short connect/first-response cap here was cutting those at
                    // ~10s → "stream ended without response.completed". The stream watchdog (not this
                    // plugin) does the granular first-byte/idle enforcement, so keep these generous.
                    connectTimeoutMillis = firstByteTimeoutMs
                    requestTimeoutMillis = totalTimeoutMs
                    socketTimeoutMillis = firstByteTimeoutMs
                }
                engine {
                    // CIO's engine requestTimeout DEFAULTS to 15s — it does NOT inherit the plugin's
                    // 900s and was cutting every long turn. Match the total cap; watchdog does the rest.
                    requestTimeout = totalTimeoutMs
                    maxConnectionsCount = MAX_CONNECTIONS
                    // PARITY with Node's undici Agent (pipelining:1, keepAlive 60s). CIO's default
                    // pipelineMaxSize is 20 — up to 20 requests share one connection, so ONE cancelled
                    // SSE stream (Claude Code supersedes/cancels turns constantly) corrupts its siblings
                    // → "stream ended without response.completed" + resets. Pin to 1: a stream owns its
                    // connection; a cancel can only hurt itself.
                    endpoint.pipelineMaxSize = 1
                    endpoint.keepAliveTime = KEEP_ALIVE_MS
                    endpoint.connectAttempts = CONNECT_ATTEMPTS
                }
            }

        private const val MAX_CONNECTIONS = 256
        private const val KEEP_ALIVE_MS = 60_000L
        private const val CONNECT_ATTEMPTS = 3
    }
}

public class UpstreamAuthMissing : RuntimeException("no upstream credentials")
public class UpstreamFailed(public val body: String) : RuntimeException("upstream failed after retries")
