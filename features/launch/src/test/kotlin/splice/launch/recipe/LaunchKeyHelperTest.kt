// Key rotation (Oct 26, 2026): how a launched session holds the bearer its head checks on every turn. Claude Code reads
// ANTHROPIC_AUTH_TOKEN once at start, so a rotated management key stranded every session that held its turn key there.
// A gateway head's session reads it through the apiKeyHelper its settings carry instead, which Claude Code runs again
// after a 401 before it resends the turn on the same head (read in the 2.1.310 binary).
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
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.TranscriptModelRewrite
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchRecipe
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launch
import java.nio.file.Files
import java.nio.file.Path

// why: the launch only writes the command; Claude Code is what runs it, so any command will do here
private const val HELPER = "sed -n 's/^Authorization: Bearer //p' /run/turn-auth-header"

class LaunchKeyHelperTest(@param:TempDir private val tmp: Path) {

    private val service = LaunchService(
        ClaudeConfigMaterializer(tmp),
        resumeAcrossHeads = ResumeAcrossHeads(
            TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = tmp.resolve("state")))),
        ),
    )

    @Test
    fun `a gateway head's session reads its turn key through the key helper and holds none planted`() {
        val recipe = launch(spec("codex"))
        assertNull(recipe.env["ANTHROPIC_AUTH_TOKEN"], "a planted token is read once and never again")
        assertTrue("ANTHROPIC_AUTH_TOKEN" in recipe.unset, "one an outer session passes down would shadow the helper")
        assertEquals(HELPER, helperOf("codex"))
    }

    // LAN share: a command another machine serves is reached with the share's own word, which no rotation here
    // changes, so it is still planted, and the session gets no key helper.
    @Test
    fun `a planted bearer is planted as before, and no key helper is written`() {
        val recipe = launch(spec("lan", bearer = GatewayBearer.Planted("splice-lan")))
        assertEquals("splice-lan", recipe.env["ANTHROPIC_AUTH_TOKEN"])
        assertFalse("ANTHROPIC_AUTH_TOKEN" in recipe.unset, "the planted token is the session's credential")
        assertNull(helperOf("lan"))
    }

    // A native head forwards the client's own login: a key helper would replace it, exactly as a planted bearer
    // would, and the client's own variables stay as they are.
    @Test
    fun `a native-auth head gets no key helper`() {
        val recipe = launch(spec("claude-splice", forwardClientAuth = true))
        assertNull(helperOf("claude-splice"), "the client's own login answers this head")
        assertNull(recipe.env["ANTHROPIC_AUTH_TOKEN"])
        assertFalse("ANTHROPIC_AUTH_TOKEN" in recipe.unset)
    }

    private fun launch(spec: LaunchSpec): LaunchRecipe =
        service.launch(spec, extraArgs = emptyList(), dangerouslySkipPermissions = false)

    private fun helperOf(head: String): String? {
        val settings = Json.parseToJsonElement(Files.readString(tmp.resolve(".claude-$head/settings.json"))).jsonObject
        return settings["apiKeyHelper"]?.jsonPrimitive?.content
    }

    private fun spec(
        head: String,
        bearer: GatewayBearer = GatewayBearer.Helper(HELPER),
        forwardClientAuth: Boolean = false,
    ) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-$head")),
        models = LaunchModels(
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol"),
            modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol"),
            contextWindow = 272000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "claudex login", signInLabel = "Codex (ChatGPT)"),
        gateway = LaunchGateway(
            statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
            port = 3099,
            bearer = bearer,
            apiTimeoutMs = 960_000,
            forwardClientAuth = forwardClientAuth,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )
}
