// NEW: LAN share (docs/specs/lan-share.md). A request from another machine's Claude Code, handed to this machine's head
// on loopback (a turn) or to its control plane (a statusline), with this machine's turn key in place of whatever the
// other machine sent, and the answer streamed back as it arrives. A head that takes the caller's own login gets the
// node's word instead of the turn key, and its pool sends a login it holds. The head runs the turn as for a session here:
// its admission, account selection, cooldowns and honest failures all apply unchanged, and no credential of any
// provider leaves this process. The answer goes back as HeadAnswers gives it: an event stream as it arrives, any other
// answer whole. The body is read under the head's own limits first (its maxRequestBytes
// and read timeout), so a body the head would refuse is refused here in the head's words, before this listener holds
// more of it than the head would. A head that does not answer is said as that, in this machine's name: a 503 Claude
// Code prints, never a 529 it retries in silence as "overloaded".
//
// Server mode (docs/specs/server-mode.md) relays a key holder's turn through this same class, under its own voice.
// Each blocking step (the send, each read of the answer) is interruptible, so a caller that cancels the relay, as the
// server listener does when a turn's client hangs up, ends it at once and hangs up on the head, which then sees its own
// client leave: no head goes on computing an answer nobody is waiting for. A turn the server listener asks to be kept
// alive, and that asks for a stream, is not left waiting on a silent head: after a second its stream is opened to the
// client and kept alive until the head answers (HeadAnswers.early).
package splice.share

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.TurnKey
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** Whose words a relay refuses in, and the name its log lines carry. */
public data class RelaySpeaker(val errors: RefusalVoice, val tag: String)

/** One request for a head: its [path] under the head (`/v1/messages?beta=true`), what the listener that relays it
 *  says about it in headers of its own ([named]), such as the upstream the caller's person prefers, and how often its
 *  client is sent a [keepalive] while a streamed answer is silent (null: never, and the client waits on the head). */
public data class HeadTurn(
    val head: SharedHead,
    val path: String,
    val named: Map<String, String> = emptyMap(),
    val keepalive: Duration? = null,
)

public class ShareRelay(
    private val turnKey: TurnKey,
    private val controlPort: Int,
    private val machine: String,
    /** Blocking JDK I/O runs here: the send, and each read of the answer. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val speaker: RelaySpeaker = RelaySpeaker(ShareErrors.voice, "share"),
) {
    private val errors get() = speaker.errors
    private val answers = HeadAnswers(io, speaker.errors)
    private val streamed = StreamedBodies()

    /** What a statusline's body passes before it is relayed: the listener pins the session it names to the login the
     *  address chose, so the line shows that login from its first draw, before the session's first turn. */
    internal fun interface StatuslineAdmission {
        /** Why [body]'s statusline may not be drawn, or null once it may. */
        fun refusal(body: ByteArray): String?
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(java.time.Duration.ofMillis(RELAY_CONNECT_TIMEOUT_MS))
        .build()

    /** [turn] on its head, under the head's own body limits. */
    public suspend fun turn(call: ApplicationCall, turn: HeadTurn) {
        val head = turn.head
        val body = body(call, head.maxRequestBytes(), head.requestReadTimeoutMs()) ?: return
        val silent = "${head.command} on $machine is not answering"
        // A head that takes the caller's own login refuses this machine's turn key as a credential to send upstream.
        // It gets the node's word instead, which opens nothing: its pool sends a login it holds in that word's place.
        val bearer = if (head.forwardsClientAuth) SHARE_NODE_TOKEN else turnKey.get()
        // PR #247 review, Oct 29: that head serves its logins only to a caller proven splice's, and this relay is.
        val proof = if (head.forwardsClientAuth) mapOf(LAUNCH_PROOF_HEADER to turnKey.get()) else emptyMap()
        val signed = mapOf(HttpHeaders.Authorization to "Bearer $bearer") + proof + turn.named
        val request = request(call, URI.create("http://127.0.0.1:${head.port}${turn.path}"), body, signed)
        forward(call, request, ClientWait(silent, turn.keepalive?.takeIf { streamed.asks(body) }))
    }

    /** The control plane's statusline for [head], fed the session's JSON as Claude Code feeds its statusline, once
     *  [admission] has read the body: a refusal it returns is answered instead. */
    internal suspend fun statusline(call: ApplicationCall, head: SharedHead, admission: StatuslineAdmission) {
        val body = body(call, STATUSLINE_BODY_MAX_BYTES, STATUSLINE_READ_MS) ?: return
        val refusal = withContext(io) { admission.refusal(body) }
        if (refusal != null) return errors.refuse(call, HttpStatus.BAD_REQUEST, refusal)
        val silent = "$machine's statusline for ${head.command} is not answering"
        val signed = mapOf(HttpHeaders.Authorization to "Bearer ${turnKey.get()}")
        val target = URI.create("http://127.0.0.1:$controlPort/statusline/${head.key}")
        forward(call, request(call, target, body, signed), ClientWait(silent))
    }

    /** [body] for [target], with the [signed] headers in place of whatever the other machine sent: the bearer, and
     *  for a Claude head that holds logins, the launch proof. */
    private fun request(call: ApplicationCall, target: URI, body: ByteArray, signed: Map<String, String>): HttpRequest {
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val request = HttpRequest.newBuilder(target).method(call.request.httpMethod.value, publisher)
        call.request.headers.forEach { name, values ->
            if (name.lowercase() !in notForwarded) values.forEach { request.header(name, it) }
        }
        signed.forEach { (name, value) -> request.header(name, value) }
        return request.build()
    }

    /** [request] sent, and its answer given to [call]. A client that is kept alive does not wait on a silent head for
     *  its stream to open: a head that has not answered within [HEAD_SOON_MS] finds the stream open when it does. */
    private suspend fun forward(call: ApplicationCall, request: HttpRequest, wait: ClientWait) {
        val answer = AtomicReference<HttpResponse<InputStream>>()
        try {
            if (wait.every == null) return answers.given(call, send(request, answer, wait), wait)
            coroutineScope {
                val sending = async { send(request, answer, wait) }
                val soon = withTimeoutOrNull(HEAD_SOON_MS) { sending.await() }
                if (soon == null) answers.early(call, sending, wait) else answers.given(call, soon, wait)
            }
        } finally {
            // However the answer ended, a relay cancelled before it read a byte included: the head is hung up on.
            answer.get()?.let { response ->
                Cancellables.discard(
                    Cancellables.runCatchingCancellable { response.body().close() },
                    "closing an answer already read to its end or already closed changes nothing",
                )
            }
        }
    }

    /** The head's answer to [request], kept in [answer] from the moment there is one, so it is closed however the
     *  relay ends; a head that does not answer is said in the log. */
    private suspend fun send(
        request: HttpRequest,
        answer: AtomicReference<HttpResponse<InputStream>>,
        wait: ClientWait,
    ): Result<HttpResponse<InputStream>> = runInterruptible(io) {
        Cancellables.runCatchingCancellable {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream()).also(answer::set)
        }
    }.onFailure { failure ->
        log("[${speaker.tag}] ${wait.silent} on :${request.uri().port}: ${SafeFailureText.render(failure)}\n")
    }

    /** [call]'s body, or null once it is refused the way a head's own admission refuses one: 413 for a body declared
     *  or read past [cap], 408 for one still arriving after [readTimeoutMs]. */
    private suspend fun body(call: ApplicationCall, cap: Int, readTimeoutMs: Long): ByteArray? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val read = if (declared != null && declared > cap) {
            null
        } else {
            try {
                // One byte past the cap is enough to know the body is too large; no more is ever held.
                withTimeout(readTimeoutMs) { call.receiveChannel().readBuffer(cap + 1L).readByteArray() }
            } catch (_: TimeoutCancellationException) {
                val why = "the request body was still arriving after ${readTimeoutMs}ms"
                errors.refuse(call, HttpStatus.REQUEST_TIMEOUT, why)
                return null
            }
        }
        if (read != null && read.size <= cap) return read
        errors.refuse(call, HttpStatus.CONTENT_TOO_LARGE, "request body exceeds $cap bytes")
        return null
    }

    // Hop-by-hop, framing, the other machine's own token, and the names java.net.http refuses to set. Host becomes
    // 127.0.0.1, which the head's own DNS-rebinding guard requires. Which upstream a turn prefers is the relaying
    // listener's to say, from what the operator set for the person, and never the other machine's.
    private val notForwarded = setOf(
        "host", "connection", "content-length", "transfer-encoding", "upgrade", "expect", "keep-alive", "te",
        "trailer", "proxy-authorization", "proxy-connection", "authorization", "x-api-key", "date", "via",
        "warning", "from", "origin", "referer", LAUNCH_PROOF_HEADER, PREFERRED_UPSTREAM_HEADER,
    )
}

// why: the head and the control plane are on this machine's loopback, where a live listener accepts at once; a connect
// still waiting after five seconds is one that is not answering, and the other machine hears that in words.
private const val RELAY_CONNECT_TIMEOUT_MS = 5_000L

// why: a head that refuses a turn (a body it cannot read, a model it does not serve, a cap of its own) does so in
// milliseconds, and its own status reaches the client only while no stream is open; a head still silent after a
// second is waiting on its model server, and a client that asked to be kept alive has its stream opened then, well
// inside the two seconds server mode promises one (docs/specs/server-mode.md, F4).
private const val HEAD_SOON_MS = 1_000L

// why: Claude Code's statusline input is one session's JSON, a few KiB; 256 KiB holds it with room, and nothing larger
// is read before it is refused.
private const val STATUSLINE_BODY_MAX_BYTES = 256 * 1024

// why: that JSON arrives in one or two packets on a LAN; a statusline still unfinished after five seconds is refused.
private const val STATUSLINE_READ_MS = 5_000L
