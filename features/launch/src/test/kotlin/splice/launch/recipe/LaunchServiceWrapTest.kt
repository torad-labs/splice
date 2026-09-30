// NEW: V4-129 — LaunchService's two new seams. (1) argv[0] must plant the real absolute claude
// binary instead of the bare string whenever wrap is active — the self-exec hazard is not specific
// to the wrapped head, it is EVERY head's launch, because they all share the one LaunchService
// instance and the one bare "claude" default. (2) V4-276 (V4-237, V4-250): a launch never writes a
// head's .credentials.json. V4-129 materialized the selected stored login here, and the rotated
// refresh token it put back got the login revoked; the pins below keep the live login byte for byte.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.wrap.WrapState
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LaunchServiceWrapTest(@param:TempDir private val tmp: Path) {

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

    /** V4-129 review, and V4-445. A launch THROUGH the wrapped `claude` runs the client-auth claude-splice head
     *  over the operator's own state. FEATURES.md 4.5 says the selected Claude login is materialized "never on a
     *  wrapped default head", and V4-445 goes further: nothing is written into ~/.claude or ~/.claude.json at
     *  all, and CLAUDE_CONFIG_DIR stays unset, because Claude Code reads its global .claude.json (mcpServers,
     *  projects, the account) from ~/.claude.json only then. The head's settings ride a --settings overlay. */
    @Test
    fun `a launch through the wrapped claude reads the operator's own state and writes none - V4-445`() {
        val home = tmp.resolve("wrapped-home").createDirectories()
        val claudeJson = """{"mcpServers":{"ast-grep":{"command":"ast-grep"}},"projects":{"/work/app":{}}}"""
        home.resolve(".claude.json").writeText(claudeJson)
        val vanilla = home.resolve(".claude").createDirectories()
        vanilla.resolve("settings.json").writeText("""{"theme":"dark"}""")
        val stateStore = WrapStateStore(file = tmp.resolve("wrapped-state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/2.1.281", "/opt/claude/2.1.281", "/share/splice-launch", "", "", 0L))
        val service = LaunchService(
            ClaudeConfigMaterializer(home),
            wrap = WrappedHead(home, stateStore = stateStore),
        )
        val through = service.wrap.launchThrough("claude") ?: error("a wrap state is present: claude must resolve")

        val recipe = service.launch(
            spec("claude-splice", forwardClientAuth = true),
            listOf("-p"),
            dangerouslySkipPermissions = false,
            wrapped = through,
            inheritedConfigDir = tmp.resolve(".claude-claude-splice").toString(),
        )

        assertFalse("CLAUDE_CONFIG_DIR" in recipe.env, "set, Claude Code reads ~/.claude/.claude.json: ${recipe.env}")
        assertTrue(
            "CLAUDE_CONFIG_DIR" in recipe.unset,
            "a parent head's inherited config root must not hide vanilla state",
        )
        assertEquals("http://127.0.0.1:3104", recipe.env["ANTHROPIC_BASE_URL"], "it is the claude-splice head")
        assertEquals(listOf("/opt/claude/2.1.281", "--settings"), recipe.argv.take(2))
        assertEquals("-p", recipe.argv.last())
        val overlay = Json.parseToJsonElement(recipe.argv[2]).jsonObject
        assertEquals(listOf("m1"), overlay.getValue("availableModels").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, overlay.getValue("enforceAvailableModels").jsonPrimitive.boolean)
        assertEquals(
            "\"/bin/curl\" -s :3096/statusline",
            overlay.getValue("statusLine").jsonObject.getValue("command").jsonPrimitive.content,
        )
        assertEquals(claudeJson, home.resolve(".claude.json").readText(), "~/.claude.json is byte for byte")
        assertEquals("""{"theme":"dark"}""", vanilla.resolve("settings.json").readText())
        assertEquals(
            listOf("settings.json"),
            vanilla.toFile().list().orEmpty().sorted(),
            "nothing was added to ~/.claude, a .credentials.json or a .claude.json least of all",
        )
        assertEquals(null, service.wrap.launchThrough("claudex"), "only the wrapped command resolves through wrap")
    }

    @Test
    fun `a wrapped launch retains an operator's custom config root`() {
        val home = tmp.resolve("custom-home").createDirectories()
        val stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/current", "/opt/claude/current", "/share/splice-launch", "", "", 0L))
        val service = LaunchService(ClaudeConfigMaterializer(home), wrap = WrappedHead(home, stateStore = stateStore))
        val through = service.wrap.launchThrough("claude") ?: error("wrap state is present")
        val customDir = home.resolve("my-custom-config").toString()

        val recipe = service.launch(
            spec("claude-splice", forwardClientAuth = true),
            emptyList(),
            dangerouslySkipPermissions = false,
            wrapped = through,
            inheritedConfigDir = customDir,
        )

        assertFalse("CLAUDE_CONFIG_DIR" in recipe.env)
        assertFalse("CLAUDE_CONFIG_DIR" in recipe.unset, "the shim preserves the caller's $customDir")
    }

    @Test
    fun `a launch of the head itself still names its own config dir`() {
        val recipe = LaunchService(ClaudeConfigMaterializer(tmp))
            .launch(spec("claude-splice", forwardClientAuth = true), emptyList(), dangerouslySkipPermissions = false)
        assertEquals(tmp.resolve(".claude-claude-splice").toString(), recipe.env["CLAUDE_CONFIG_DIR"])
        assertFalse("--settings" in recipe.argv, "the head's settings are materialized in its own tree")
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
