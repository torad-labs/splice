// A head's own launch and the servers the daemon answers itself (an mcp mode that is on): the launch names the mode's
// file, and a launch that passes `--strict-mcp-config`, which asks for the servers its own `--mcp-config` names and no
// others, is handed none of them.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.client.mcp.OwnMcpLaunch
import splice.core.util.LogSink
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchOutcome
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "acme"

class OwnMcpHeadLaunchTest(@param:TempDir private val tmp: Path) {
    private val state: Path = Files.createDirectories(tmp.resolve("state"))

    private val service = LaunchService(ClaudeConfigMaterializer(tmp)).also {
        val sharing =
            McpSharing(true, emptySet(), "http://127.0.0.1:3096/mcp/", { "k-mcp" }, DirectoryProbe { false })
        it.ownMcp = OwnMcpLaunch({ _, _ -> listOf("visual") }, sharing, state, LogSink { })
    }

    private val spec = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-acme")),
        models = LaunchModels(
            pinnedModel = "m",
            availableModelIds = listOf("m"),
            modelLabels = mapOf("m" to "M"),
            contextWindow = 200_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "$HEAD login", signInLabel = "Acme", headKey = HEAD),
        gateway = LaunchGateway(
            statuslineCommand = "\"/bin/curl\" -s :3096/statusline/$HEAD",
            port = 3102,
            bearer = GatewayBearer.Planted("test-token"),
            apiTimeoutMs = 960_000,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    /** The files of the daemon's own servers among the argv a launch of the head with [words] is handed. */
    private fun modeFiles(vararg words: String): List<String> {
        val outcome = service.launchOutcome(spec, words.toList(), false, caller = LaunchCaller(cwd = tmp.toString()))
        val argv = (outcome as? LaunchOutcome.Ready)?.recipe?.argv ?: error("the launch was refused: $outcome")
        return argv.filter { it == "--mcp-config=${state.resolve("own-mcp-$HEAD-head.json")}" }
    }

    @Test
    fun `a head's own launch with an mcp mode on names the mode's file`() {
        assertEquals(1, modeFiles("-n", "probe").size)
    }

    @Test
    fun `a head's own launch that passes --strict-mcp-config is handed no mode's server`() {
        assertEquals(emptyList<String>(), modeFiles("--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}"))
    }
}
