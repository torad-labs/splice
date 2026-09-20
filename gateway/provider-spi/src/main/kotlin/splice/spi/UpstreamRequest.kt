// PORT-OF: splice/spi/UpstreamClient.kt (RequestBody, HeaderRules, applyAuth, attemptRequest's assembly half) @ 3879c4c — invariants unchanged: bodies encoded once and NEVER gzipped, case-insensitive header dedupe, forward mode writes no auth header.
//
// ASSEMBLY of one upstream POST (HD-25): the body bytes, the headers, and the prepared statement.
// Was UpstreamClient.RequestBody / HeaderRules / applyAuth and the first half of attemptRequest;
// only the receiver moved.
//
// THE SEAM IS `statement.execute`. Status, error body and Retry-After are extracted INSIDE that
// block because the response body channel dies at its close — which is why the whole execute
// moved here rather than being split (HD-25 follow-up, 2026-08-20). The loop still owns the
// four budgets; this file owns one attempt's HTTP round-trip.
//
// Request bodies are NEVER gzipped: xAI 400s on a gzipped body ("Failed to parse the request body
// as JSON: expected value at line 1 column 1" — verified live 2026-07-18, first >=2KiB turn after
// the gzip experiment deployed); ChatGPT is unproven. The body still rides as pre-encoded UTF-8
// bytes so retries never re-encode the string.
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import splice.core.auth.Credentials
import splice.core.wire.HttpStatus

/** [json] for the RC-4 amender; [bytes] for the wire, encoded once.
 *
 *  ZSTD (CX-03, 2026-08-11): measured from codex-cli 0.145.0, which sends
 *  `content-encoding: zstd` to this exact endpoint — 73,473 bytes compressed to 27,590 (2.7x).
 *  The 2.7x is PER SSE-PATH TURN only: on a head with `websocket = true` (codex, live) the
 *  chained majority of rounds ride raw WsUpstream text frames that never reach this method, so
 *  compression covers the SSE-fallback minority. Codex-head bandwidth is dominated by WS, not
 *  this path — see the CX-03 follow-up on compressing WS frames if the wire cost is the goal.
 *
 *  PER-PROVIDER AND DEFAULT OFF, deliberately: the no-compression rule exists because xAI 400d
 *  on a GZIPPED body and broke grok live on 2026-07-18. This is zstd, not gzip, and it is
 *  proven only for ChatGPT by its own first-party client — so it is opt-in per provider and
 *  the gzip ban stands untouched. */
internal data class RequestBody(val json: String, val zstd: Boolean = false) {
    val bytes: ByteArray =
        json.toByteArray(Charsets.UTF_8).let { if (zstd) com.github.luben.zstd.Zstd.compress(it) else it }

    /** The content-encoding the bytes ride under, for the trace; null when they are the JSON itself. */
    val encoding: String? get() = if (zstd) "zstd" else null
}

/** The header half of a request: what the credential writes, and the case-insensitive merge
 *  that keeps a configured and a forwarded header from both reaching the wire. Was the
 *  companion's `authHeaders` / `dedupeCaseInsensitive`; only the receiver moved. */
internal class HeaderRules {
    /** The auth header this credential writes, if any. FORWARD MODE writes NOTHING: the head
     *  holds no credential and the caller's own auth rides in the per-turn extra headers, so
     *  emitting anything here would either overwrite it or sit beside it as a second, empty
     *  Authorization (campaign claude-head, CH-5). */
    internal fun authHeaders(creds: Credentials): Map<String, String> = when (creds) {
        is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
        is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        Credentials.ClientForwarded -> emptyMap()
    }

    /** Ktor's header builder APPENDS and HTTP header names are case-INSENSITIVE, while a Kotlin
     *  map merge is case-SENSITIVE — so a configured `anthropic-version` plus a forwarded
     *  `Anthropic-Version` would survive the merge as two entries and reach the wire twice.
     *  Last-wins on the case-folded name, which makes a forwarded header REPLACE a configured
     *  default (the intent) instead of duplicating it. Casing of the surviving entry is kept. */
    internal fun dedupeCaseInsensitive(headers: Map<String, String>): Map<String, String> =
        headers.entries
            .associateBy({ it.key.lowercase() }, { it.key to it.value })
            .values
            .toMap()
}

internal class UpstreamRequest(
    private val client: HttpClient,
    private val zstdRequestBody: Boolean,
) {
    private val headerRules = HeaderRules()
    private val retryAfter = RetryAfter()
    private val failureRules = FailureRules()

    /** Encode ONCE; retries resend the same bytes (no per-attempt string re-encode). Never gzip. */
    fun body(bodyJson: String): RequestBody = RequestBody(bodyJson, zstdRequestBody)

    /** The prepared POST, up to but NOT including `execute` — [execute] owns the block because
     *  the response body channel only lives inside it. */
    suspend fun prepare(
        url: String,
        creds: Credentials,
        extraHeaders: CredentialHeaders,
        bodyBytes: ByteArray,
        recorder: AttemptRecorder? = null,
    ): HttpStatement {
        val allHeaders = headerRules.dedupeCaseInsensitive(applyAuth(creds, extraHeaders(creds)))
        // V4-174: the recorder sees the SAME map the wire gets, after the dedupe — redacted on the
        // way in (AttemptRecorder.request), so the credential never leaves this assembly.
        recorder?.request(allHeaders)
        return client.preparePost(url) {
            contentType(ContentType.Application.Json)
            headers {
                allHeaders.forEach { (k, v) -> append(k, v) }
                if (zstdRequestBody) append("Content-Encoding", "zstd")
            }
            setBody(ByteArrayContent(bodyBytes, ContentType.Application.Json))
        }
    }

    private fun applyAuth(creds: Credentials, extra: Map<String, String>): Map<String, String> =
        headerRules.authHeaders(creds) + extra

    /** The READ-BEFORE-CLOSE half of one attempt. Status, error body and Retry-After are all
     *  extracted INSIDE the execute block because the response body channel dies at its close. */
    suspend fun <T> execute(
        ctx: PostContext,
        bodyBytes: ByteArray,
        creds: Credentials,
        onStreamStart: StreamStart,
        block: UpstreamHandler<T>,
        recorder: AttemptRecorder? = null,
    ): RetryOutcome<T> {
        val statement = prepare(ctx.url, creds, ctx.extraHeaders, bodyBytes, recorder)
        return statement.execute { resp ->
            ctx.markHeaders()
            recorder?.response(resp.status.value, resp.headers.entries().associate { (k, v) -> k to v.joinToString() })
            if (resp.status.isSuccess()) {
                onStreamStart()
                RetryOutcome.Done(block(UpstreamResponse(resp)))
            } else {
                val realStatus = resp.status.value
                val text = UpstreamResponse(resp).bodyTextLimited(MAX_ERROR_BODY_BYTES)
                recorder?.errorText(text)
                RetryOutcome.Failed(
                    quotaExhaustedStatus(realStatus, text, ctx),
                    text,
                    retryAfter.retryAfterMs(resp.headers["Retry-After"]),
                )
            }
        }
    }

    /**
     * V4-73: A QUOTA EXHAUSTION IS A RATE LIMIT, whatever status the vendor dressed it in.
     *
     *  One rewrite, at the single site that builds [RetryOutcome.Failed], is what makes every layer
     *  above agree without any of them learning a vendor's spelling: rateLimitedPlan arms the shared
     *  cooldown, the account pool marks the account unavailable and moves on, the thrown
     *  UpstreamFailed carries 429, the classifier's RATE_LIMIT-by-status fires, and the admission
     *  refusal answers with the headers the client's persistent retry reads.
     *
     *  AUTH REFRESH IS NEVER SPENT ON A REWRITTEN FAILURE, structurally rather than by a second
     *  guard: the refresh decision asks isAuthRefreshableFailure(status, body), which is true only
     *  for 401 and for 403-with-an-auth-body — and by the time it is asked, the status is 429. An
     *  UNRECOGNISED 403 keeps its status and therefore keeps V4-38's freshness rule exactly.
     *
     *  The body rides through UNCHANGED: the vendor's own sentence (grok's "run out of credits") is
     *  the honest thing to show, and it is deliberately none of the phrases that stop the client's
     *  persistent retry (pinned in the client-contract test).
     */
    private fun quotaExhaustedStatus(realStatus: Int, body: String, ctx: PostContext): Int {
        val exhausted = failureRules.isQuotaExhaustionStatus(realStatus) ||
            ctx.auth.isQuotaExhausted(realStatus, body)
        if (!exhausted) return realStatus
        ctx.onRetry(
            "upstream $realStatus is a quota exhaustion — treating it as " +
                "${HttpStatus.TOO_MANY_REQUESTS} so the retry, cooldown and pool layers see the " +
                "rate limit it is",
        )
        return HttpStatus.TOO_MANY_REQUESTS
    }
}

private const val MAX_ERROR_BODY_BYTES = 64 * 1024
