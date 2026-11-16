// NEW: the relay's HTTP client (docs/specs/lan-share.md; docs/specs/server-mode.md, F4): the JDK's, asked the way that
// parks no thread. A relayed turn spends nearly all of its time waiting, first for its answer's response headers and
// then for each piece of the answer, and a server holds many such waits at once. So a request is sent as a future the
// relay suspends on, and the answer's body is taken piece by piece as the client publishes it (AnswerBody). Whatever
// number of turns wait on their model servers, the threads the rest of the daemon runs on are left alone.
package splice.share

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The answer one relay holds, from the moment there is one, so that it is hung up on however the relay ends: read
 *  to its end, broken off, or cancelled before a byte of it was read, by a cancel that crossed the answer on its way
 *  included. */
internal class HeldAnswer {
    private val answer = AtomicReference<HttpResponse<AnswerBody>?>()

    @Volatile
    private var over = false

    /** [response] is the answer, kept from now; one that came for a relay already over is hung up on at once. */
    fun take(response: HttpResponse<AnswerBody>) {
        answer.set(response)
        if (over) response.body().close()
    }

    /** The relay is over: its answer is hung up on, now or when it comes. One read to its end is left as it is. */
    fun hangUp() {
        over = true
        answer.get()?.body()?.close()
    }
}

internal class RelayClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(java.time.Duration.ofMillis(RELAY_CONNECT_TIMEOUT_MS))
        .build()

    // The response is handed over when its headers have come, with its body still to be asked for.
    private val answering = HttpResponse.BodyHandler { _ ->
        HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofPublisher()) { arriving ->
            AnswerBody().also(arriving::subscribe)
        }
    }

    /** The answer to [request], back when its response headers have come and kept in [held] from then. No thread
     *  waits for it, and a caller that is cancelled hangs up on the server. It fails as the JDK client's own send
     *  does: with a server that took no connection, or one that went away without answering. */
    suspend fun send(request: HttpRequest, held: HeldAnswer): HttpResponse<AnswerBody> =
        suspendCancellableCoroutine { waiting ->
            val sending = http.sendAsync(request, answering)
            sending.whenComplete { response, failure ->
                if (failure == null) {
                    held.take(response)
                    waiting.resume(response)
                } else {
                    // A future says what failed inside a wrapper of its own.
                    waiting.resumeWithException((failure as? CompletionException)?.cause ?: failure)
                }
            }
            // The JDK client drops the exchange, connection and all, for a cancel that may interrupt.
            waiting.invokeOnCancellation { sending.cancel(true) }
        }
}

// why: the head and the control plane are on this machine's loopback, where a live listener accepts at once; a connect
// still waiting after five seconds is one that is not answering, and the other machine hears that in words.
private const val RELAY_CONNECT_TIMEOUT_MS = 5_000L
