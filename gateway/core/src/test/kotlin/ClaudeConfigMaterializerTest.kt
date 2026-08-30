// PORT-OF: server/test/launcher.test.mjs prepareClaudexConfig pins @ pre-public-port-baseline — refuses outside
// .claude*, settings is a REAL merged file (never a symlink), availableModels + enforce +
// preserved-allowed-model + statusline, shared items symlinked, a real operator DIRECTORY never
// deleted, .claude.json gets modelOptionsCache + MCP inherit + PORT_KEYS + onboarding, isolate
// overrides share.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.MaterializeSpec
import splice.core.launch.TokenCaptureSpec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeConfigMaterializerTest {

    // DR-11c: the local .claude.json is Claude Code's own state; an unparseable one must abort
    // the rewrite (the KeyStore strict doctrine), not become an empty merge base that a five-key
    // file then atomically replaces — destroying, among everything else, the approved
    // customApiKeyResponses the rewrite exists to preserve.
    @Test
    fun `a corrupt local claude json aborts materialize and keeps its bytes`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val corrupt = """{"customApiKeyResponses": {"approved": ["k1"]}, TRUNCATED"""
        configDir.resolve(".claude.json").writeText(corrupt)

        assertThrows(IOException::class.java) {
            materializer(home).materialize(configDir, allPolicy, listOf("m1"), "m1", optionsCache)
        }
        assertEquals(
            corrupt,
            configDir.resolve(".claude.json").readText(),
            "the unreadable file must keep its bytes for the operator to fix",
        )
    }

    // DR-11b: both generated files are rewritten while a LIVE Claude Code can be mid-read; the
    // truncate-then-write pair is replaced by the repo's one atomic-write primitive. The
    // observable pin is the primitive's 0600 stamp — a plain writeString leaves umask perms.
    @Test
    fun `settings and claude json are written through the atomic 0600 primitive`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")

        materializer(home).materialize(configDir, allPolicy, listOf("m1"), "m1", optionsCache)

        val perms = { p: Path -> PosixFilePermissions.toString(Files.getPosixFilePermissions(p)) }
        assertEquals("rw-------", perms(configDir.resolve("settings.json")))
        assertEquals("rw-------", perms(configDir.resolve(".claude.json")))
    }

    private val allPolicy = ClaudePolicy(
        share = setOf("settings", "mcps", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md"),
        isolate = emptySet(),
    )
    private val optionsCache = buildJsonObject { put("cache", "codex-models") }
    private val statusline = "\"/usr/bin/curl\" -s :3096/statusline"

    private fun materializer(home: Path) = ClaudeConfigMaterializer(home)

    // Test shim: materialize() now takes a MaterializeSpec; supply a fixed statusline so the
    // existing positional call sites read unchanged.
    private fun ClaudeConfigMaterializer.materialize(
        configDir: Path,
        policy: ClaudePolicy,
        availableModelIds: List<String>,
        defaultModel: String,
        modelOptionsCache: JsonElement,
    ) = materialize(
        MaterializeSpec(configDir, policy, availableModelIds, defaultModel, modelOptionsCache, statusline),
    )

    private fun seedGlobal(home: Path) {
        val g = home.resolve(".claude")
        Files.createDirectories(g.resolve("agents"))
        Files.createDirectories(g.resolve("commands"))
        g.resolve("settings.json").writeText("""{"theme":"dark","permissions":{"allow":["Bash"]}}""")
        g.resolve("CLAUDE.md").writeText("global rules")
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"fs":{"command":"x"}},"verbose":true,"theme":"dark","extra":"keepme"}""",
        )
    }

    @Test
    fun `refuses to materialize outside a claude dir`(@TempDir tmp: Path) {
        assertThrows(IllegalArgumentException::class.java) {
            materializer(tmp).materialize(
                tmp.resolve("other"),
                allPolicy,
                listOf("gpt-5.6-sol"),
                "gpt-5.6-sol",
                optionsCache,
            )
        }
    }

    @Test
    fun `settings is a real merged file with allowlist, enforce, statusline`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol", "gpt-5.4"), "gpt-5.6-sol", optionsCache)
        val settings = dir.resolve("settings.json")
        assertFalse(settings.isSymbolicLink()) // never a symlink (would clobber global)
        val obj = Json.parseToJsonElement(settings.readText()).jsonObject
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.4"),
            obj["availableModels"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("true", obj["enforceAvailableModels"]?.jsonPrimitive?.content)
        assertEquals("gpt-5.6-sol", obj["model"]?.jsonPrimitive?.content)
        assertEquals("dark", obj["theme"]?.jsonPrimitive?.content) // global merged in
        assertTrue(obj["statusLine"]!!.jsonObject["command"]?.jsonPrimitive?.content!!.contains("statusline"))
    }

    @Test
    fun `preserves a saved model choice when still allowed`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        Files.createDirectories(dir)
        dir.resolve("settings.json").writeText("""{"model":"gpt-5.4"}""")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol", "gpt-5.4"), "gpt-5.6-sol", optionsCache)
        val obj = Json.parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        assertEquals("gpt-5.4", obj["model"]?.jsonPrimitive?.content) // preserved (still allowed)
        // a disallowed saved choice falls back to default
        dir.resolve("settings.json").writeText("""{"model":"gpt-4o"}""")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        val obj2 = Json
            .parseToJsonElement(dir.resolve("settings.json").readText())
            .jsonObject
        assertEquals("gpt-5.6-sol", obj2["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `shared items symlink but a real operator directory is never deleted`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        Files.createDirectories(dir.resolve("agents")) // a REAL dir the operator made
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertTrue(dir.resolve("commands").isSymbolicLink()) // fresh link
        assertFalse(dir.resolve("agents").isSymbolicLink()) // real dir preserved, not replaced
        assertTrue(Files.isDirectory(dir.resolve("agents")))
    }

    @Test
    fun `claude json gets model cache, mcp inherit, port keys, onboarding`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        val result = materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertEquals(1, result.mcpServers)
        val obj = Json.parseToJsonElement(dir.resolve(".claude.json").readText()).jsonObject
        assertTrue(obj["additionalModelOptionsCache"]!!.jsonObject.containsKey("cache"))
        assertTrue(obj["mcpServers"]!!.jsonObject.containsKey("fs"))
        assertEquals("true", obj["verbose"]?.jsonPrimitive?.content) // PORT_KEY inherited
        assertEquals("true", obj["hasCompletedOnboarding"]?.jsonPrimitive?.content)
    }

    @Test
    fun `isolate overrides share - item not linked`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        val policy = ClaudePolicy(share = allPolicy.share, isolate = setOf("commands"))
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertFalse(Files.exists(dir.resolve("commands"), NOFOLLOW_LINKS)) // isolated: no link
        assertTrue(dir.resolve("agents").isSymbolicLink()) // still shared
    }

    @Test
    fun `sessions registry migrates a real head dir into the global and links it`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val globalSessions = tmp.resolve(".claude/sessions")
        Files.createDirectories(globalSessions)
        val dir = tmp.resolve(".claude-codex")
        val headSessions = dir.resolve("sessions")
        Files.createDirectories(headSessions) // Claude Code made this before the link existed
        headSessions.resolve("12345.json").writeText("""{"pid":12345}""")
        headSessions.resolve("12345.abc.key").writeText("k")
        val policy = ClaudePolicy(share = allPolicy.share + "sessions", isolate = emptySet())
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertTrue(headSessions.isSymbolicLink())
        assertEquals(globalSessions, Files.readSymbolicLink(headSessions))
        // registrations moved with the dir swap: a live session keeps writing through the link
        assertTrue(Files.isRegularFile(globalSessions.resolve("12345.json")))
        assertTrue(Files.isRegularFile(globalSessions.resolve("12345.abc.key")))
    }

    @Test
    fun `sessions link is idempotent across rematerializations`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val globalSessions = tmp.resolve(".claude/sessions")
        Files.createDirectories(globalSessions)
        val dir = tmp.resolve(".claude-codex")
        val policy = ClaudePolicy(share = allPolicy.share + "sessions", isolate = emptySet())
        repeat(2) { materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache) }
        assertTrue(dir.resolve("sessions").isSymbolicLink())
        assertEquals(globalSessions, Files.readSymbolicLink(dir.resolve("sessions")))
    }

    // INVERTED (DR-1 redo, 2026-08-30). This used to pin "a missing global registry leaves the head's
    // sessions dir alone", which is the skip-if-missing rule every OTHER shared item follows. The
    // registry is not like the others: Claude Code GENERATES it, so "missing" means a machine that has
    // never run plain `claude` — precisely the fresh install where cross-head visibility is wanted and
    // where its absence is invisible, because nothing said sharing did not happen. DR-1 demanded
    // Files.createDirectories(globalSessions) for exactly that case. The share-vs-isolate decision is
    // untouched and still pinned by `isolate sessions keeps the head registry private` below — this
    // arm only covers a policy that ASKED to share.
    @Test
    fun `a missing global registry is created so sessions sharing is never silently inert`(@TempDir tmp: Path) {
        seedGlobal(tmp) // seeds ~/.claude WITHOUT a sessions dir
        val dir = tmp.resolve(".claude-codex")
        val headSessions = dir.resolve("sessions")
        Files.createDirectories(headSessions)
        val policy = ClaudePolicy(share = allPolicy.share + "sessions", isolate = emptySet())
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertTrue(Files.isDirectory(tmp.resolve(".claude/sessions")), "the registry must be created")
        assertTrue(headSessions.isSymbolicLink(), "a sharing head must end up linked, not left private")
        assertEquals(tmp.resolve(".claude/sessions"), Files.readSymbolicLink(headSessions))
    }

    @Test
    fun `isolate sessions keeps the head registry private`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        Files.createDirectories(tmp.resolve(".claude/sessions"))
        val dir = tmp.resolve(".claude-codex")
        val headSessions = dir.resolve("sessions")
        Files.createDirectories(headSessions)
        val policy = ClaudePolicy(share = allPolicy.share + "sessions", isolate = setOf("sessions"))
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertFalse(headSessions.isSymbolicLink())
        assertTrue(Files.isDirectory(headSessions, NOFOLLOW_LINKS))
    }

    @Test
    fun `sessions migration aborts on unexpected content and leaves the real dir`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        Files.createDirectories(tmp.resolve(".claude/sessions"))
        val dir = tmp.resolve(".claude-codex")
        val headSessions = dir.resolve("sessions")
        Files.createDirectories(headSessions.resolve("weird-subdir"))
        val policy = ClaudePolicy(share = allPolicy.share + "sessions", isolate = emptySet())
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertFalse(headSessions.isSymbolicLink())
        assertTrue(Files.isDirectory(headSessions.resolve("weird-subdir")))
    }

    @Test
    fun `the topology default vocabulary (claude_md, settings, mcps) shares everything`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        // EXACTLY the ClaudeSharingDefaults.share vocabulary — friendly names, not on-disk item names
        val policy = ClaudePolicy(
            share = setOf("settings", "mcps", "skills", "hooks", "agents", "commands", "plugins", "claude_md"),
            isolate = emptySet(),
        )
        val result = materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        // CLAUDE.md shares despite the "claude_md" alias (the bug this pins) — and agents/commands too
        assertTrue(dir.resolve("CLAUDE.md").isSymbolicLink())
        assertTrue(dir.resolve("agents").isSymbolicLink())
        // settings merged (the global theme survives alongside the allowlist)
        val settings = Json
            .parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        assertEquals("dark", settings["theme"]?.jsonPrimitive?.content)
        // MCP servers inherited via the "mcps" alias
        assertEquals(1, result.mcpServers)
    }

    @Test
    fun `isolate wins over a shared alias (claude_md)`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        val policy = ClaudePolicy(share = setOf("claude_md", "mcps"), isolate = setOf("claude_md"))
        val result = materializer(tmp).materialize(dir, policy, listOf("m"), "m", optionsCache)
        assertFalse(Files.exists(dir.resolve("CLAUDE.md"), NOFOLLOW_LINKS)) // isolated
        assertEquals(1, result.mcpServers) // mcps still shared
    }

    @Test
    fun `login interception wires custom command, hook script, and settings hook`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        tmp.resolve(".claude/commands/foo.md").writeText("a global command") // to assert it survives
        val dir = tmp.resolve(".claude-codex")
        materializer(tmp).materialize(
            MaterializeSpec(
                configDir = dir,
                policy = allPolicy,
                availableModelIds = listOf("gpt-5.6-sol"),
                defaultModel = "gpt-5.6-sol",
                modelOptionsCache = optionsCache,
                statuslineCommand = statusline,
                loginCommand = "claude-grok login",
                signInLabel = "Grok (xAI)",
            ),
        )
        // commands is now a REAL dir (a whole-dir symlink can't hold login.md), with the sentinel
        // command AND the operator's global command re-linked in.
        val commands = dir.resolve("commands")
        assertFalse(commands.isSymbolicLink())
        assertTrue(commands.resolve("login.md").readText().contains("SPLICE_CODEX_LOGIN"))
        assertTrue(commands.resolve("login.md").readText().contains("Grok (xAI)")) // provider-correct UX
        assertTrue(commands.resolve("foo.md").isSymbolicLink())
        // hook script written, carries the head's login command + provider label
        val hookText = dir.resolve("splice-login-hook.sh").readText()
        assertTrue(hookText.contains("claude-grok login"))
        assertTrue(hookText.contains("Grok (xAI)"))
        // settings.json wires the UserPromptSubmit hook at the script
        val settings = Json.parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        val ups = settings["hooks"]!!.jsonObject["UserPromptSubmit"]!!.jsonArray
        assertTrue(ups.toString().contains("splice-login-hook.sh"))
    }

    @Test
    fun `no login interception when loginCommand is blank`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        materializer(tmp).materialize(dir, allPolicy, listOf("m"), "m", optionsCache) // shim: loginCommand=""
        assertFalse(Files.exists(dir.resolve("splice-login-hook.sh")))
        assertTrue(dir.resolve("commands").isSymbolicLink()) // stays a plain shared symlink
    }

    /** THE CAPTURE HOOK IS FOR AN UNCONFIGURED HEAD ONLY (review of #75). On a head whose key is
     *  already set the hook is pure downside: it swallows any bare `sk-or-…` message, silently
     *  OVERWRITES a working credential, and the message never reaches the model — so merely
     *  DISCUSSING a key by pasting one would break the session's auth. The daemon now passes
     *  tokenCapture only while the key is missing; this pins the materializer side of that. */
    @Test
    fun `no capture hook is installed when tokenCapture is absent`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-openrouter-configured")
        materializer(tmp).materialize(
            MaterializeSpec(
                configDir = dir,
                policy = allPolicy,
                availableModelIds = listOf("m"),
                defaultModel = "m",
                modelOptionsCache = optionsCache,
                statuslineCommand = statusline,
                loginCommand = "claude-openrouter login",
                signInLabel = "OpenRouter",
                signInViaBrowser = false,
                tokenCapture = null, // the daemon withholds it once the key resolves
            ),
        )
        assertFalse(
            dir.resolve("splice-key-capture-hook.sh").toFile().exists(),
            "a configured head must not install the paste-capture hook",
        )
        assertTrue(dir.resolve("splice-login-hook.sh").toFile().exists(), "/login itself still works")
    }

    /** EVERY head keeps /login. A head without a known token shape cannot capture a paste, but it
     *  still HAS a sign-in path (`<command> login` in a terminal) — so /login must still be wired
     *  and must say so. Removing it for those heads was a regression this pins against. */
    @Test
    fun `api-key head WITHOUT capture still gets login, pointing at the terminal command`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-fireworks")
        materializer(tmp).materialize(
            MaterializeSpec(
                configDir = dir,
                policy = allPolicy,
                availableModelIds = listOf("m"),
                defaultModel = "m",
                modelOptionsCache = optionsCache,
                statuslineCommand = statusline,
                loginCommand = "claude-fireworks login",
                signInLabel = "Fireworks",
                signInViaBrowser = false,
                tokenCapture = null, // splice does not know this vendor's token shape
            ),
        )
        val loginHook = dir.resolve("splice-login-hook.sh").readText()
        assertTrue(loginHook.contains("claude-fireworks login"), "/login must still name the working command")
        assertTrue(loginHook.contains("cannot be asked for from inside this session"), "and say why, plainly")
        assertFalse(loginHook.contains("nohup"), "still nothing spawned — a detached login has no TTY")
        assertFalse(loginHook.contains("browser"))
    }

    @Test
    fun `api-key head with capture tells the user the path that WORKS, and spawns nothing`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-openrouter")
        materializer(tmp).materialize(
            MaterializeSpec(
                configDir = dir,
                policy = allPolicy,
                availableModelIds = listOf("m"),
                defaultModel = "m",
                modelOptionsCache = optionsCache,
                statuslineCommand = statusline,
                loginCommand = "claude-openrouter login",
                signInLabel = "OpenRouter",
                signInViaBrowser = false,
                tokenCapture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-[A-Za-z0-9_-]{20,}", "OpenRouter"),
            ),
        )
        // THE 2026-08-01 FIX. This used to promise "a masked terminal prompt is asking for your
        // key" while spawning `<cmd> login` DETACHED with stdout to /dev/null — no TTY, so
        // System.console() was null, so the CLI printed its pipe-hint into the void and exited.
        // The prompt could never appear and the user waited on nothing (verified by running it).
        // A capture-capable head is now told the path that actually works.
        val loginHook = dir.resolve("splice-login-hook.sh").readText()
        assertTrue(loginHook.contains("Paste your OpenRouter API key as your next message"))
        assertTrue(loginHook.contains("never sent upstream"), "the safety property must be stated")
        assertTrue(
            loginHook.contains("session log on disk still records"),
            "and the residual must be stated too — the transcript keeps the pasted line",
        )
        assertFalse(loginHook.contains("browser"))
        assertFalse(
            loginHook.contains("nohup"),
            "nothing may be spawned: a detached api-key login has no TTY and cannot prompt",
        )
        // capture hook: env name, quote-anchored pattern, store via splice key set --stdin, blocked
        val capture = dir.resolve("splice-key-capture-hook.sh").readText()
        assertTrue(capture.contains("OPENROUTER_API_KEY"))
        assertTrue(capture.contains("sk-or-[A-Za-z0-9_-]{20,}"))
        assertTrue(capture.contains("splice key set OPENROUTER_API_KEY --stdin"))
        assertTrue(capture.contains("\\\"prompt\\\""))
        // settings.json wires BOTH UserPromptSubmit hooks
        val settings = Json.parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        val ups = settings["hooks"]!!.jsonObject["UserPromptSubmit"]!!.jsonArray.toString()
        assertTrue(ups.contains("splice-login-hook.sh"))
        assertTrue(ups.contains("splice-key-capture-hook.sh"))
        // no advertiser unless asked
        assertFalse(settings["hooks"]!!.jsonObject.containsKey("SessionStart"))
    }

    @Test
    fun `advertiseKeySetup installs the SessionStart advertiser`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-openrouter")
        materializer(tmp).materialize(
            MaterializeSpec(
                configDir = dir,
                policy = allPolicy,
                availableModelIds = listOf("m"),
                defaultModel = "m",
                modelOptionsCache = optionsCache,
                statuslineCommand = statusline,
                loginCommand = "claude-openrouter login",
                signInLabel = "OpenRouter",
                signInViaBrowser = false,
                tokenCapture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-[A-Za-z0-9_-]{20,}", "OpenRouter"),
                advertiseKeySetup = true,
            ),
        )
        val script = dir.resolve("splice-keysetup-hook.sh").readText()
        assertTrue(script.contains("OPENROUTER_API_KEY"))
        assertTrue(script.contains("OpenRouter"))
        val settings = Json.parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        val ss = settings["hooks"]!!.jsonObject["SessionStart"]!!.jsonArray.toString()
        assertTrue(ss.contains("splice-keysetup-hook.sh"))
    }
}
