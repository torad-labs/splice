package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopMode
import splice.diagnostics.doctor.ToolLevel
import java.nio.file.Files
import java.nio.file.Path

class CodexPeerHooksTest {
    private val hooks = CodexPeerHooks()

    @Test
    fun `missing hooks json installs canonical direct attach and detach entries`(@TempDir tmp: Path) {
        val file = tmp.resolve(".codex/hooks.json")
        assertEquals(CodexHooksState.MISSING, hooks.inspect(file).state)

        val outcome = hooks.installOrMigrate(file)
        assertEquals(CodexHooksOutcome.Written(file, migrated = false), outcome)
        assertEquals(CodexHooksState.CURRENT, hooks.inspect(file).state)

        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val events = root["hooks"]!!.jsonObject
        assertHook(events, "SessionStart", "splice peer codex attach", 30)
        assertHook(events, "UserPromptSubmit", "splice peer codex attach", 30)
        assertHook(events, "SessionEnd", "splice peer codex detach", 3)
    }

    @Test
    fun `legacy sh jq wrapper and wrong SessionEnd timeout are detected and migrated preserving user hooks`(
        @TempDir tmp: Path,
    ) {
        val file = Files.createDirectories(tmp.resolve(".codex")).resolve("hooks.json")
        Files.writeString(file, LEGACY_HOOKS_JSON)

        val before = hooks.inspect(file)
        assertEquals(CodexHooksState.NEEDS_MIGRATION, before.state)
        assertTrue(before.issues.any { "legacy wrapper" in it }, before.issues.toString())
        assertTrue(before.issues.any { "SessionEnd timeout is 30 (expected 3)" in it }, before.issues.toString())

        val outcome = hooks.installOrMigrate(file)
        assertEquals(CodexHooksOutcome.Written(file, migrated = true), outcome)
        assertEquals(CodexHooksState.CURRENT, hooks.inspect(file).state)

        val migrated = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("true", migrated["customTopLevel"]?.jsonPrimitive?.content)
        val events = migrated["hooks"]!!.jsonObject
        val sessionStartText = events["SessionStart"]!!.jsonArray.toString()
        assertTrue("hookd notify start" in sessionStartText, "user hookd entry must survive: $sessionStartText")
        assertTrue("jq" !in sessionStartText, "legacy jq wrapper must be removed: $sessionStartText")
        assertTrue("hookd notify stop" in events["Stop"]!!.jsonArray.toString())
        assertHook(events, "SessionEnd", "splice peer codex detach", 3)
    }

    @Test
    fun `malformed hooks json is reported unreadable and never overwritten`(@TempDir tmp: Path) {
        val file = Files.createDirectories(tmp.resolve(".codex")).resolve("hooks.json")
        val broken = "{ not valid json"
        Files.writeString(file, broken)

        assertEquals(CodexHooksState.UNREADABLE, hooks.inspect(file).state)
        val outcome = hooks.installOrMigrate(file)
        assertTrue(outcome is CodexHooksOutcome.Refused, outcome.toString())
        assertEquals(broken, Files.readString(file))
    }

    @Test
    fun `doctor tools and capability gaps surface codex peer hooks without ever failing`(@TempDir tmp: Path) {
        val file = tmp.resolve(".codex/hooks.json")
        val legacy = CodexHooksReading(
            file,
            CodexHooksState.NEEDS_MIGRATION,
            listOf("SessionEnd timeout is 30 (expected 3)"),
        )
        val facts = Capabilities(
            desktop = DesktopFacts(
                program = null,
                mode = DesktopMode.OFF,
                sideBySideCli = false,
                sideBySideApp = false,
                wrapper = WrapperState.NONE,
            ),
            harness = HarnessFacts(
                agy = null,
                codex = Path.of("/usr/bin/codex"),
                geminiShim = false,
                synced = emptySet(),
                codexHooks = legacy,
            ),
            hub = HubFacts(linux = true, on = false, systemd = true),
            autoUpdate = false,
        )
        val row = CapabilityTools(facts).readings().single { it.name == "codex peer hooks" }
        assertEquals(ToolLevel.WARN, row.level)
        assertEquals(SETUP_UPDATE, row.fix)
        assertTrue(CapabilityGaps(facts, claudeWrapped = false).afterUpgrade().any { it.kind == GapKind.CODEX_HOOKS })
    }

    private fun assertHook(events: kotlinx.serialization.json.JsonObject, event: String, cmd: String, timeout: Int) {
        val groups = events[event]!!.jsonArray
        val match = groups.map { it.jsonObject }.single { g ->
            g["hooks"]!!.jsonArray.any { it.jsonObject["command"]?.jsonPrimitive?.content == cmd }
        }
        assertTrue("matcher" !in match, "splice hook on $event must omit matcher: $match")
        val entry = match["hooks"]!!.jsonArray
            .single { it.jsonObject["command"]?.jsonPrimitive?.content == cmd }
            .jsonObject
        assertEquals("command", entry["type"]?.jsonPrimitive?.content)
        assertEquals(timeout.toString(), entry["timeout"]?.jsonPrimitive?.content)
    }
}

private val LEGACY_HOOKS_JSON = """
    {
      "customTopLevel": true,
      "hooks": {
        "SessionStart": [
          {
            "hooks": [
              { "type": "command", "command": "hookd notify start", "timeout": 10 },
              {
                "type": "command",
                "command": "sh -c 'id=$(jq -r .session_id); splice peer codex attach \"${'$'}id\"'",
                "timeout": 30
              }
            ]
          }
        ],
        "UserPromptSubmit": [
          {
            "matcher": "startup",
            "hooks": [
              { "type": "command", "command": "splice peer codex attach", "timeout": 30 }
            ]
          }
        ],
        "SessionEnd": [
          {
            "hooks": [
              { "type": "command", "command": "splice peer codex detach", "timeout": 30 }
            ]
          }
        ],
        "Stop": [
          {
            "hooks": [
              { "type": "command", "command": "hookd notify stop", "timeout": 5 }
            ]
          }
        ]
      }
    }
""".trimIndent()
