package splice.app.desktop.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DesktopAppConfig
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopLaunchFilesTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `managed local doors alone receive the rotated key in the selected variable`() = UserHome.within(home) {
        val f = DesktopFileRig(home)
        f.putIn(4567)
        val prefix = listOf(
            "--program",
            "desktop",
            "--harness-map",
            "${f.state}/desktop-harnesses.json",
            "--extra-settings",
            "${f.state}/desktop-hooks.json",
        )
        fun checkDoor(url: String, managed: String = "1", selector: String = "", token: String = "", api: String = "") {
            val vars = mapOf(
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST" to managed,
                "ANTHROPIC_BASE_URL" to url,
                "CLAUDE_CODE_HOST_AUTH_ENV_VAR" to selector,
            )
            f.run(vars)
            assertEquals(prefix + f.words, f.args())
            assertEquals(token, f.env("ANTHROPIC_AUTH_TOKEN"))
            assertEquals(api, f.env("ANTHROPIC_API_KEY"))
        }
        checkDoor("http://localhost:4567/door", token = "fixture")
        checkDoor("http://[::1]:4567/desktop/", selector = "ANTHROPIC_API_KEY", api = "fixture")
        checkDoor("http://127.0.0.1:4567/door/", selector = "OTHER", token = "fixture")
        checkDoor("http://127.0.0.1:4567/door", managed = "0")
        checkDoor("http://127.0.0.1:4568/door")
        checkDoor("http://localhost:4567/door/more")
        checkDoor("http://localhost:4567@foreign.invalid/door")
        f.run(mapOf("ANTHROPIC_BASE_URL" to "https://provider.invalid", "ANTHROPIC_API_KEY" to "provider-owned"))
        assertEquals("provider-owned", f.env("ANTHROPIC_API_KEY"))
        val mantle = mapOf(
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST" to "1",
            "ANTHROPIC_BEDROCK_MANTLE_BASE_URL" to "http://localhost:4567/desktop",
        )
        f.run(mantle + ("AWS_BEARER_TOKEN_BEDROCK" to "original"))
        assertEquals("original", f.env("AWS_BEARER_TOKEN_BEDROCK"))
        Files.writeString(f.state.resolve("desktop-key"), "rotated\n")
        f.run(mantle)
        assertEquals("rotated", f.env("AWS_BEARER_TOKEN_BEDROCK"))
        assertFalse(Files.readString(f.launch).contains("rotated"))
    }

    @Test
    fun `companion launches receive distinct foreground owners`() = UserHome.within(home) {
        val f = DesktopFileRig(home)
        f.putIn(4567)
        val owners = List(2) {
            f.run(emptyMap())
            java.util.UUID.fromString(f.env("SPLICE_FOREGROUND_OWNER"))
        }
        assertEquals(2, owners.toSet().size)
    }

    @Test
    fun `daemon restart upgrades the shared launcher for a side-by-side-only installation`() = UserHome.within(home) {
        val f = DesktopFileRig(home)
        f.putInBeside(4567)
        f.run(emptyMap())
        assertEquals("--program", f.args().first())
    }

    @Test
    fun `removing the companion runs Desktop's original program model and credential without its profile overrides`() =
        UserHome.within(home) {
            val f = DesktopFileRig(home)
            f.putIn(5678)
            Files.delete(f.pipe)
            val profile = mapOf(
                "CLAUDE_SPLICE_DESKTOP" to "1",
                "CHROME_DESKTOP" to "desktop",
                "XDG_CONFIG_HOME" to "profile",
                "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS" to "1",
            )
            val door = mapOf(
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST" to "1",
                "ANTHROPIC_BASE_URL" to "http://localhost:5678/door",
                "CLAUDE_CONFIG_DIR" to "sessions",
            )
            f.run(profile + door)
            assertEquals(f.words.drop(1), f.args())
            assertEquals("fixture", f.env("ANTHROPIC_AUTH_TOKEN"))
            assertEquals("sessions", f.env("CLAUDE_CONFIG_DIR"))
            for (name in profile.keys) assertEquals("", f.env(name))
        }
}

internal class DesktopFileRig(private val home: Path) {
    val state = home.resolve("state's files")
    val pipe = home.resolve("scratch share/seatd/fixture/seatd-pipe")
    val launch = state.resolve("desktop-launch")
    private val claude = home.resolve("Claude Code")
    val words = listOf(claude.toString(), "--model", "claude-antigravity--test", "a b", "")
    private val vars = mapOf(
        "HOME" to home.toString(),
        "XDG_CONFIG_HOME" to home.resolve("config").toString(),
        "SPLICE_STATE_DIR" to state.toString(),
    )
    private val env = EnvReader { vars[it] }

    private fun standIns() {
        for (program in listOf(pipe, claude)) {
            Files.createDirectories(program.parent)
            Files.writeString(program, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > argv\nenv > environment\n")
            check(program.toFile().setExecutable(true))
        }
    }

    fun putInBeside(port: Int) {
        standIns()
        val door = "http://127.0.0.1:$port/door"
        SideBySideDesktop(env = env, out = {}, err = {}).prepareProfile(door)
        Files.writeString(launch, "#!/bin/sh\nexec \"\$@\"\n")
        val records = DesktopRecords(state.resolve("desktop.json")) {}
        val app = DesktopAppMode(records, DesktopAppLibrary(DesktopAppConfig(env)), DesktopPrograms(state, env, pipe))
        app.refreshed(door)
    }

    fun putIn(port: Int) {
        standIns()
        val records = DesktopRecords(state.resolve("desktop.json")) {}
        DesktopManagedPolicy.within(home.resolve("managed-settings.json")) {
            DesktopAppMode(records, DesktopAppLibrary(DesktopAppConfig(env)), DesktopPrograms(state, env, pipe))
                .putIn("http://127.0.0.1:$port/door")
        }
        Files.writeString(state.resolve("desktop-key"), "fixture\n")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(launch)))
        assertFalse(Files.readString(launch).contains("desktop launch"))
    }

    fun run(extra: Map<String, String>) {
        val process = ProcessBuilder(listOf(launch.toString()) + words).directory(home.toFile())
            .redirectErrorStream(true).apply {
                environment().clear()
                environment().putAll(vars + ("PATH" to "/usr/bin:/bin") + extra)
            }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output.isEmpty(), "launcher emitted output")
        assertEquals(0, process.waitFor())
    }

    fun args(): List<String> = Files.readAllLines(home.resolve("argv"))
    fun env(name: String): String = Files.readAllLines(home.resolve("environment"))
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
}
