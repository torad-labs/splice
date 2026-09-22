// NEW: 2026-09-22 — the transport half of `splice models`: which credential is presented, which
// headers ride, and how each status becomes an answer the operator can act on. No socket: the HTTP
// seam is a lambda that records what it was asked for.
package splice.app.cli.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.UpstreamRoster
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

/** The fixture endpoint, named once: several cases build URLs on it, and spelling it out put each
 *  of them past the line limit. */
private const val TEST_BASE = "https://api.example.test"

/** A loopback base_url, which [ProviderConfig.isLocal] reads as a local runtime by default. */
private const val LOCAL_BASE = "http://127.0.0.1:8099/v1"

/** An endpoint that served a roster with nothing in it — an answer, not a failure. */
private const val EMPTY_LIST = """{"data":[]}"""

class ModelsProbeTest {

    private val env = EnvReader { name -> if (name == "TEST_API_KEY") "key-abc" else null }
    private val asked = mutableListOf<Pair<String, Map<String, String>>>()

    private fun provider(
        dialect: Dialect = Dialect.OPENAI_CHAT,
        baseUrl: String = "$TEST_BASE/v1",
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

    private fun probeWith(answer: ModelsAnswer) = ModelsProbe(
        http = { url, headers -> answer.also { asked += url to headers } },
    )

    private fun roster(provider: ProviderConfig, answer: ModelsAnswer): UpstreamRoster =
        probeWith(answer).probe("test", provider, env).roster

    private fun ok(body: String) = ModelsAnswer.Answered(200, body)

    @Test
    fun `an openai-chat provider is asked at its dialect path with a bearer`() {
        val answer = roster(provider(), ok("""{"data":[{"id":"m-1","context_length":128000}]}"""))
        assertEquals("$TEST_BASE/v1/models", asked.single().first)
        assertEquals("Bearer key-abc", asked.single().second["Authorization"])
        assertTrue(answer is UpstreamRoster.Published)
        assertEquals("m-1", (answer as UpstreamRoster.Published).models.single().id)
    }

    @Test
    fun `an anthropic provider also gets x-api-key and a version, and its own header wins`() {
        roster(provider(dialect = Dialect.ANTHROPIC_PASSTHROUGH, baseUrl = TEST_BASE), ok(EMPTY_LIST))
        val headers = asked.single().second
        assertEquals("$TEST_BASE/v1/models", asked.single().first)
        assertEquals("key-abc", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        asked.clear()
        // A provider that declares its own version header is not silently overridden by the default:
        // the operator's splice.toml is the same source a TURN reads.
        roster(
            provider(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = TEST_BASE,
                headers = mapOf("anthropic-version" to "2026-01-01"),
            ),
            ok(EMPTY_LIST),
        )
        assertEquals("2026-01-01", asked.single().second["anthropic-version"])
    }

    @Test
    fun `models_url outranks the dialect path`() {
        roster(
            provider(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "$TEST_BASE/anthropic",
                modelsUrl = "$TEST_BASE/models",
            ),
            ok(EMPTY_LIST),
        )
        assertEquals("$TEST_BASE/models", asked.single().first)
    }

    @Test
    fun `a refused credential names the sign-in, and any other status names itself`() {
        val refused = roster(provider(), ModelsAnswer.Answered(401, ""))
        assertTrue(refused is UpstreamRoster.Unreadable)
        assertTrue((refused as UpstreamRoster.Unreadable).detail.contains("TEST_API_KEY"))
        val other = roster(provider(), ModelsAnswer.Answered(404, ""))
        assertTrue((other as UpstreamRoster.Unreadable).detail.contains("404"))
    }

    @Test
    fun `a responses provider and a client-auth provider are unpublished, not failures`() {
        val responses = roster(provider(dialect = Dialect.OPENAI_RESPONSES), ok(EMPTY_LIST))
        assertTrue(responses is UpstreamRoster.Unpublished)
        // Nothing was even asked: there is no URL to ask.
        assertTrue(asked.isEmpty())
        val client = roster(provider(kind = "client"), ok(EMPTY_LIST))
        assertTrue(client is UpstreamRoster.Unpublished)
        assertTrue((client as UpstreamRoster.Unpublished).reason.contains("your own Claude login"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a local runtime that is not running is unpublished, while a remote silence is unreadable`() {
        val notListening = ModelsAnswer.NotListening("connection refused")
        val down = roster(provider(baseUrl = LOCAL_BASE), notListening)
        assertTrue(down is UpstreamRoster.Unpublished, "a loopback base_url is local by default")
        assertTrue((down as UpstreamRoster.Unpublished).reason.contains("not running"))
        asked.clear()
        val remote = roster(provider(), notListening)
        assertTrue(remote is UpstreamRoster.Unreadable)
    }

    @Test
    fun `a transport fault keeps its reason even on a local provider, and is never called not running`() {
        // The defect this pins: every throwable used to arrive as null, so a malformed models_url on
        // a loopback provider printed "this local runtime is not running" and sent the operator to
        // restart a process that was already up.
        val failed = ModelsAnswer.Failed("no protocol: api.example.test/models")
        val local = roster(provider(baseUrl = LOCAL_BASE), failed)
        assertTrue(local is UpstreamRoster.Unreadable, "a transport fault is a fault, local or not")
        val detail = (local as UpstreamRoster.Unreadable).detail
        assertTrue(detail.contains("no protocol"), "the rendered reason survives: $detail")
        assertFalse(detail.contains("not running"), "absence must not be printed over a fault: $detail")
    }

    @Test
    fun `a refusal says whether splice even held a credential`() {
        // 401 with a key set and 401 with none are different problems; the status alone cannot say.
        val held = roster(provider(), ModelsAnswer.Answered(401, ""))
        assertTrue((held as UpstreamRoster.Unreadable).detail.contains("refused the stored credential"))
        val none = ModelsProbe(http = { _, _ -> ModelsAnswer.Answered(401, "") })
            .probe("test", provider(kind = "api-key"), EnvReader { null })
            .roster
        assertTrue((none as UpstreamRoster.Unreadable).detail.contains("splice holds none"), none.detail)
    }
}
