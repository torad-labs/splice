// NEW: the harness (docs/specs/harness.md, decision 4). An HTTP worker: splice posts it one JSON object, with the
// worker's own key as a bearer when its declaration names one, and reads the JSON lines it answers in until one result
// or one refusal. Cancelling the call (the client left, the time limit passed, the head stopped) drops the request.
package splice.harness

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import splice.core.capability.CapabilityWorker
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import splice.harness.wire.SentRequests
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Hears each progress message a worker sends, for what the held turn says. */
internal fun interface WorkerProgress {
    fun said(message: String)
}

internal class HttpWorker(
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
    private val keys: WorkerKeys,
) {
    private val lines = WorkerLines()
    private val sends = SentRequests()

    /** Posts [body] to [worker], telling [posted] once it is sent, and reads its answer to the line that ends the
     *  work. */
    suspend fun call(
        worker: CapabilityWorker.Http,
        body: JsonObject,
        progress: WorkerProgress,
        posted: WorkerPosted = WorkerPosted { },
    ): WorkerEnd {
        val request = when (val built = request(worker, body, posted)) {
            is Built.Request -> built.request
            is Built.Failed -> return built.end
        }
        val response = try {
            sends.response(client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()))
        } catch (_: IOException) {
            return WorkerEnd.Failed("it could not be reached")
        }
        return response.body().use { stream ->
            // a worker's answer is read only from a 200
            if (response.statusCode() != HttpStatus.OK) {
                WorkerEnd.Failed("it answered ${response.statusCode()}")
            } else {
                read(stream, progress)
            }
        }
    }

    /** The request [worker] is posted: its URL, and its own key as a bearer when its declaration names one. */
    private fun request(worker: CapabilityWorker.Http, body: JsonObject, posted: WorkerPosted): Built {
        val uri = try {
            URI(worker.url)
        } catch (_: java.net.URISyntaxException) {
            return Built.Failed(WorkerEnd.Failed("its url is not a URL"))
        }
        val env = worker.authEnv
        val key = env?.let(keys::key)
        if (env != null && key == null) return Built.Failed(WorkerEnd.Failed("$env holds no key"))
        val request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .POST(PostedBody(JsonWire.bytes(body), posted))
            .build()
        return Built.Request(request)
    }

    /** A request ready to post, or the failure that keeps it from being made. */
    private sealed class Built {
        class Request(val request: HttpRequest) : Built()

        class Failed(val end: WorkerEnd) : Built()
    }

    private suspend fun read(stream: InputStream, progress: WorkerProgress): WorkerEnd = try {
        runInterruptible(io) {
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            // splice stops reading at the line that ends the work: whatever the worker sends after it is not read.
            generateSequence { reader.readLine() }.firstNotNullOfOrNull { heard(it, progress) } ?: lines.unended()
        }
    } catch (_: IOException) {
        WorkerEnd.Failed("its answer broke off")
    }

    /** What one line of the answer says: the end of the work, or null for a progress line or a blank one. */
    private fun heard(line: String, progress: WorkerProgress): WorkerEnd? {
        if (line.isBlank()) return null
        return when (val read = lines.read(line)) {
            is WorkerLine.Progress -> null.also { progress.said(read.message) }
            is WorkerLine.Ended -> read.end
        }
    }
}
