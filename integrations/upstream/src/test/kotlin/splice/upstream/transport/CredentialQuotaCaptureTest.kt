// NEW: successful quota readings stay joined to the credential actually sent on the wire.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import java.util.concurrent.atomic.AtomicInteger

class CredentialQuotaCaptureTest {
    @Test
    fun `capture uses effective request carriers without another credential lookup`() = runTest {
        val credentials = AtomicInteger()
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials =
                Credentials.Bearer("generated-${credentials.incrementAndGet()}")
            override suspend fun refresh(): Credentials? = null
            override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
        }
        val forwarded = mapOf("authorization" to "Bearer caller", "X-API-KEY" to "caller-key")
        val engine = MockEngine {
            respond("ok", HttpStatusCode.OK, headersOf("anthropic-ratelimit-unified-5h-utilization", "0.25"))
        }
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { forwarded },
            onRetry = {},
            clientFrameEmitted = { false },
        )
        var observed: String? = null
        HttpClient(engine).use { http ->
            val client = UpstreamClient(totalTimeoutMs = 5_000, maxRetries = 1, client = http)
            client.posted(context, "{}") { response ->
                response.observeQuota { key, headers ->
                    observed = key
                    assertEquals("0.25", headers("anthropic-ratelimit-unified-5h-utilization"))
                }
            }
        }
        assertEquals(CredentialKey.fromHeaders(forwarded), observed)
        assertEquals(1, credentials.get(), "the observation must not ask for a potentially rotated login")
    }
}
