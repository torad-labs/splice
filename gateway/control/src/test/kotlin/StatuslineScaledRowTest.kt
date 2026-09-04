// NEW: the statusline on a SCALED row. Claude Code fixes its context window per process (the
// pinned row's) and splice scales the counts it reports so another row compacts at its own window,
// which left the bar reading the client's window and the scaled counts however the operator
// switched: picking grok-4.6[500k] on a 256k grok head kept "…/256k" on screen (operator report,
// 2026-09-02). With the head's catalog the renderer shows the picked row's label, its declared
// window and the real counts; a row that agrees with the client, and a head with no catalog, render
// the blob exactly as before.
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.StatuslineRenderer
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

    // What Claude Code pipes on the 500k row of a 256k session: its window is still the process's
    // 256000, and the counts are the ones splice scaled by 256000/500000 = 0.512 (real used: 250k).
    private fun blob(id: String, display: String) = """
        {"model":{"id":"$id","display_name":"$display"},
         "context_window":{"context_window_size":256000,"used_percentage":50,
           "current_usage":{"input_tokens":28000,"cache_read_input_tokens":100000,"cache_creation_input_tokens":0}}}
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
    fun `the pinned row and a catalog-less head render the blob as sent`() {
        val pinned = render(StatuslineRenderer(label = "grok", catalog = grok), blob("grok-4.6", "grok-4.6"))
        assertTrue("Grok 4.6" in pinned && "128k/256k" in pinned, "scale 1.0 touches nothing but the label: $pinned")
        val bare = render(StatuslineRenderer(label = "grok"), blob("grok-4.6[500k]", "grok-4.6[500k]"))
        assertTrue("grok-4.6[500k]" in bare && "128k/256k" in bare, "no catalog, no repair: $bare")
    }
}
