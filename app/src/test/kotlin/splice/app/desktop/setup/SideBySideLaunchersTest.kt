package splice.app.desktop.setup

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.desktop.DaemonUp
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SideBySideLaunchersTest {
    @Test
    fun `Linux installs desktop entry in XDG applications dir and keeps custom files`(
        @TempDir home: Path,
    ) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val launchers = SideBySideLaunchers(env, osName = "Linux")
            val installed = launchers.installDesktopApp()
            assertTrue(installed is AppLauncherInstall.Installed, installed.toString())
            val path = launchers.desktopLauncherPath()
            assertTrue(path.toString().endsWith("/.local/share/applications/ai.torad.claude-splice.desktop"))
            val text = Files.readString(path)
            assertTrue(text.contains("Name=Claude (Splice)"), text)
            assertTrue(text.contains("launch claude-desktop %U"), text)

            assertTrue(launchers.removeIfManaged())
            assertFalse(Files.exists(path))

            // User-authored file without splice marker is never overwritten or removed
            Files.createDirectories(path.parent)
            Files.writeString(path, "[Desktop Entry]\nName=Custom\n")
            val skipped = launchers.installDesktopApp()
            assertTrue(skipped is AppLauncherInstall.Skipped, skipped.toString())
            assertFalse(launchers.removeIfManaged())
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `macOS installs Claude (Splice) app bundle in user Applications folder and cleans it up`(@TempDir home: Path) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val launchers = SideBySideLaunchers(env, osName = "Mac OS X")
            val installed = launchers.installDesktopApp()
            assertTrue(installed is AppLauncherInstall.Installed, installed.toString())
            val app = launchers.desktopLauncherPath()
            assertTrue(app.toString().endsWith("/Applications/Claude (Splice).app"))
            assertTrue(Files.isRegularFile(app.resolve("Contents/Info.plist")))
            val exec = app.resolve("Contents/MacOS/claude-splice")
            assertTrue(Files.isRegularFile(exec))
            assertTrue(Files.readString(exec).contains("launch claude-desktop"))

            assertTrue(launchers.removeIfManaged())
            assertFalse(Files.exists(app))
        }
    }

    @Test
    fun `prepareProfile configures isolated claude-splice folder, syncs 1P setup, and refreshes existing profiles`(
        @TempDir home: Path,
    ) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val defaultClaudeDir = home.resolve(".config/Claude-3p/configLibrary")
            val onePDir = Files.createDirectories(home.resolve(".config/Claude"))
            Files.writeString(
                onePDir.resolve("claude_desktop_config.json"),
                """{"mcpServers":{"desktop-mcp":{"command":"npx","args":["-y","demo"]}}}""",
            )
            Files.writeString(
                home.resolve(".claude.json"),
                """{"mcpServers":{"cli-stdio":{"type":"stdio","command":"uvx","args":["tool"]}}}""",
            )
            val extDir = Files.createDirectories(onePDir.resolve("Claude Extensions/demo.ext"))
            Files.writeString(extDir.resolve("manifest.json"), """{"name":"demo"}""")

            val desktop = SideBySideDesktop(
                env = env,
                out = TerminalOutput {},
                err = TerminalOutput {},
                osName = "Linux",
            )
            val door = "http://127.0.0.1:3096/door"
            val profile = desktop.prepareProfile(door)
            assertEquals(home.resolve(".config/claude-splice"), profile)
            assertFalse(Files.exists(defaultClaudeDir), "default Claude Desktop config must stay untouched")
            val isolatedLib = profile.resolve("Claude-3p/configLibrary")
            assertTrue(Files.isRegularFile(isolatedLib.resolve("_meta.json")))
            val configFile = isolatedLib.resolve("splice-side-by-side.json")
            val configText = Files.readString(configFile)
            assertTrue(configText.contains(door), configText)
            assertTrue(configText.contains("\"projectsEnabled\": true"), configText)
            val syncedCfg = Files.readString(profile.resolve("Claude-3p/claude_desktop_config.json"))
            assertTrue(syncedCfg.contains("desktop-mcp"), syncedCfg)
            assertTrue(syncedCfg.contains("cli-stdio"), syncedCfg)
            assertTrue(Files.isRegularFile(profile.resolve("Claude-3p/Claude Extensions/demo.ext/manifest.json")))

            // Simulate an older side-by-side config missing projectsEnabled while keeping user's custom effort.
            Files.writeString(
                configFile,
                """{"inferenceProvider":"gateway","inferenceGatewayBaseUrl":"$door","defaultModelEffort":"low"}""",
            )
            desktop.prepareProfile(door)
            val refreshed = Files.readString(configFile)
            assertTrue(refreshed.contains("\"projectsEnabled\": true"), refreshed)
            assertTrue(refreshed.contains("\"defaultModelEffort\": \"low\""), refreshed)
        }
    }

    @Test
    fun `launch still starts Desktop with empty stderr when publish returns non-200`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val binDir = Files.createDirectories(tmp.resolve("bin"))
        val claudeBin = binDir.resolve("claude-desktop").also { Files.writeString(it, "#!/bin/sh\n") }
        assertTrue(claudeBin.toFile().setExecutable(true))
        val publishHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/desktop/publish") { ex ->
            publishHits.incrementAndGet()
            val _ = ex.requestBody.readBytes()
            val body = """{"error":"busy"}""".toByteArray()
            ex.sendResponseHeaders(500, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val stateDir = tmp.resolve("state")
            val vars = mapOf(
                "HOME" to home.toString(),
                "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
                "SPLICE_STATE_DIR" to stateDir.toString(),
                "SPLICE_CONTROL_PORT" to server.address.port.toString(),
                "PATH" to binDir.toString(),
            )
            val env = EnvReader { vars[it] }
            UserHome.within(home) {
                val _ = MgmtKey(StatePaths(baseOverride = stateDir, envReader = env)).get()
                val errLines = mutableListOf<String>()
                val launched = mutableListOf<List<String>>()
                val desktop = SideBySideDesktop(
                    env = env,
                    out = TerminalOutput {},
                    err = TerminalOutput { errLines += it },
                    daemon = DaemonUp { true },
                    osName = "Linux",
                    starter = DesktopProcessStarter { cmd, _ -> 0.also { launched += cmd } },
                )
                assertEquals(0, desktop.launch(emptyList()))
                assertEquals(1, publishHits.get())
                assertTrue(errLines.isEmpty(), errLines.toString())
                assertEquals(1, launched.size)
            }
        } finally {
            server.stop(0)
        }
    }
}
