// NEW: V4-35 — DeepSeek over its Anthropic-format endpoint. The failure this profile can cause is
// a head that refuses to BOOT, and nothing in the profile source reveals it on inspection, so the
// tests build the head rather than reading the fields back.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class DeepSeekProfileTest {
    private val profile = requireNotNull(AddProfiles().find("deepseek")) { "deepseek profile missing" }

    @Test
    fun `the emitted topology actually loads`(@TempDir dir: Path) {
        // Slots are flat-mapped to one row per slot, so two slots on one id emit two rows with the
        // same id and modelsFor throws "head model list contains duplicates". A profile can look
        // perfectly reasonable in source and still produce a head that cannot boot.
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "deepseek", PORT))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["deepseek"]) { "deepseek head absent after load" }
        assertEquals("claude-deepseek", head.claude.command)
        // catalogFor is where modelsFor runs, and modelsFor is where the duplicate-id and
        // unknown-slot rules live. Loading alone does NOT reach them — measured, not assumed: a
        // mutation giving one id two slots left this test green until this line existed. Parsing a
        // topology and BUILDING a head from it are different claims and only the second is the one
        // an operator experiences at daemon boot.
        val provider = requireNotNull(topology.providers["deepseek"]) { "deepseek provider absent" }
        val catalog = provider.catalogFor(head)
        assertEquals(2, catalog.models.size, "both declared rows must resolve")
        assertEquals("deepseek-flash", catalog.pinnedModel)
    }

    @Test
    fun `the allowlist drops every block DeepSeek reject, redacted_thinking above all`() {
        val quirks = profile.provider.extra.single { it.startsWith("block_allowlist") }
        // DR-118 forwards redacted_thinking verbatim because Anthropic requires it back unchanged.
        // On this endpoint that block is rejected, so it must not survive into the request.
        for (rejected in REJECTED) {
            assertFalse(rejected in quirks, "DeepSeek reject '$rejected' — it must not be allowlisted: $quirks")
        }
        for (supported in SUPPORTED) {
            assertTrue(supported in quirks, "'$supported' is supported and must ride: $quirks")
        }
    }
}

private const val PORT = 3107

/** Absent from the endpoint's own accepted-variant list, so each of these 400s the request. */
private val REJECTED = listOf(
    "redacted_thinking",
    "search_result",
    "mcp_tool_use",
    "mcp_tool_result",
    "container_upload",
    "code_execution_tool_result",
)

/** All nine the deserializer names. image and document are load-bearing: they were dropped from
 *  every request until 2026-09-16, which is multimodal input and every attached file silently
 *  going nowhere, so they are pinned here by name rather than left to the list's length. */
private val SUPPORTED = listOf(
    "text",
    "tool_reference",
    "image",
    "document",
    "server_tool_use",
    "tool_use",
    "tool_result",
    "web_search_tool_result",
    "thinking",
)
private val DAEMON_BLOCK = """
    [daemon]
    control_port = 3096
""".trimIndent() + "\n"
