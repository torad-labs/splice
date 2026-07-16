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
import io.ktor.client.statement.HttpStatement
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
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
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
            val allHeaders = applyAuth(creds, extraHeaders(creds)) // resolve suspend fn outside the builder
            val statement = client.preparePost(url) {
                contentType(ContentType.Application.Json)
                headers { allHeaders.forEach { (k, v) -> append(k, v) } }
                setBody(bodyJson)
            }
            val outcome = statement.execute { resp ->
                if (resp.status.isSuccess()) {
                    RetryOutcome.Done(block(UpstreamResponse(resp)))
                } else {
                    val text = resp.bodyText()
                    lastErrText = text
                    RetryOutcome.Failed(resp.status.value, text)
                }
            }
            when (outcome) {
                is RetryOutcome.Done<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    return outcome.value as T
                }
                is RetryOutcome.Failed -> {
                    if (outcome.status == UNAUTHORIZED && !refreshedOnce) {
                        refreshedOnce = true
                        // refresh persists the new token; the loop re-reads auth.credentials()
                        if (auth.refresh() != null) continue // does not consume a normal attempt
                    }
                    val retryable = outcome.status in RETRYABLE_STATUSES
                    onRetry("upstream ${outcome.status} attempt ${attempt + 1}/$maxRetries: ${outcome.text.take(160)}")
                    if (!retryable || attempt == maxRetries - 1) break
                    backoff(attempt)
                }
            }
            attempt += 1
        }
        throw UpstreamFailed(lastErrText)
    }

    private fun applyAuth(creds: Credentials, extra: Map<String, String>): Map<String, String> {
        val base = when (creds) {
            is Credentials.Bearer -> buildMap {
                put("Authorization", "Bearer ${creds.token}")
                creds.accountId?.let { put("ChatGPT-Account-ID", it) }
            }
            is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        }
        return base + extra
    }

    private sealed interface RetryOutcome {
        data class Done<T>(val value: T) : RetryOutcome
        data class Failed(val status: Int, val text: String) : RetryOutcome
    }

    public companion object {
        private const val BACKOFF_BASE_MS = 200L
        private const val UNAUTHORIZED = 401
        private val RETRYABLE_STATUSES = setOf(502, 503, 529, 429)

        public fun defaultClient(firstByteTimeoutMs: Long, totalTimeoutMs: Long): HttpClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    // headers-phase = first byte; the stream watchdog governs the body phase
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = totalTimeoutMs
                    socketTimeoutMillis = firstByteTimeoutMs
                }
                engine { maxConnectionsCount = MAX_CONNECTIONS }
            }

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MAX_CONNECTIONS = 256
    }
}

public class UpstreamAuthMissing : RuntimeException("no upstream credentials")
public class UpstreamFailed(public val body: String) : RuntimeException("upstream failed after retries")
