// NEW: the harness (docs/specs/harness.md, decisions 4 and 13). The one way a worker's HTTP request is waited for, so
// that a call cancelled (the time limit, the client leaving, a drain) drops the request it made.
package splice.harness

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resumeWithException

internal class SentRequests {
    /** The response [future] answers. A cancelled call cancels it with `cancel(true)`, the one way the JDK's client
     *  drops the request (a plain cancel leaves it open until the worker answers). */
    suspend fun response(future: CompletableFuture<HttpResponse<InputStream>>): HttpResponse<InputStream> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, failure ->
                if (failure == null) {
                    // A response that comes after the call was cancelled has its body closed, which drops it.
                    continuation.resume(response) { _, late, _ -> late.body().close() }
                } else {
                    continuation.resumeWithException((failure as? CompletionException)?.cause ?: failure)
                }
            }
        }
}
