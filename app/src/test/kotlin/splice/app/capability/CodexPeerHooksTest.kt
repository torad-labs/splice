package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `custom splice binary path and flags are accepted as current and never overwritten`(@TempDir tmp: Path) {
        val file = Files.createDirectories(tmp.resolve(".codex")).resolve("hooks.json")
        Files.writeString(file, CUSTOM_DIRECT_HOOKS_JSON)

        val reading = hooks.inspect(file)
        assertEquals(CodexHooksState.CURRENT, reading.state, reading.issues.toString())
        assertEquals(CodexHooksOutcome.Current(file), hooks.installOrMigrate(file))
        assertEquals(CUSTOM_DIRECT_HOOKS_JSON, Files.readString(file))
    }

    @Test
    fun `legacy wrapper migration preserves custom binary path, flags, user hooks, backup and symlink`(
        @TempDir tmp: Path,
    ) {
        val realTarget = Files.createDirectories(tmp.resolve("shared")).resolve("hooks.json")
        Files.writeString(realTarget, LEGACY_HOOKS_JSON)
        val link = Files.createDirectories(tmp.resolve(".codex")).resolve("hooks.json")
        Files.createSymbolicLink(link, realTarget)

        val before = hooks.inspect(link)
        assertEquals(CodexHooksState.NEEDS_MIGRATION, before.state)
        assertTrue(before.issues.any { "legacy wrapper" in it }, before.issues.toString())
        assertTrue(before.issues.any { "SessionEnd timeout is 30 (expected 3)" in it }, before.issues.toString())

        val outcome = hooks.installOrMigrate(link)
        assertEquals(CodexHooksOutcome.Written(link, migrated = true), outcome)
        assertEquals(CodexHooksState.CURRENT, hooks.inspect(link).state)
        assertTrue(Files.isSymbolicLink(link), "hooks.json symlink must remain a symlink")
        val backup = realTarget.resolveSibling("hooks.json.bak")
        assertTrue(Files.isRegularFile(backup), "prior hooks.json must be backed up before overwrite")
        assertEquals(LEGACY_HOOKS_JSON, Files.readString(backup))

        val migrated = Json.parseToJsonElement(Files.readString(realTarget)).jsonObject
        assertEquals("true", migrated["customTopLevel"]?.jsonPrimitive?.content)
        val events = migrated["hooks"]!!.jsonObject
        val sessionStartText = events["SessionStart"]!!.jsonArray.toString()
        assertTrue("user-notify start" in sessionStartText, "user entry must survive: $sessionStartText")
        assertTrue("jq" !in sessionStartText, "legacy jq wrapper must be removed: $sessionStartText")
        assertHook(events, "SessionStart", "/opt/bin/splice peer codex attach --peer-name work", 30)
        assertHook(events, "SessionEnd", "splice peer codex detach", 3)
        assertTrue("user-notify stop" in events["Stop"]!!.jsonArray.toString())
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
    fun `missing hooks is doctor INFO not upgrade gap while legacy migration is WARN and upgrade gap`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve(".codex/hooks.json")
        val missingFacts = factsWith(CodexHooksReading(file, CodexHooksState.MISSING))
        val infoRow = CapabilityTools(missingFacts).readings().single { it.name == "codex peer hooks" }
        assertEquals(ToolLevel.INFO, infoRow.level)
        assertFalse(
            CapabilityGaps(missingFacts, claudeWrapped = false).afterUpgrade().any { it.kind == GapKind.CODEX_HOOKS },
        )

        val legacy = CodexHooksReading(
            file,
            CodexHooksState.NEEDS_MIGRATION,
            listOf("SessionEnd timeout is 30 (expected 3)"),
        )
        val legacyFacts = factsWith(legacy)
        val warnRow = CapabilityTools(legacyFacts).readings().single { it.name == "codex peer hooks" }
        assertEquals(ToolLevel.WARN, warnRow.level)
        assertEquals(SETUP_UPDATE, warnRow.fix)
        assertTrue(
            CapabilityGaps(legacyFacts, claudeWrapped = false).afterUpgrade().any { it.kind == GapKind.CODEX_HOOKS },
        )
    }

    private fun factsWith(reading: CodexHooksReading): Capabilities = Capabilities(
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
            codexHooks = reading,
        ),
        hub = HubFacts(linux = true, on = false, systemd = true),
        autoUpdate = false,
    )

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

private val CUSTOM_DIRECT_HOOKS_JSON = """
    {
      "hooks": {
        "SessionStart": [
          {
            "hooks": [
              {
                "type": "command",
                "command": "/opt/bin/splice peer codex attach --peer-name work --cwd /repo",
                "timeout": 45
              }
            ]
          }
        ],
        "UserPromptSubmit": [
          {
            "hooks": [
              {
                "type": "command",
                "command": "/opt/bin/splice peer codex attach --peer-name work",
                "timeout": 30
              }
            ]
          }
        ],
        "SessionEnd": [
          {
            "hooks": [
              {
                "type": "command",
                "command": "/opt/bin/splice peer codex detach --peer-name work",
                "timeout": 3
              }
            ]
          }
        ]
      }
    }
""".trimIndent()

private val LEGACY_HOOKS_JSON = """
    {
      "customTopLevel": true,
      "hooks": {
        "SessionStart": [
          {
            "hooks": [
              { "type": "command", "command": "user-notify start", "timeout": 10 },
              {
                "type": "command",
                "command": "sh -c 'id=$(jq -r .session_id); /opt/bin/splice peer codex attach --peer-name work \"${'$'}id\"'",
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
              { "type": "command", "command": "user-notify stop", "timeout": 5 }
            ]
          }
        ]
      }
    }
""".trimIndent()
