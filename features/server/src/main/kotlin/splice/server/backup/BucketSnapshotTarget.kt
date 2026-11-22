// NEW: server mode (docs/specs/server-mode.md, F14). S3-compatible bucket snapshot target (`s3://bucket/prefix`):
// signs both PUT and GET requests with SigV4, returning Missing on HTTP 404 and Failed on auth/5xx/network errors so
// startup restore fails closed whenever the bucket cannot be verified.
package splice.server.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.server.export.BucketPlace
import splice.server.export.BucketReach
import splice.server.export.SigV4
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class BucketSnapshotTarget(
    private val place: BucketPlace,
    private val reach: BucketReach,
    private val io: CoroutineDispatcher,
) : SnapshotTarget {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(BACKUP_CONNECT_SECONDS))
        .build()

    override suspend fun put(key: String, bytes: ByteArray): String? {
        val signer = signerOrNull() ?: return missingCredentialsMessage()
        val uri = place.base.resolve(SigV4.Paths.of(key))
        return putOnce(uri, signer, bytes)
    }

    override fun get(key: String): SnapshotRead {
        val signer = signerOrNull() ?: return SnapshotRead.Failed(missingCredentialsMessage())
        val uri = place.base.resolve(SigV4.Paths.of(key))
        return getOnce(uri, signer)
    }

    private fun signerOrNull(): SigV4? {
        val access = reach.env(reach.names.access)?.takeIf(String::isNotBlank) ?: return null
        val secret = reach.env(reach.names.secret)?.takeIf(String::isNotBlank) ?: return null
        return SigV4(access, secret, place.region)
    }

    private fun missingCredentialsMessage(): String =
        "the backup bucket's key is not set: ${reach.names.access} and ${reach.names.secret} must both be set"

    private suspend fun putOnce(uri: URI, signer: SigV4, bytes: ByteArray): String? = withContext(io) {
        val signed = signer.headers("PUT", uri, bytes, reach.clock.instant())
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(BACKUP_IO_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        signed.forEach { (name, value) -> request.header(name, value) }
        try {
            val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (answer.statusCode() in HttpStatus.OK..HttpStatus.MAX_SUCCESS) {
                null
            } else {
                "the backup bucket answered ${answer.statusCode()}"
            }
        } catch (e: IOException) {
            "could not reach backup bucket at ${uri.host}: ${SafeFailureText.render(e)}"
        } catch (e: InterruptedException) {
            throw CancellationException("the backup was stopped").apply { initCause(e) }
        }
    }

    private fun getOnce(uri: URI, signer: SigV4): SnapshotRead {
        val empty = ByteArray(0)
        val signed = signer.headers("GET", uri, empty, reach.clock.instant())
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(BACKUP_IO_SECONDS))
            .GET()
        signed.forEach { (name, value) -> request.header(name, value) }
        return try {
            val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            when (val status = answer.statusCode()) {
                in HttpStatus.OK..HttpStatus.MAX_SUCCESS -> SnapshotRead.Found(answer.body())
                HttpStatus.NOT_FOUND -> SnapshotRead.Missing
                else -> SnapshotRead.Failed("the backup bucket answered $status")
            }
        } catch (e: IOException) {
            SnapshotRead.Failed("could not reach backup bucket at ${uri.host}: ${SafeFailureText.render(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            SnapshotRead.Failed("backup restore interrupted: ${SafeFailureText.render(e)}")
        }
    }
}

// why: ten seconds bounds connecting to an unreachable object store at boot or during a snapshot.
private const val BACKUP_CONNECT_SECONDS = 10L

// why: one minute covers uploading or downloading a team server's state bundle on a slow link.
private const val BACKUP_IO_SECONDS = 60L
