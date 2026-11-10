// NEW: the client-side retry limits a launch plants. Beside LaunchServiceTest's "is planted" tests, which sit at the
// size wall (detekt LargeClass 400), so this one is its own class, as ClientPickedLaunchTest is.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launch
import java.nio.file.Path

class LaunchRetryEnvTest(@param:TempDir private val tmp: Path) {
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(forwardClientAuth: Boolean) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-retry")),
        models = LaunchModels(
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol"),
            modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol"),
            contextWindow = 272_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "claudex login", signInLabel = "Codex (ChatGPT)"),
        gateway = LaunchGateway(
            statuslineCommand = "synthetic-status",
            port = 3099,
            bearer = GatewayBearer.Planted("synthetic-token"),
            apiTimeoutMs = 960_000,
            forwardClientAuth = forwardClientAuth,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    // Nov 8-9: a subagent re-sent one request 228 times over 25 hours through splice, because each non-streaming
    // attempt hit the client's 360 s timeout and the client's limit for those retries is unlimited while this is
    // unset. 2 is the limit the client itself applies when its retry watchdog judges a failed stream outlasted the
    // timeout. Planted for EVERY head, the native one (forwardClientAuth) included, as CLAUDE_CODE_RETRY_WATCHDOG is.
    @Test
    fun `CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES is planted for a foreign and the native head`() {
        for (native in listOf(false, true)) {
            val env = service.launch(spec(native), emptyList(), dangerouslySkipPermissions = false).env
            assertEquals("2", env["CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES"], "forwardClientAuth=$native")
        }
    }
}
