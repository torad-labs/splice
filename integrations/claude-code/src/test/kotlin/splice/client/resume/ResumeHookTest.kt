// NEW: V4-169 — the SessionStart resume hook as the materializer installs it: registered for
// `resume` and (V4-183) `startup`, never `compact` or `clear`, as one 0700 script that
// authenticates from the daemon's 0600 turn-auth header file (no bearer literal, none in curl's argv),
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    private fun materialize(home: Path, configDir: Path, resumeHook: ResumeHookTarget?): JsonObject {
        Files.createDirectories(home.resolve(".claude"))
        ClaudeConfigMaterializer(home, resumeHook = resumeHook).materialize(
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

        val entries = sessionStart(materialize(home, head, ResumeHookTarget(PORT, home.resolve("turn auth header"))))

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
        val headerArg = "-H '@${home.resolve("turn auth header")}'"
        assertTrue(text.contains(headerArg), "reads the turn key's header file: $text")
        assertFalse(text.contains("ANTHROPIC_AUTH_TOKEN"), "a client-auth head's session has no such variable")
        assertFalse(text.contains("Bearer [0-9a-f]{16}".toRegex()), "no bearer literal is written into the script")
        assertTrue(text.trimEnd().endsWith("exit 0"), "never blocks the session")
    }

    // v0.4.0: /proc/<pid>/cmdline is world-readable, so an expanded `-H "Authorization: Bearer $TOKEN"`
    // handed the session's key to every local user for the length of the call. The script is RUN here
    // against a recording `curl` first on PATH: the bearer must reach curl as a header it reads from a
    // file, and never as an argument.
    //
    // v0.4.0 review: and the bearer is the TURN KEY from the daemon's header file whatever the session's
    // environment holds. The script read ANTHROPIC_AUTH_TOKEN, which a client-auth head never plants, so
    // on that head it exited before calling and no session was ever recorded; and where the operator's
    // shell exports their OWN credential under that name, it sent that and got a 401. Both are run.
    @ParameterizedTest
    @ValueSource(strings = ["", "the-operator's-own-anthropic-credential"])
    fun `the script hands curl the turn key through its header file, whatever the session env holds`(
        sessionToken: String,
        @TempDir dir: Path,
    ) {
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
        val token = "turn-key-that-must-not-reach-argv"
        val headerFile = dir.resolve("turn-auth-header")
        Files.writeString(headerFile, "Authorization: Bearer $token\n")
        val script = dir.resolve(ResumeHook.RESUME_HOOK_SH)
        Files.writeString(script, ResumeHook.script(ResumeHookTarget(PORT, headerFile), "codex"))

        val process = ProcessBuilder("bash", script.toString())
            .apply {
                environment()["PATH"] = "$bin:${System.getenv("PATH")}"
                environment().remove("ANTHROPIC_AUTH_TOKEN")
                if (sessionToken.isNotEmpty()) environment()["ANTHROPIC_AUTH_TOKEN"] = sessionToken
            }
            .start()
        process.outputStream.use { it.write("{}".toByteArray()) }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the hook script exits on its own")

        assertEquals(0, process.exitValue(), "never blocks the session")
        val argv = Files.readString(argvFile)
        assertFalse(argv.contains(token), "the bearer is not in curl's argv: $argv")
        assertTrue(argv.contains("http://127.0.0.1:$PORT/hooks/resume/codex"), argv)
        assertEquals(
            listOf("Authorization: Bearer $token"),
            Files.readString(headersFile).lines().filter { it.startsWith("Authorization:") },
            "curl reads the turn key, and only it, as a header from its file",
        )
    }

    @Test
    fun `no daemon port, no hook - a test materializer changes nothing`(@TempDir home: Path) {
        val head = home.resolve(".claude-codex")

        assertNull(sessionStart(materialize(home, head, resumeHook = null)))
        assertFalse(Files.exists(head.resolve(ResumeHook.RESUME_HOOK_SH)))
    }

    @Test
    fun `a config dir that cannot execute a hook gets no registration and one log line`(@TempDir dir: Path) {
        val log = StringBuilder()

        val additions = ResumeHook.install(
            dir,
            ResumeHookTarget(PORT, dir.resolve("turn-auth-header")),
            "codex",
            log = { log.append(it) },
            execProbe = { _, _ -> IOException("mounted noexec") },
        )

        assertEquals(emptyMap<String, Any>(), additions, "a hook that cannot run is not registered")
        assertFalse(Files.exists(dir.resolve(ResumeHook.RESUME_HOOK_SH)))
        assertTrue(log.contains("resume hook NOT installed") && log.contains("noexec"), log.toString())
    }
}
