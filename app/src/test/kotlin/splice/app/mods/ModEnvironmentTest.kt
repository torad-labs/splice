package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModEnvironmentTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a mod gets the small process environment and Bun does not auto-load dotenv`() = testApplication {
        val secrets = mapOf(
            "ANTHROPIC_API_KEY" to "scratch-provider-key",
            "SPLICE_MGMT_KEY" to "scratch-management-key",
            "SPLICE_MCP_ACCESS_KEY" to "scratch-mcp-key",
            "SPLICE_MOD_UNEXPECTED" to "scratch-not-an-owned-variable",
        )
        val bun = checkNotNull(RunnerTestRig(root).mods.bun.atInstall())
        val wrapper = root.resolve("bun-wrapper")
        Files.writeString(wrapper, "#!/bin/sh\nexport SHIM_ONLY=from-the-wrapper\nexec '$bun' \"\$@\"\n")
        check(wrapper.toFile().setExecutable(true, true))
        val rig = RunnerTestRig(root, System.getenv() + secrets + ("SPLICE_BUN" to wrapper.toString()))
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "environment",
            """
            export function register(on) {
              on("mod.start", async ($) => {
                $.log("env=" + JSON.stringify(process.env));
                $.log("argv=" + JSON.stringify(process.argv));
              });
            }
            """.trimIndent(),
        )
        Files.writeString(source.resolve(".env"), "EXTRA_FROM_DOTENV=not-for-the-host\n")
        Files.writeString(source.resolve("bunfig.toml"), "preload = [\"./preload.ts\"]")
        Files.writeString(source.resolve("preload.ts"), "console.error('unexpected preload ran')")
        rig.runner.start()
        try {
            rig.install(client, source)
            val log = rig.eventually { rig.log("environment").takeIf { "argv=" in it } }
            assertAllowedEnvironment(log)
            secrets.values.forEach { assertFalse(log.contains(it)) }
            assertFalse(log.contains("EXTRA_FROM_DOTENV"))
            assertFalse(log.contains("unexpected preload ran"))
        } finally {
            rig.runner.stop()
        }
    }

    private fun assertAllowedEnvironment(log: String) {
        val env = log.lineSequence().single { it.startsWith("env=") }.substringAfter("=")
        val allowed = setOf(
            "PATH",
            "HOME",
            "LANG",
            "TMPDIR",
            "SPLICE_MOD_NAME",
            "SPLICE_MOD_ROOT",
            "SPLICE_MOD_DATA",
        )
        val names = Json.parseToJsonElement(env).jsonObject.keys
        assertTrue(names.all(allowed::contains), "Unexpected environment keys: ${names - allowed}")
    }
}
