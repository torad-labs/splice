package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ClientModelPolicy
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry

class StatuslineScaledRowTest {
    private val grok = ModelCatalog(
        discoveryPrefix = "claude-grok--",
        models = listOf(
            ModelEntry(id = "grok-4.6", label = "Grok 4.6", contextWindow = 256_000),
            ModelEntry(id = "grok-4.6[500k]", label = "Grok 4.6 (500k)", contextWindow = 500_000),
        ),
        defaultContextWindow = 256_000,
        pinnedModel = "grok-4.6",
    )

    private fun post(id: String, size: Long, input: Int = 28_000, cached: Int = 172_000) = """
        {"session_id":"s-old","model":{"id":"$id","display_name":"$id"},
         "context_window":{"context_window_size":$size,"used_percentage":50,
           "current_usage":{"input_tokens":$input,"cache_read_input_tokens":$cached,"cache_creation_input_tokens":0}}}
    """.trimIndent()

    private fun render(renderer: StatuslineRenderer, stdin: String): String =
        renderer.render(stdin, usage = null, StatuslineWarn(0, 0)).replace(Regex("\u001B\\[[0-9;]*m"), "")

    @Test
    fun `a reply this daemon cannot match keeps the posted counts even for a declared wide row`() {
        val line = render(
            StatuslineRenderer(label = "grok", catalog = grok, clientWindows = ClientWindows()),
            post("grok-4.6[500k]", 400_000),
        )
        assertTrue("Grok 4.6 (500k)" in line, line)
        assertTrue("200k/400k" in line, line)
        assertTrue("50%" in line, line)
    }

    @Test
    fun `a session on an older env teaches its window without changing the counts it posted`() {
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "grok", catalog = grok, clientWindows = windows)
        val line = render(renderer, post("grok-4.6", 400_000))
        assertTrue("200k/400k" in line, line)
        assertEquals(400_000L, windows.windowFor("s-old"))
    }

    @Test
    fun `a 1m row's post teaches nothing about the env`() {
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "grok", catalog = grok, clientWindows = windows)
        render(renderer, post("grok-4.6[1m]", 1_000_000))
        assertNull(windows.windowFor("s-old"))
    }

    @Test
    fun `an open Claude head learns the divisor while presented and 1m rows do not`() {
        val open = ModelCatalog(
            discoveryPrefix = "claude-splice--",
            models = emptyList(),
            defaultContextWindow = 1_000_000,
            client = ClientModelPolicy(open = true),
        )
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "Claude", catalog = open, clientWindows = windows)
        render(renderer, post("claude-opus-5-5", 200_000))
        assertEquals(200_000L, windows.windowFor("s-old"))
        val hints = ClientWindows()
        val hinted = StatuslineRenderer(label = "Claude", catalog = open, clientWindows = hints)
        render(hinted, post("claude-opus-5-5[1m]", 1_000_000))
        assertNull(hints.windowFor("s-old"))

        val presented = open.copy(
            models = listOf(
                ModelEntry(id = "claude-sonnet-5", contextWindow = 1_000_000, clientModel = "claude-opus-5-5"),
            ),
        )
        val presentedWindows = ClientWindows()
        val presentedRenderer = StatuslineRenderer(
            label = "Claude",
            catalog = presented,
            clientWindows = presentedWindows,
        )
        render(presentedRenderer, post("claude-sonnet-5", 200_000))
        assertNull(presentedWindows.windowFor("s-old"))
    }
}
