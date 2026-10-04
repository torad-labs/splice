// NEW: V4-283 — the launch hands its cwd and the other heads' config dirs to the materializer, which
// carries the folder-trust records the operator granted into the launching head. claudex -r after a
// claude-splice session in the same folder met Claude Code's "Quick safety check" because the record
// lived only in claude-splice's .claude.json (take-resume-3, 2026-09-26). The fixture home is a temp dir.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import java.nio.file.Files
import java.nio.file.Path

class LaunchFolderTrustTest {
    @TempDir
    lateinit var home: Path

    private fun claudex(siblings: List<Path>) = LaunchSpec(
        trees = HeadTrees(home.resolve(".claude-claudex"), siblings),
        pinnedModel = "gpt-6-sol",
        availableModelIds = listOf("gpt-6-sol"),
        modelLabels = mapOf("gpt-6-sol" to "Codex 6 Sol"),
        contextWindow = 272000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claudex login",
        signInLabel = "Codex (ChatGPT)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3099,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
    )

    @Test
    fun `a launch carries another head's folder trust for its cwd`() {
        val take = Files.createDirectories(home.resolve("tally-resume-3")).toRealPath()
        val spliceHead = Files.createDirectories(home.resolve(".claude-claude-splice"))
        Files.writeString(
            spliceHead.resolve(".claude.json"),
            """{"projects":{"$take":{"hasTrustDialogAccepted":true}}}""",
        )

        val service = LaunchService(ClaudeConfigMaterializer(home))
        val spec = claudex(listOf(spliceHead))
        service.launch(spec, listOf("-r"), dangerouslySkipPermissions = false, cwd = take.toString())

        val state = Json.parseToJsonElement(Files.readString(home.resolve(".claude-claudex/.claude.json"))).jsonObject
        val record = state["projects"]?.jsonObject?.get(take.toString())?.jsonObject
        assertEquals("true", record?.get("hasTrustDialogAccepted")?.jsonPrimitive?.content, "no safety check: $state")
    }
}
