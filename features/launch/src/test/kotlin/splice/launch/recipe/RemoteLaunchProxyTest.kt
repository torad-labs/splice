// LAN share (docs/specs/lan-share.md): a command another machine serves sends its turns, and its statusline's curl, to
// that machine's address, which a proxy the shell names can't reach. The launch keeps that host out of the proxy, as it
// keeps loopback out for a head on this machine.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launch
import java.nio.file.Path

class RemoteLaunchProxyTest(@param:TempDir private val tmp: Path) {
    private fun remote(address: String) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-claudex")),
        models = LaunchModels(
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol"),
            modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol"),
            contextWindow = 272000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "claudex login", signInLabel = "Codex (ChatGPT) on torad-server"),
        gateway = LaunchGateway(
            port = 0,
            inferenceToken = "splice-lan",
            apiTimeoutMs = 960_000,
            statuslineCommand = "curl -sS --data-binary @- '$address/statusline'",
            remote = address,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    @Test
    fun `a command another machine serves reaches that machine around any proxy`() {
        val merged = LaunchService(
            ClaudeConfigMaterializer(tmp),
            envReader = { name -> if (name == "HTTPS_PROXY") "http://proxy.corp:8080" else null },
        ).launch(remote("http://torad-server.lan:3097/claudex/@work"), emptyList(), dangerouslySkipPermissions = false)
            .env
            .getValue("NO_PROXY")
        assertTrue("torad-server.lan" in merged.split(','), merged)
    }
}
