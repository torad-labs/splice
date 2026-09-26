// NEW: V4-239 — GET /api/models/upstream's payload: `splice models`' comparison as JSON, asked of a real
// endpoint on loopback, and carrying no credential anywhere. The bearer is presented to the provider (the
// endpoint records it, so the no-credential assertions are not vacuous) and never reaches the answer;
// a models_url that holds a credential of its own (`user:pass@`, `?key=`) goes out without it, in the
// url field and in the sentence that names the URL.
package splice.models.list

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.net.InetAddress
import java.net.InetSocketAddress

private const val BEARER = "tok-SECRET-123"
private const val URL_KEY = "sekrit-url-key"
private const val HOST = "127.0.0.1"
private const val BROKEN = "/broken/models"
private const val PUBLISHED =
    """{"data":[{"id":"m-1","context_length":128000},{"id":"m-new","context_length":64000}]}"""

class UpstreamModelsRouteTest {
    private lateinit var server: HttpServer
    private val authorizations = mutableListOf<String?>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val (status, body) = if (exchange.requestURI.path == "/v1/models") 200 to PUBLISHED else 500 to "{}"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val base: String get() = "http://$HOST:${server.address.port}"

    private fun provider(modelsUrl: String, models: List<ModelEntry> = emptyList()) = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "$base/v1",
        auth = AuthConfig(kind = "api-key", env = "TEST_API_KEY"),
        models = models,
        modelsUrl = modelsUrl,
        local = false,
    )

    private val reporter = ModelsReporter(
        ModelConfigurationSource {
            ModelConfiguration(
                "/fixture/splice.toml",
                linkedMapOf(
                    "remote" to provider(
                        "$base/v1/models?key=$URL_KEY",
                        listOf(
                            ModelEntry(id = "m-1", contextWindow = 100_000),
                            ModelEntry(id = "gone", contextWindow = 50_000),
                        ),
                    ),
                    "broken" to provider("http://user:pw-in-url@$HOST:${server.address.port}$BROKEN?key=$URL_KEY#f"),
                ),
            )
        },
        ModelCredentialSource { _, _, _ -> BEARER },
    )

    private val route =
        UpstreamModelsRoute(ModelsReporterSource { reporter }, EnvReader { null }, Dispatchers.Unconfined)

    private fun answer(): Pair<String, Map<String, JsonObject>> {
        val report = reporter.report(null, EnvReader { null })
        assertTrue(report is ModelsReport.Compared, "$report")
        val text = route.json(report as ModelsReport.Compared)
        val providers = Json.parseToJsonElement(text).jsonObject.getValue("providers").jsonArray
            .associate { it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject }
        return text to providers
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    @Test
    fun `each declared row carries the verdict the verb prints, and each served model no row declares`() {
        val remote = answer().second.getValue("remote")

        assertEquals("published", remote.str("roster"))
        val verdicts = remote.getValue("rows").jsonArray
            .associate { it.jsonObject.str("id") to it.jsonObject.str("verdict") }
        assertEquals(mapOf("m-1" to "capped", "gone" to "unserved", "m-new" to "new"), verdicts)
        assertFalse(remote.getValue("agrees").jsonPrimitive.boolean, "an unserved declared row is a disagreement")
        val capped = remote.getValue("rows").jsonArray.first().jsonObject
        assertEquals("100000", capped.str("declared_window"))
        assertEquals("128000", capped.str("upstream_window"))
        assertEquals("openai-chat", remote.str("dialect"))
    }

    @Test
    fun `a provider that could not be read says why, with no rows`() {
        val broken = answer().second.getValue("broken")

        assertEquals("unreadable", broken.str("roster"))
        assertTrue(broken.str("reason").isNotBlank(), "$broken")
        assertTrue(broken.getValue("rows").jsonArray.isEmpty())
        assertFalse(broken.getValue("agrees").jsonPrimitive.boolean)
    }

    @Test
    fun `no credential leaves - not the bearer, not a url's user info, key or fragment`() {
        val (text, providers) = answer()

        assertTrue(authorizations.contains("Bearer $BEARER"), "the provider saw the credential: $authorizations")
        assertFalse(text.contains(BEARER), text)
        assertFalse(text.contains(URL_KEY), text)
        assertFalse(text.contains("pw-in-url"), text)
        assertEquals("$base/v1/models", providers.getValue("remote").str("url"))
        assertEquals("http://$HOST:${server.address.port}$BROKEN", providers.getValue("broken").str("url"))
        assertTrue(
            providers.getValue("broken").str("reason").contains("$HOST:${server.address.port}$BROKEN"),
            "the sentence still names where it asked: ${providers.getValue("broken")}",
        )
    }
}
