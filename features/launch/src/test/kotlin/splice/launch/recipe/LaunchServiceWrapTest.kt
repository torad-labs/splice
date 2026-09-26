// NEW: V4-129 — LaunchService's two new seams. (1) argv[0] must plant the real absolute claude
// binary instead of the bare string whenever wrap is active — the self-exec hazard is not specific
// to the wrapped head, it is EVERY head's launch, because they all share the one LaunchService
// instance and the one bare "claude" default. (2) V4-276 (V4-237, V4-250): a launch never writes a
// head's .credentials.json. V4-129 materialized the selected stored login here, and the rotated
// refresh token it put back got the login revoked; the pins below keep the live login byte for byte.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.wrap.WrapState
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
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

    // V4-250's verify line: the head's live login is newer than any stored copy, and a launch keeps it.
    @Test
    fun `a launch keeps the client-auth head's live login byte for byte - V4-250`() {
        val live = tmp.resolve(".claude-claude-splice").createDirectories().resolve(".credentials.json")
        live.writeText("max-gen2-newer")

        val service = LaunchService(ClaudeConfigMaterializer(tmp))
        service.launch(spec("claude-splice", forwardClientAuth = true), emptyList(), dangerouslySkipPermissions = false)

        assertEquals("max-gen2-newer", live.readText())
    }

    /** V4-129 review. A launch THROUGH the wrapped `claude` runs the client-auth claude-splice head
     *  over the operator's own ~/.claude — whose credential IS the operator's login. FEATURES.md 4.5
     *  says the selected Claude login is materialized "never on a wrapped default head": writing it
     *  there would overwrite the operator's real login with a splice-stored one. */
    @Test
    fun `a launch through the wrapped claude runs over the vanilla dir and never writes a login into it`() {
        val home = tmp.resolve("wrapped-home").createDirectories()
        val stateStore = WrapStateStore(file = tmp.resolve("wrapped-state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/2.1.281", "/opt/claude/2.1.281", "/share/splice-launch", "", "", 0L))
        val materializer = ClaudeConfigMaterializer(home)
        val service = LaunchService(
            materializer,
            wrap = WrappedHead(home, stateStore = stateStore, materializer = materializer),
        )
        val through = service.wrap.launchThrough("claude") ?: error("a wrap state is present: claude must resolve")

        val recipe = service.launch(
            spec("claude-splice", forwardClientAuth = true),
            emptyList(),
            dangerouslySkipPermissions = false,
            wrapped = through,
        )

        assertEquals(home.resolve(".claude").toString(), recipe.env["CLAUDE_CONFIG_DIR"])
        assertEquals("/opt/claude/2.1.281", recipe.argv.first())
        assertTrue(
            home.resolve(".claude/settings.json").exists(),
            "the vanilla dir takes wrap's narrow materialization",
        )
        assertFalse(
            home.resolve(".claude/.credentials.json").exists(),
            "the operator's own ~/.claude login must never be overwritten by a stored splice login",
        )
        assertEquals(null, service.wrap.launchThrough("claudex"), "only the wrapped command resolves through wrap")
    }

    @Test
    fun `a head that forwards no client auth never gets a login written`() {
        val service = LaunchService(ClaudeConfigMaterializer(tmp))
        service.launch(spec("claudex", forwardClientAuth = false), emptyList(), dangerouslySkipPermissions = false)

        assertFalse(
            tmp.resolve(".claude-claudex/.credentials.json").exists(),
            "a foreign-vendor head must never receive Claude Code's own login file",
        )
    }
}
