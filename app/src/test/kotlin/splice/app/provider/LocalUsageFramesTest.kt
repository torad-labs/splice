// NEW: V4-163 — WHO ASKS FOR USAGE FRAMES, decided at the assembly point.
//
// Usage is opt-in on this dialect (stream_options.include_usage), and a head that never asks
// reports zero tokens on every turn — so Claude Code, which sizes its context from the usage it is
// told, never reaches its auto-compact threshold and the session runs into the context wall. That
// is the failure this row repairs, and it is invisible to every other test in the tree because a
// zero is a valid number.
//
// The denominator is the PROVIDER KINDS this arm can be handed, not a list of suspects: a local
// runtime asks (llama.cpp measured here, Ollama ollama#6784, vLLM vllm#5135, LM Studio), grok-oauth
// keeps the `true` its own profile already carried, and every other vendor keeps OFF because strict
// ones (Gemini, Fireworks) 400 on unrecognized stream_options members. `local` is the topology's
// own reading (LocalProviderRule + the local= override), never a second guess at the URL, so a
// loopback provider the operator opted OUT of stays off and a LAN runtime marked local asks.
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.daemon.TopologyLoader
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig

class LocalUsageFramesTest {

    private val overlay = QuirksOverlay()

    private fun asks(
        baseUrl: String,
        authKind: String = "api-key",
        local: Boolean? = null,
        quirks: QuirksConfig = QuirksConfig(),
    ): Boolean = overlay.chatQuirks(
        ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = baseUrl,
            auth = AuthConfig(authKind),
            quirks = quirks,
            local = local,
        ),
        key = "head",
        label = "head",
    ).emitUsageInStream

    @Test
    fun `a local runtime asks for usage frames, so its head can report tokens`() {
        assertEquals(true, asks("http://127.0.0.1:8099/v1"))
        assertEquals(true, asks("http://localhost:11434/v1"))
    }

    // Mutant: default the field on rather than on `local`. Every hosted vendor on this dialect
    // would start carrying an extra request field, and the strict ones answer 400 to the turn.
    @Test
    fun `a hosted vendor is unchanged, and grok-oauth keeps the frames it already had`() {
        assertEquals(false, asks("https://api.example.com/v1"))
        assertEquals(false, asks("https://openrouter.ai/api/v1", authKind = "custom"))
        assertEquals(true, asks("https://api.x.ai/v1", authKind = "grok-oauth"))
    }

    // The escape hatch in both directions: a local runtime that refuses the field, and a hosted
    // vendor the operator knows accepts it.
    @Test
    fun `the TOML knob overrides the default either way`() {
        assertEquals(false, asks("http://127.0.0.1:8099/v1", quirks = QuirksConfig(streamUsage = false)))
        assertEquals(true, asks("https://api.example.com/v1", quirks = QuirksConfig(streamUsage = true)))
        val grokOff = asks("https://api.x.ai/v1", authKind = "grok-oauth", quirks = QuirksConfig(streamUsage = false))
        assertEquals(false, grokOff)
    }

    // `local` is the topology's answer, not this arm's: FEATURES.md §10 gives the operator both
    // overrides, and the usage default has to follow them rather than re-reading the URL.
    @Test
    fun `the provider's own local flag decides, not the shape of its base_url`() {
        assertEquals(false, asks("http://127.0.0.1:8099/v1", local = false))
        assertEquals(true, asks("http://192.168.1.50:8000/v1", local = true))
    }

    @Test
    fun `splice toml carries stream_usage into the provider's quirks`() {
        val toml = """
            [providers.ex]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:8099/v1"
            auth = { kind = "api-key", env = "EX_KEY" }
            quirks = { stream_usage = false }

            [[providers.ex.models]]
            id = "m1"
            context_window = 131072

            [heads.ex]
            provider = "ex"
            port = 3301
            discovery_prefix = "ex/"
            pinned_model = "m1"
        """.trimIndent()

        assertEquals(false, TopologyLoader.parse(toml).providers.getValue("ex").quirks.streamUsage)
    }
}
