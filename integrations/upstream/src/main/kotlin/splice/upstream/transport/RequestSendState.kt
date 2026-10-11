// NEW: V4-307 (2026-09-26) — a thread start the host refuses is placed by where the request was when it struck.
//
// OkHttp starts threads before it writes a byte (its task runner, when a new connection joins the pool), and
// okio starts one at the first timed read or write after a quiet spell (its timeout watchdog, which also
// times every response read). So the same refusal can strike before the upstream has the request or after it
// has all of it, and only the second makes a retry a possible duplicate. The refusal itself does not say
// which; the request's body does, and this interceptor is where both are seen. Its own file: the client
// construction installs it with one line, and the verdict reads only the type it throws.
package splice.upstream.transport

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V4-307: a thread start the host refused before the request's body was written whole, so the upstream
 * cannot hold the request and the retry sends it for the first time (TransportFailures reads it as CONNECT).
 * An Error, like the refusal it carries, so OkHttp ends the call exactly as it ends one on the bare refusal:
 * RealCall.AsyncCall cancels the call and hands the Error over as the cause of IOException("canceled due to
 * …"). The mark adds the send state and changes nothing else.
 */
internal class RefusedBeforeSend(refused: OutOfMemoryError) :
    Error("refused before the request was written whole", refused)

/**
 * V4-307: marks a refused thread start that struck before the request's body was written whole as
 * [RefusedBeforeSend]. One after that, or on a request with no body to finish, is left as it is, and so keeps
 * the possible-duplicate reading every failure this client cannot place gets (TransportFailures). The body
 * counts as written when its writeTo returns; the exchange flushes what okio still buffers after that, with
 * no timeout on a request with a body (RequestWriteBound.untimedWrite), so no watchdog start can strike there.
 *
 * An APPLICATION interceptor, because a refusal while OkHttp sets up the connection is thrown before any
 * network interceptor runs; the body it wraps is the one the exchange writes.
 */
internal class RequestSendState : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val written = AtomicBoolean()
        val marked = request.newBuilder().method(request.method, WrittenMark(body, written)).build()
        try {
            return chain.proceed(marked)
        } catch (refused: OutOfMemoryError) {
            if (written.get() || !FailureChain.refusedThreadStart(refused)) throw refused
            throw RefusedBeforeSend(refused)
        }
    }

    /** [body], noting in [written] that it was written whole. Once written it stays so for the call, even
     *  if OkHttp writes it again on another connection: the upstream has had it whole once. */
    private class WrittenMark(private val body: RequestBody, private val written: AtomicBoolean) : RequestBody() {
        override fun contentType(): MediaType? = body.contentType()

        override fun contentLength(): Long = body.contentLength()

        override fun isOneShot(): Boolean = body.isOneShot()

        override fun isDuplex(): Boolean = body.isDuplex()

        override fun writeTo(sink: BufferedSink) {
            body.writeTo(sink)
            written.set(true)
        }
    }
}
