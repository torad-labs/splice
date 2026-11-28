// `splice setup --update` footer accounting across skipped launchers, desktop activation outcomes, failed daemon
// restarts, and Antigravity shim upkeep: green "Up to date." only when no open or failed item was reported.
package splice.app.cli.setup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.capability.Capabilities
import splice.app.capability.DesktopFacts
import splice.app.capability.HarnessFacts
import splice.app.capability.HubFacts
import splice.app.capability.WrapperSettings
import splice.app.desktop.setup.DesktopMode
import splice.client.ManagedFile
import splice.client.ManagedReading
import splice.configuration.add.DaemonRestart
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.YELLOW
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller
import splice.lifecycle.seatd.SeatdRehearsal
import splice.terminal.ConfirmPrompt
import splice.terminal.MultiSelectOutcome
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

class SetupUpdateFooterTest {

    @Test
    fun `an unattended update that lays a missing antigravity shim ends on green Up to date`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val launch = Files.writeString(home.resolve("desktop-launch"), "#!/bin/sh\n")
            launch.toFile().setExecutable(true)
            val right = managed("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""", launch)
            val complete = desktop(cli = true, app = true).copy(mode = DesktopMode.APP, managed = right)
            val harness = HarnessFacts(
                Path.of("/usr/local/bin/agy"),
                null,
                geminiShim = false,
                synced = setOf(McpHarness.AGY),
            )
            val caps = Capabilities(complete, harness, HubFacts(linux = true, on = false, systemd = true), false)
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said),
                spinner = Spinner(tty = false),
                hasConsole = { false },
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd()),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = { error("unattended never asks") },
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("Laid the Antigravity shim" in said, said.toString())
        assertFalse("Antigravity shim Claude Desktop runs for agy's models is missing" in said, said.toString())
        assertTrue("${GREEN}Up to date.$RESET" in said, said.toString())
    }

    @Test
    fun `a failed antigravity shim write does not print Nothing new to set up`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            Files.createDirectories(home.resolve(".local/lib/splice"))
            Files.writeString(home.resolve(".local/lib/splice/gemini"), "blocking file")
            val launch = Files.writeString(home.resolve("desktop-launch"), "#!/bin/sh\n")
            launch.toFile().setExecutable(true)
            val right = managed("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""", launch)
            val complete = desktop(cli = true, app = true).copy(mode = DesktopMode.APP, managed = right)
            val caps = Capabilities(
                complete,
                HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                HubFacts(linux = true, on = false, systemd = true),
                autoUpdate = false,
            )
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said),
                spinner = Spinner(tty = false),
                hasConsole = { false },
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd()),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = { error("unattended never asks") },
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("Could not lay the Antigravity shim" in said, said.toString())
        assertFalse("Nothing new to set up" in said, said.toString())
        assertFalse("Up to date." in said, said.toString())
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said.toString())
    }

    @Test
    fun `a skipped CLI shortcut leaves an open item instead of green Up to date`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val bin = Files.createDirectories(home.resolve(".local/bin"))
            Files.writeString(bin.resolve("claude-splice"), "#!/bin/sh\necho custom\n")
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said, ask = ConfirmPrompt { _, _ -> false }),
                pickLaunchers = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf(OPT_CLI)) },
                spinner = Spinner(tty = false),
                hasConsole = { true },
            )
            val caps = Capabilities(
                desktop(program = null, cli = false, app = false),
                HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                HubFacts(linux = true, on = false, systemd = true),
                autoUpdate = false,
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(
                    env,
                    add = noOpAdd(),
                    launchers = SetupLaunchers(env, osName = "Linux"),
                    seatd = installedSeatd(env),
                ),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = { error("desktop not installed") },
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("CLI shortcut skipped:" in said, said.toString())
        assertFalse("Up to date." in said, said.toString())
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said.toString())
    }

    @Test
    fun `a failed desktop activation leaves an open item instead of green Up to date`(@TempDir home: Path) {
        val said = runDesktopOutcome(home, confirmCosts = true, desktopOn = { false })
        assertFalse("Up to date." in said, said)
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said)
    }

    @Test
    fun `declining desktop on confirmation leaves nothing open and ends on green Up to date`(@TempDir home: Path) {
        val said = runDesktopOutcome(home, confirmCosts = false, desktopOn = { error("declined before desktop on") })
        assertTrue("Nothing was changed." in said, said)
        assertTrue("Nothing changed" in said, said)
        assertTrue("${GREEN}Up to date.$RESET" in said, said)
    }

    @Test
    fun `activating desktop exposes a managed-settings gap and reports it as open`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said, ask = ConfirmPrompt { _, _ -> true }),
                spinner = Spinner(tty = false),
                hasConsole = { true },
            )
            var activated = false
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd(), seatd = installedSeatd(env)),
                detect = {
                    val mode = if (activated) DesktopMode.APP else DesktopMode.OFF
                    val desk = desktop(cli = true, app = true).copy(mode = mode, managed = managed(OTHER_WRAPPER))
                    val caps = Capabilities(
                        desk,
                        HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                        HubFacts(linux = true, on = false, systemd = true),
                        autoUpdate = false,
                    )
                    SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps)
                },
                desktopOn = {
                    activated = true
                    true
                },
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("sets processWrapper to \"/usr/bin/other\"" in said, said.toString())
        assertFalse("Up to date." in said, said.toString())
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said.toString())
    }

    @Test
    fun `a failed daemon restart after enabling a mode leaves an open item`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said, ask = ConfirmPrompt { _, _ -> true }),
                spinner = Spinner(tty = false),
                hasConsole = { true },
            )
            val caps = Capabilities(
                desktop(program = null, cli = true, app = true),
                HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                HubFacts(linux = true, on = false, systemd = true),
                autoUpdate = false,
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd(), restart = DaemonRestart { false }, seatd = installedSeatd(env)),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = { error("desktop not installed") },
                modes = SetupModes(prompts, env, rows = ModeRows { listOf(ModeRow("demo", "demo mode", on = false)) }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("Could not restart the daemon" in said, said.toString())
        assertFalse("Up to date." in said, said.toString())
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said.toString())
    }

    @Test
    fun `a failed seatd-pipe install leaves an open item instead of green Up to date`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val env = EnvReader { null }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said, ask = ConfirmPrompt { _, _ -> true }),
                spinner = Spinner(tty = false),
                hasConsole = { true },
            )
            val caps = Capabilities(
                desktop(program = null, cli = true, app = true),
                HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                HubFacts(linux = true, on = false, systemd = true),
                autoUpdate = false,
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd(), seatd = SeatdRehearsal(env).missingAsset()),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = { error("desktop not installed") },
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        assertTrue("seatd-pipe" in said && "splice setup --update" in said, said.toString())
        assertFalse("Up to date." in said, said.toString())
        assertTrue("${YELLOW}1 open item listed above.$RESET" in said, said.toString())
    }

    private fun runDesktopOutcome(home: Path, confirmCosts: Boolean, desktopOn: DesktopOn): String {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home)
            val env = EnvReader { null }
            val ask = ConfirmPrompt { question, _ ->
                if (question == "Put Claude Desktop on splice?") confirmCosts else true
            }
            val prompts = SetupPrompts(
                frame = WizardFrame(out = said, ask = ask),
                spinner = Spinner(tty = false),
                hasConsole = { true },
            )
            val caps = Capabilities(
                desktop(cli = true, app = true),
                HarnessFacts(null, null, geminiShim = true, synced = emptySet()),
                HubFacts(linux = true, on = false, systemd = true),
                autoUpdate = false,
            )
            val runner = SetupUpdate(
                prompts,
                env,
                SetupEffects(env, add = noOpAdd(), seatd = installedSeatd(env)),
                detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, caps) },
                desktopOn = desktopOn,
                modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
            )
            runBlocking { runner.run() }
        }
        return said.toString()
    }

    private fun installedSeatd(env: EnvReader): SeatdCompanion = SeatdRehearsal(env).installer().also {
        assertTrue(it.install() is SeatdInstaller.Result.Installed)
    }

    private fun noOpAdd(): ProfileAdd = object : ProfileAdd {
        override suspend fun invoke(name: String): Boolean = error("an update adds no plan")

        override fun checkup(env: EnvReader) = Unit
    }

    private fun seedToml(home: Path) {
        val toml = Files.createDirectories(home.resolve(".config/splice")).resolve("splice.toml")
        Files.writeString(toml, "[daemon]\ncontrol_port = 3096\nauto_update = false\n")
    }

    private fun desktop(
        program: Path? = Path.of("/usr/bin/claude-desktop"),
        cli: Boolean = false,
        app: Boolean = false,
    ) = DesktopFacts(program, DesktopMode.OFF, cli, app)

    private fun managed(text: String, launch: Path = Path.of("/s/desktop-launch")): WrapperSettings {
        val file = Path.of("/etc/claude-code/managed-settings.json")
        val settings = Json.parseToJsonElement(text).jsonObject
        val reading = ManagedReading(file, ManagedFile.Read(settings), listOf(file to settings), emptyList())
        return WrapperSettings(reading, launch)
    }
}

private const val OTHER_WRAPPER = """{"processWrapper": "/usr/bin/other", "parentSettingsBehavior": "merge"}"""
