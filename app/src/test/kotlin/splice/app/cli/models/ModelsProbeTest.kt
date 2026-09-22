// NEW: 2026-09-22 — the transport half of `splice models`: which credential is presented, which
// headers ride, and how each status becomes an answer the operator can act on. No socket: the HTTP
// seam is a lambda that records what it was asked for.
package splice.app.cli.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.UpstreamRoster
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

class ModelsProbeTest {

    private val env = EnvReader { name -> if (name == "TEST_API_KEY") "key-abc" else null }
    private val asked = mutableListOf<Pair<String, Map<String, String>>>()

    private fun provider(
        dialect: Dialect = Dialect.OPENAI_CHAT,
        baseUrl: String = "https://api.example.test/v1",
        kind: String = "api-key",
        modelsUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        local: Boolean? = null,
    ) = ProviderConfig(
        dialect = dialect,
        baseUrl = baseUrl,
        auth = AuthConfig(kind = kind, env = "TEST_API_KEY"),
        extraHeaders = headers,
        modelsUrl = modelsUrl,
        local = local,
    )

    private fun probeWith(reply: ModelsReply?) = ModelsProbe(
        http = { url, headers -> reply.also { asked += url to headers } },
    )

    private fun roster(provider: ProviderConfig, reply: ModelsReply?): UpstreamRoster =
        probeWith(reply).probe("test", provider, env).roster

    @Test
    fun `an openai-chat provider is asked at its dialect path with a bearer`() {
        val answer = roster(provider(), ModelsReply(200, """{"data":[{"id":"m-1","context_length":128000}]}"""))
        assertEquals("https://api.example.test/v1/models", asked.single().first)
        assertEquals("Bearer key-abc", asked.single().second["Authorization"])
        assertTrue(answer is UpstreamRoster.Published)
        assertEquals("m-1", (answer as UpstreamRoster.Published).models.single().id)
    }

    @Test
    fun `an anthropic provider also gets x-api-key and a version, and its own header wins`() {
        roster(provider(dialect = Dialect.ANTHROPIC_PASSTHROUGH, baseUrl = "https://api.example.test"), ModelsReply(200, """{"data":[]}"""))
        val headers = asked.single().second
        assertEquals("https://api.example.test/v1/models", asked.single().first)
        assertEquals("key-abc", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        asked.clear()
        // A provider that declares its own version header is not silently overridden by the default:
        // the operator's splice.toml is the same source a TURN reads.
        roster(
            provider(dialect = Dialect.ANTHROPIC_PASSTHROUGH, baseUrl = "https://api.example.test", headers = mapOf("anthropic-version" to "2026-01-01")),
            ModelsReply(200, """{"data":[]}"""),
        )
        assertEquals("2026-01-01", asked.single().second["anthropic-version"])
    }

    @Test
    fun `models_url outranks the dialect path`() {
        roster(
            provider(dialect = Dialect.ANTHROPIC_PASSTHROUGH, baseUrl = "https://api.example.test/anthropic", modelsUrl = "https://api.example.test/models"),
            ModelsReply(200, """{"data":[]}"""),
        )
        assertEquals("https://api.example.test/models", asked.single().first)
    }

    @Test
    fun `a refused credential names the sign-in, and any other status names itself`() {
        val refused = roster(provider(), ModelsReply(401, ""))
        assertTrue(refused is UpstreamRoster.Unreadable)
        assertTrue((refused as UpstreamRoster.Unreadable).detail.contains("TEST_API_KEY"))
        val other = roster(provider(), ModelsReply(404, ""))
        assertTrue((other as UpstreamRoster.Unreadable).detail.contains("404"))
    }

    @Test
    fun `a responses provider and a client-auth provider are unpublished, not failures`() {
        val responses = roster(provider(dialect = Dialect.OPENAI_RESPONSES), ModelsReply(200, """{"data":[]}"""))
        assertTrue(responses is UpstreamRoster.Unpublished)
        // Nothing was even asked: there is no URL to ask.
        assertTrue(asked.isEmpty())
        val client = roster(provider(kind = "client"), ModelsReply(200, """{"data":[]}"""))
        assertTrue(client is UpstreamRoster.Unpublished)
        assertTrue((client as UpstreamRoster.Unpublished).reason.contains("your own Claude login"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a local runtime that is not running is unpublished, while a remote silence is unreadable`() {
        val down = roster(provider(baseUrl = "http://127.0.0.1:8099/v1"), null)
        assertTrue(down is UpstreamRoster.Unpublished, "a loopback base_url is local by default")
        assertTrue((down as UpstreamRoster.Unpublished).reason.contains("not running"))
        asked.clear()
        val remote = roster(provider(), null)
        assertTrue(remote is UpstreamRoster.Unreadable)
    }
}
