// NEW: #791 §5 (Nov 23, 2026) — with heads gone, a team's launcher shows its own status line through the launch's
// `--settings`. The overlay splice hands Claude Code on the door now carries an `env` of its own (§3), and a caller's
// `statusLine` must come through the merge untouched beside it, as must every other key of theirs.
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

// why: a terminal key's shape, recognisable wherever it would leak
private const val KEY = "splice-terminal-secret-key-0123456789"

class DoorOverlayMergeTest(@param:TempDir private val tmp: Path) {
    private val session = DoorSession("http://127.0.0.1:3096/door", KEY, 400_000L, "claude-gpt-5.6-sol")
    private val status = """{"type":"command","command":"acme-ai statusline","padding":0}"""

    private fun merged(args: List<String>, held: Map<String, String> = mapOf("API_TIMEOUT_MS" to "605760000")) =
        DoorOverlayMerge(session.overlay(marked = false, markKey = "spliceLaunch"), tmp.toString(), held).merge(args)

    private fun overlayOf(result: DoorOverlayMerge.Merged): JsonObject =
        Json.parseToJsonElement((result as DoorOverlayMerge.Merged.Ready).overlay).jsonObject

    private fun envOf(overlay: JsonObject): Map<String, String> =
        overlay.getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test
    fun `a caller's inline statusLine is kept beside splice's env`() {
        val overlay = overlayOf(merged(listOf("--settings", """{"statusLine":$status}""")))
        assertEquals(Json.parseToJsonElement(status), overlay["statusLine"])
        assertEquals("605760000", envOf(overlay)["API_TIMEOUT_MS"])
        assertEquals("claude-gpt-5.6-sol", envOf(overlay)["ANTHROPIC_MODEL"])
    }

    @Test
    fun `a caller's statusLine from a settings file is kept, with the file's own env beside splice's`() {
        val file = tmp.resolve("wrapper.json")
        file.writeText("""{"statusLine":$status,"env":{"OTEL_SERVICE_NAME":"acme","ANTHROPIC_MODEL":"elsewhere"}}""")
        val overlay = overlayOf(merged(listOf("--settings", file.toString())))
        assertEquals(Json.parseToJsonElement(status), overlay["statusLine"])
        val env = envOf(overlay)
        assertEquals("acme", env["OTEL_SERVICE_NAME"])
        assertEquals("claude-gpt-5.6-sol", env["ANTHROPIC_MODEL"], "the starting model stays splice's")
    }

    @Test
    fun `a later --settings with no statusLine leaves an earlier one in place`() {
        val args = listOf("--settings", """{"statusLine":$status}""", "--settings", """{"env":{"FOO":"bar"}}""")
        val overlay = overlayOf(merged(args))
        assertEquals(Json.parseToJsonElement(status), overlay["statusLine"])
        assertEquals("bar", envOf(overlay)["FOO"])
    }

    @Test
    fun `a caller env that is not an object cannot drop splice's env`() {
        val overlay = overlayOf(merged(listOf("--settings", """{"env":"nope","statusLine":$status}""")))
        assertEquals("605760000", envOf(overlay)["API_TIMEOUT_MS"])
        assertEquals("0", envOf(overlay)["CLAUDE_CODE_USE_BEDROCK"])
        assertEquals(Json.parseToJsonElement(status), overlay["statusLine"])
    }

    @Test
    fun `the overlay never carries the key, and a caller's token is taken out`() {
        val result = merged(listOf("--settings", """{"env":{"ANTHROPIC_AUTH_TOKEN":"wrapper-token"}}"""))
        val text = (result as DoorOverlayMerge.Merged.Ready).overlay
        assertFalse(KEY in text)
        assertFalse("ANTHROPIC_AUTH_TOKEN" in envOf(overlayOf(result)))
        assertEquals(KEY, session.env()["ANTHROPIC_AUTH_TOKEN"], "the key rides the process environment")
    }

    @Test
    fun `a caller's custom headers leave the overlay, where they would outrank the wall, and come back apart`() {
        val inline = """{"env":{"ANTHROPIC_CUSTOM_HEADERS":"x-splice-wrapper: acme-ai/1.4.0","FOO":"bar"}}"""
        val result = merged(listOf("--settings", inline)) as DoorOverlayMerge.Merged.Ready
        val env = envOf(overlayOf(result))
        assertFalse("ANTHROPIC_CUSTOM_HEADERS" in env)
        assertEquals("bar", env["FOO"])
        assertEquals("x-splice-wrapper: acme-ai/1.4.0", result.headers)
    }
}
