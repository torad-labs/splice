package splice.app.cli.setup

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.capability.CapabilityAftercare
import splice.app.cli.desktop.DaemonUp
import splice.app.cli.desktop.DesktopCommand
import splice.app.cli.desktop.DesktopConfirm
import splice.app.cli.hook.StreamTerminal
import splice.core.config.UserHome
import splice.core.install.seatdPipeRepository
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller
import splice.lifecycle.seatd.SeatdRehearsal
import splice.lifecycle.upgrade.UpgradeVerb
import splice.lifecycle.upgrade.VersionedRestart
import splice.terminal.ConfirmPrompt
import splice.terminal.MultiSelectOutcome
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

class SeatdTouchpointsTest(@param:TempDir private val home: Path) {
    private val share = home.resolve("share")
    private val state = home.resolve("state")
    private val toml = home.resolve("config/splice/splice.toml")
    private val target = share.resolve("seatd/0.1.0/seatd-pipe")
    private val env = EnvReader { key ->
        when (key) {
            "HOME" -> home.toString()
            "XDG_CONFIG_HOME" -> home.resolve("config").toString()
            "SPLICE_SHARE_DIR" -> share.toString()
            "SPLICE_STATE_DIR" -> state.toString()
            else -> null
        }
    }
    private val said = StringBuilder()

    @Test
    fun `fresh setup only installs or records consent after the plan is confirmed`() = inHome {
        Files.createDirectories(share)
        Files.writeString(share.resolve("splice-launch"), "#!/bin/sh\n")
        val installer = SeatdRehearsal(env).installer()
        assertTrue(fresh(installer, confirmPlan = false))
        assertTrue("not installing" in said, said.toString())
        assertOffer()
        assertFalse(Files.exists(target) || Files.exists(state.resolve("seatd.json")))
        said.clear()
        assertTrue(fresh(installer, confirmPlan = true) { assertTrue(Files.isExecutable(target)) })
        assertTrue("opted_in" in Files.readString(state.resolve("seatd.json")))
    }

    @Test
    fun `setup update installs the approved archive before checkup and does not ask twice`() = inHome {
        seedToml()
        val installer = SeatdRehearsal(env).installer()
        val ok = update(installer, answer = true) {
            assertEquals("seatd-pipe\n", Files.readString(target))
        }
        assertTrue(ok)
        assertOffer()
        assertTrue("Installed seatd-pipe 0.1.0" in said, said.toString())
        said.clear()
        assertTrue(update(installer, answer = false))
        assertFalse("Install seatd-pipe?" in said, said.toString())
        assertTrue("opted_in" in Files.readString(state.resolve("seatd.json")))
    }

    @Test
    fun `setup update defaults to No and remembers an explicit decline only on a terminal`() = inHome {
        seedToml()
        val installer = SeatdRehearsal(env).installer()
        assertTrue(update(installer, answer = null))
        assertFalse("Install seatd-pipe?" in said, said.toString())
        assertFalse(Files.exists(state.resolve("seatd.json")))
        said.clear()
        assertTrue(update(installer, answer = false))
        assertOffer()
        assertFalse(Files.exists(target))
        assertTrue("declined" in Files.readString(state.resolve("seatd.json")))
    }

    @Test
    fun `setup update stays usable when the archive checksum is wrong`() = inHome {
        seedToml()
        val installer = SeatdRehearsal(env).mismatched()
        assertTrue(update(installer, answer = true))
        assertTrue("checksum" in said && "splice setup --update" in said, said.toString())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `desktop yes is not install consent on a terminal or without one`() = inHome {
        val installer = SeatdRehearsal(env).installer()
        assertEquals(0, desktop(installer, answer = false, flags = listOf("--yes")))
        assertOffer()
        assertTrue("run `splice desktop on`" in said, said.toString())
        said.clear()
        assertEquals(0, desktop(installer, answer = null, flags = listOf("--yes")))
        assertTrue("run `splice desktop on`" in said, said.toString())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `desktop installs with affirmative consent before attempting to enable`() = inHome {
        val installer = SeatdRehearsal(env).installer()
        assertEquals(1, desktop(installer, answer = true, flags = listOf("--yes")))
        assertEquals("seatd-pipe\n", Files.readString(target))
        assertTrue("Installed seatd-pipe 0.1.0" in said, said.toString())
        assertTrue("daemon isn't running" in said, said.toString())
    }

    @Test
    fun `desktop on refuses a missing release or unwritable install before enabling`() = inHome {
        val fixture = SeatdRehearsal(env)
        assertEquals(1, desktop(fixture.missingAsset(), answer = true))
        assertTrue("splice desktop on" in said && "release" in said, said.toString())
        assertFalse("daemon isn't running" in said || "all of Claude Desktop will run" in said, said.toString())
        said.clear()
        Files.move(share.resolve("seatd"), share.resolve("previous-attempt"))
        Files.writeString(share.resolve("seatd"), "not a directory")
        assertEquals(1, desktop(fixture.installer(), answer = true))
        assertTrue("writ" in said && "splice desktop on" in said, said.toString())
        assertFalse("daemon isn't running" in said, said.toString())
    }

    @Test
    fun `only a consented existing installation is refreshed by the new release and auto is quiet`() = inHome {
        seedToml()
        val fixture = SeatdRehearsal(env)
        val next = fixture.installer("0.2.0")
        assertTrue(aftercare(next, auto = false))
        assertTrue("What's new / action needed" in said && "splice desktop on" in said, said.toString())
        assertTrue(seatdPipeRepository in said && "splice setup --update" in said, said.toString())
        assertTrue(fixture.installer().install() is SeatdInstaller.Result.Installed)
        said.clear()
        assertTrue(aftercare(next, auto = true))
        val newTarget = share.resolve("seatd/0.2.0/seatd-pipe")
        assertFalse(Files.exists(newTarget))
        assertFalse("seatd-pipe" in said || "What's new / action needed" in said, said.toString())
        assertTrue(update(next, answer = false))
        assertTrue(aftercare(next, auto = true))
        assertFalse(Files.exists(newTarget))
    }

    @Test
    fun `an automatic upgrade refreshes the pin for a consented installation without output`() = inHome {
        seedToml()
        val fixture = SeatdRehearsal(env)
        assertTrue(update(fixture.installer(), answer = true))
        said.clear()
        assertTrue(aftercare(fixture.installer("0.2.0"), auto = true))
        assertEquals("seatd-pipe\n", Files.readString(share.resolve("seatd/0.2.0/seatd-pipe")))
        assertEquals("seatd-pipe\n", Files.readString(target))
        assertFalse("seatd-pipe" in said || "What's new / action needed" in said, said.toString())
    }

    private fun assertOffer() {
        listOf("Install seatd-pipe?", "open-source", seatdPipeRepository, "terminal claude", "Gemini").forEach {
            assertTrue(said.contains(it, ignoreCase = true), said.toString())
        }
    }

    private fun seedToml() {
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[daemon]\nstate_dir = \"$state\"\nauto_update = false\n")
    }

    private suspend fun fresh(installer: SeatdCompanion, confirmPlan: Boolean, checkup: () -> Unit = {}): Boolean {
        val prompts = prompts(answer = true, confirmPlan = confirmPlan)
        return SetupCommand(
            loginHead = { error("no plans selected") },
            prompts = prompts,
            detect = { emptyFacts() },
            env = env,
            effects = effects(installer, checkup),
        ).setup()
    }

    private fun prompts(answer: Boolean?, confirmPlan: Boolean = false): SetupPrompts =
        SetupPrompts(
            frame = WizardFrame(
                out = said,
                ask = ConfirmPrompt { question, default ->
                    said.appendLine(question)
                    when (question) {
                        "Install seatd-pipe?" -> {
                            assertFalse(default)
                            checkNotNull(answer)
                        }
                        "Install now?" -> confirmPlan
                        else -> false
                    }
                },
            ),
            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(emptyList()) },
            pickLaunchers = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(emptyList()) },
            pickMcpHarnesses = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(emptyList()) },
            spinner = Spinner(tty = false),
            hasConsole = { answer != null },
        )
    private fun effects(installer: SeatdCompanion, checkup: () -> Unit): SetupEffects = SetupEffects(
        env,
        add = object : ProfileAdd {
            override suspend fun invoke(name: String): Boolean = error("no plans selected")

            override fun checkup(env: EnvReader) = checkup()
        },
        seatd = installer,
    )

    private fun emptyFacts(): SetupFacts = SetupFacts(emptySet(), emptySet(), openRouterKey = false, daemonUp = false)

    private suspend fun update(installer: SeatdCompanion, answer: Boolean?, checkup: () -> Unit = {}): Boolean {
        val prompts = prompts(answer)
        return SetupUpdate(
            prompts,
            env,
            effects(installer, checkup),
            detect = { emptyFacts() },
            modes = SetupModes(prompts, env, rows = ModeRows { emptyList() }),
        ).run()
    }

    private suspend fun desktop(installer: SeatdCompanion, answer: Boolean?, flags: List<String> = emptyList()): Int {
        val output = TerminalOutput { said.appendLine(it) }
        return DesktopCommand(
            env,
            output,
            output,
            daemon = DaemonUp { false },
            stdin = StreamTerminal { answer != null },
            confirm = DesktopConfirm { question ->
                said.appendLine(question)
                checkNotNull(answer)
            },
            seatd = installer,
        ).run(listOf("on") + flags)
    }

    private fun aftercare(installer: SeatdCompanion, auto: Boolean): Boolean {
        val out = TerminalOutput { said.appendLine(it) }
        val verb = UpgradeVerb(
            out,
            out,
            env,
            VersionedRestart { error("aftercare must not restart Splice") },
            aftercare = CapabilityAftercare(env, hub = { emptyList() }, seatd = installer).aftercare(out),
        )
        return verb.upgrade(if (auto) listOf("--aftercare", "--auto") else listOf("--aftercare"))
    }

    private fun inHome(block: suspend () -> Unit) = UserHome.within(home) { runTest { block() } }
}
