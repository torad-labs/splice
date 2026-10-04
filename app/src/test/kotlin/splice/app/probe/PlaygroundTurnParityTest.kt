// NEW: V4-444 — the Playground sends what a turn sends. marlin's check of 0e22c018b found every ChatGPT send refused with
// 400 {"detail": "Input must be a list"}: the probe hand-built each dialect's body, a second copy of the request shape that
// had drifted from the turn path. This holds the probe to the production builder on every arm ProviderAssembly dispatches
// to (codex, an api-key Responses provider, chat, anthropic passthrough): the body it posts is the one the head's own
// provider builds for the same one-message prompt, at the provider's own URL, under the provider's and the turn's headers.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.app.TokenUrlRefreshCall
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.diagnostics.playground.PlaygroundHead
import splice.topology.TopologyLoader
import splice.upstream.Provider
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val PROMPT = "Say hello and name your model."

/** The one-message turn Claude Code would send with this prompt: what the probe must hand the production builder. */
private fun onePrompt(model: String): String =
    """{"model":"$model","max_tokens":1024,"stream":true,""" +
        """"messages":[{"role":"user","content":[{"type":"text","text":"$PROMPT"}]}]}"""

private fun topology(root: Path): String = """
    [providers.codex]
    dialect = "openai-responses"
    base_url = "https://chatgpt.example.com/backend-api/codex"
    auth = { kind = "chatgpt-oauth", file = "${root.resolve("auth.json")}" }

    [providers.platform]
    dialect = "openai-responses"
    base_url = "https://platform.example.com/v1"
    auth = { kind = "api-key", env = "PLAYGROUND_PARITY_PLATFORM_KEY" }

    [providers.chat]
    dialect = "openai-chat"
    base_url = "https://chat.example.com/v1"
    auth = { kind = "api-key", env = "PLAYGROUND_PARITY_CHAT_KEY" }

    [providers.anthro]
    dialect = "anthropic-passthrough"
    base_url = "https://anthropic.example.com"
    auth = { kind = "api-key", env = "PLAYGROUND_PARITY_ANTHRO_KEY" }
    extra_headers = { "anthropic-version" = "2023-06-01" }

    [heads.claudex]
    provider = "codex"
    port = 9101
    discovery_prefix = "claudex/"
    pinned_model = "gpt-6-sol"

    [heads.platformy]
    provider = "platform"
    port = 9102
    discovery_prefix = "platformy/"
    pinned_model = "gpt-6-luna"

    [heads.chatty]
    provider = "chat"
    port = 9103
    discovery_prefix = "chatty/"
    pinned_model = "chat-model"

    [heads.anthropicy]
    provider = "anthro"
    port = 9104
    discovery_prefix = "anthropicy/"
    pinned_model = "claude-model"
""".trimIndent()

internal val syntheticCredentials: Map<String, Credentials> = mapOf(
    "claudex" to Credentials.Bearer("synthetic-token", accountId = "synthetic-account"),
    "platformy" to Credentials.ApiKey("synthetic-key", "Authorization", "Bearer "),
    "chatty" to Credentials.ApiKey("synthetic-key", "Authorization", "Bearer "),
    "anthropicy" to Credentials.ApiKey("synthetic-key", "x-api-key", ""),
)

private fun auth(creds: Credentials?): AuthProvider = object : AuthProvider {
    override suspend fun credentials(): Credentials? = creds
    override suspend fun describe() = AuthDescription(creds != null, "synthetic", emptyMap())
}

/** Each head's provider, built by the same dispatch the daemon boots heads with. */
internal fun assembledProviders(root: Path): Map<String, Provider> {
    val paths = StatePaths(baseOverride = root.resolve("state"))
    val assembly = ProviderAssembly(
        paths,
        CoroutineScope(Dispatchers.Unconfined),
        log = {},
        refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
    )
    val parsed = TopologyLoader.parse(topology(root))
    return parsed.heads.mapValues { (key, head) ->
        val provider = parsed.providers.getValue(head.provider)
        assembly.buildProvider(
            ProviderBuild(
                key = key,
                head = head,
                providerCfg = provider,
                catalog = provider.catalogFor(head),
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                cfg = ConfigService(paths).getConfig(key),
                loginCommand = "$key login",
            ),
        ).provider
    }
}

/** The registry the daemon's head factory fills, filled with [assembledProviders]. */
internal fun registered(root: Path): PlaygroundProviders =
    PlaygroundProviders().also { registry -> assembledProviders(root).forEach(registry::register) }

/** One head as the route hands it to the probe, with the synthetic credential [syntheticCredentials] gives its key. */
internal fun playgroundHead(key: String, creds: Credentials? = syntheticCredentials.getValue(key)): PlaygroundHead =
    PlaygroundHead(key, auth(creds))

class PlaygroundTurnParityTest {

    private suspend fun sent(root: Path, key: String): HttpRequestData {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        UpstreamPlaygroundProbe(registered(root), HttpClient(engine)).run(playgroundHead(key), PROMPT, null)
        return requireNotNull(captured) { "the probe never posted for $key" }
    }

    private fun bodyOf(request: HttpRequestData): String = when (val body = request.body) {
        is TextContent -> body.text
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> error("unexpected body ${body::class}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["claudex", "platformy", "chatty", "anthropicy"])
    fun `the probe posts the body the head's own provider builds, at its URL, under its headers`(
        key: String,
        @TempDir root: Path,
    ) = runTest {
        val provider = assembledProviders(root).getValue(key)
        val creds = syntheticCredentials.getValue(key)
        val asked = Json.parseToJsonElement(PlaygroundTurn.of(provider.pinnedModel, PROMPT))
        assertEquals(Json.parseToJsonElement(onePrompt(provider.pinnedModel)), asked, "the probe's one-message turn")
        val turn = provider.buildTurn(AnthropicParse.parseAnthropicBody(onePrompt(provider.pinnedModel)), false, null)
        val request = sent(root, key)

        assertEquals(provider.upstreamUrl, request.url.toString(), "the URL a turn posts to")
        assertEquals(turn.requestBody, Json.parseToJsonElement(bodyOf(request)).jsonObject, "the body a turn builds")
        val headers = request.headers.entries().associate { (name, values) -> name.lowercase() to values.single() }
        for ((name, value) in provider.extraHeaders(creds)) {
            assertEquals(value, headers[name.lowercase()], "the provider's own header $name")
        }
        for (name in turn.extraHeaders.keys) assertTrue(name.lowercase() in headers, "the turn's own header $name")
    }

    /** The concrete refusal marlin hit: ChatGPT answers 400 "Input must be a list" to a plain-string input. */
    @ParameterizedTest
    @ValueSource(strings = ["claudex", "platformy"])
    fun `a Responses send carries its input as a list of items, never a plain string`(
        key: String,
        @TempDir root: Path,
    ) = runTest {
        val body = Json.parseToJsonElement(bodyOf(sent(root, key))).jsonObject
        assertTrue(body["input"] is JsonArray, "input was ${body["input"]}")
    }
}
