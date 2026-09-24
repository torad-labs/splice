// NEW: V4-169 — the SessionStart resume hook as the materializer installs it: registered for
// `resume` and (V4-183) `startup`, never `compact` or `clear`, as one 0700 script that
// authenticates from the session's own env (no bearer literal, and v0.4.0: no bearer in curl's argv),
// and absent — loudly — when the config dir cannot execute a hook or no daemon port was given.
package splice.client.resume

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
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.MaterializeSpec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

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
        assertTrue(text.contains("\\$\\{?ANTHROPIC_AUTH_TOKEN".toRegex()), "authenticates from the session's own env")
        assertFalse(text.contains("Bearer [0-9a-f]{16}".toRegex()), "no bearer literal is written into the script")
        assertTrue(text.trimEnd().endsWith("exit 0"), "never blocks the session")
    }

    // v0.4.0: /proc/<pid>/cmdline is world-readable, so an expanded `-H "Authorization: Bearer $TOKEN"`
    // handed the session's key to every local user for the length of the call. The script is RUN here
    // against a recording `curl` first on PATH: the bearer must reach curl as a header it reads from a
    // file, and never as an argument.
    @Test
    fun `the script hands curl the bearer through a header file, never through argv`(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val argvFile = dir.resolve("argv")
        val headersFile = dir.resolve("headers")
        val fakeCurl = bin.resolve("curl")
        Files.writeString(
            fakeCurl,
            "#!/usr/bin/env bash\n" +
                "printf '%s\\n' \"\$@\" > '$argvFile'\n" +
                "prev=\n" +
                "for a in \"\$@\"; do\n" +
                "  if [ \"\$prev\" = -H ] && [ \"\${a:0:1}\" = @ ]; then cat \"\${a:1}\" >> '$headersFile'; fi\n" +
                "  prev=\$a\n" +
                "done\n" +
                "cat >/dev/null\n",
        )
        Files.setPosixFilePermissions(fakeCurl, PosixFilePermissions.fromString("rwx------"))
        val script = dir.resolve(ResumeHook.RESUME_HOOK_SH)
        Files.writeString(script, ResumeHook.script(PORT, "codex"))
        val token = "turn-key-that-must-not-reach-argv"

        val process = ProcessBuilder("bash", script.toString())
            .apply {
                environment()["PATH"] = "$bin:${System.getenv("PATH")}"
                environment()["ANTHROPIC_AUTH_TOKEN"] = token
            }
            .start()
        process.outputStream.use { it.write("{}".toByteArray()) }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the hook script exits on its own")

        assertEquals(0, process.exitValue(), "never blocks the session")
        val argv = Files.readString(argvFile)
        assertFalse(argv.contains(token), "the bearer is not in curl's argv: $argv")
        assertTrue(argv.contains("http://127.0.0.1:$PORT/hooks/resume/codex"), argv)
        assertTrue(
            Files.readString(headersFile).lines().contains("Authorization: Bearer $token"),
            "curl reads the bearer as a header from its file",
        )
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
