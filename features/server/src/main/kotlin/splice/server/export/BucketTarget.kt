// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). An export to an S3-compatible bucket
// (`to = "s3://bucket/prefix"`): AWS S3, R2, MinIO and the like, at `endpoint` (AWS's own for the region when none is
// set), path-style (`endpoint/bucket/key`) or virtual-hosted (`bucket.endpoint/key`). Each hour is one signed PUT
// (SigV4). The key pair is read from the environment variables `[server.export]` names at each export, held for that
// request only, and never written anywhere, the log included: a refusal says what the bucket answered, never the key.
package splice.server.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class BucketTarget(
    private val place: BucketPlace,
    private val reach: BucketReach,
    private val io: CoroutineDispatcher,
) : ExportTarget {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(BUCKET_CONNECT_SECONDS)).build()

    override suspend fun put(key: String, bytes: ByteArray): String? {
        val access = reach.env(reach.names.access)?.takeIf(String::isNotBlank)
        val secret = reach.env(reach.names.secret)?.takeIf(String::isNotBlank)
        if (access == null || secret == null) {
            return "the bucket's key is not set: ${reach.names.access} and ${reach.names.secret} must both be set " +
                "in the daemon's environment"
        }
        val uri = place.base.resolve(SigV4.Paths.of(key))
        val signer = SigV4(access, secret, place.region)
        var why: String? = null
        for (attempt in 1..PUT_ATTEMPTS) {
            if (attempt > 1) reach.waiter.wait(RETRY_MS * (attempt - 1))
            val tried = attempt(uri, signer, bytes)
            why = tried.why
            if (why == null || !tried.again) break
        }
        return why
    }

    /** One PUT of [bytes] to [uri]: null when stored, else why not and whether another try could go differently. */
    private suspend fun attempt(uri: URI, signer: SigV4, bytes: ByteArray): PutTried = withContext(io) {
        val signed = signer.headers("PUT", uri, bytes, reach.clock.instant())
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(PUT_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        signed.forEach { (name, value) -> request.header(name, value) }
        try {
            val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            val status = answer.statusCode()
            if (status in HttpStatus.OK..HttpStatus.MAX_SUCCESS) {
                PutTried(null, again = false)
            } else {
                val said = BucketWords.of(answer.body())
                PutTried("the bucket answered $status: $said", again = status >= HttpStatus.INTERNAL_SERVER_ERROR)
            }
        } catch (e: IOException) {
            PutTried("could not reach the bucket at ${uri.host}: ${SafeFailureText.render(e)}", again = true)
        } catch (e: InterruptedException) {
            throw CancellationException("the export was stopped").apply { initCause(e) }
        }
    }
}

/** How one PUT went. */
private data class PutTried(val why: String?, val again: Boolean)

/** What an S3 error body says, in a line: its `<Code>` and `<Message>`, or the start of whatever it is. */
private object BucketWords {
    private val code = Regex("<Code>([^<]*)</Code>")
    private val message = Regex("<Message>([^<]*)</Message>")

    fun of(body: String): String {
        val said = listOfNotNull(code.find(body)?.groupValues?.get(1), message.find(body)?.groupValues?.get(1))
        return said.joinToString(": ").ifBlank { body.take(SHOWN_CHARS).ifBlank { "no reason given" } }
    }
}

// why: a bucket that takes ten seconds to accept a connection is down for now, and the next export tries again.
private const val BUCKET_CONNECT_SECONDS = 10L

// why: an hour of rows is a few megabytes at most; a minute covers a slow link, and a hung store is let go after it.
private const val PUT_SECONDS = 60L

// why: a store that answers 5xx or drops the connection is usually back within seconds; three tries, two and four
// seconds apart, ride that out, and anything longer waits for the next export, which writes the hour whole again.
private const val PUT_ATTEMPTS = 3

// why: the wait before the second try; the third waits twice as long.
private const val RETRY_MS = 2_000L

// why: a refusal names the bucket's own words in the log and to the CLI; a body past this is not a reason, it is a page.
private const val SHOWN_CHARS = 200
