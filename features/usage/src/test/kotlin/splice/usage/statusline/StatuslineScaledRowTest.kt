// NEW: the statusline on a SCALED row. Claude Code fixes its context window per process (the
// pinned row's, planted at launch) and splice scales the counts it reports so every row compacts at
// its own window, which leaves the bar reading the client's window and the scaled counts however
// the operator switched: picking grok-4.6[500k] on a 256k grok head kept "…/256k" on screen
// (operator report, 2026-09-02). With the head's catalog the renderer shows the picked row's label,
// its declared window and the real counts — the pinned row included, now that it scales too; a
// head with no catalog renders the blob exactly as sent.
package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ClientWindows
import splice.core.model.CompactionReserve
import splice.core.model.CompactionReserveDefaults
import splice.core.model.LiveWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry

/** gpt-6.1-sol's row in core's bundled calibration table (growth p95, generation p99). */
private val SOL_6_1_RESERVE = CompactionReserve(1_303, 22_844)

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

    // What Claude Code pipes: its window is the process's own (here 1000000, a session launched on
    // the 2026-09-05 constant), and the counts are the ones splice scaled by 1e6/declared — 500k
    // reported is real 250k on the 500k row (x2.0) and real 128k on the 256k row (x3.90625); 50%
    // either way, because the ratio is the row's own.
    private fun blob(id: String, display: String) = """
        {"model":{"id":"$id","display_name":"$display"},
         "context_window":{"context_window_size":1000000,"used_percentage":50,
           "current_usage":{"input_tokens":56000,"cache_read_input_tokens":444000,"cache_creation_input_tokens":0}}}
    """.trimIndent()

    private fun render(renderer: StatuslineRenderer, stdin: String): String =
        renderer.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0).replace(Regex("\\[[0-9;]*m"), "")

    @Test
    fun `a scaled row renders its own label, declared window and real counts`() {
        val line = render(StatuslineRenderer(label = "grok", catalog = grok), blob("grok-4.6[500k]", "grok-4.6[500k]"))
        assertTrue("Grok 4.6 (500k)" in line, "the row's label, not the raw id: $line")
        assertTrue("250k/500k" in line, "counts unscaled and the declared window: $line")
        assertTrue("50%" in line, "the percentage is already right and stays: $line")
    }

    @Test
    fun `the pinned row is repaired too and a catalog-less head renders the blob as sent`() {
        val pinned = render(StatuslineRenderer(label = "grok", catalog = grok), blob("grok-4.6", "grok-4.6"))
        assertTrue("Grok 4.6" in pinned && "128k/256k" in pinned, "the pinned row's real counts and window: $pinned")
        val bare = render(StatuslineRenderer(label = "grok"), blob("grok-4.6[500k]", "grok-4.6[500k]"))
        assertTrue("grok-4.6[500k]" in bare && "500k/1000k" in bare, "no catalog, no repair: $bare")
    }

    // 2026-09-05: a session launched on an earlier window still runs on its old env (here
    // 400000), and the head must scale that session's counts against it — the status-line post is
    // where the window is learned. The bar is right by the same arithmetic: counts x 400k/256k on
    // the 256k row, so 200k reported is real 128k.
    private fun oldSessionBlob(id: String, size: Long, input: Int = 28_000, cached: Int = 172_000) = """
        {"session_id":"s-old","model":{"id":"$id","display_name":"$id"},
         "context_window":{"context_window_size":$size,"used_percentage":50,
           "current_usage":{"input_tokens":$input,"cache_read_input_tokens":$cached,"cache_creation_input_tokens":0}}}
    """.trimIndent()

    @Test
    fun `a session on an older env is read against ITS window and the head learns it`() {
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "grok", catalog = grok, clientWindows = windows)
        val line = render(renderer, oldSessionBlob("grok-4.6", 400_000))
        assertTrue("128k/256k" in line, "counts unscaled by the SESSION's factor, declared window: $line")
        assertEquals(400_000L, windows.windowFor("s-old"), "learned from the post")
    }

    @Test
    fun `a 1m row's post teaches nothing about the env`() {
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "grok", catalog = grok, clientWindows = windows)
        render(renderer, oldSessionBlob("grok-4.6[1m]", 1_000_000))
        assertNull(windows.windowFor("s-old"), "a [1m] id is always 1e6 whatever the env")
    }

    @Test
    fun `the next render follows a live row window edit`() {
        val boot = grok.copy(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-6.1-sol", contextWindow = 272_000)),
            pinnedModel = "gpt-6.1-sol",
            compactionReserveDefaults = CompactionReserveDefaults { _, _ -> SOL_6_1_RESERVE }, // core's calibrated row
        )
        var current = boot
        val renderer = StatuslineRenderer(label = "codex", catalog = boot.copy(liveWindows = LiveWindows { current }))
        val stdin = oldSessionBlob("gpt-6.1-sol", 872_000)
        assertTrue("/272k" in render(renderer, stdin))
        current = boot.copy(models = listOf(ModelEntry("gpt-6.1-sol", contextWindow = 400_000)))
        assertTrue("/400k" in render(renderer, stdin))
    }

    @Test
    fun `a running Claude session teaches its divisor and renders the declared million`() {
        val claude = ModelCatalog(
            discoveryPrefix = "claude-splice--",
            models = listOf(ModelEntry(id = "claude-opus-5-5", contextWindow = 1_000_000)),
            defaultContextWindow = 1_000_000,
            pinnedModel = "claude-opus-5-5",
        )
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "Claude", catalog = claude, clientWindows = windows)
        val line = render(renderer, oldSessionBlob("claude-opus-5-5", 200_000, 20_000, 80_000))
        assertEquals(200_000L, windows.windowFor("s-old"), "the posted divisor, not the launch env")
        assertTrue("500k/1000k" in line, "reported 100k/200k at scale 0.2 means real 500k/1M: $line")
    }

    @Test
    fun `an open Claude head learns the divisor while presented and 1m rows do not`() {
        val open = ModelCatalog(
            discoveryPrefix = "claude-splice--",
            models = emptyList(),
            defaultContextWindow = 1_000_000,
            open = true,
        )
        val windows = ClientWindows()
        val renderer = StatuslineRenderer(label = "Claude", catalog = open, clientWindows = windows)
        render(renderer, oldSessionBlob("claude-opus-5-5", 200_000))
        assertEquals(200_000L, windows.windowFor("s-old"))
        val hints = ClientWindows()
        val hinted = StatuslineRenderer(label = "Claude", catalog = open, clientWindows = hints)
        render(hinted, oldSessionBlob("claude-opus-5-5[1m]", 1_000_000))
        assertNull(hints.windowFor("s-old"), "a [1m] spelling has a client-owned divisor")

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
        render(presentedRenderer, oldSessionBlob("claude-sonnet-5", 200_000))
        assertNull(presentedWindows.windowFor("s-old"), "a presented row uses the client's model table")
    }
}
