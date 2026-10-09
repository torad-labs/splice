// NEW: V4-441 — a Codex model the BACKEND marks `tool_mode = "code_mode_only"` runs on the one-`exec` surface
// without being named in a list. gpt-6.1-sol shipped on 2026-09-29 marked so and would have run with direct
// tools (2026-09-28's break: 98% direct calls, 1.6% exec) but for a hand edit of `code_mode_models`; the list
// is now an addition to what the backend says, and the backend's flag is asked at TURN time (the port), so a
// roster refreshed while the daemon runs reaches the next turn.
package splice.provider.codex.v4441

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.DiscoveredModel
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodexCodeModeTurnBuilder

class BackendCodeModeOnlyTest : CodeModeBridgeTestSupport() {

    /** The marks the backend's catalog carried on 2026-09-29: seven listed rows and gpt-5.5, which has none. */
    private val backend = listOf(
        DiscoveredModel("gpt-6.1-sol", toolMode = "code_mode_only"),
        DiscoveredModel("gpt-6-luna", toolMode = "code_mode_only"),
        DiscoveredModel("gpt-5.6-terra", toolMode = "code_mode_only"),
        DiscoveredModel("gpt-5.5"),
    )

    @Volatile
    private var roster: List<DiscoveredModel> = backend

    private val port = CodeModeOnlyModels {
        roster.filter { it.codeModeOnly }.flatMap { it.spellings }
    }

    private fun builder(operator: List<String>? = null) =
        CodexCodeModeTurnBuilder(bridge(ScriptedRuntime(ArrayDeque())), media(), operator, port)

    /** True when the turn on [model] was armed with the exec surface, which returns a new BuiltTurn; a turn
     *  that is not eligible comes back as the very object it went in as. */
    private fun offered(builder: CodexCodeModeTurnBuilder, model: String): Boolean {
        val original = built(model, lite = true)
        return builder.prepare(toolBody(), "session", original) !== original
    }

    @Test
    fun `a model the backend marks code_mode_only gets exec with code_mode_models unset`() {
        assertEquals(true, offered(builder(), "gpt-6.1-sol"))
    }

    @Test
    fun `and with a list that omits it`() {
        assertEquals(true, offered(builder(listOf("gpt-6-astra")), "gpt-6.1-sol"))
    }

    @Test
    fun `a model the backend does not mark does not get it`() {
        assertEquals(false, offered(builder(), "gpt-5.5"))
        assertEquals(false, offered(builder(), "a-model-nobody-listed"))
    }

    @Test
    fun `a model the list names but the backend does not mark still does`() {
        assertEquals(true, offered(builder(listOf(" GPT-5.5 ")), "gpt-5.5"))
    }

    @Test
    fun `the context suffix and the case of the id are ignored, as they were for the list`() {
        assertEquals(true, offered(builder(), "GPT-6.1-Sol[1m]"))
    }

    @Test
    fun `a hidden model the backend leaves out of its list reaches code mode only by code_mode_models`() {
        // gpt-reserve and codex-auto-review are code_mode_only but visibility hide, which discovery drops.
        assertEquals(false, offered(builder(), "codex-auto-review"))
        assertEquals(true, offered(builder(listOf("codex-auto-review")), "codex-auto-review"))
    }

    @Test
    fun `no backend list known leaves the operator's list alone, and an empty one leaves no model`() {
        roster = emptyList()
        assertEquals(false, offered(builder(), "gpt-6-luna"))
        assertEquals(true, offered(builder(listOf("gpt-6-luna")), "gpt-6-luna"))
    }

    @Test
    fun `the port is read each turn, so a refreshed roster reaches the next turn of a builder already built`() {
        roster = listOf(DiscoveredModel("gpt-5.5"))
        val builder = builder()
        assertEquals(false, offered(builder, "gpt-5.5"), "not marked when the builder was made")
        roster = listOf(DiscoveredModel("gpt-5.5", toolMode = "code_mode_only"))
        assertEquals(true, offered(builder, "gpt-5.5"), "the backend marked it since: the next turn follows")
        roster = emptyList()
        assertEquals(false, offered(builder, "gpt-5.5"), "and unmarked again")
    }
}
