// Server mode (docs/specs/server-mode.md, F9, the laptop's half): what a launch of a command a team's server serves
// hands Claude Code. Every one names this laptop's splice in x-splice-client beside the shell's own headers, and a
// command on no team server names none.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.TranscriptModelRewrite
import splice.client.resume.originals.TranscriptOriginals
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchRecipe
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launch
import java.nio.file.Path

private const val CLIENT = "x-splice-client"

class LaunchTeamServerTest(@param:TempDir private val tmp: Path) {
    private val shell = mapOf("ANTHROPIC_CUSTOM_HEADERS" to "x-team-tag: blue")

    private val service = LaunchService(
        ClaudeConfigMaterializer(tmp),
        envReader = EnvReader { shell[it] },
        resumeAcrossHeads = ResumeAcrossHeads(
            TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = tmp.resolve("state")))),
        ),
    )

    @Test
    fun `every launch of a server's command names this laptop's splice beside the shell's own headers`() {
        val lines = headerLines(launch(spec(teamServer = true)))
        assertTrue("x-team-tag: blue" in lines)
        assertEquals(listOf("x-splice-client: $GATEWAY_VERSION"), lines.filter { it.substringBefore(':') == CLIENT })
    }

    @Test
    fun `a command on no team server sends no version`() {
        val lines = headerLines(launch(spec(teamServer = false)))
        assertEquals(emptyList<String>(), lines.filter { it.substringBefore(':') == CLIENT })
    }

    private fun launch(spec: LaunchSpec): LaunchRecipe =
        service.launch(spec, extraArgs = emptyList(), dangerouslySkipPermissions = false)

    private fun headerLines(recipe: LaunchRecipe): List<String> =
        recipe.env["ANTHROPIC_CUSTOM_HEADERS"].orEmpty().lines().map(String::trim).filter(String::isNotEmpty)

    private fun spec(teamServer: Boolean) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-team")),
        models = LaunchModels(
            pinnedModel = "team-model",
            availableModelIds = listOf("team-model"),
            modelLabels = mapOf("team-model" to "team-model"),
            contextWindow = 200_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "splice login", signInLabel = "the team server"),
        gateway = LaunchGateway(
            statuslineCommand = "curl -s https://gateway.team.example/team/statusline",
            port = 0,
            bearer = GatewayBearer.Helper("cat /run/team.key"),
            apiTimeoutMs = 960_000,
            remote = "https://gateway.team.example/team",
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        teamServer = teamServer,
    )
}
