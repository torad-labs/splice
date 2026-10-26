// NEW: V4-129 — LaunchService's two new seams. (1) argv[0] must plant the real absolute claude
// binary instead of the bare string whenever wrap is active — the self-exec hazard is not specific
// to the wrapped head, it is EVERY head's launch, because they all share the one LaunchService
// instance and the one bare "claude" default. (2) V4-276 (V4-237, V4-250): a launch never writes a
// head's .credentials.json. V4-129 materialized the selected stored login here, and the rotated
// refresh token it put back got the login revoked; the pins below keep the live login byte for byte.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.resume.ResumeHookTarget
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapState
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchHead
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.absentAuth
import splice.launch.launch
import splice.launch.launchHeadsOf
import splice.launch.resume.ResumeHookRoute
import splice.launch.runningHead
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// why: the shape of the id Claude Code gives a session, as the hook names it
private const val SESSION = "5e3c1a2b-0d4f-4c6e-8a9b-1c2d3e4f5a6b"

// why: on a Mac every launch carries splice's mark in a --settings of its own (LaunchMarker), since macOS hides a
// hardened client's environment from ps; elsewhere nothing is added. The OS decides, so the OS is asked here.
private val launchMark: List<String> =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
        listOf("--settings", """{"spliceLaunch":true}""")
    } else {
        emptyList()
    }

class LaunchServiceWrapTest(@param:TempDir private val tmp: Path) {

    private fun spec(head: String, forwardClientAuth: Boolean = false) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-$head")),
        models = LaunchModels(
            pinnedModel = "m1",
            availableModelIds = listOf("m1"),
            modelLabels = mapOf("m1" to "m1"),
            contextWindow = 200_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(
            loginCommand = "claude-splice login",
            signInLabel = "Claude",
            headKey = head,
        ),
        gateway = LaunchGateway(
            statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
            port = 3104,
            bearer = GatewayBearer.Helper("printf test-token"),
            apiTimeoutMs = 960_000,
            forwardClientAuth = forwardClientAuth,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
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
            wrapState = WrapStateRead { ClaudeToRun.Wrapped(realBinary) },
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
    fun `a launch keeps the client-auth head's live login byte for byte`() {
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
    fun `a launch through the wrapped claude reads the operator's own state and writes none`() {
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
            caller = LaunchCaller(
                wrapped = through,
                inheritedConfigDir = tmp.resolve(".claude-claude-splice").toString(),
            ),
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
        assertFalse("availableModels" in overlay, "the client-login picker remains the client's own")
        assertFalse("enforceAvailableModels" in overlay)
        assertEquals("m1", recipe.env["ANTHROPIC_MODEL"], "the explicit pin selects only this launch's default")
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
        assertFalse("apiKeyHelper" in overlay, "the client's own login answers a client-auth head")
    }

    // Key rotation (Oct 26): a wrapped launch materializes no settings.json, so a head that checks the turn key gets
    // its key helper in the --settings overlay, or its session would reach the head with no key at all.
    @Test
    fun `a wrapped launch of a head that checks the turn key carries the key helper in its overlay`() {
        val home = tmp.resolve("helper-home").createDirectories()
        val stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/current", "/opt/claude/current", "/share/splice-launch", "", "", 0L))
        val service = LaunchService(ClaudeConfigMaterializer(home), wrap = WrappedHead(home, stateStore = stateStore))
        val through = service.wrap.launchThrough("claude") ?: error("wrap state is present")

        val recipe = service.launch(
            spec("claude-splice", forwardClientAuth = false),
            emptyList(),
            dangerouslySkipPermissions = false,
            caller = LaunchCaller(wrapped = through),
        )

        val overlay = Json.parseToJsonElement(recipe.argv[2]).jsonObject
        assertEquals("printf test-token", overlay["apiKeyHelper"]?.jsonPrimitive?.content)
        assertFalse("ANTHROPIC_AUTH_TOKEN" in recipe.env)
        assertTrue("ANTHROPIC_AUTH_TOKEN" in recipe.unset, "an outer session's planted token would win over it")
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
            caller = LaunchCaller(wrapped = through, inheritedConfigDir = customDir),
        )

        assertFalse("CLAUDE_CONFIG_DIR" in recipe.env)
        assertFalse("CLAUDE_CONFIG_DIR" in recipe.unset, "the shim preserves the caller's $customDir")
    }

    // Round 6 of #247, finding 13. README: a bare -c stays with its head and resumes the newest session that head
    // started or resumed in the folder. In Wrap mode `claude` runs in a folder, then `claude -c` there. Claude Code
    // runs the startup hooks of the settings it reads, the launch's --settings overlay among them, and the hook route
    // records the session in the head's own index. The overlay carried no hook and -c read an index under ~/.claude
    // that nothing writes, so every wrapped `claude -c` started a new session.
    @Test
    fun `a wrapped claude -c goes on with the session a wrapped claude started in that folder`() {
        val home = tmp.resolve("continue-home").createDirectories()
        val shared = home.resolve(".claude/projects").createDirectories()
        val head = spec("claude-splice", forwardClientAuth = true)
        // the head's own launches link its transcript tree to the shared one (ProjectsLink, the default share policy)
        Files.createSymbolicLink(head.trees.own.createDirectories().resolve("projects"), shared)
        val stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/current", "/opt/claude/current", "/share/splice-launch", "", "", 0L))
        val service = LaunchService(
            ClaudeConfigMaterializer(home, resumeHook = ResumeHookTarget(3096) { home.resolve("turn-auth-header") }),
            wrap = WrappedHead(home, stateStore = stateStore),
        )
        val caller = LaunchCaller(
            cwd = home.resolve("work/app").createDirectories().toString(),
            wrapped = service.wrap.launchThrough("claude") ?: error("wrap state is present"),
        )
        val folder = checkNotNull(caller.cwd)
        val transcript = shared.resolve(folder.replace(Regex("[^A-Za-z0-9]"), "-")).resolve("$SESSION.jsonl")

        val first = service.launch(head, emptyList(), dangerouslySkipPermissions = false, caller = caller)
        startSession(first.argv, head, startupHook(transcript, folder))
        // Claude Code's first row of the session
        transcript.parent.createDirectories()
        transcript.writeText("""{"type":"user","sessionId":"$SESSION","message":{"role":"user","content":"hi"}}""")
        val again = service.launch(head, listOf("-c"), dangerouslySkipPermissions = false, caller = caller)

        assertEquals(listOf("--resume", SESSION), again.argv.takeLast(2), "${again.argv}, warned: ${again.warning}")
        assertNull(again.warning, "going on with one's own session is the quiet path")
        assertEquals(
            listOf("projects"),
            home.resolve(".claude").toFile().list().orEmpty().sorted(),
            "the hook's script is the head's own: nothing is written into ~/.claude",
        )
    }

    /** The JSON Claude Code pipes to a SessionStart hook when it starts [SESSION] in [folder]. */
    private fun startupHook(transcript: Path, folder: String): String = buildJsonObject {
        put("session_id", SESSION)
        put("transcript_path", transcript.toString())
        put("cwd", folder)
        put("hook_event_name", "SessionStart")
        put("source", "startup")
    }.toString()

    /** Claude Code starting a session: each SessionStart command the launch's --settings registers for a startup runs
     *  with [hook] on its stdin. curl is a recording one first on PATH, and what it would POST to the daemon is handed
     *  to the hook route under the head its URL names. */
    private fun startSession(argv: List<String>, head: LaunchSpec, hook: String) {
        val heads = launchHeadsOf(LaunchHead(runningHead("claude-splice"), absentAuth("test"), head))
        val route = ResumeHookRoute(heads, log = {})
        val bin = tmp.resolve("bin").createDirectories()
        val curl = bin.resolve("curl")
        curl.writeText(
            "#!/usr/bin/env bash\nfor a; do url=\$a; done\nprintf '%s' \"\$url\" > '$bin/url'\ncat > '$bin/body'\n",
        )
        Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwx------"))
        val overlay = Json.parseToJsonElement(argv[argv.indexOf("--settings") + 1]).jsonObject
        overlay["hooks"]?.jsonObject?.get("SessionStart")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["matcher"]?.jsonPrimitive?.content in setOf(null, "startup") }
            .flatMap { it.getValue("hooks").jsonArray }
            .forEach { command ->
                val run = ProcessBuilder(command.jsonObject.getValue("command").jsonPrimitive.content)
                run.environment()["PATH"] = "$bin:${System.getenv("PATH")}"
                val process = run.start()
                process.outputStream.use { it.write(hook.toByteArray()) }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the hook exits on its own")
                val key = bin.resolve("url").readText().substringAfterLast("/hooks/resume/")
                assertNull(route.handle(key, bin.resolve("body").readText()), "the route records the session")
            }
    }

    @Test
    fun `a launch of the head itself still names its own config dir`() {
        val recipe = LaunchService(ClaudeConfigMaterializer(tmp))
            .launch(spec("claude-splice", forwardClientAuth = true), emptyList(), dangerouslySkipPermissions = false)
        assertEquals(tmp.resolve(".claude-claude-splice").toString(), recipe.env["CLAUDE_CONFIG_DIR"])
        val settings = recipe.argv.zipWithNext().filter { it.first == "--settings" }.map { it.second }
        assertEquals(launchMark.drop(1), settings, "the head's settings are materialized in its own tree")
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
