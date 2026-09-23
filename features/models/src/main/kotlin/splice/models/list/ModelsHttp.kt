// NEW: 2026-09-22 — the one network seam `splice models` uses, and its JDK implementation.
//
// A SEPARATE SEAM FROM `splice add`'s (AddHttp) ON PURPOSE: this one carries arbitrary request
// HEADERS, because the two dialects that publish a list want different ones — an
// anthropic-passthrough endpoint expects `x-api-key` and `anthropic-version`, an openai-chat one a
// bearer. Widening AddHttp would have put that requirement on every check `splice add` runs, which
// is unrelated code.
//
// IT RETURNS THREE ANSWERS, NOT TWO, AND THAT IS THE WHOLE POINT OF THE TYPE. AddHttp returns null
// for every throwable, so "nothing is listening" and "that URL does not parse" arrive identical.
// This verb then labels a local provider's silence "not running" — which would have been printed
// over a malformed `models_url` or a TLS failure, telling the operator to start a process that is
// already running. A transport fault keeps its rendered reason; only a refused connection is
// absence. (Raised by the builder seat reviewing this file, 2026-09-22.)
package splice.models.list

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// why: matches `splice add`'s probe budget (AddHttp.PROBE_TIMEOUT_S). This verb asks every provider
// in turn, so the whole report costs at most this per unreachable one; a remote list is a few KiB
// and answers in well under a second, and a local runtime refuses instantly when it is down.
private const val LIST_TIMEOUT_S = 10L

/** What asking one URL yielded. */
internal sealed class ModelsAnswer {
    /** The endpoint answered, whatever it said — a 401 is an answer. */
    data class Answered(val status: Int, val body: String) : ModelsAnswer()

    /** Nothing accepted a connection at that address: the one failure that means "not running". */
    data class NotListening(val detail: String) : ModelsAnswer()

    /** The request could not be made or completed for any OTHER reason — a URL that does not parse,
     *  a header the client refuses, a TLS failure, a protocol error. A fault with a name, never
     *  absence, so a caller cannot print "not running" over it. */
    data class Failed(val detail: String) : ModelsAnswer()
}

/** One GET. Total: every outcome is one of [ModelsAnswer]'s three, so nothing collapses to null. */
internal fun interface ModelsHttp {
    operator fun invoke(url: String, headers: Map<String, String>): ModelsAnswer
}

internal class JdkModelsHttp(private val client: HttpClient = HttpClient.newHttpClient()) : ModelsHttp {

    override fun invoke(url: String, headers: Map<String, String>): ModelsAnswer = Cancellables
        .runCatchingCancellable {
            // URI.create, not URI(): the constructor throws the checked URISyntaxException, which
            // escapes the catch and aborts every later provider; create() raises it as the
            // IllegalArgumentException this seam classifies as Failed.
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(LIST_TIMEOUT_S))
                .GET()
            headers.forEach { (name, value) -> builder.header(name, value) }
            val reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            ModelsAnswer.Answered(reply.statusCode(), reply.body())
        }
        .getOrElse(::classify)

    /** A refused or timed-out CONNECTION is the only absence; the JDK wraps it, so the whole cause
     *  chain is walked rather than the top frame alone. */
    private fun classify(failure: Throwable): ModelsAnswer {
        val detail = SafeFailureText.render(failure)
        val refused = generateSequence(failure, Throwable::cause)
            .any { it is ConnectException || it is HttpConnectTimeoutException }
        return if (refused) ModelsAnswer.NotListening(detail) else ModelsAnswer.Failed(detail)
    }
}
