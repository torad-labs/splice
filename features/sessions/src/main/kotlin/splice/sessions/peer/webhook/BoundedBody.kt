// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): reads at most the first bytes of a webhook receiver's answer.
// JDK 21 has no capped body handler, so this one collects bytes until it has the limit, then cancels the rest of the
// body, which closes the connection instead of downloading what a receiver chose to send.
package splice.sessions.peer.webhook

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** A handler for answers read as text: the first [limit] bytes, decoded as UTF-8 with a bad byte replaced. */
internal class BoundedBody(private val limit: Int) : HttpResponse.BodyHandler<String> {

    override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<String> = Collector()

    private inner class Collector : HttpResponse.BodySubscriber<String> {
        private val bytes = ByteArrayOutputStream()
        private val result = CompletableFuture<String>()
        private var upstream: Flow.Subscription? = null

        override fun getBody(): CompletionStage<String> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            upstream = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            item.forEach { keep(it) }
            if (bytes.size() >= limit) {
                upstream?.cancel()
                result.complete(text())
            }
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(text())
        }

        private fun keep(buffer: ByteBuffer) {
            val chunk = ByteArray(minOf(buffer.remaining(), limit - bytes.size()))
            buffer.get(chunk)
            bytes.write(chunk, 0, chunk.size)
        }

        private fun text(): String = String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
