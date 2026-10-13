// NEW: what one upstream POST carries: its body bytes, encoded once, and its headers, composed one way for every sender.
// Split from UpstreamRequest.kt, which keeps the round-trip; see its header for why bodies are never gzipped.
package splice.upstream.transport

import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.upstream.RoundBody
import splice.upstream.sse.AttemptRecorder

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
internal data class RequestBody(val body: RoundBody, val zstd: Boolean = false) {
    /** Encoded ONCE and straight from [body]: a retry resends these bytes and never re-encodes, and a
     *  round whose text nobody reads never builds one. [RoundBody.bytes] fills one exactly sized array,
     *  so on a 916,086-byte body this keeps about one body of allocation for a tree or text, where the
     *  buffered encode it replaced kept 1,853,728 and 2,748,376 (RequestBodyAllocationTest). */
    val bytes: ByteArray = body.bytes().let { if (zstd) com.github.luben.zstd.Zstd.compress(it) else it }

    /** The RC-4 amender's and the trace's view. Materialised on the first read, which on an ordinary
     *  round never comes: UpstreamClient reads it only inside `ctx.observers.wire?.let` and on a failure. */
    val json: String get() = body.text

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
    internal fun authHeaders(creds: Credentials): Map<String, String> = CredentialKey.headers(creds, emptyMap())

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

/** Every upstream answer is asked for uncompressed, whatever a provider's headers say: it is merged last, so
 *  a configured Accept-Encoding cannot replace it. splice decodes no content encoding itself, and OkHttp
 *  inflates only an encoding it asked for, so a configured gzip would hand raw gzip to the event parser.
 *  With no Accept-Encoding of ours, OkHttp's BridgeInterceptor asks for gzip on its own and inflates the
 *  answer out of sight of the trace.
 *  Anthropic's event streams answer with Vary: Accept-Encoding, and a compressed event stream is flushed in
 *  compressed blocks, not one event at a time. On Oct 2, 12 to 27 percent of Claude-head turns an hour had
 *  their first delta past 90% of the turn, the whole thinking and tool input landing in its last tens of
 *  milliseconds. A streaming proxy wants each event as it is made; the bytes saved are not worth one held event. */
private val IDENTITY_RESPONSE = mapOf("Accept-Encoding" to "identity")

/** Every header one upstream POST carries: the credential's auth header, then the provider's and the turn's own, merged
 *  case-insensitively, and the identity answer encoding last. One composition for every sender: a turn's attempt and the
 *  Playground's one call (V4-444), so the Playground cannot send a header set a turn would not. */
public object UpstreamHeaders {
    private val rules = HeaderRules()

    public fun compose(creds: Credentials, extra: Map<String, String>): Map<String, String> =
        rules.dedupeCaseInsensitive(rules.authHeaders(creds) + extra + IDENTITY_RESPONSE)
}

/** What one attempt puts on the wire: the credential, the headers it adds, the body bytes, and the recorder that
 *  sees the same map the wire gets. Owns composing them, so a sender names what it sends and not how it is merged. */
internal class AttemptWire(
    private val creds: Credentials,
    private val extra: Map<String, String>,
    private val bodyBytes: ByteArray,
    val recorder: AttemptRecorder? = null,
) {
    /** V4-174: the recorder sees the SAME map the wire gets, after the dedupe — redacted on the way in
     *  (AttemptRecorder.request), so the credential never leaves this assembly. */
    fun headers(): Map<String, String> = UpstreamHeaders.compose(creds, extra).also { recorder?.request(it) }

    fun content(): ByteArrayContent = ByteArrayContent(bodyBytes, ContentType.Application.Json)
}
