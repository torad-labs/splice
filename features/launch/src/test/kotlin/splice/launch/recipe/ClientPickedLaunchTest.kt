// NEW: forwarded client login owns its model picker; topology pins only select a launch default.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import java.nio.file.Files
import java.nio.file.Path

class ClientPickedLaunchTest(@param:TempDir private val tmp: Path) {
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))
    private val configDir: Path get() = tmp.resolve(".claude-synthetic")

    private fun spec(pinned: String = "") = LaunchSpec(
        trees = HeadTrees(configDir),
        pinnedModel = pinned,
        availableModelIds = listOf("synthetic-metadata-row"),
        modelLabels = mapOf("synthetic-metadata-row" to "Synthetic metadata"),
        contextWindow = 272_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "synthetic-status",
        loginCommand = "",
        signInLabel = "Synthetic",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 0,
        inferenceToken = "synthetic-token",
        apiTimeoutMs = 1_000,
        forwardClientAuth = true,
    )

    @Test
    fun `an unpinned login head keeps the client choice and writes no roster or window`() {
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("settings.json"), """{"model":"claude-synthetic-chosen"}""")
        Files.writeString(configDir.resolve(".claude.json"), """{"additionalModelOptionsCache":["stale"]}""")
        val recipe = service.launch(
            spec().copy(
                tiers = ModelTiers(modelOverrides = mapOf("claude-synthetic-chosen" to "synthetic-metadata-row")),
            ),
            listOf("--model", "claude-synthetic-next"),
            dangerouslySkipPermissions = false,
        )
        val settings = Json.parseToJsonElement(Files.readString(configDir.resolve("settings.json"))).jsonObject
        val state = Json.parseToJsonElement(Files.readString(configDir.resolve(".claude.json"))).jsonObject
        assertEquals("claude-synthetic-chosen", settings["model"]?.jsonPrimitive?.content)
        assertFalse("availableModels" in settings)
        assertFalse("enforceAvailableModels" in settings)
        assertFalse("additionalModelOptionsCache" in state)
        assertFalse("modelOverrides" in settings, "provider metadata must not override a client-owned model")
        assertNull(recipe.env["ANTHROPIC_MODEL"])
        assertNull(recipe.env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertNull(recipe.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
        assertTrue(recipe.env.keys.none { it.startsWith("ANTHROPIC_DEFAULT_") })
        assertEquals(listOf("--model", "claude-synthetic-next"), recipe.argv.takeLast(2))
    }

    @Test
    fun `an explicit login-head pin selects only the launch default`() {
        val recipe = service.launch(
            spec("claude-synthetic-default"),
            emptyList(),
            dangerouslySkipPermissions = false,
        )
        assertEquals("claude-synthetic-default", recipe.env["ANTHROPIC_MODEL"])
        assertFalse("ANTHROPIC_MODEL" in recipe.unset)
        assertTrue(recipe.env.keys.none { it.startsWith("ANTHROPIC_DEFAULT_") })
        val settings = Json.parseToJsonElement(Files.readString(configDir.resolve("settings.json"))).jsonObject
        assertFalse("availableModels" in settings)
        assertFalse("enforceAvailableModels" in settings)
    }

    @Test
    fun `an unpinned login head resumes native model rows without retagging them`() {
        val project = configDir.resolve("projects/synthetic-project")
        Files.createDirectories(project)
        val transcript = project.resolve("synthetic-session.jsonl")
        val nativeRow = """{"type":"assistant","message":{"model":"claude-synthetic-next","content":[{"type":"text","text":"kept"}]}}"""
        val foreignRow = nativeRow.replace(
            "claude-synthetic-next",
            "claude-synthetic-provider--foreign-row",
        )
        Files.writeString(transcript, nativeRow + "\n" + foreignRow + "\n")
        service.launch(
            spec().copy(availableModelIds = emptyList()),
            listOf("-r", "synthetic-session"),
            dangerouslySkipPermissions = false,
        )
        assertEquals(nativeRow + "\n" + nativeRow + "\n", Files.readString(transcript))
    }
}
