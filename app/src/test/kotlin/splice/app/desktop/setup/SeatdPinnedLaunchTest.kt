package splice.app.desktop.setup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DesktopAppConfig
import splice.core.install.seatdPipePinDocument
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SeatdPinnedLaunchTest(@param:TempDir private val home: Path) {
    private val share = home.resolve("share")
    private val launch = home.resolve("state/desktop-launch")
    private val shim = home.resolve(".local/lib/splice/gemini/claude")
    private val claude = home.resolve("bin/claude")

    @Test
    fun `an activated pin is laid before installation and only the next launch takes the new binary`() {
        executable(claude, "#!/bin/sh\nprintf 'claude:%s\\n' \"\$1\"\n")
        boot("2.3.4")
        assertEquals("claude:gemini-model", desktop())
        assertEquals("claude:gemini-model", run(shim, "gemini-model"))
        companion("2.3.4")

        val running = child(launch, claude.toString(), "gemini-model", hold = true)
        try {
            val report = running.inputStream.bufferedReader()
            assertEquals("2.3.4:--program", report.readLine())
            boot("2.4.0")
            assertEquals("claude:gemini-model", desktop()) // The old binary is still there but not selected.
            assertEquals("claude:gemini-model", run(shim, "gemini-model"))

            companion("2.4.0") // Aftercare makes precisely the already-laid path executable.
            assertEquals("2.4.0:--program", desktop())
            assertEquals("2.4.0:--acp", run(shim, "gemini-model"))
            running.outputStream.close()
            assertEquals("still:2.3.4", report.readLine())
            assertEquals(0, running.waitFor())
        } finally {
            running.destroy()
        }
    }

    private fun boot(version: String) {
        val pinDir = Files.createDirectories(home.resolve("pin-$version"))
        val pin = JsonObject(requireNotNull(seatdPipePinDocument) + ("version" to JsonPrimitive(version)))
        Files.writeString(pinDir.resolve("seatd-pipe.json"), pin.toString())
        val cp = "$pinDir${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = child(
            Path.of(System.getProperty("java.home"), "bin/java"),
            "-cp",
            cp,
            SeatdBootFixture::class.java.name,
            home.toString(),
        )
        assertEquals(0, proc.waitFor(), proc.inputStream.bufferedReader().readText())
    }

    private fun companion(version: String) = executable(
        share.resolve("seatd/$version/seatd-pipe"),
        """
        #!/bin/sh
        printf '%s:%s\n' '$version' "$1"
        if [ "${'$'}{HOLD:-}" = 1 ]; then
          read -r ignored
          printf 'still:%s\n' '$version'
        fi
        """.trimIndent() + "\n",
    )

    private fun executable(path: Path, body: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, body)
        check(path.toFile().setExecutable(true))
    }

    private fun desktop(): String = run(launch, claude.toString(), "gemini-model")

    private fun run(program: Path, vararg args: String): String {
        val proc = child(program, *args)
        val output = proc.inputStream.bufferedReader().readText().trimEnd()
        assertEquals(0, proc.waitFor(), output)
        return output
    }

    private fun child(program: Path, vararg args: String, hold: Boolean = false): Process =
        ProcessBuilder(listOf(program.toString()) + args).redirectErrorStream(true).apply {
            environment().clear()
            environment()["HOME"] = home.toString()
            environment()["PATH"] = "${claude.parent}:/usr/bin:/bin"
            if (hold) environment()["HOLD"] = "1"
        }.start()
}

/** Fresh-JVM fixture: each release loads only its own bundled-resource stand-in. No daemon or Desktop is started. */
object SeatdBootFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val home = Path.of(args.single())
        val state = home.resolve("state")
        val vars = mapOf(
            "HOME" to home.toString(),
            "XDG_CONFIG_HOME" to home.resolve("config").toString(),
            "SPLICE_STATE_DIR" to state.toString(),
            "SPLICE_SHARE_DIR" to home.resolve("share").toString(),
        )
        val env = EnvReader { vars[it] }
        val records = DesktopRecords(state.resolve("desktop.json")) {}
        val app = DesktopAppMode(records, DesktopAppLibrary(DesktopAppConfig(env)), DesktopPrograms(state, env))
        DesktopManagedPolicy.within(home.resolve("managed-settings.json")) {
            val door = "http://127.0.0.1:4567/door"
            if (records.get().appId == null) app.putIn(door) else app.refreshed(door)
        }
        GeminiShim(env).lay()
    }
}
