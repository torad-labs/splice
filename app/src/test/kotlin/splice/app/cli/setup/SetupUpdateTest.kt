// `splice setup --update`, and the launcher offer it shares with the wizard: an existing install is asked only about
// what it does not have yet, the plans it has are never asked again, and Claude (Splice) is offered only where Claude
// Desktop is installed.
package splice.app.cli.setup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
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
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.terminal.ConfirmPrompt
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOption
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

class SetupUpdateTest {

    @Test
    fun `Claude (Splice) is not offered where Claude Desktop is not installed, and the reason is said`() {
        val offered = mutableListOf<String>()
        val said = StringBuilder()
        val choice = SetupLaunchers(EnvReader { null }, osName = "Linux").offer(
            prompts(said, launchers = { options -> offered += options.map { it.value } }),
            desktop(program = null),
        )
        assertEquals(LauncherChoice(), choice)
        assertEquals(listOf(OPT_CLI), offered)
        assertTrue("Claude Desktop is not installed here" in said, said.toString())
    }

    @Test
    fun `with Claude Desktop found, both launchers are offered and splice desktop on is named beside them`() {
        val offered = mutableListOf<String>()
        val said = StringBuilder()
        SetupLaunchers(EnvReader { null }, osName = "Linux")
            .offer(prompts(said, launchers = { options -> offered += options.map { it.value } }), desktop())
        assertEquals(listOf(OPT_CLI, OPT_DESKTOP_APP), offered)
        assertTrue("splice desktop on" in said, said.toString())
    }

    @Test
    fun `an update offers no launcher that is already installed, and asks nothing when none is left`() {
        var asked = 0
        val choice = SetupLaunchers(EnvReader { null }, osName = "Linux").offer(
            prompts(StringBuilder(), launchers = { asked++ }),
            desktop(cli = true, app = true),
            onlyNew = true,
        )
        assertEquals(LauncherChoice(), choice)
        assertEquals(0, asked)
    }

    @Test
    fun `an update with no splice set up says so and changes nothing`(@TempDir home: Path) {
        val said = StringBuilder()
        val ok = UserHome.within(home) {
            runBlocking { update(said, facts = null).run() }
        }
        assertFalse(ok)
        assertTrue("not set up here yet" in said, said.toString())
        assertFalse(Files.exists(home.resolve(".config/splice/splice.toml")))
    }

    @Test
    fun `an update of an install that has everything asks nothing and says there is nothing new`(@TempDir home: Path) {
        val said = StringBuilder()
        var checkups = 0
        val toml = home.resolve(".config/splice/splice.toml")
        val ok = UserHome.within(home) {
            Files.createDirectories(toml.parent)
            Files.writeString(toml, "[daemon]\ncontrol_port = 3096\nauto_update = false\n")
            val complete = desktop(cli = true, app = true).copy(mode = DesktopMode.APP, managed = managed(BOTH_RIGHT))
            runBlocking { update(said, facts = complete, onCheckup = { checkups++ }).run() }
        }
        assertTrue(ok)
        assertTrue("Nothing new to set up" in said, said.toString())
        assertEquals(1, checkups, "an update ends on the doctor, as setup does")
        assertEquals("[daemon]\ncontrol_port = 3096\nauto_update = false\n", Files.readString(toml), "nothing written")
    }

    @Test
    fun `an update says what only root can fix in the managed settings`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            val toml = Files.createDirectories(home.resolve(".config/splice")).resolve("splice.toml")
            Files.writeString(toml, "[daemon]\ncontrol_port = 3096\nauto_update = false\n")
            val other = desktop(cli = true, app = true).copy(mode = DesktopMode.APP, managed = managed(OTHER))
            runBlocking { update(said, facts = other).run() }
        }
        assertTrue("sets processWrapper to \"/usr/bin/other\"" in said, said.toString())
        assertTrue("edit it in place as an administrator so it holds both keys" in said, said.toString())
    }

    private fun update(
        said: StringBuilder,
        facts: DesktopFacts?,
        onCheckup: () -> Unit = {},
        console: Boolean = false,
        autoUpdate: Boolean? = false,
    ): SetupUpdate {
        val env = EnvReader { null }
        val capabilities = facts?.let {
            val harness = HarnessFacts(null, null, geminiShim = true, synced = emptySet())
            Capabilities(it, harness, HubFacts(linux = true, on = false, systemd = true), autoUpdate)
        }
        val prompts = prompts(said, console = console)
        return SetupUpdate(
            prompts,
            env,
            SetupEffects(
                env,
                add = object : ProfileAdd {
                    override suspend fun invoke(name: String): Boolean = error("an update adds no plan")

                    override fun checkup(env: EnvReader) = onCheckup()
                },
            ),
            detect = { SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false, capabilities) },
            desktopOn = { error("nothing asked for splice desktop on") },
            modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
        )
    }

    // Without a terminal nothing is asked, so what is missing is said; "nothing new" would be a claim nobody checked.
    @Test
    fun `an unattended update lists what is missing instead of claiming nothing is new`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home, autoUpdate = null)
            runBlocking { update(said, facts = desktop(cli = true, app = true), autoUpdate = null).run() }
        }
        assertFalse("Nothing new to set up" in said, said.toString())
        assertTrue("automatic updates are off" in said, said.toString())
        assertTrue("in a terminal" in said, said.toString())
    }

    // An offer declined leaves the install as it was: that is "nothing changed", never "nothing new".
    @Test
    fun `an interactive update whose offers are declined says nothing changed`(@TempDir home: Path) {
        val said = StringBuilder()
        UserHome.within(home) {
            seedToml(home, autoUpdate = false)
            runBlocking { update(said, facts = desktop(cli = true, app = true), console = true).run() }
        }
        assertTrue("splice desktop on" in said, "the full-app offer was made: $said")
        assertFalse("Nothing new to set up" in said, said.toString())
        assertTrue("Nothing changed" in said, said.toString())
    }

    private fun seedToml(home: Path, autoUpdate: Boolean?) {
        val toml = Files.createDirectories(home.resolve(".config/splice")).resolve("splice.toml")
        Files.writeString(toml, "[daemon]\ncontrol_port = 3096\n" + autoUpdate?.let { "auto_update = $it\n" }.orEmpty())
    }

    private fun prompts(
        said: StringBuilder,
        console: Boolean = true,
        launchers: (List<SelectOption<String>>) -> Unit = {},
    ) = SetupPrompts(
        // A question is said into the same log, so a test can see what was offered.
        frame = WizardFrame(
            out = said,
            ask = ConfirmPrompt { question, default -> default.also { said.append(question) } },
        ),
        pickLaunchers = HeadPicker { options, _ ->
            launchers(options)
            MultiSelectOutcome.Chosen(emptyList())
        },
        spinner = Spinner(tty = false),
        hasConsole = { console },
    )

    private fun desktop(
        program: Path? = Path.of("/usr/bin/claude-desktop"),
        cli: Boolean = false,
        app: Boolean = false,
    ) = DesktopFacts(program, DesktopMode.OFF, cli, app)

    private fun managed(text: String): WrapperSettings {
        val file = Path.of("/etc/claude-code/managed-settings.json")
        val reading = ManagedReading(file, ManagedFile.Read(Json.parseToJsonElement(text).jsonObject), emptyList())
        return WrapperSettings(reading, Path.of(LAUNCH))
    }
}

private const val LAUNCH = "/s/desktop-launch"
private const val BOTH_RIGHT = """{"processWrapper": "$LAUNCH", "parentSettingsBehavior": "merge"}"""
private const val OTHER = """{"processWrapper": "/usr/bin/other", "parentSettingsBehavior": "merge"}"""
