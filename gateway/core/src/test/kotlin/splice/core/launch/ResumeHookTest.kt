// NEW: V4-169 — the SessionStart resume hook as the materializer installs it: registered for
// `resume` and (V4-183) `startup`, never `compact` or `clear`, as one 0700 script that
// authenticates from the session's own env (no bearer literal),
// and absent — loudly — when the config dir cannot execute a hook or no daemon port was given.
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val PORT = 3096

class ResumeHookTest {

    private fun materialize(home: Path, configDir: Path, resumeHookPort: Int?): JsonObject {
        Files.createDirectories(home.resolve(".claude"))
        ClaudeConfigMaterializer(home, resumeHookPort = resumeHookPort).materialize(
            MaterializeSpec(
                configDir = configDir,
                policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
                availableModelIds = listOf("gpt-5.6-sol"),
                defaultModel = "gpt-5.6-sol",
                modelOptionsCache = buildJsonObject { put("cache", "x") },
                statuslineCommand = "curl :3096/statusline",
                headKey = "codex",
            ),
        )
        return Json.parseToJsonElement(Files.readString(configDir.resolve("settings.json"))).jsonObject
    }

    private fun sessionStart(settings: JsonObject): JsonArray? =
        settings["hooks"]?.jsonObject?.get("SessionStart")?.jsonArray

    @Test
    fun `a daemon-backed materializer registers the hook for resume and startup only, as a 0700 script`(
        @TempDir home: Path,
    ) {
        val head = home.resolve(".claude-codex")

        val entries = sessionStart(materialize(home, head, PORT))

        val matchers = entries!!.map { it.jsonObject["matcher"]!!.jsonPrimitive.content }
        assertEquals(listOf("resume", "startup"), matchers, "/clear and compact never call (V4-183 adds startup)")
        val entry = entries.first().jsonObject
        val command = entry["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content
        assertEquals(
            command,
            entries.last().jsonObject["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content,
            "both matchers run the one script",
        )
        val script = head.resolve(ResumeHook.RESUME_HOOK_SH)
        assertEquals(script.toString(), command)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
        val text = Files.readString(script)
        assertTrue(text.contains("http://127.0.0.1:$PORT/hooks/resume/codex"), text)
        assertTrue(text.contains("\${ANTHROPIC_AUTH_TOKEN}"), "authenticates from the session's own env")
        assertFalse(text.contains("Bearer [0-9a-f]{16}".toRegex()), "no bearer literal is written into the script")
        assertTrue(text.trimEnd().endsWith("exit 0"), "never blocks the session")
    }

    @Test
    fun `no daemon port, no hook - a test materializer changes nothing`(@TempDir home: Path) {
        val head = home.resolve(".claude-codex")

        assertNull(sessionStart(materialize(home, head, resumeHookPort = null)))
        assertFalse(Files.exists(head.resolve(ResumeHook.RESUME_HOOK_SH)))
    }

    @Test
    fun `a config dir that cannot execute a hook gets no registration and one log line`(@TempDir dir: Path) {
        val log = StringBuilder()

        val additions = ResumeHook.install(
            dir,
            PORT,
            "codex",
            log = { log.append(it) },
            execProbe = { _, _ -> IOException("mounted noexec") },
        )

        assertEquals(emptyMap<String, Any>(), additions, "a hook that cannot run is not registered")
        assertFalse(Files.exists(dir.resolve(ResumeHook.RESUME_HOOK_SH)))
        assertTrue(log.contains("resume hook NOT installed") && log.contains("noexec"), log.toString())
    }
}
