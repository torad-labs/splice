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
// No thread waits on a head: the relay is suspended on the send and on each piece of the answer (RelayClient,
// AnswerBody), so however many turns wait on their model servers, each is relayed as if it were the only one. A
// caller that cancels the relay, as the server listener does when a turn's client hangs up, ends it at once and hangs
// up on the head, which then sees its own client leave: no head goes on computing an answer nobody is waiting for.
//
// A turn the server listener asks to be kept alive, and that asks for a stream, is not left waiting on a silent head:
// after a second without the first byte of an answer its stream is opened to the client and kept alive until the
// head answers (HeadAnswers.early). That second, and each interval between two keepalives, goes by the relay's clock
// (RelayClock): the process clock in production, and in a test one the test steps.
//
// Server mode also answers OpenAI-style clients for a command's provider itself (F4, openai-route): `outward` sends
// such a request to that provider's server, and gives the answer back the same way a head's is given. That server is
// no part of this machine, so of what the client sent only the headers the listener names ride on, under the
// provider's own headers; a request for a head carries all but the few a hop never passes on, because the head is
// the one that decides what leaves the machine. A server that took no connection got nothing of the request, so the
// request goes on to the next server its way names (OutwardWay), and its client is told that none answers only once
// none is left. A streamed request the listener asks to be kept alive is kept alive as a head's turn is, and its way
// through those servers is walked behind the stream when that is open by then. And of such a server's answer the
// client gets nothing of the server's own but what the answer is for (HeadAnswers).
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.TurnKey
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.upstream.transport.TransportFailures
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration

/** Whose words a relay refuses in, and the name its log lines carry. */
public data class RelaySpeaker(val errors: RefusalVoice, val tag: String)

/** One request for a head: its [path] under the head (`/v1/messages?beta=true`), what the listener that relays it
 *  says about it in headers of its own ([named]), such as the upstream the caller's person prefers, how often its
 *  client is sent a [keepalive] while a streamed answer is silent (null: never, and the client waits on the head),
 *  and the caller's headers the listener keeps to itself ([held], lowercase names), which the head is never handed. */
public data class HeadTurn(
    val head: SharedHead,
    val path: String,
    val named: Map<String, String> = emptyMap(),
    val keepalive: Duration? = null,
    val held: Set<String> = emptySet(),
)

public class ShareRelay(
    private val turnKey: TurnKey,
    private val controlPort: Int,
    private val machine: String,
    /** Blocking work runs here: what a statusline's admission reads. No wait on a head does (RelayClient). */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val speaker: RelaySpeaker = RelaySpeaker(ShareErrors.voice, "share"),
    /** What a kept-alive client's silences are timed on. */
    private val clock: RelayClock = RelayClock(),
) {
    private val errors get() = speaker.errors
    private val answers = HeadAnswers(speaker, clock, log)
    private val client = RelayClient()
    private val streamed = StreamedBodies()
    private val failures = TransportFailures()

    /** What a statusline's body passes before it is relayed: the listener pins the session it names to the login the
     *  address chose, so the line shows that login from its first draw, before the session's first turn. */
    internal fun interface StatuslineAdmission {
        /** Why [body]'s statusline may not be drawn, or null once it may. */
        fun refusal(body: ByteArray): String?
    }

    /** This relay with [voice] for its refusals: the same machine and the same heads, for a client that reads errors
     *  in another shape. */
    public fun speaking(voice: RefusalVoice): ShareRelay =
        ShareRelay(turnKey, controlPort, machine, io, log, speaker.copy(errors = voice), clock)

    /** [call]'s body, read under [head]'s own limits: null once it is refused, the way the head would refuse it. */
    public suspend fun sent(call: ApplicationCall, head: SharedHead): ByteArray? =
        body(call, head.maxRequestBytes(), head.requestReadTimeoutMs())

    /** [ask] asked the way [call] asked it, of the first server of [way] that takes a connection, and that server's
     *  answer given to [call]; or, once none of them took one, what [way] says of that. */
    public suspend fun outward(call: ApplicationCall, ask: OutwardAsk, way: OutwardWay) {
        val wait = ClientWait(ask.keepalive, ask.edit, ask.whole, outside = true)
        answered(call, wait) { held -> taken(call, ask, way, held) }
    }

    /** [ask] asked of [way]'s servers, one at a time. The first that takes a connection is the one whose answer, or
     *  whose silence after it, the client gets. One that takes none got nothing of the request, so the next is
     *  asked. */
    private suspend fun taken(
        call: ApplicationCall,
        ask: OutwardAsk,
        way: OutwardWay,
        held: HeldAnswer,
    ): Sent {
        // The answer is read here when it is a stream to edit, so the server is asked to send it as it is.
        val own = ask.headers.own + (HttpHeaders.AcceptEncoding to "identity")
        var server = way.next()
        while (server != null) {
            val request = request(call, server.target, ask.body, own) { it in ask.headers.carried }
            val sent = send(request, held, server.name)
            val failure = sent.exceptionOrNull() ?: return Sent.Answered(sent.getOrThrow(), server.name)
            if (!failures.neverConnected(failure)) return Sent.Silent(silent(server.name))
            server = way.next()
        }
        return Sent.Silent(way.noneTook())
    }

    /** [turn] on its head, under the head's own body limits. */
    public suspend fun turn(call: ApplicationCall, turn: HeadTurn) {
        val head = turn.head
        val body = body(call, head.maxRequestBytes(), head.requestReadTimeoutMs()) ?: return
        val who = "${head.command} on $machine"
        // A head that takes the caller's own login refuses this machine's turn key as a credential to send upstream.
        // It gets the node's word instead, which opens nothing: its pool sends a login it holds in that word's place.
        val bearer = if (head.forwardsClientAuth) SHARE_NODE_TOKEN else turnKey.get()
        // PR #247 review, Oct 29: that head serves its logins only to a caller proven splice's, and this relay is.
        val proof = if (head.forwardsClientAuth) mapOf(LAUNCH_PROOF_HEADER to turnKey.get()) else emptyMap()
        val signed = mapOf(HttpHeaders.Authorization to "Bearer $bearer") + proof + turn.named
        val target = URI.create("http://127.0.0.1:${head.port}${turn.path}")
        val request = request(call, target, body, signed) { it !in turn.held }
        forward(call, request, who, ClientWait(turn.keepalive?.takeIf { streamed.asks(body) }))
    }

    /** The control plane's statusline for [head], fed the session's JSON as Claude Code feeds its statusline, once
     *  [admission] has read the body: a refusal it returns is answered instead. */
    internal suspend fun statusline(call: ApplicationCall, head: SharedHead, admission: StatuslineAdmission) {
        val body = body(call, STATUSLINE_BODY_MAX_BYTES, STATUSLINE_READ_MS) ?: return
        val refusal = withContext(io) { admission.refusal(body) }
        if (refusal != null) return errors.refuse(call, HttpStatus.BAD_REQUEST, refusal)
        val who = "$machine's statusline for ${head.command}"
        val signed = mapOf(HttpHeaders.Authorization to "Bearer ${turnKey.get()}")
        val target = URI.create("http://127.0.0.1:$controlPort/statusline/${head.key}")
        forward(call, request(call, target, body, signed), who)
    }

    /** [body] for [target], with the [signed] headers in place of whatever the other machine sent under those names:
     *  the bearer, and for a Claude head that holds logins, the launch proof. Of the other machine's own headers,
     *  all ride on but the ones no hop passes on, and of those only the ones [rides] takes. */
    private fun request(
        call: ApplicationCall,
        target: URI,
        body: ByteArray,
        signed: Map<String, String>,
        rides: RidingHeader = RidingHeader { true },
    ): HttpRequest {
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val request = HttpRequest.newBuilder(target).method(call.request.httpMethod.value, publisher)
        call.request.headers.forEach { name, values ->
            val passed = name.lowercase().let { it !in notForwarded && rides(it) }
            if (passed) values.forEach { request.header(name, it) }
        }
        signed.forEach { (name, value) -> request.setHeader(name, value) }
        return request.build()
    }

    /** [request] sent to [who], as the client hears the head or the control plane named, and the answer given to
     *  [call]. */
    private suspend fun forward(
        call: ApplicationCall,
        request: HttpRequest,
        who: String,
        wait: ClientWait = ClientWait(),
    ) {
        answered(call, wait) { held ->
            send(request, held, who).fold({ Sent.Answered(it, who) }, { Sent.Silent(silent(who)) })
        }
    }

    /** What [asking] ends in, given to [call]. A client that is kept alive does not wait in silence for its stream
     *  to open: when no answer has started within [HEAD_SOON_MS], the answer finds the stream open when it does. */
    private suspend fun answered(call: ApplicationCall, wait: ClientWait, asking: Asking) {
        val held = HeldAnswer()
        try {
            if (wait.every == null) return answers.given(call, asking.sent(held), wait)
            coroutineScope {
                // Response headers alone start no answer: a server may send them at once and then prefill in silence.
                val sending = async { answers.started(asking.sent(held)) }
                if (clock.endedWithin(HEAD_SOON_MS, sending)) {
                    answers.given(call, sending.await(), wait)
                } else {
                    answers.early(call, sending, wait)
                }
            }
        } finally {
            // However the answer ended, a relay cancelled before it read a byte included: the head is hung up on.
            held.hangUp()
        }
    }

    /** What a client hears when [who] gives no answer. */
    private fun silent(who: String): String = "$who is not answering"

    /** [who]'s answer to [request], kept in [held] from the moment there is one, so it is hung up on however the
     *  relay ends; one that does not answer is said in the log. */
    private suspend fun send(
        request: HttpRequest,
        held: HeldAnswer,
        who: String,
    ): Result<HttpResponse<AnswerBody>> = Cancellables.runCatchingCancellable { client.send(request, held) }
        .onFailure { failure ->
            log("[${speaker.tag}] ${silent(who)} on :${request.uri().port}: ${SafeFailureText.render(failure)}\n")
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

// why: a head that refuses a turn (a body it cannot read, a model it does not serve, a cap of its own) does so in
// milliseconds, and its own status reaches the client only while no stream is open; a head still silent after a
// second is waiting on its model server, and a client that asked to be kept alive has its stream opened then, well
// inside the two seconds server mode promises one (docs/specs/server-mode.md, F4). A provider's own server asked for
// an OpenAI-style client is given the same second, for the same reasons. The second is for the first byte of an
// answer, not for its response headers, which some model servers send before they prefill.
private const val HEAD_SOON_MS = 1_000L

// why: Claude Code's statusline input is one session's JSON, a few KiB; 256 KiB holds it with room, and nothing larger
// is read before it is refused.
private const val STATUSLINE_BODY_MAX_BYTES = 256 * 1024

// why: that JSON arrives in one or two packets on a LAN; a statusline still unfinished after five seconds is refused.
private const val STATUSLINE_READ_MS = 5_000L
