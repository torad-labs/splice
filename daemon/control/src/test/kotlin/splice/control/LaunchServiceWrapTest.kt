// NEW: V4-129 — LaunchService's two new seams. (1) argv[0] must plant the real absolute claude
// binary instead of the bare string whenever wrap is active — the self-exec hazard is not specific
// to the wrapped head, it is EVERY head's launch, because they all share the one LaunchService
// instance and the one bare "claude" default. (2) the splice-owned Claude head's selected login
// materializes into its OWN config dir at launch, gated on forwardClientAuth (never a hardcoded
// head-key string) so every other head stays a no-op.
package splice.control

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudeLogins
import splice.client.ClaudePolicy
import splice.client.wrap.WrapStateRead
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LaunchServiceWrapTest {

    private val tmp = Files.createTempDirectory("launch-service-wrap-test")

    private fun spec(head: String, forwardClientAuth: Boolean = false) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-$head")),
        pinnedModel = "m1",
        availableModelIds = listOf("m1"),
        modelLabels = mapOf("m1" to "m1"),
        contextWindow = 200_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claude-splice login",
        signInLabel = "Claude",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3104,
        inferenceToken = "test-token",
        apiTimeoutMs = 960_000,
        forwardClientAuth = forwardClientAuth,
        headKey = head,
    )

    @Test
    fun `argv plants bare claude when no wrap state is wired - byte-identical default`() {
        val service = LaunchService(ClaudeConfigMaterializer(tmp))
        val argv = service.launch(spec("claudex"), emptyList(), dangerouslySkipPermissions = false).argv
        assertEquals("claude", argv.first())
    }

    @Test
    fun `argv plants the real absolute binary for EVERY head once wrap is active, not only the wrapped one`() {
        val realBinary = "/home/op/.local/share/claude/versions/2.1.278"
        val service = LaunchService(
            ClaudeConfigMaterializer(tmp),
            wrapState = WrapStateRead { realBinary },
        )
        val argv = service.launch(spec("claudex"), emptyList(), dangerouslySkipPermissions = false).argv
        assertEquals(
            realBinary,
            argv.first(),
            "claudex has nothing to do with wrap, but it shares the one bare-'claude' default that would " +
                "otherwise resolve straight back to the shim occupying 'claude' on PATH",
        )
    }

    @Test
    fun `the selected Claude login materializes into the client-auth head's own config dir at launch`() {
        val logins = ClaudeLogins(storeDir = tmp.resolve("claude-logins"))
        val source = tmp.resolve("source-session").createDirectories()
        source.resolve(".credentials.json").writeText("""{"accessToken":"the-selected-one"}""")
        logins.store("work", source)
        logins.select("work")

        val service = LaunchService(ClaudeConfigMaterializer(tmp), claudeLogins = logins)
        service.launch(spec("claude-splice", forwardClientAuth = true), emptyList(), dangerouslySkipPermissions = false)

        val materialized = tmp.resolve(".claude-claude-splice/.credentials.json")
        assertTrue(materialized.exists())
        assertEquals("""{"accessToken":"the-selected-one"}""", materialized.readText())
    }

    @Test
    fun `a head that forwards no client auth never gets a login materialized`() {
        val logins = ClaudeLogins(storeDir = tmp.resolve("claude-logins-2"))
        val source = tmp.resolve("source-session-2").createDirectories()
        source.resolve(".credentials.json").writeText("""{"accessToken":"should-not-leak"}""")
        logins.store("work", source)
        logins.select("work")

        val service = LaunchService(ClaudeConfigMaterializer(tmp), claudeLogins = logins)
        service.launch(spec("claudex", forwardClientAuth = false), emptyList(), dangerouslySkipPermissions = false)

        assertFalse(
            tmp.resolve(".claude-claudex/.credentials.json").exists(),
            "a foreign-vendor head must never receive Claude Code's own login file",
        )
    }
}
