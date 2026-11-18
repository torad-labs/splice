// NEW: the agent's call (docs/specs/harness.md, decision 12, Beside the client's calls). The body a worker is posted,
// which says when its last bytes have gone to the HTTP client: a call made beside the client's own is asked before the
// client gets its calls, so the worker runs while the client runs them.
package splice.harness

import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow

/** Hears that a worker's request has been sent whole. */
internal fun interface WorkerPosted {
    fun posted()
}

/** [bytes] as a request body, telling [posted] once the client has taken all of them. */
internal class PostedBody(bytes: ByteArray, private val posted: WorkerPosted) : HttpRequest.BodyPublisher {
    private val body = HttpRequest.BodyPublishers.ofByteArray(bytes)

    override fun contentLength(): Long = body.contentLength()

    override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
        body.subscribe(Told(subscriber, posted))
    }

    /** [subscriber], and [posted] told once it has had the whole body. */
    private class Told(
        private val subscriber: Flow.Subscriber<in ByteBuffer>,
        private val posted: WorkerPosted,
    ) : Flow.Subscriber<ByteBuffer> {
        override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)

        override fun onNext(item: ByteBuffer) = subscriber.onNext(item)

        override fun onError(throwable: Throwable) = subscriber.onError(throwable)

        override fun onComplete() {
            subscriber.onComplete()
            posted.posted()
        }
    }
}
