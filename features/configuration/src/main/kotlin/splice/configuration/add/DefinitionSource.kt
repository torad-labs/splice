// NEW: custom-provider (issue #680, cp-install). Where `splice add provider <file|url>` reads a team's published
// provider definition from: a file, or an address. Over the network, https from any host and plain http only from this
// machine (127.0.0.1, ::1, localhost), at most 15 seconds and 1 MiB: a definition names the commands splice runs to sign
// a person in, so it is never read in the clear across a network.
package splice.configuration.add

import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.wire.HTTPS_SCHEME
import splice.core.wire.HTTP_SCHEME
import splice.core.wire.HttpStatus
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/** What reading a definition found: its text, or why there is none. */
internal sealed class DefinitionRead {
    data class Text(val text: String) : DefinitionRead()

    data class Refused(val why: String) : DefinitionRead()
}

/** Reads the definition [source] names, a path or an http(s) address. */
internal class DefinitionSource(private val source: String) {
    fun read(): DefinitionRead {
        val uri = Cancellables.runCatchingCancellable { URI(source) }.fold(onSuccess = { it }, onFailure = { null })
        return when (uri?.scheme?.lowercase()) {
            HTTPS_SCHEME -> fetched(uri)
            HTTP_SCHEME -> if (loopback(uri.host.orEmpty())) fetched(uri) else DefinitionRead.Refused(PLAIN_HTTP)
            else -> file()
        }
    }

    private fun file(): DefinitionRead {
        val path = Paths.get(UserHome.expand(source))
        return Cancellables.runCatchingCancellable {
            Files.newInputStream(path).use(::capped)
        }.fold(
            onSuccess = { it },
            onFailure = { DefinitionRead.Refused("$source could not be read: ${SafeFailureText.render(it)}") },
        )
    }

    private fun fetched(uri: URI): DefinitionRead = Cancellables.runCatchingCancellable {
        // HTTP/1.1: a plain-http server that does not answer an h2c upgrade would otherwise hold the read to its timeout
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build()
        val reply = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        reply.body().use { body ->
            val status = reply.statusCode()
            if (status !in OK) DefinitionRead.Refused("$source answered HTTP $status") else capped(body)
        }
    }.fold(
        onSuccess = { it },
        onFailure = { DefinitionRead.Refused("$source could not be fetched: ${why(it)}") },
    )

    private fun why(failure: Throwable): String = when (failure) {
        is java.net.http.HttpTimeoutException -> "it did not answer within 15 seconds"
        is java.net.ConnectException -> "nothing answered there"
        else -> SafeFailureText.render(failure)
    }

    /** At most [MAX_DEFINITION_BYTES]: a definition is a page of TOML, never more. */
    private fun capped(input: InputStream): DefinitionRead {
        val bytes = input.readNBytes(MAX_DEFINITION_BYTES + 1)
        if (bytes.size > MAX_DEFINITION_BYTES) {
            return DefinitionRead.Refused("$source is larger than 1 MiB; no definition is")
        }
        return DefinitionRead.Text(bytes.decodeToString())
    }

    private fun loopback(host: String): Boolean = host.trim('[', ']').lowercase() in LOOPBACK
}

private const val PLAIN_HTTP =
    "a definition is read over https, or over plain http only from this machine; publish it at an https address"
private val LOOPBACK = setOf("127.0.0.1", "::1", "localhost", "0:0:0:0:0:0:0:1")
private val OK = HttpStatus.OK..HttpStatus.MAX_SUCCESS

// why: a definition server that takes longer than this is down; the item decided the bound.
private const val FETCH_TIMEOUT_S = 15L
private val TIMEOUT: Duration = Duration.ofSeconds(FETCH_TIMEOUT_S)

// why: a definition is a page of TOML, never more; the item decided 1 MiB as the most one may be.
private const val MAX_DEFINITION_BYTES = 1024 * 1024
