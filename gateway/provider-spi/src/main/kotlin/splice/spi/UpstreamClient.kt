// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ 4ca99f7 — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); headers-phase (first-byte) timeout is the
// long firstByteTimeout (a near-window prompt / compaction prefills for minutes); the body phase
// is governed by the stream watchdog, NOT here; retry on 502/503/529/429 with exponential
// backoff; a 401 triggers a SINGLE single-flight refresh that does not consume a normal attempt;
// abort() is the only lock-safe kill (Ktor: cancel the calling coroutine → channel closes).
//
// Transport lessons from Grok Build / Codex CLI:
//   - shorter keepAlive than upstream idle so we don't reuse LB-killed sockets
//   - pipelineMaxSize=1 so a cancelled SSE cannot poison siblings
//   - request bodies are NEVER gzipped: xAI 400s on a gzipped body ("Failed to parse the
//     request body as JSON: expected value at line 1 column 1" — verified live 2026-07-18,
//     first >=2KiB turn after the gzip experiment deployed); ChatGPT is unproven. The body
//     still rides as pre-encoded UTF-8 bytes so retries never re-encode the string.
//   - encrypted_content decrypt 400s are NOT retried (Grok Build)
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.timedOr

public class UpstreamClient(
    private val firstByteTimeoutMs: Long,
    private val totalTimeoutMs: Long,
    private val maxRetries: Int,
    private val client: HttpClient = defaultClient(firstByteTimeoutMs, totalTimeoutMs),
    private val backoff: suspend (attempt: Int) -> Unit = { attempt ->
        delay(BACKOFF_BASE_MS * (1L shl attempt))
    },
) {
    /** The per-post collaborators threaded through every attempt (grouped: one cohesive argument). */
    private data class PostContext(
        val url: String,
        val auth: RefreshableAuthProvider,
        val extraHeaders: suspend (Credentials) -> Map<String, String>,
        val onRetry: (String) -> Unit,
        val perf: TurnPerf?,
    )

    /**
     * Prepare an upstream POST and run [block] with the streaming response. Handles retries
     * and one single-flight 401 refresh. [applyAuth] writes the current credentials onto the
     * request builder; [auth] supplies + refreshes them. Cancelling the calling coroutine
     * aborts the in-flight body (the lock-safe kill). When [perf] is wired it records the
     * auth/refresh/backoff durations, the attempt counters, and the headers-arrival mark
     * (TTFB — re-marked per attempt so the successful attempt's value wins).
     */
    public suspend fun <T> post(
        url: String,
        bodyJson: String,
        auth: RefreshableAuthProvider,
        extraHeaders: suspend (Credentials) -> Map<String, String>,
        onRetry: (String) -> Unit = {},
        perf: TurnPerf? = null,
        block: suspend (UpstreamResponse) -> T,
    ): T {
        val ctx = PostContext(url, auth, extraHeaders, onRetry, perf)
        // Encode ONCE; retries resend the same bytes (no per-attempt string re-encode). Never gzip.
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        var refreshedOnce = false
        var lastErr: RetryOutcome.Failed? = null
        var attempt = 0
        while (attempt < maxRetries) {
            val creds = perf.timedOr(PerfKeys.AUTH_MS) { auth.credentials() } ?: throw UpstreamAuthMissing()
            perf?.add(PerfKeys.ATTEMPTS, 1)
            when (val outcome = attemptRequest(ctx, bodyBytes, creds, block)) {
                is RetryOutcome.Done -> return outcome.value
                is RetryOutcome.Failed -> {
                    lastErr = outcome
                    val plan = planRetry(ctx, outcome, attempt, refreshedOnce)
                    refreshedOnce = plan.refreshedOnce
                    when (plan.decision) {
                        RetryDecision.RETRY -> Unit // refresh succeeded — does not consume a normal attempt
                        RetryDecision.BACKOFF -> {
                            perf?.add(PerfKeys.RETRIES, 1)
                            perf.timedOr(PerfKeys.BACKOFF_MS) { backoff(attempt) }
                            attempt += 1
                        }
                        RetryDecision.GIVE_UP -> return giveUp(lastErr)
                    }
                }
            }
        }
        return giveUp(lastErr)
    }

    /** The sole failure exit of the retry loop — carries the HTTP status so the classifier's
     *  429/401/5xx floors actually fire (body-text-only classification left them dead code). */
    private fun giveUp(last: RetryOutcome.Failed?): Nothing =
        throw UpstreamFailed(last?.text.orEmpty(), last?.status)

    private suspend fun <T> attemptRequest(
        ctx: PostContext,
        bodyBytes: ByteArray,
        creds: Credentials,
        block: suspend (UpstreamResponse) -> T,
    ): RetryOutcome<T> {
        val allHeaders = applyAuth(creds, ctx.extraHeaders(creds))
        val statement = client.preparePost(ctx.url) {
            contentType(ContentType.Application.Json)
            headers { allHeaders.forEach { (k, v) -> append(k, v) } }
            setBody(ByteArrayContent(bodyBytes, ContentType.Application.Json))
        }
        return statement.execute { resp ->
            ctx.perf?.mark(PerfKeys.HEADERS)
            if (resp.status.isSuccess()) {
                RetryOutcome.Done(block(UpstreamResponse(resp)))
            } else {
                RetryOutcome.Failed(resp.status.value, resp.bodyText())
            }
        }
    }

    private suspend fun planRetry(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        refreshedOnce: Boolean,
    ): RetryPlan {
        // Grok Build: encrypted_content decrypt failures must not spin retries.
        if (isEncryptedContentError(failed.status, failed.text)) {
            ctx.onRetry(
                "upstream ${failed.status} encrypted_content error (no retry): " +
                    failed.text.take(ERR_SNIPPET),
            )
            return RetryPlan(RetryDecision.GIVE_UP, refreshedOnce)
        }
        val refreshable = failed.status == UNAUTHORIZED && !refreshedOnce
        if (refreshable) ctx.perf?.add(PerfKeys.REFRESHES, 1)
        if (refreshable && ctx.perf.timedOr(PerfKeys.REFRESH_MS) { ctx.auth.refresh() } != null) {
            return RetryPlan(RetryDecision.RETRY, refreshedOnce = true)
        }
        ctx.onRetry(
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
                    connectTimeoutMillis = firstByteTimeoutMs
                    requestTimeoutMillis = totalTimeoutMs
                    socketTimeoutMillis = firstByteTimeoutMs
                }
                engine {
                    requestTimeout = totalTimeoutMs
                    maxConnectionsCount = MAX_CONNECTIONS
                    // ALL of a head's turns hit ONE upstream host, and CIO's per-route default
                    // (100) quietly caps concurrency far below maxConnectionsCount — the
                    // 1000-stream target needs the per-route limit raised in lockstep
                    // (HeadServerLoadTest pins the end-to-end ceiling).
                    endpoint.maxConnectionsPerRoute = MAX_CONNECTIONS
                    // One stream owns its connection (cancel-safe). Shorter keepAlive than many
                    // LBs (~60–100s) so we don't reuse a socket the edge already dropped
                    // (Grok Build pool_idle_timeout lesson).
                    endpoint.pipelineMaxSize = 1
                    endpoint.keepAliveTime = KEEP_ALIVE_MS
                    endpoint.connectAttempts = CONNECT_ATTEMPTS
                }
            }

        // 2x the 1000-concurrent-stream design target (one upstream connection per live turn).
        private const val MAX_CONNECTIONS = 2048

        // Grok Build uses 30s pool idle; Node used 60s. Prefer the shorter window so a
        // Cloudflare/LB silent drop is less likely to poison the next turn.
        private const val KEEP_ALIVE_MS = 30_000L
        private const val CONNECT_ATTEMPTS = 3

        private const val CLIENT_ERROR_MIN = 400
        private const val CLIENT_ERROR_MAX = 499

        /** Grok Build: 4xx + "encrypted_content" in the message → do not retry. */
        internal fun isEncryptedContentError(status: Int, body: String): Boolean =
            status in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX && body.contains("encrypted_content", ignoreCase = true)
    }
}

public class UpstreamAuthMissing : RuntimeException("no upstream credentials")

public class UpstreamFailed(
    public val body: String,
    public val status: Int? = null,
) : RuntimeException("upstream failed after retries (status=$status)")
