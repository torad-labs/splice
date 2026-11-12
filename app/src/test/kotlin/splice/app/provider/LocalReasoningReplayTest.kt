// NEW: WHO GETS ITS EARLIER REASONING BACK, decided at the assembly point (Marcos, 2026-10-23: "pass reasoning for
// self served heads like glm and qwen").
//
// A self-served head is a local runtime (llama-server here): its chat template renders each earlier assistant turn's
// reasoning_content inside that turn's think block, so sending it back is how the model reads the history it wrote.
// A hosted vendor on this dialect keeps its request unchanged, because some refuse reasoning_content in input
// messages. `local` is the topology's own reading (LocalProviderRule plus the local= override).
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig

class LocalReasoningReplayTest {

    private val overlay = QuirksOverlay()

    private fun replays(
        baseUrl: String,
        authKind: String = "api-key",
        local: Boolean? = null,
    ): Boolean = overlay.chatQuirks(
        ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = baseUrl,
            auth = AuthConfig(authKind),
            local = local,
        ),
        key = "head",
        label = "head",
    ).reasoning.replayReasoning

    @Test
    fun `a self-served runtime gets its earlier reasoning back`() {
        assertEquals(true, replays("http://127.0.0.1:8102/v1"))
        assertEquals(true, replays("http://localhost:11434/v1"))
    }

    @Test
    fun `a hosted vendor's request is unchanged`() {
        assertEquals(false, replays("https://api.example.com/v1"))
        assertEquals(false, replays("https://openrouter.ai/api/v1", authKind = "custom"))
        assertEquals(false, replays("https://api.x.ai/v1", authKind = "grok-oauth"))
    }

    @Test
    fun `the provider's own local flag decides, not the shape of its base_url`() {
        assertEquals(false, replays("http://127.0.0.1:8102/v1", local = false))
        assertEquals(true, replays("http://192.168.1.50:8000/v1", local = true))
    }
}
