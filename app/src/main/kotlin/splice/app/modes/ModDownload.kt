package splice.app.modes

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpResponse.BodySubscribers
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An untrusted response stays in memory, with one deadline covering DNS, headers, and the entire body. */
internal object ModDownload {
    // The JDK client's default redirect policy is NEVER.
    private val client by lazy { HttpClient.newHttpClient() }

    fun read(address: URI, deadlineMs: Long = MOD_DOWNLOAD_MS): ByteArray {
        val download = client.sendAsync(HttpRequest.newBuilder(address).build(), ::body)
        return try {
            download.get(deadlineMs, TimeUnit.MILLISECONDS).body()
        } catch (_: TimeoutException) {
            throw ModFileFailure("download exceeded its deadline", "url")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            download.cancel(true)
        }
    }

    private fun body(info: HttpResponse.ResponseInfo): BodySubscriber<ByteArray> {
        if (!HttpStatusCode.fromValue(info.statusCode()).isSuccess()) {
            throw ModFileFailure("download did not return success; use a direct URL", "url")
        }
        return BoundedModBody()
    }
}

/** Cancel an oversized response before handing any excess bytes to the accumulator. */
private class BoundedModBody(
    private val accumulator: BodySubscriber<ByteArray> = BodySubscribers.ofByteArray(),
) : BodySubscriber<ByteArray> by accumulator {
    private var subscription: Flow.Subscription? = null
    private var remaining = MAX_JAR_BYTES.toLong()

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        accumulator.onSubscribe(subscription)
    }

    override fun onNext(items: List<ByteBuffer>) {
        remaining -= items.sumOf { it.remaining().toLong() }
        if (remaining < 0) {
            subscription?.cancel()
            accumulator.onError(ModFileFailure("archive exceeds the 32 MiB size limit", "url"))
        } else {
            accumulator.onNext(items)
        }
    }
}

// Bound startup and preflight even if the server keeps sending small chunks.
private const val MOD_DOWNLOAD_MS = 10_000L
