// NEW: V4-36 redo (2026-09-17) — the doctor row for `system_prompt_mode = "replace"`. The operator
// amendment of 2026-09-15 binds this row to say the consequence plainly rather than let an operator
// discover it: replace substitutes the client's whole system field, and Claude Code ships its entire
// operating instruction set in that field.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.topology.TopologyLoader
import java.nio.file.Paths

class DoctorSystemPromptCheckTest {

    @Test
    fun `a replace-mode head warns that Claude Code's instruction set is stripped`() {
        val row = promptRows(head("one", "\"stay terse\"", "\"replace\"")).single()
        assertEquals("system-prompt:one", row.name)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("system_prompt_mode = \"replace\""), row.detail)
        assertTrue(row.detail.contains("entire operating instruction set"), row.detail)
        assertTrue(row.detail.contains("bare model with tools attached"), row.detail)
        assertTrue(requireNotNull(row.fix).contains("append"), row.fix.orEmpty())
    }

    /** V4-171: strip edits the client's field too, so it gets the same row — the repo ships no
     *  pattern list; the stance lives in this warning. */
    @Test
    fun `a strip-mode head warns that the client field is edited and what is removed is the operator's`() {
        val row = promptRows(head("one", "\"^# Context management\"", "\"strip\"")).single()
        assertEquals("system-prompt:one", row.name)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("system_prompt_mode = \"strip\""), row.detail)
        assertTrue(row.detail.contains("yours to own"), row.detail)
        // V4-172: NOT the replace remedy — "use append instead" would ship the regex list upstream
        // as prompt text.
        val fix = requireNotNull(row.fix)
        assertTrue(fix.contains("regexes, never prompt text"), fix)
        assertTrue(fix.contains("remove the layer"), fix)
    }

    /** V4-172: a mode that edits the client's field but names no text resolves to null and never
     *  reaches a seam. The row above fires only on a head that carries a prompt, so without this one
     *  the operator's belief that stripping happens is never contradicted. */
    @Test
    fun `strip mode with no pattern list warns that the client field is not edited at all`() {
        val row = promptRows(head("one", null, "\"strip\"")).single()

        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("with no system_prompt or system_prompt_file"), row.detail)
        assertTrue(row.detail.contains("not edited at all"), row.detail)
    }

    @Test
    fun `a replace-mode head named by file warns too`() {
        val toml = head("one", null, "\"replace\"", file = "\"~/prompts/one.md\"")
        assertEquals(listOf("system-prompt:one"), promptRows(toml).map { it.name })
    }

    @Test
    fun `append mode is silent`() {
        assertEquals(emptyList<String>(), promptRows(head("one", "\"stay terse\"", "\"append\"")).map { it.name })
    }

    @Test
    fun `no declared mode is silent — append is the default`() {
        assertEquals(emptyList<String>(), promptRows(head("one", "\"stay terse\"", null)).map { it.name })
    }

    @Test
    fun `replace with no prompt is silent — nothing reaches the wire`() {
        assertEquals(emptyList<String>(), promptRows(head("one", null, "\"replace\"")).map { it.name })
    }

    @Test
    fun `only the replace head warns when two heads share a provider`() {
        val toml = head("one", "\"stay terse\"", "\"replace\"") +
            head("two", "\"stay terse\"", "\"append\"", port = 3902, provider = false)
        assertEquals(listOf("system-prompt:one"), promptRows(toml).map { it.name })
    }

    /** Only the system-prompt rows: the section always also emits its topology summary. */
    private fun promptRows(toml: String) = DoctorTestPorts.configChecks()
        .configurationChecks(DoctorTopology.Parsed(TopologyLoader.parse(toml)), Paths.get("/tmp/splice.toml"))
        .filter { it.name.startsWith("system-prompt:") }

    private fun head(
        key: String,
        prompt: String?,
        mode: String?,
        file: String? = null,
        port: Int = 3901,
        provider: Boolean = true,
    ): String = buildString {
        if (provider) {
            append(
                """
                [providers.cloud]
                dialect = "openai-chat"
                base_url = "https://openrouter.ai/api/v1"
                auth = { kind = "api-key", env = "K" }
                [[providers.cloud.models]]
                id = "m1"
                context_window = 8192

                """.trimIndent() + "\n",
            )
        }
        append("[heads.$key]\n")
        append("provider = \"cloud\"\n")
        append("port = $port\n")
        append("discovery_prefix = \"claude-$key--\"\n")
        append("pinned_model = \"m1\"\n")
        if (prompt != null) append("system_prompt = $prompt\n")
        if (file != null) append("system_prompt_file = $file\n")
        if (mode != null) append("system_prompt_mode = $mode\n")
        append("\n")
    }
}
