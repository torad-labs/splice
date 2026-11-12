// NEW: V4-268 — `splice models` says why it could not ask a provider for its list. On the film stack it
// printed "…/codex/models?client_version=999.0.0 could not be asked: failure (message withheld: it may
// quote file bytes)": JdkModelsHttp rendered every failure through SafeFailureText, which withholds
// the client's timeout and TLS failures. The failures the client names now read in splice's words;
// anything else stays withheld.
package splice.models.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters

/** A client whose every request fails with [failure], the way the JDK's does. */
private class FailingClient(private val failure: Exception) : HttpClient() {
    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = throw failure

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        push: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)
}

class ModelsHttpTest {

    private val url = "https://chatgpt.com/backend-api/codex/models?client_version=999.0.0"

    private fun asked(failure: Exception, at: String = url) = JdkModelsHttp(FailingClient(failure))(at, emptyMap())

    @Test
    fun `a list that does not arrive in time says so, with the budget`() {
        assertEquals(ModelsAnswer.Failed("no answer within 10 s"), asked(HttpTimeoutException("request timed out")))
    }

    @Test
    fun `a TLS failure is named as one`() {
        assertEquals(
            ModelsAnswer.Failed("the TLS connection failed"),
            asked(IOException(SSLHandshakeException("PKIX path building failed"))),
        )
    }

    @Test
    fun `a URL that does not parse is named as one`() {
        val unparsed = asked(IOException("never sent"), "http://bad host/models")
        assertEquals(ModelsAnswer.Failed("the URL does not parse"), unparsed)
    }

    @Test
    fun `any other failure stays withheld`() {
        assertEquals(
            ModelsAnswer.Failed("failure (message withheld: it may quote file bytes)"),
            asked(IOException("GOAWAY received")),
        )
    }
}
